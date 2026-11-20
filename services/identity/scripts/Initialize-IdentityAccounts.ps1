param(
    [string]$PlanPath=(Join-Path $PSScriptRoot '..\bootstrap\local-accounts.json'),
    [string]$SecretPath='C:\ias-local\Develop\IAS-MySQL84\secrets\identity-local-passwords.json',
    [switch]$GenerateSecrets
)
$ErrorActionPreference='Stop'
$module=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$PlanPath=[IO.Path]::GetFullPath($PlanPath)
$SecretPath=[IO.Path]::GetFullPath($SecretPath)
function Assert-PrivateFileSystemAcl([string]$PathToCheck){
    $allowed=@([Security.Principal.WindowsIdentity]::GetCurrent().User.Value,'S-1-5-18')
    foreach($rule in (Get-Acl -LiteralPath $PathToCheck).Access){
        if($rule.AccessControlType -eq 'Allow' -and $rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value -notin $allowed){throw 'Secret file/directory must grant access only to the current operator and SYSTEM.'}
    }
}
if($SecretPath.StartsWith([IO.Path]::GetFullPath((Join-Path $module '..\..\..')), [StringComparison]::OrdinalIgnoreCase)){throw 'Secret files must stay outside project workspace.'}
if(!(Test-Path -LiteralPath $SecretPath)){
    if(!$GenerateSecrets){throw 'Secret file missing; use explicit -GenerateSecrets once, or supply a protected private file.'}
    $plan=Get-Content -LiteralPath $PlanPath -Raw -Encoding UTF8|ConvertFrom-Json
    $passwords=[ordered]@{}
    $rng=[Security.Cryptography.RandomNumberGenerator]::Create()
    try{
        foreach($user in $plan.users){$bytes=New-Object byte[] 32;$rng.GetBytes($bytes);$passwords[$user.passwordRef]=[Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_')}
    }finally{$rng.Dispose()}
    # Parent is the existing protected secrets directory; no workspace/command-line password output.
    if(!(Test-Path -LiteralPath ([IO.Path]::GetDirectoryName($SecretPath)))){throw 'Use an existing private secrets directory.'}
    Assert-PrivateFileSystemAcl ([IO.Path]::GetDirectoryName($SecretPath))
    $stream=[IO.File]::Open($SecretPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try{$bytes=[Text.Encoding]::UTF8.GetBytes(($passwords|ConvertTo-Json));$stream.Write($bytes,0,$bytes.Length)}finally{$stream.Dispose()}
    $acl=Get-Acl -LiteralPath $SecretPath
    $acl.SetAccessRuleProtection($true,$false)
    $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.WindowsIdentity]::GetCurrent().User,'FullControl','Allow'))
    $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.SecurityIdentifier]::new('S-1-5-18'),'FullControl','Allow'))
    Set-Acl -LiteralPath $SecretPath -AclObject $acl
    Remove-Variable passwords,bytes
}
Assert-PrivateFileSystemAcl $SecretPath
. (Join-Path $module '..\..\..\环境工具\Enter-IdentityEnv.ps1')
$credentials=Get-Content -LiteralPath 'C:\ias-local\Develop\IAS-MySQL84\secrets\mysql-credentials.json' -Raw|ConvertFrom-Json
if(!$credentials.PSObject.Properties['initializerUser']){throw 'Run explicit Grant-AccountPrivileges.ps1 first.'}
$env:IAS_INITIALIZER_USER=$credentials.initializerUser
$env:IAS_INITIALIZER_PASSWORD=$credentials.initializerPassword
$env:IAS_BOOTSTRAP_PLAN=$PlanPath
$env:IAS_BOOTSTRAP_SECRET_FILE=$SecretPath
Remove-Variable credentials
try{
    $jar=Join-Path $module 'target\identity-0.0.1-SNAPSHOT.jar'
    if(!(Test-Path -LiteralPath $jar)){throw 'Build the current runtime JAR first.'}
    & (Join-Path $env:JAVA_HOME 'bin\java.exe') '-Dloader.main=com.ias.identity.bootstrap.BootstrapMain' '-cp' $jar 'org.springframework.boot.loader.launch.PropertiesLauncher'
    if($LASTEXITCODE -ne 0){throw 'Initialization not confirmed. Inspect non-secret plan/privileges; rerun same plan; never reset existing passwords.'}
}finally{Remove-Item Env:\IAS_INITIALIZER_USER,Env:\IAS_INITIALIZER_PASSWORD,Env:\IAS_BOOTSTRAP_PLAN,Env:\IAS_BOOTSTRAP_SECRET_FILE -ErrorAction SilentlyContinue}
