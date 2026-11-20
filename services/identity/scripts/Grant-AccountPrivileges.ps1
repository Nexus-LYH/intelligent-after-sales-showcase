$ErrorActionPreference='Stop'
$mysql='C:\ias-local\Develop\mysql8.4\main\bin\mysql.exe'
$config='--defaults-extra-file=C:\ias-local\Develop\IAS-MySQL84\secrets\mysql-root.cnf'
$check=& $mysql $config --host=127.0.0.1 --port=3307 --database=ias_identity_test --raw --batch --skip-column-names --execute='SELECT @@port,DATABASE(),@@datadir;'
if($LASTEXITCODE -ne 0 -or ($check -replace '\\','/').Trim() -ne "3307`tias_identity_test`tC:/ias-local/Develop/IAS-MySQL84/data/"){throw 'Refusing privileges outside dedicated instance.'}
$file='C:\ias-local\Develop\IAS-MySQL84\secrets\mysql-credentials.json'
$credentials=Get-Content -LiteralPath $file -Raw | ConvertFrom-Json
if(!$credentials.PSObject.Properties['initializerUser']){
    $bytes=New-Object byte[] 32
    $rng=[Security.Cryptography.RandomNumberGenerator]::Create()
    try{$rng.GetBytes($bytes)}finally{$rng.Dispose()}
    $credentials|Add-Member NoteProperty initializerUser 'ias_identity_initializer'
    $credentials|Add-Member NoteProperty initializerPassword ([Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_'))
    [IO.File]::WriteAllText($file,($credentials|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
}
$app=$credentials.appUser
$operator=$credentials.initializerUser
$secret=$credentials.initializerPassword
if($app -notmatch '^[a-zA-Z0-9_]+$' -or $operator -ne 'ias_identity_initializer' -or $secret -notmatch '^[A-Za-z0-9_-]{43}$'){throw 'Unexpected scoped operator configuration.'}
$sql=@"
CREATE USER IF NOT EXISTS '$operator'@'127.0.0.1' IDENTIFIED BY '$secret';
GRANT SELECT ON ias_identity_test.tenant TO '$operator'@'127.0.0.1';
GRANT SELECT ON ias_identity_test.identity_user TO '$operator'@'127.0.0.1';
GRANT SELECT ON ias_identity_test.identity_user_capability TO '$operator'@'127.0.0.1';
GRANT SELECT ON ias_identity_test.flyway_schema_history TO '$operator'@'127.0.0.1';
GRANT INSERT ON ias_identity_test.tenant TO '$operator'@'127.0.0.1';
GRANT INSERT ON ias_identity_test.identity_user TO '$operator'@'127.0.0.1';
GRANT INSERT ON ias_identity_test.identity_user_capability TO '$operator'@'127.0.0.1';
GRANT INSERT ON ias_identity_test.identity_security_audit TO '$operator'@'127.0.0.1';
GRANT LOCK TABLES ON ias_identity_test.* TO '$operator'@'127.0.0.1';
GRANT UPDATE (password_verifier,must_change_password) ON ias_identity_test.identity_user TO '$app'@'127.0.0.1';
"@
# Secret SQL goes through stdin, never argv or output. Existing operator/password is not reset.
$sql | & $mysql $config --host=127.0.0.1 --port=3307 --database=ias_identity_test --batch 2>$null
if($LASTEXITCODE -ne 0){throw 'Scoped privilege setup failed; private operator check required.'}
Remove-Variable credentials,secret,sql
Write-Host 'Dedicated offline initializer and password-update privileges ready; no business accounts created.'
