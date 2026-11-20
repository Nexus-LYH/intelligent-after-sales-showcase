param(
 [Parameter(Mandatory=$true)][ValidateSet('ReviewRestore')][string]$Action,
 [Parameter(Mandatory=$true)][string]$ConfigPath,
 [Parameter(Mandatory=$true)]$Context
)
$ErrorActionPreference='Stop'
try {
 $config=Get-Content -LiteralPath $ConfigPath -Raw -Encoding utf8 | ConvertFrom-Json
 if($Context.databasePort -ne 3307 -or $Context.gitSha -notmatch '^[a-fA-F0-9]{40}$'){throw 'Invalid context'}
 if(!(Test-Path -LiteralPath $config.mysqlExecutable -PathType Leaf) -or $config.mysqlSha256 -notmatch '^[a-fA-F0-9]{64}$' -or (Get-FileHash -LiteralPath $config.mysqlExecutable -Algorithm SHA256).Hash -ne $config.mysqlSha256 -or !(Test-Path -LiteralPath $config.mysqlOptionFile -PathType Leaf)){throw 'Invalid MySQL reference'}
 $map=@($Context.schemaMap)
 if($map.Count -ne 7 -or @($map.source | Select-Object -Unique).Count -ne 7 -or @($map.target | Select-Object -Unique).Count -ne 7 -or @($config.principals).Count -ne 7){throw 'Invalid owner mapping'}
 $owners=@('identity','asset','ticket','approval','stock','notification','finance')
 foreach($entry in $map){
  if($entry.source -cnotin @($owners | ForEach-Object {'ias_'+$_+'_test'}) -or $entry.target -notmatch '^ias_restore_[a-z0-9_]{1,30}_(identity|asset|ticket|approval|stock|notification|finance)$' -or !$entry.target.EndsWith('_'+($entry.source -replace '^ias_|_test$'),[StringComparison]::Ordinal)){throw 'Invalid schema mapping'}
 }
 function Query([string]$sql){
  $lines=@(& $config.mysqlExecutable ('--defaults-extra-file='+$config.mysqlOptionFile) --host=127.0.0.1 --port=3307 --protocol=TCP --batch --skip-column-names ('--execute='+$sql) 2>$null)
  if($LASTEXITCODE -ne 0){throw 'MySQL review query failed'}
  return $lines
 }
 $instance=@(Query 'SELECT @@datadir')
 if($instance.Count -ne 1 -or ([string]$instance[0]).Replace('\\','/').TrimEnd('/') -ine 'C:/ias-local/Develop/IAS-MySQL84/data'){throw 'Unexpected MySQL instance'}
 function GrantExpression([string]$user){return "CONCAT(CHAR(39),'$user',CHAR(39),'@',CHAR(39),'127.0.0.1',CHAR(39))"}
 function Grants([string]$schema,[string]$user){
  $g=GrantExpression $user
  $sql="SELECT CONCAT_WS('|','S','','',PRIVILEGE_TYPE,IS_GRANTABLE) FROM information_schema.SCHEMA_PRIVILEGES WHERE TABLE_SCHEMA='$schema' AND GRANTEE=$g UNION ALL SELECT CONCAT_WS('|','T',TABLE_NAME,'',PRIVILEGE_TYPE,IS_GRANTABLE) FROM information_schema.TABLE_PRIVILEGES WHERE TABLE_SCHEMA='$schema' AND GRANTEE=$g UNION ALL SELECT CONCAT_WS('|','C',TABLE_NAME,COLUMN_NAME,PRIVILEGE_TYPE,IS_GRANTABLE) FROM information_schema.COLUMN_PRIVILEGES WHERE TABLE_SCHEMA='$schema' AND GRANTEE=$g ORDER BY 1"
  return @(Query $sql)
 }
 foreach($entry in $map){
  $source=$entry.source;$target=$entry.target
  $principals=@($config.principals | Where-Object {$_.schema -ceq $source})
  if($principals.Count -ne 1){throw 'Missing principal mapping'}
  $from=[string]$principals[0].sourceUser;$to=[string]$principals[0].restoreUser
  if($from -notmatch '^[A-Za-z0-9_]{1,32}$' -or $to -notmatch '^ias_restore_[A-Za-z0-9_]{1,20}$' -or $from -ceq $to){throw 'Invalid dedicated restore principal'}
  $check="SELECT (SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='$target'),(SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA='$target' AND REFERENCED_TABLE_SCHEMA IS NOT NULL AND REFERENCED_TABLE_SCHEMA<>TABLE_SCHEMA),(SELECT COUNT(*) FROM information_schema.VIEWS WHERE TABLE_SCHEMA IN ('$source','$target'))+(SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA IN ('$source','$target'))+(SELECT COUNT(*) FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA IN ('$source','$target'))+(SELECT COUNT(*) FROM information_schema.EVENTS WHERE EVENT_SCHEMA IN ('$source','$target'))"
  $found=@(Query $check);if($found.Count -ne 1 -or $found[0] -cne "1`t0`t0"){throw 'Schema absent, cross-schema reference or executable object requires review'}
  # Reject incomplete restores, including missing tables and differences in column definitions.
  $structure="SELECT (SELECT COUNT(*) FROM information_schema.TABLES s LEFT JOIN information_schema.TABLES t ON t.TABLE_SCHEMA='$target' AND t.TABLE_NAME=s.TABLE_NAME AND t.TABLE_TYPE=s.TABLE_TYPE WHERE s.TABLE_SCHEMA='$source' AND t.TABLE_NAME IS NULL)+(SELECT COUNT(*) FROM information_schema.TABLES t LEFT JOIN information_schema.TABLES s ON s.TABLE_SCHEMA='$source' AND s.TABLE_NAME=t.TABLE_NAME AND s.TABLE_TYPE=t.TABLE_TYPE WHERE t.TABLE_SCHEMA='$target' AND s.TABLE_NAME IS NULL)+(SELECT COUNT(*) FROM information_schema.COLUMNS s LEFT JOIN information_schema.COLUMNS t ON t.TABLE_SCHEMA='$target' AND t.TABLE_NAME=s.TABLE_NAME AND t.COLUMN_NAME=s.COLUMN_NAME AND t.ORDINAL_POSITION=s.ORDINAL_POSITION AND t.COLUMN_TYPE=s.COLUMN_TYPE AND t.IS_NULLABLE=s.IS_NULLABLE AND (t.COLUMN_DEFAULT <=> s.COLUMN_DEFAULT) AND t.EXTRA=s.EXTRA AND (t.GENERATION_EXPRESSION <=> s.GENERATION_EXPRESSION) WHERE s.TABLE_SCHEMA='$source' AND t.COLUMN_NAME IS NULL)+(SELECT COUNT(*) FROM information_schema.COLUMNS t LEFT JOIN information_schema.COLUMNS s ON s.TABLE_SCHEMA='$source' AND s.TABLE_NAME=t.TABLE_NAME AND s.COLUMN_NAME=t.COLUMN_NAME WHERE t.TABLE_SCHEMA='$target' AND s.COLUMN_NAME IS NULL)"
  $difference=@(Query $structure);if($difference.Count -ne 1 -or $difference[0] -cne '0'){throw 'Restored schema structure differs'}
  $expected=@(Grants $source $from);$actual=@(Grants $target $to)
  if($expected.Count -eq 0 -or $expected.Count -ne $actual.Count -or (Compare-Object $expected $actual -CaseSensitive)){throw 'Restore principal permissions do not match owner permissions'}
  $g=GrantExpression $to
  $extra="SELECT (SELECT COUNT(*) FROM information_schema.USER_PRIVILEGES WHERE GRANTEE=$g AND PRIVILEGE_TYPE<>'USAGE')+(SELECT COUNT(*) FROM information_schema.SCHEMA_PRIVILEGES WHERE GRANTEE=$g AND TABLE_SCHEMA<>'$target')+(SELECT COUNT(*) FROM information_schema.TABLE_PRIVILEGES WHERE GRANTEE=$g AND TABLE_SCHEMA<>'$target')+(SELECT COUNT(*) FROM information_schema.COLUMN_PRIVILEGES WHERE GRANTEE=$g AND TABLE_SCHEMA<>'$target')+(SELECT COUNT(*) FROM mysql.role_edges WHERE TO_USER='$to' AND TO_HOST='127.0.0.1')+(SELECT COUNT(*) FROM mysql.global_grants WHERE USER='$to' AND HOST='127.0.0.1')"
  $risk=@(Query $extra);if($risk.Count -ne 1 -or $risk[0] -cne '0'){throw 'Restore principal has extra or inherited access'}
 }
 $restoreRoot=[IO.Path]::GetFullPath($Context.privateRoots)
 $backupRoot=Split-Path -Parent $restoreRoot
 $manifest=Get-Content -LiteralPath (Join-Path $backupRoot 'backup-complete.json') -Raw -Encoding utf8 | ConvertFrom-Json
 if($manifest.gitSha -cne $Context.gitSha -or $manifest.schemaVersion -ne 1 -or !(Test-Path -LiteralPath $restoreRoot -PathType Container)){throw 'Backup or restored files missing'}
 if((Get-Item -LiteralPath $restoreRoot -Force).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Restored root is a link'}
 if($manifest.privateRootCount -lt 0 -or $manifest.privateRootCount -gt 100){throw 'Invalid private root count'}
 for($index=0;$index -lt $manifest.privateRootCount;$index++){
  $root=Join-Path $restoreRoot ('private-'+$index)
  if(!(Test-Path -LiteralPath $root -PathType Container) -or ((Get-Item -LiteralPath $root -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'Missing or linked restored private root'}
 }
 $expectedFiles=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
 foreach($file in $manifest.files){
  if($file.name -notmatch '^private-([0-9]+)[\\/]'){continue}
  $relative=([string]$file.name).Replace('/','\');$path=[IO.Path]::GetFullPath((Join-Path $restoreRoot $relative))
  if(!$path.StartsWith($restoreRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or !$expectedFiles.Add($path) -or !(Test-Path -LiteralPath $path -PathType Leaf) -or ((Get-Item -LiteralPath $path -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -or $file.sha256 -notmatch '^[a-fA-F0-9]{64}$' -or (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $file.sha256){throw 'Restored private file mismatch'}
 }
 foreach($item in Get-ChildItem -LiteralPath $restoreRoot -Recurse -Force){if(($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -or (!$item.PSIsContainer -and !$expectedFiles.Contains([IO.Path]::GetFullPath($item.FullName)))){throw 'Unexpected restored private file'}}
 [pscustomobject]@{ok=$true;action='ReviewRestore';gitSha=$Context.gitSha;schemaMap=$map;allSchemasExist=$true;schemaReferencesVerified=$true;definersVerified=$true;minimumGrantsVerified=$true;privateFilesVerified=$true}
} catch {throw 'Restore review failed; preserve the isolated targets and inspect restricted diagnostics'}
