param([string]$Tests = '*Test')
$ErrorActionPreference = 'Stop'
$toolsRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..\..\环境工具'))
. (Join-Path $toolsRoot 'Enter-IdentityEnv.ps1')
$credentials = Get-Content -LiteralPath 'C:\ias-local\Develop\IAS-MySQL84\secrets\mysql-credentials.json' -Raw | ConvertFrom-Json
$env:IAS_MIGRATION_USER = $credentials.migrationUser
$env:IAS_MIGRATION_PASSWORD = $credentials.migrationPassword
Remove-Variable credentials
try {
    mvn -B -ntp -f '..\trust\pom.xml' '-DskipTests' install
    if ($LASTEXITCODE -ne 0) { throw 'Current shared trust library build failed.' }
    # Restart regression launches the current runtime JAR in a separate process.
    mvn -B -ntp '-DskipTests' package
    if ($LASTEXITCODE -ne 0) { throw 'Current runtime JAR build failed.' }
    mvn -B -ntp "-Dtest=$Tests" test
    if ($LASTEXITCODE -ne 0) { throw 'identity verification failed; inspect target/surefire-reports. Do not print credentials.' }
} finally {
    Remove-Item Env:\IAS_MIGRATION_USER,Env:\IAS_MIGRATION_PASSWORD -ErrorAction SilentlyContinue
}
