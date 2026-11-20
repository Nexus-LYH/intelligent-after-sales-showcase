$ErrorActionPreference = 'Stop'
# Explicitly scoped operator step; never called by runtime startup or the test runner.
$mysql = 'C:\ias-local\Develop\mysql8.4\main\bin\mysql.exe'
$config = '--defaults-extra-file=C:\ias-local\Develop\IAS-MySQL84\secrets\mysql-root.cnf'
$check = & $mysql $config --host=127.0.0.1 --port=3307 --database=ias_identity_test --raw --batch --skip-column-names --execute='SELECT @@port,DATABASE(),@@datadir;'
if ($LASTEXITCODE -ne 0 -or ($check -replace '\\','/').Trim() -ne "3307`tias_identity_test`tC:/ias-local/Develop/IAS-MySQL84/data/") { throw 'Refusing privilege changes outside dedicated test instance.' }
$credentials = Get-Content -LiteralPath 'C:\ias-local\Develop\IAS-MySQL84\secrets\mysql-credentials.json' -Raw | ConvertFrom-Json
$app = $credentials.appUser
Remove-Variable credentials
if ($app -notmatch '^[a-zA-Z0-9_]+$') { throw 'Unexpected app account identifier.' }
$sql = "GRANT UPDATE (status,security_version,row_version,updated_at) ON ias_identity_test.identity_user TO '$app'@'127.0.0.1'; GRANT INSERT ON ias_identity_test.identity_user TO '$app'@'127.0.0.1'; GRANT INSERT ON ias_identity_test.identity_employee_configuration TO '$app'@'127.0.0.1'; GRANT SELECT,INSERT ON ias_identity_test.identity_employee_create_operation TO '$app'@'127.0.0.1'; GRANT UPDATE (department_id) ON ias_identity_test.identity_user TO '$app'@'127.0.0.1'; GRANT INSERT,UPDATE (enabled,row_version,updated_at) ON ias_identity_test.identity_user_capability TO '$app'@'127.0.0.1'; GRANT INSERT,UPDATE (enabled) ON ias_identity_test.identity_employee_company_scope TO '$app'@'127.0.0.1'; GRANT INSERT,UPDATE (enabled) ON ias_identity_test.identity_employee_department_scope TO '$app'@'127.0.0.1'; GRANT INSERT,UPDATE (enabled) ON ias_identity_test.identity_approval_department_scope TO '$app'@'127.0.0.1'; GRANT INSERT,UPDATE (enabled) ON ias_identity_test.identity_reopen_department_scope TO '$app'@'127.0.0.1'; GRANT INSERT,UPDATE (enabled) ON ias_identity_test.identity_warehouse_scope TO '$app'@'127.0.0.1'; GRANT SELECT,INSERT ON ias_identity_test.identity_employee_scope_operation TO '$app'@'127.0.0.1'; GRANT SELECT,INSERT ON ias_identity_test.identity_employee_status_operation TO '$app'@'127.0.0.1';"
& $mysql $config --host=127.0.0.1 --port=3307 --database=ias_identity_test --execute=$sql
if ($LASTEXITCODE -ne 0) { throw 'Dedicated privilege update failed.' }
Write-Host 'Dedicated employee status privileges applied; no capability grants or users created.'




