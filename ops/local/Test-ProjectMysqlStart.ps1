$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'LocalLifecycle.ps1')
$script:listener=$false; $script:existing=$false; $script:starts=0; $script:confirms=0; $script:reject=$false
function Assert-IasFileHash($Path,$Hash){if($Hash -ne 'valid'){throw 'Artifact hash mismatch'}}
function Get-NetTCPConnection {if($script:listener){[pscustomobject]@{LocalPort=3307}}}
function Get-CimInstance {if($script:existing){[pscustomobject]@{ExecutablePath='C:\ias-local\Develop\mysql8.4\main\bin\mysqld.exe';CommandLine='mysqld --defaults-file=C:\ias-local\Develop\IAS-MySQL84\my.ini'}}}
function Test-Path {return $true}
function Start-Process {$script:starts++;$script:listener=$true}
function Start-Sleep {$script:listener=$true}
function Confirm-IasMysqlInstance($Config){$script:confirms++;if($script:reject){throw 'Wrong database'}}
$server=[pscustomobject]@{executable='C:\ias-local\Develop\mysql8.4\main\bin\mysqld.exe';sha256='valid';configPath='C:\ias-local\Develop\IAS-MySQL84\my.ini';configSha256='valid'}
$config=[pscustomobject]@{databasePort=3307;mysqlServer=$server}
function Expect-Error([scriptblock]$body){$failed=$false;try{& $body}catch{$failed=$true};if(!$failed){throw 'Expected failure'}}
$script:listener=$true; Start-IasProjectMysql $config
if($script:starts -ne 0 -or $script:confirms -ne 1){throw 'Existing listener not verified'}
$script:reject=$true; Expect-Error {Start-IasProjectMysql $config}
if($script:starts -ne 0){throw 'Unexpected start on occupied port'}
$script:reject=$false; $script:listener=$false; $script:existing=$true; Start-IasProjectMysql $config
if($script:starts -ne 0){throw 'Started duplicate MySQL'}
$script:listener=$false; $script:existing=$false; Start-IasProjectMysql $config
if($script:starts -ne 1){throw 'Did not start project MySQL once'}
$config.databasePort=3306; Expect-Error {Start-IasProjectMysql $config}
$config.databasePort=3307; $server.configSha256='bad'; Expect-Error {Start-IasProjectMysql $config}
if($script:starts -ne 1){throw 'Invalid configuration started MySQL'}
Write-Output 'PASS: project MySQL startup selection and rejection checks; mocked processes only.'
