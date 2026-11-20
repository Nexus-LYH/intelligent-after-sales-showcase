$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'LocalLifecycle.ps1')
$checks=0
function Expect-Blocked([scriptblock]$Body,[string]$Message=''){
 $blocked=$false
 try { & $Body } catch { if($Message -and $_.Exception.Message -notlike ('*'+$Message+'*')){throw}; $blocked=$true }
 if(!$blocked){throw 'Expected safety gate was bypassed'}
 $script:checks++
}
Expect-Blocked { Assert-IasFileHash (Join-Path $PSScriptRoot 'missing.jar') ('0'*64) }
Expect-Blocked { Assert-IasFileHash $PSCommandPath ('0'*64) }
$example=Join-Path $PSScriptRoot 'local-release.example.json'
$config=Read-IasLocalConfig $example
Expect-Blocked { Test-IasLocalConfig $config }
$config.databasePort=3306
# Invalid database port is checked before release or runtime probes.
Expect-Blocked { Test-IasLocalConfig $config }
function Assert-IasNoServices($Config) { }
Expect-Blocked { Invoke-IasRestore $config 'unused' 'ias_identity_test' }
Expect-Blocked { Invoke-IasBackup $config $PSScriptRoot }
Expect-Blocked { Assert-IasFrontends $config }
$referenceFixture=Join-Path ([IO.Path]::GetTempPath()) ('ias-runtime-refs-'+[guid]::NewGuid().ToString('N'))
try {
 New-Item -ItemType Directory -Path $referenceFixture | Out-Null
 $environment=Join-Path $referenceFixture 'environment.ps1';$credentials=Join-Path $referenceFixture 'credentials.json';$domain=Join-Path $referenceFixture 'domain.json'
 foreach($file in @($environment,$credentials,$domain)){[IO.File]::WriteAllText($file,'fixture')}
 foreach($role in @('identity','asset','ticket','approval','stock','notification','finance','gateway')){[IO.File]::WriteAllText((Join-Path $referenceFixture ($role+'.json')),'fixture')}
 $refs=[pscustomobject]@{environmentScript=$environment;environmentScriptSha256=(Get-FileHash $environment).Hash;chainDirectory=$referenceFixture;mysqlCredentialsConfig=$credentials;domainRuntimeConfig=$domain}
 Assert-IasRuntimeReferences $refs
 $refs.environmentScriptSha256='0'*64
 Expect-Blocked { Assert-IasRuntimeReferences $refs } 'hash mismatch'
 $refs.environmentScriptSha256=(Get-FileHash $environment).Hash
 Remove-Item -LiteralPath (Join-Path $referenceFixture 'gateway.json')
 Expect-Blocked { Assert-IasRuntimeReferences $refs } 'role configuration'
} finally {
 $base=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar)+[IO.Path]::DirectorySeparatorChar
 $resolved=[IO.Path]::GetFullPath($referenceFixture)
 if(!$resolved.StartsWith($base,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($resolved) -notmatch '^ias-runtime-refs-[a-f0-9]{32}$'){throw 'Unexpected fixture path'}
 Remove-Item -LiteralPath $resolved -Recurse -Force -ErrorAction SilentlyContinue
}
$migrationSchemas=@('ias_identity_test','ias_asset_test','ias_ticket_test','ias_approval_test','ias_stock_test','ias_notification_test','ias_finance_test')
$targetFixture=Join-Path ([IO.Path]::GetTempPath()) ('ias-target-check-'+[guid]::NewGuid().ToString('N')+'.ps1')
try {
 [IO.File]::WriteAllText($targetFixture,'$global:LASTEXITCODE=0; Write-Output "0"')
 $targetConfig=[pscustomobject]@{mysqlExecutable=$targetFixture;mysqlOptionFile='fixture'}
 Assert-IasRestoreTargetsAvailable $targetConfig $migrationSchemas 'ias_restore_fixture'
 [IO.File]::WriteAllText($targetFixture,'$global:LASTEXITCODE=0; Write-Output "1"')
 Expect-Blocked { Assert-IasRestoreTargetsAvailable $targetConfig $migrationSchemas 'ias_restore_fixture' } 'target already exists'
 Expect-Blocked { Assert-IasRestoreTargetsAvailable $targetConfig $migrationSchemas 'ias_identity_test' } 'Invalid restore prefix'
} finally { Remove-Item -LiteralPath $targetFixture -Force -ErrorAction SilentlyContinue }
Assert-IasMigrationCoverage $migrationSchemas @([pscustomobject]@{schemas=$migrationSchemas})
Expect-Blocked { Assert-IasMigrationCoverage $migrationSchemas @([pscustomobject]@{schemas=$migrationSchemas[0..5]}) } 'coverage incomplete'
Expect-Blocked { Assert-IasMigrationCoverage $migrationSchemas @([pscustomobject]@{schemas=$migrationSchemas},[pscustomobject]@{schemas=@($migrationSchemas[0])}) } 'unexpected or duplicated'
Expect-Blocked { Assert-IasMigrationCoverage $migrationSchemas @([pscustomobject]@{schemas=@($migrationSchemas[0..5])+@('ias_other_test')}) } 'unexpected or duplicated'
Expect-Blocked { Assert-IasMigrationCoverage $migrationSchemas @([pscustomobject]@{script='missing-schema-declaration'}) } 'explicit schemas'
Expect-Blocked { $wrong=@($migrationSchemas[0..5])+@('ias_other_test'); Assert-IasMigrationCoverage $wrong @([pscustomobject]@{schemas=$wrong}) } 'Unexpected, duplicate or missing'
$stopFixture=Join-Path ([IO.Path]::GetTempPath()) ('ias-stop-manifest-'+[guid]::NewGuid().ToString('N')+'.json')
try {
 $stopConfig=[pscustomobject]@{runtimeManifest=$stopFixture;services=@();frontends=@();tools=@()}
 [IO.File]::WriteAllText($stopFixture,'[]')
 Expect-Blocked { Stop-IasLocal $stopConfig } 'manifest is empty'
 [IO.File]::WriteAllText($stopFixture,'[{"role":"identity","pid":1},{"role":"identity","pid":2}]')
 Expect-Blocked { Stop-IasLocal $stopConfig } 'Duplicate service role or PID'
 [IO.File]::WriteAllText($stopFixture,'[{"role":"identity","pid":1},{"role":"asset","pid":1}]')
 Expect-Blocked { Stop-IasLocal $stopConfig } 'Duplicate service role or PID'
} finally { Remove-Item -LiteralPath $stopFixture -ErrorAction SilentlyContinue }
Assert-IasExpectedMysqlDataDir 'C:/ias-local/Develop/IAS-MySQL84/data/'
Expect-Blocked { Assert-IasExpectedMysqlDataDir 'C:/ias-local/Develop/other-data/' } 'not the project data directory'
Expect-Blocked { Assert-IasExpectedMysqlDataDir '' } 'not returned'
$mysqlFixture=Join-Path ([IO.Path]::GetTempPath()) ('ias-mysql-probe-'+[guid]::NewGuid().ToString('N')+'.ps1')
try {
 [IO.File]::WriteAllText($mysqlFixture, '$global:LASTEXITCODE=0; Write-Output "C:/ias-local/Develop/IAS-MySQL84/data/"')
 $mysqlHash=(Get-FileHash -LiteralPath $mysqlFixture -Algorithm SHA256).Hash
 $mysqlConfig=[pscustomobject]@{databasePort=3307;mysqlOptionFile=$PSCommandPath;mysqlExecutable=$mysqlFixture;tools=@([pscustomobject]@{name='mysql';executable=$mysqlFixture;sha256=$mysqlHash})}
 Confirm-IasMysqlInstance $mysqlConfig
 [IO.File]::WriteAllText($mysqlFixture, '$global:LASTEXITCODE=0; Write-Output "C:/ias-local/Develop/other-data/"')
 $mysqlConfig.tools[0].sha256=(Get-FileHash -LiteralPath $mysqlFixture -Algorithm SHA256).Hash
 Expect-Blocked { Confirm-IasMysqlInstance $mysqlConfig } 'not the project data directory'
 [IO.File]::WriteAllText($mysqlFixture, '$global:LASTEXITCODE=0; Write-Output "C:/ias-local/Develop/IAS-MySQL84/data/"; Write-Output "unexpected"')
 $mysqlConfig.tools[0].sha256=(Get-FileHash -LiteralPath $mysqlFixture -Algorithm SHA256).Hash
 Expect-Blocked { Confirm-IasMysqlInstance $mysqlConfig } 'Unable to verify'
 $mysqlConfig.mysqlExecutable='different-client'
 Expect-Blocked { Confirm-IasMysqlInstance $mysqlConfig } 'Pinned MySQL client mismatch'
} finally { Remove-Item -LiteralPath $mysqlFixture -ErrorAction SilentlyContinue }
$probe=Join-Path ([IO.Path]::GetTempPath()) ('ias-dump-check-'+[guid]::NewGuid().ToString('N')+'.sql')
try {
 [IO.File]::WriteAllText($probe,'CREATE TABLE `sample` (`id` bigint);')
 Assert-IasRestoreDumpSafe $probe
 [IO.File]::WriteAllText($probe,"INSERT INTO sample VALUES ('tester@example.invalid','ticket.accepted','USE otherdb;');")
 Assert-IasRestoreDumpSafe $probe
 [IO.File]::WriteAllText($probe,"-- source info can contain otherdb.table and USE otherdb;")
 Assert-IasRestoreDumpSafe $probe
 [IO.File]::WriteAllText($probe,"INSERT INTO sample VALUES ('unclosed);")
 Expect-Blocked { Assert-IasRestoreDumpSafe $probe } 'Ambiguous quoted SQL'
 [IO.File]::WriteAllText($probe,'SELECT "otherdb"."sample";')
 Expect-Blocked { Assert-IasRestoreDumpSafe $probe } 'Ambiguous quoted SQL'
 foreach($sql in @('USE `ias_identity_test`;','CREATE DATABASE `ias_identity_test`;','CREATE DEFINER=`root`@`localhost` TRIGGER t BEFORE INSERT ON sample FOR EACH ROW SET @x=1;','INSERT INTO `ias_identity_test`.`sample` VALUES (1);','INSERT INTO otherdb.sample VALUES (1);','INSERT INTO `otherdb`.sample VALUES (1);','INSERT INTO otherdb.`sample` VALUES (1);')){
  [IO.File]::WriteAllText($probe,$sql)
  Expect-Blocked { Assert-IasRestoreDumpSafe $probe }
 }
} finally { Remove-Item -LiteralPath $probe -ErrorAction SilentlyContinue }
$fixture=Join-Path ([IO.Path]::GetTempPath()) ('ias-restore-preflight-'+[guid]::NewGuid().ToString('N'))
try {
 New-Item -ItemType Directory -Path $fixture | Out-Null
 $schemas=@('ias_identity_test','ias_asset_test','ias_ticket_test','ias_approval_test','ias_stock_test','ias_notification_test','ias_finance_test')
 $files=@()
 foreach($schema in $schemas){
  $name=$schema+'.sql'; $path=Join-Path $fixture $name
  [IO.File]::WriteAllText($path,'CREATE TABLE sample (id bigint);')
  $files+=@{name=$name;sha256=(Get-FileHash -LiteralPath $path).Hash}
 }
 New-Item -ItemType Directory -Path (Join-Path $fixture 'private-0') | Out-Null
 $private=Join-Path $fixture 'private-0\sample.txt'
 [IO.File]::WriteAllText($private,'fixture')
 $files+=@{name='private-0/sample.txt';sha256=(Get-FileHash -LiteralPath $private).Hash}
 $backup=@{schemaVersion=1;gitSha=('a'*40);schemas=$schemas;privateRootCount=1;files=$files}
 $backup | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $fixture 'backup-complete.json') -Encoding utf8
 $restoreConfig=[pscustomobject]@{gitSha=('a'*40);schemas=$schemas;privateFileRoots=@('fixture');mysqlExecutable='THIS_MUST_NOT_BE_EXECUTED'}
 [IO.File]::WriteAllText((Join-Path $fixture 'private-0\unpinned.txt'),'extra')
 Expect-Blocked { Invoke-IasRestore $restoreConfig $fixture 'ias_restore_test' } 'Unpinned private backup file'
 Remove-Item -LiteralPath (Join-Path $fixture 'private-0\unpinned.txt')
 New-Item -ItemType Directory -Path (Join-Path $fixture 'ias_restore_test-private-restored') | Out-Null
 Expect-Blocked { Invoke-IasRestore $restoreConfig $fixture 'ias_restore_test' } 'Restore private-file target exists'
} finally {
 $tempBase=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar)+[IO.Path]::DirectorySeparatorChar
 $resolvedFixture=[IO.Path]::GetFullPath($fixture)
 if(!$resolvedFixture.StartsWith($tempBase,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($resolvedFixture) -notmatch '^ias-restore-preflight-[a-f0-9]{32}$'){throw 'Refusing to delete unexpected test fixture'}
 Remove-Item -LiteralPath $resolvedFixture -Recurse -Force -ErrorAction SilentlyContinue
}
Write-Output "PASS: $checks offline safety gates; no services/database/private configurations accessed."
