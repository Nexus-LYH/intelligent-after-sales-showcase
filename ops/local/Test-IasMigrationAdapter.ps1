$ErrorActionPreference='Stop'
$oldMarker=$env:IAS_FIXTURE_MARKER
$fixture=Join-Path ([IO.Path]::GetTempPath()) ('ias-migration-adapter-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixture | Out-Null
try{
 $launcher=Join-Path $fixture 'fake-java.ps1';$marker=Join-Path $fixture 'called.txt';$jar=Join-Path $fixture 'fixture.jar';$directory=Join-Path $fixture 'migration'
 New-Item -ItemType Directory -Path $directory | Out-Null
 [IO.File]::WriteAllText($launcher, '$global:LASTEXITCODE=0; [IO.File]::AppendAllText($env:IAS_FIXTURE_MARKER,"called`n"); $schema=@($args | Where-Object {$_ -eq "ias_identity_test"})[0]; Write-Output ("IAS_MIGRATION_REENTRANT_OK:"+$schema)')
 [IO.File]::WriteAllText($jar,'test-only jar');$sql=Join-Path $directory 'V1__fixture.sql';[IO.File]::WriteAllText($sql,'CREATE TABLE fixture(id INT);')
 $accounts=Join-Path $fixture 'accounts.json';@{ias_identity_test=@{user='fixture_user';password='fixture-only'}} | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $accounts -Encoding utf8
 $configPath=Join-Path $fixture 'config.json'
 $config=@{java=@{path=$launcher;sha256=(Get-FileHash $launcher).Hash};ticketJar=@{path=$jar;sha256=(Get-FileHash $jar).Hash};accountsPath=$accounts;schemas=@(@{name='ias_identity_test';directory=$directory;files=@(@{name='V1__fixture.sql';sha256=(Get-FileHash $sql).Hash})})}
 $config | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $configPath -Encoding utf8
 $context=[pscustomobject]@{gitSha=('a'*40);databasePort=3307;schemas=@('ias_identity_test');tools=@([pscustomobject]@{name='java';executable=$launcher;sha256=$config.java.sha256});services=@([pscustomobject]@{role='ticket';jar=$jar;sha256=$config.ticketJar.sha256})};$env:IAS_FIXTURE_MARKER=$marker
 $oldUser=$env:IAS_MIGRATION_USER;$oldPassword=$env:IAS_MIGRATION_PASSWORD
 $result=& (Join-Path $PSScriptRoot 'Invoke-IasMigrations.ps1') -Action Migrate -ConfigPath $configPath -Context $context
 if(!$result.ok -or !$result.reentrant -or @($result.schemas).Count -ne 1 -or $result.schemas[0] -cne 'ias_identity_test'){throw 'Unexpected adapter receipt'}
 if((Get-Content -LiteralPath $marker).Count -ne 1 -or $env:IAS_MIGRATION_USER -ne $oldUser -or $env:IAS_MIGRATION_PASSWORD -ne $oldPassword){throw 'Incorrect invocation or environment restoration'}
 $context.services[0].sha256='0'*64;$blocked=$false
 try{& (Join-Path $PSScriptRoot 'Invoke-IasMigrations.ps1') -Action Migrate -ConfigPath $configPath -Context $context | Out-Null}catch{$blocked=$true}
 if(!$blocked -or (Get-Content -LiteralPath $marker).Count -ne 1){throw 'Release artifact mismatch was not blocked'}
 $context.services[0].sha256=$config.ticketJar.sha256
 [IO.File]::WriteAllText($sql,'tampered');$blocked=$false
 try{& (Join-Path $PSScriptRoot 'Invoke-IasMigrations.ps1') -Action Migrate -ConfigPath $configPath -Context $context | Out-Null}catch{$blocked=$true}
 if(!$blocked -or (Get-Content -LiteralPath $marker).Count -ne 1){throw 'Modified migration was not blocked before execution'}
 [IO.File]::WriteAllText($sql,'CREATE TABLE fixture(id INT);');[IO.File]::WriteAllText((Join-Path $directory 'V2__unexpected.sql'),'extra');$blocked=$false
 try{& (Join-Path $PSScriptRoot 'Invoke-IasMigrations.ps1') -Action Migrate -ConfigPath $configPath -Context $context | Out-Null}catch{$blocked=$true}
 if(!$blocked -or (Get-Content -LiteralPath $marker).Count -ne 1){throw 'Unexpected migration was not blocked before execution'}
 'PASS: simulated adapter receipt, release pin, environment restoration, changed/extra SQL rejection; no database accessed.'
}finally{
 if($null -eq $oldMarker){Remove-Item Env:\IAS_FIXTURE_MARKER -ErrorAction SilentlyContinue}else{$env:IAS_FIXTURE_MARKER=$oldMarker}
 $base=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar)+[IO.Path]::DirectorySeparatorChar
 $resolved=[IO.Path]::GetFullPath($fixture)
 if(!$resolved.StartsWith($base,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($resolved) -notmatch '^ias-migration-adapter-[a-f0-9]{32}$'){throw 'Refusing unexpected fixture cleanup'}
 Remove-Item -LiteralPath $resolved -Recurse -Force -ErrorAction SilentlyContinue
}
