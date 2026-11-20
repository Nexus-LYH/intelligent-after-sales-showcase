param([Parameter(Mandatory=$true)][string]$ProbeJar,[Parameter(Mandatory=$true)][string]$EvidenceDirectory)
$ErrorActionPreference='Stop'
$java='C:\ias-local\Develop\jdk21\bin\java.exe';$keytool='C:\ias-local\Develop\jdk21\bin\keytool.exe'
if(!(Test-Path -LiteralPath $ProbeJar -PathType Leaf) -or (Test-Path -LiteralPath $EvidenceDirectory)){throw 'Candidate JAR and new evidence directory required'}
if(Get-NetTCPConnection -LocalPort 8441 -State Listen -ErrorAction SilentlyContinue){throw 'Fixture port occupied'}
New-Item -ItemType Directory -Path $EvidenceDirectory | Out-Null
$serverStore=Join-Path $EvidenceDirectory 'synthetic-server.p12';$otherStore=Join-Path $EvidenceDirectory 'synthetic-other.p12'
$cert=Join-Path $EvidenceDirectory 'synthetic-server.cer';$otherCert=Join-Path $EvidenceDirectory 'synthetic-other.cer'
$trust=Join-Path $EvidenceDirectory 'synthetic-trust.p12';$wrongTrust=Join-Path $EvidenceDirectory 'synthetic-wrong-trust.p12'
foreach($pair in @(@($serverStore,'fixture'),@($otherStore,'other'))){
 & $keytool -genkeypair -alias $pair[1] -keyalg RSA -keysize 2048 -validity 2 -dname ('CN='+$pair[1]) -ext 'SAN=IP:127.0.0.1' -storetype PKCS12 -keystore $pair[0] -storepass 'fixture-only' -keypass 'fixture-only' *> $null
 if($LASTEXITCODE -ne 0){throw 'Synthetic key generation failed'}
}
foreach($item in @(@($serverStore,'fixture',$cert,$trust),@($otherStore,'other',$otherCert,$wrongTrust))){
 & $keytool -exportcert -alias $item[1] -keystore $item[0] -storepass 'fixture-only' -file $item[2] *> $null
 if($LASTEXITCODE -ne 0){throw 'Synthetic certificate export failed'}
 & $keytool -importcert -noprompt -alias $item[1] -file $item[2] -keystore $item[3] -storetype PKCS12 -storepass 'fixture-only' *> $null
 if($LASTEXITCODE -ne 0){throw 'Synthetic trust store generation failed'}
}
$fixture=Join-Path $PSScriptRoot 'test-fixtures\ProbeTlsFixture.java';$stdout=Join-Path $EvidenceDirectory 'fixture.out';$stderr=Join-Path $EvidenceDirectory 'fixture.err'
$process=Start-Process -FilePath $java -ArgumentList @('"'+$fixture+'"','"'+$serverStore+'"') -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
$old=$env:IAS_CHAIN_CONFIG
try{
 $deadline=[datetime]::UtcNow.AddSeconds(25);do{if($process.HasExited){throw 'Fixture failed to start'};if((Test-Path -LiteralPath $stdout) -and (Get-Content -LiteralPath $stdout -Raw) -match 'FIXTURE_READY'){break};Start-Sleep -Milliseconds 200}while([datetime]::UtcNow -lt $deadline)
 if(!(Test-Path -LiteralPath $stdout) -or (Get-Content -LiteralPath $stdout -Raw) -notmatch 'FIXTURE_READY'){throw 'Fixture did not become ready'}
 $cases=@(@('valid',$trust,('a'*43),0),@('wrong-token',$trust,('b'*43),1),@('wrong-certificate',$wrongTrust,('a'*43),1))
 foreach($case in $cases){
  $config=Join-Path $EvidenceDirectory ($case[0]+'.json')
  @{role='ticket';storePassword='fixture-only';edges=@{'ticket-identity'=$case[2]};peers=@{identity=@{baseUrl='https://127.0.0.1:8441';trustStore=$case[1]}}} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $config -Encoding utf8
  $env:IAS_CHAIN_CONFIG=$config
  $output=@(& $java '-Dloader.main=com.ias.trust.ServiceProbeCli' '-cp' $ProbeJar 'org.springframework.boot.loader.launch.PropertiesLauncher' 'identity' 2>&1)
  $status=$LASTEXITCODE;if($status -ne $case[3]){throw ('Unexpected TLS fixture result: '+$case[0])}
  if($status -eq 0){$receipt=($output -join '') | ConvertFrom-Json;if($receipt.role -cne 'identity' -or $receipt.tlsVerified -ne $true -or $receipt.trustAllUsed -ne $false){throw 'Invalid success receipt'}}
  elseif(($output -join '') -notmatch 'SERVICE_PROBE_FAILED'){throw 'Invalid failure receipt'}
  Write-Output ($case[0]+': PASS')
 }
}finally{
 if($null -eq $old){Remove-Item Env:\IAS_CHAIN_CONFIG -ErrorAction SilentlyContinue}else{$env:IAS_CHAIN_CONFIG=$old}
 if(!$process.HasExited){$process.Kill();$process.WaitForExit()}
}
