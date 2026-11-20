param(
 [Parameter(Mandatory=$true)][ValidateSet('Migrate')][string]$Action,
 [Parameter(Mandatory=$true)][string]$ConfigPath,
 [Parameter(Mandatory=$true)]$Context
)
$ErrorActionPreference='Stop'
try{
 if($Context.databasePort -ne 3307 -or $Context.gitSha -notmatch '^[a-fA-F0-9]{40}$'){throw 'Release context invalid'}
 $config=Get-Content -LiteralPath $ConfigPath -Raw -Encoding utf8 | ConvertFrom-Json
 foreach($pin in @($config.java,$config.ticketJar)){
  if(!$pin -or !(Test-Path -LiteralPath $pin.path -PathType Leaf) -or $pin.sha256 -notmatch '^[a-fA-F0-9]{64}$' -or (Get-FileHash -LiteralPath $pin.path -Algorithm SHA256).Hash -ne $pin.sha256){throw 'Pinned migration executable invalid'}
 }
 $releaseJava=@($Context.tools | Where-Object name -eq 'java');$releaseTicket=@($Context.services | Where-Object role -eq 'ticket')
 if($releaseJava.Count -ne 1 -or $releaseTicket.Count -ne 1 -or $config.java.path -cne $releaseJava[0].executable -or $config.java.sha256 -ine $releaseJava[0].sha256 -or $config.ticketJar.path -cne $releaseTicket[0].jar -or $config.ticketJar.sha256 -ine $releaseTicket[0].sha256){throw 'Migration executable does not match release'}
 if(!(Test-Path -LiteralPath $config.accountsPath -PathType Leaf)){throw 'Migration account references missing'}
 $accounts=Get-Content -LiteralPath $config.accountsPath -Raw -Encoding utf8 | ConvertFrom-Json
 $expected=@($Context.schemas | Sort-Object -CaseSensitive);$actual=@($config.schemas.name | Sort-Object -CaseSensitive)
 if($expected.Count -eq 0 -or $expected.Count -ne $actual.Count -or (Compare-Object -ReferenceObject $expected -DifferenceObject $actual -CaseSensitive)){throw 'Migration schema mapping mismatch'}
 $allowed=@('ias_identity_test','ias_asset_test','ias_ticket_test','ias_approval_test','ias_stock_test','ias_notification_test','ias_finance_test')
 $seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
 # Verify the entire declared input before starting any migration.
 foreach($schema in $config.schemas){
  if($schema.name -cnotin $allowed -or !$seen.Add([string]$schema.name)){throw 'Invalid migration schema'}
  $directory=Get-Item -LiteralPath $schema.directory -Force
  if(!$directory.PSIsContainer -or ($directory.Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'Invalid migration directory'}
  $files=@(Get-ChildItem -LiteralPath $directory.FullName -Force)
  if($files.Count -eq 0 -or $files.Count -ne @($schema.files).Count){throw 'Migration input set mismatch'}
  $fileNames=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
  foreach($entry in $schema.files){
   if(!$entry.name -or [IO.Path]::GetFileName($entry.name) -cne $entry.name -or $entry.name -notmatch '^V[0-9]+__[^\\/]+\.sql$' -or !$fileNames.Add([string]$entry.name)){throw 'Invalid migration filename'}
   $file=Get-Item -LiteralPath (Join-Path $directory.FullName $entry.name) -Force
   if($file.Name -cne $entry.name -or $file.PSIsContainer -or ($file.Attributes -band [IO.FileAttributes]::ReparsePoint) -or $entry.sha256 -notmatch '^[a-fA-F0-9]{64}$' -or (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash -ne $entry.sha256){throw 'Migration file pin mismatch'}
  }
  $account=$accounts.PSObject.Properties[$schema.name].Value
  if(!$account -or $account.user -notmatch '^[A-Za-z0-9_]+$' -or [string]::IsNullOrWhiteSpace($account.password)){throw 'Migration account reference invalid'}
 }
 $oldUser=$env:IAS_MIGRATION_USER;$oldPassword=$env:IAS_MIGRATION_PASSWORD
 try{
  foreach($schema in $config.schemas){
   $account=$accounts.PSObject.Properties[$schema.name].Value;$env:IAS_MIGRATION_USER=$account.user;$env:IAS_MIGRATION_PASSWORD=$account.password
   $out=@(& $config.java.path '-Dloader.main=com.ias.ticket.LocalMigrationCli' '-cp' $config.ticketJar.path 'org.springframework.boot.loader.launch.PropertiesLauncher' $schema.name ([IO.Path]::GetFullPath($schema.directory)) 2>$null)
   if($LASTEXITCODE -ne 0 -or @($out | Where-Object { $_ -ceq ('IAS_MIGRATION_REENTRANT_OK:'+$schema.name) }).Count -ne 1){throw 'Migration or reentry check failed'}
  }
 }finally{
  if($null -eq $oldUser){Remove-Item Env:\IAS_MIGRATION_USER -ErrorAction SilentlyContinue}else{$env:IAS_MIGRATION_USER=$oldUser}
  if($null -eq $oldPassword){Remove-Item Env:\IAS_MIGRATION_PASSWORD -ErrorAction SilentlyContinue}else{$env:IAS_MIGRATION_PASSWORD=$oldPassword}
 }
 [pscustomobject]@{ok=$true;action='Migrate';gitSha=$Context.gitSha;schemas=$expected;reentrant=$true}
}catch{throw 'Pinned migration adapter failed; inspect restricted local diagnostics'}
