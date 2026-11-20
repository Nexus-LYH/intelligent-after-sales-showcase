param(
 [Parameter(Mandatory=$true)][string]$ConfigPath,
 [Parameter(Mandatory=$true)][string]$CredentialOutput
)
$ErrorActionPreference='Stop'
if(Test-Path -LiteralPath $CredentialOutput){throw 'Credential output exists; inspect previous outcome before retry'}
$config=Get-Content -LiteralPath $ConfigPath -Raw -Encoding utf8 | ConvertFrom-Json
if(!(Test-Path -LiteralPath $config.mysqlExecutable -PathType Leaf) -or $config.mysqlSha256 -notmatch '^[a-fA-F0-9]{64}$' -or (Get-FileHash -LiteralPath $config.mysqlExecutable).Hash -ne $config.mysqlSha256 -or !(Test-Path -LiteralPath $config.mysqlOptionFile -PathType Leaf)){throw 'Invalid MySQL reference'}
if(@($config.principals).Count -ne 7 -or @($config.principals.schema | Select-Object -Unique).Count -ne 7){throw 'Seven owner mappings required'}
function Query([string]$sql){
 $result=@(& $config.mysqlExecutable ('--defaults-extra-file='+$config.mysqlOptionFile) --host=127.0.0.1 --port=3307 --protocol=TCP --batch --skip-column-names ('--execute='+$sql) 2>$null)
 if($LASTEXITCODE -ne 0){throw 'Database preflight failed'};return $result
}
$instance=@(Query 'SELECT @@datadir')
if($instance.Count -ne 1 -or ([string]$instance[0]).Replace('\\','/').TrimEnd('/') -ine 'C:/ias-local/Develop/IAS-MySQL84/data'){throw 'Unexpected MySQL instance'}
$owners=@('identity','asset','ticket','approval','stock','notification','finance');$targetUsers=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal);$statements=[Collections.Generic.List[string]]::new();$secrets=@{}
foreach($item in $config.principals){
 $source=[string]$item.schema;$target=[string]$item.targetSchema;$from=[string]$item.sourceUser;$to=[string]$item.restoreUser
 if($source -cnotin @($owners|ForEach-Object {'ias_'+$_+'_test'}) -or $target -notmatch '^ias_restore_[a-z0-9_]{1,30}_(identity|asset|ticket|approval|stock|notification|finance)$' -or !$target.EndsWith('_'+($source -replace '^ias_|_test$'),[StringComparison]::Ordinal) -or $from -notmatch '^[A-Za-z0-9_]{1,32}$' -or $to -notmatch '^ias_restore_[A-Za-z0-9_]{1,20}$' -or !$targetUsers.Add($to)){throw 'Invalid owner mapping'}
 $exists=@(Query "SELECT (SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='$target'),(SELECT COUNT(*) FROM mysql.user WHERE User='$to')")
 if($exists.Count -ne 1 -or $exists[0] -cne "1`t0"){throw 'Restore target missing or principal already exists; no user created'}
 $fromGrant="CONCAT(CHAR(39),'$from',CHAR(39),'@',CHAR(39),'127.0.0.1',CHAR(39))"
 $risk=@(Query "SELECT (SELECT COUNT(*) FROM information_schema.USER_PRIVILEGES WHERE GRANTEE=$fromGrant AND PRIVILEGE_TYPE<>'USAGE')+(SELECT COUNT(*) FROM mysql.role_edges WHERE TO_USER='$from' AND TO_HOST='127.0.0.1')+(SELECT COUNT(*) FROM mysql.global_grants WHERE USER='$from' AND HOST='127.0.0.1')")
 if($risk.Count -ne 1 -or $risk[0] -cne '0'){throw 'Source uses global or inherited permissions; manual review required'}
 $sql="SELECT CONCAT_WS('|','S','','',PRIVILEGE_TYPE,IS_GRANTABLE) FROM information_schema.SCHEMA_PRIVILEGES WHERE TABLE_SCHEMA='$source' AND GRANTEE=$fromGrant UNION ALL SELECT CONCAT_WS('|','T',TABLE_NAME,'',PRIVILEGE_TYPE,IS_GRANTABLE) FROM information_schema.TABLE_PRIVILEGES WHERE TABLE_SCHEMA='$source' AND GRANTEE=$fromGrant UNION ALL SELECT CONCAT_WS('|','C',TABLE_NAME,COLUMN_NAME,PRIVILEGE_TYPE,IS_GRANTABLE) FROM information_schema.COLUMN_PRIVILEGES WHERE TABLE_SCHEMA='$source' AND GRANTEE=$fromGrant ORDER BY 1"
 $grants=@(Query $sql);if($grants.Count -eq 0){throw 'Source owner has no explicit privileges'}
 $pw=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32));$secrets[$source]=@{user=$to;password=$pw;schema=$target}
 $statements.Add("CREATE USER '$to'@'127.0.0.1' IDENTIFIED BY '$pw';")
 foreach($grant in $grants){
  $parts=$grant.Split('|');if($parts.Count -ne 5 -or $parts[4] -cne 'NO' -or $parts[3] -cnotin @('SELECT','INSERT','UPDATE','DELETE','LOCK TABLES','EXECUTE','REFERENCES')){throw 'Unsupported or grantable privilege; manual review required'}
  $kind=$parts[0];$table=$parts[1];$column=$parts[2];$privilege=$parts[3]
  if($kind -eq 'S'){$object='`'+$target+'`.*';if($table -or $column){throw 'Invalid schema grant'}}
  elseif($kind -in @('T','C')){if($table -notmatch '^[A-Za-z0-9_]+$'){throw 'Invalid table grant'};$object='`'+$target+'`.`'+$table+'`';if($kind -eq 'C'){if($column -notmatch '^[A-Za-z0-9_]+$'){throw 'Invalid column grant'};$privilege+=' (`'+$column+'`)'}elseif($column){throw 'Invalid table grant'}}
  else{throw 'Invalid grant type'}
  $statements.Add("GRANT $privilege ON $object TO '$to'@'127.0.0.1';")
 }
}
# CredentialOutput must be inside the existing, access-restricted project secrets directory.
$secretRoot=[IO.Path]::GetFullPath('C:\ias-local\Develop\IAS-MySQL84\secrets').TrimEnd([IO.Path]::DirectorySeparatorChar)+[IO.Path]::DirectorySeparatorChar
$destination=[IO.Path]::GetFullPath($CredentialOutput)
if(!$destination.StartsWith($secretRoot,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetDirectoryName($destination) -ine $secretRoot.TrimEnd([IO.Path]::DirectorySeparatorChar)){throw 'Credential output must be directly in the restricted secrets directory'}
$allowedSids=@([Security.Principal.WindowsIdentity]::GetCurrent().User.Value,'S-1-5-18')
foreach($rule in (Get-Acl -LiteralPath $secretRoot).Access){
 if($rule.AccessControlType -eq [Security.AccessControl.AccessControlType]::Allow){
  try {$sid=$rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value}catch{throw 'Cannot verify secrets directory permissions'}
  if($sid -notin $allowedSids){throw 'Secrets directory grants access to another principal'}
 }
}
$secretJson=$secrets | ConvertTo-Json -Depth 5
$stream=[IO.File]::Open($destination,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
try {$writer=[IO.StreamWriter]::new($stream,[Text.UTF8Encoding]::new($false));try{$writer.Write($secretJson)}finally{$writer.Dispose()}}finally{$stream.Dispose()}
try {
 $statements | & $config.mysqlExecutable ('--defaults-extra-file='+$config.mysqlOptionFile) --host=127.0.0.1 --port=3307 --protocol=TCP 2>$null
 if($LASTEXITCODE -ne 0){throw 'Provisioning result unknown'}
 'Restore principals provisioned; perform independent access review before any switch.'
} catch {throw 'Provisioning failed or result unknown; retain credential file and inspect existing users before retry'}
