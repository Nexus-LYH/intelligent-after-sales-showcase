param(
 [Parameter(Mandatory=$true)][ValidateSet('Bootstrap')][string]$Action,
 [Parameter(Mandatory=$true)][string]$ConfigPath,
 [Parameter(Mandatory=$true)]$Context
)
$ErrorActionPreference='Stop'
try {
 if($Context.databasePort -ne 3307 -or $Context.gitSha -notmatch '^[a-fA-F0-9]{40}$' -or 'ias_identity_test' -cnotin @($Context.schemas)){throw 'Invalid release context'}
 $config=Get-Content -LiteralPath $ConfigPath -Raw -Encoding utf8 | ConvertFrom-Json
 foreach($pin in @($config.java,$config.identityJar,$config.plan)){
  if(!$pin -or !(Test-Path -LiteralPath $pin.path -PathType Leaf) -or $pin.sha256 -notmatch '^[a-fA-F0-9]{64}$' -or (Get-FileHash -LiteralPath $pin.path -Algorithm SHA256).Hash -ne $pin.sha256){throw 'Invalid pinned input'}
 }
 $java=@($Context.tools | Where-Object name -eq 'java');$identity=@($Context.services | Where-Object role -eq 'identity')
 if($java.Count -ne 1 -or $identity.Count -ne 1 -or $config.java.path -cne $java[0].executable -or $config.java.sha256 -ine $java[0].sha256 -or $config.identityJar.path -cne $identity[0].jar -or $config.identityJar.sha256 -ine $identity[0].sha256){throw 'Bootstrap does not match release artifacts'}
 if(!(Test-Path -LiteralPath $config.secretsPath -PathType Leaf) -or !(Test-Path -LiteralPath $config.credentialsPath -PathType Leaf)){throw 'Restricted inputs missing'}
 $credentials=Get-Content -LiteralPath $config.credentialsPath -Raw -Encoding utf8 | ConvertFrom-Json
 if(!$credentials.initializerUser -or !$credentials.initializerPassword){throw 'Initializer principal missing'}
 $keys=@('IAS_INITIALIZE_DATABASE','IAS_BOOTSTRAP_PLAN','IAS_BOOTSTRAP_SECRET_FILE','IAS_INITIALIZER_USER','IAS_INITIALIZER_PASSWORD')
 $old=@{};foreach($key in $keys){$old[$key]=[Environment]::GetEnvironmentVariable($key,'Process')}
 try {
  $env:IAS_INITIALIZE_DATABASE='ias_identity_test';$env:IAS_BOOTSTRAP_PLAN=$config.plan.path;$env:IAS_BOOTSTRAP_SECRET_FILE=$config.secretsPath
  $env:IAS_INITIALIZER_USER=$credentials.initializerUser;$env:IAS_INITIALIZER_PASSWORD=$credentials.initializerPassword
  $out=@(& $config.java.path '-Dloader.main=com.ias.identity.bootstrap.LocalReleaseBootstrapCli' '-cp' $config.identityJar.path 'org.springframework.boot.loader.launch.PropertiesLauncher' 2>$null)
  if($LASTEXITCODE -ne 0 -or $out.Count -ne 1 -or $out[0] -cne 'IAS_BOOTSTRAP_REENTRANT_PASSWORDS_UNCHANGED'){throw 'Bootstrap result unknown'}
 } finally {
  foreach($key in $keys){if($null -eq $old[$key]){Remove-Item -LiteralPath ('Env:'+ $key) -ErrorAction SilentlyContinue}else{[Environment]::SetEnvironmentVariable($key,$old[$key],'Process')}}
  Remove-Variable credentials -ErrorAction SilentlyContinue
 }
 [pscustomobject]@{ok=$true;action='Bootstrap';gitSha=$Context.gitSha;reentrant=$true;existingPasswordsChanged=$false}
} catch {throw 'Pinned bootstrap failed or outcome unknown; retain the original inputs and inspect restricted diagnostics'}
