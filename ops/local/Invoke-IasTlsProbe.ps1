param(
 [Parameter(Mandatory=$true)][ValidateSet('Readiness')][string]$Action,
 [Parameter(Mandatory=$true)][string]$ConfigPath,
 [Parameter(Mandatory=$true)]$Context
)
$ErrorActionPreference='Stop'
try {
 $config=Get-Content -LiteralPath $ConfigPath -Raw -Encoding utf8 | ConvertFrom-Json
 if(!(Test-Path -LiteralPath $config.javaExecutable -PathType Leaf) -or $config.javaSha256 -notmatch '^[a-fA-F0-9]{64}$' -or (Get-FileHash -LiteralPath $config.javaExecutable -Algorithm SHA256).Hash -ne $config.javaSha256){throw 'Invalid Java pin'}
 $tickets=@($Context.services | Where-Object role -eq 'ticket')
 if($tickets.Count -ne 1 -or !(Test-Path -LiteralPath $tickets[0].jar -PathType Leaf) -or $tickets[0].sha256 -notmatch '^[a-fA-F0-9]{64}$' -or (Get-FileHash -LiteralPath $tickets[0].jar -Algorithm SHA256).Hash -ne $tickets[0].sha256){throw 'Invalid probe artifact pin'}
 foreach($role in @('ticket','stock')){
  $path=$config.roleConfigs.$role;if(!(Test-Path -LiteralPath $path -PathType Leaf)){throw 'Missing private role config'}
  $private=Get-Content -LiteralPath $path -Raw -Encoding utf8 | ConvertFrom-Json
  if($private.role -cne $role){throw 'Invalid private role config'}
 }
 $old=$env:IAS_CHAIN_CONFIG;$roles=@()
 try {
  foreach($role in @('identity','asset','ticket','approval','stock','notification','finance')){
   $source=if($role -eq 'ticket'){'stock'}else{'ticket'};$env:IAS_CHAIN_CONFIG=$config.roleConfigs.$source
   $output=@(& $config.javaExecutable '-Dloader.main=com.ias.trust.ServiceProbeCli' '-cp' $tickets[0].jar 'org.springframework.boot.loader.launch.PropertiesLauncher' $role 2>$null)
   if($LASTEXITCODE -ne 0 -or $output.Count -ne 1){throw 'Authenticated TLS probe failed'}
   $receipt=$output[0] | ConvertFrom-Json
   if($receipt.role -cne $role -or $receipt.httpVerified -ne $true -or $receipt.tlsVerified -ne $true -or $receipt.roleIdentityVerified -ne $true -or $receipt.trustAllUsed -ne $false){throw 'Invalid role probe receipt'}
   $roles+=$receipt
  }
 } finally {if($null -eq $old){Remove-Item Env:\IAS_CHAIN_CONFIG -ErrorAction SilentlyContinue}else{$env:IAS_CHAIN_CONFIG=$old}}
 [pscustomobject]@{ok=$true;action='Readiness';gitSha=$Context.gitSha;roles=$roles}
}catch{throw 'Authenticated TLS role verification failed'}
