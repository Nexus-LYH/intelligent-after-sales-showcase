$ErrorActionPreference='Stop'
$root=Join-Path ([IO.Path]::GetTempPath()) ('ias-bootstrap-adapter-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $root | Out-Null
try {
 $java=Join-Path $root 'java.ps1';$jar=Join-Path $root 'identity.jar';$plan=Join-Path $root 'plan.json';$secret=Join-Path $root 'secret.json';$credentials=Join-Path $root 'credentials.json';$config=Join-Path $root 'config.json'
 [IO.File]::WriteAllText($java,'$global:LASTEXITCODE=0; Write-Output "IAS_BOOTSTRAP_REENTRANT_PASSWORDS_UNCHANGED"')
 [IO.File]::WriteAllText($jar,'fixture');[IO.File]::WriteAllText($plan,'{}');[IO.File]::WriteAllText($secret,'{}')
 @{initializerUser='fixture';initializerPassword='fixture-only'} | ConvertTo-Json | Set-Content -LiteralPath $credentials -Encoding utf8
 $javaHash=(Get-FileHash $java).Hash;$jarHash=(Get-FileHash $jar).Hash
 @{java=@{path=$java;sha256=$javaHash};identityJar=@{path=$jar;sha256=$jarHash};plan=@{path=$plan;sha256=(Get-FileHash $plan).Hash};secretsPath=$secret;credentialsPath=$credentials} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $config -Encoding utf8
 $context=[pscustomobject]@{databasePort=3307;gitSha=('a'*40);schemas=@('ias_identity_test');tools=@([pscustomobject]@{name='java';executable=$java;sha256=$javaHash});services=@([pscustomobject]@{role='identity';jar=$jar;sha256=$jarHash})}
 $before=$env:IAS_INITIALIZER_USER
 $receipt=& (Join-Path $PSScriptRoot 'Invoke-IasBootstrap.ps1') -Action Bootstrap -ConfigPath $config -Context $context
 if(!$receipt.ok -or !$receipt.reentrant -or $receipt.existingPasswordsChanged -ne $false -or $receipt.gitSha -cne $context.gitSha){throw 'Incorrect bootstrap receipt'}
 if([Environment]::GetEnvironmentVariable('IAS_INITIALIZER_USER','Process') -ne $before){throw 'Environment restoration failed'}
 $context.services[0].sha256='0'*64;$blocked=$false
 try {& (Join-Path $PSScriptRoot 'Invoke-IasBootstrap.ps1') -Action Bootstrap -ConfigPath $config -Context $context | Out-Null} catch {$blocked=$true}
 if(!$blocked){throw 'Incorrect release artifact was accepted'}
 $context.services[0].sha256=$jarHash;[IO.File]::WriteAllText($plan,'changed');$blocked=$false
 try {& (Join-Path $PSScriptRoot 'Invoke-IasBootstrap.ps1') -Action Bootstrap -ConfigPath $config -Context $context | Out-Null} catch {$blocked=$true}
 if(!$blocked){throw 'Changed plan was accepted'}
 'PASS: bootstrap adapter receipt, environment restoration, release pin and plan pin; fixture only.'
} finally {
 $parent=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar)+[IO.Path]::DirectorySeparatorChar
 $resolved=[IO.Path]::GetFullPath($root)
 if(!$resolved.StartsWith($parent,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($resolved) -notmatch '^ias-bootstrap-adapter-[a-f0-9]{32}$'){throw 'Unexpected fixture path'}
 Remove-Item -LiteralPath $resolved -Recurse -Force -ErrorAction SilentlyContinue
}
