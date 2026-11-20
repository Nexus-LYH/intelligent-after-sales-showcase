. (Join-Path $PSScriptRoot 'ReleaseGates.ps1')
function Assert-IasFileHash($Path,$Hash){
 if(!(Test-Path -LiteralPath $Path -PathType Leaf) -or $Hash -notmatch '^[a-fA-F0-9]{64}$'){throw 'Missing pinned artifact'}
 if((Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash -ne $Hash){throw 'Artifact hash mismatch'}
}
function Assert-IasExpectedMysqlDataDir([string]$DataDir){
 if([string]::IsNullOrWhiteSpace($DataDir)){throw 'MySQL data directory not returned'}
 $expected=[IO.Path]::GetFullPath('C:\ias-local\Develop\IAS-MySQL84\data').TrimEnd([IO.Path]::DirectorySeparatorChar,[IO.Path]::AltDirectorySeparatorChar)
 try {$actual=[IO.Path]::GetFullPath($DataDir.Trim()).TrimEnd([IO.Path]::DirectorySeparatorChar,[IO.Path]::AltDirectorySeparatorChar)}catch{throw 'Invalid MySQL data directory'}
 if(!$actual.Equals($expected,[StringComparison]::OrdinalIgnoreCase)){throw 'MySQL 3307 is not the project data directory'}
}
function Confirm-IasMysqlInstance($Config){
 if($Config.databasePort -ne 3307){throw 'Only independent MySQL 3307 supported'}
 if(!(Test-Path -LiteralPath $Config.mysqlOptionFile -PathType Leaf)){throw 'Restricted MySQL option-file reference required'}
 $mysql=@($Config.tools | Where-Object name -eq 'mysql')
 if($mysql.Count -ne 1 -or $Config.mysqlExecutable -cne $mysql[0].executable){throw 'Pinned MySQL client mismatch'}
 Assert-IasFileHash $mysql[0].executable $mysql[0].sha256
 $result=@(& $Config.mysqlExecutable ('--defaults-extra-file='+$Config.mysqlOptionFile) --host=127.0.0.1 --port=3307 --protocol=TCP --batch --skip-column-names '--execute=SELECT @@datadir' 2>$null)
 if($LASTEXITCODE -ne 0 -or $result.Count -ne 1){throw 'Unable to verify MySQL data directory'}
 Assert-IasExpectedMysqlDataDir ([string]$result[0])
}
function Start-IasProjectMysql($Config){
 if($Config.databasePort -ne 3307){throw 'Only independent MySQL 3307 supported'}
 $server=Get-IasOptional $Config 'mysqlServer'
 if(!$server){throw 'Pinned project MySQL server configuration required'}
 if($server.executable -cne 'C:\ias-local\Develop\mysql8.4\main\bin\mysqld.exe' -or $server.configPath -cne 'C:\ias-local\Develop\IAS-MySQL84\my.ini'){throw 'Unexpected MySQL server or configuration path'}
 Assert-IasFileHash $server.executable $server.sha256
 Assert-IasFileHash $server.configPath $server.configSha256
 $listener=Get-NetTCPConnection -LocalPort 3307 -State Listen -ErrorAction SilentlyContinue
 if($listener){Confirm-IasMysqlInstance $Config;return}
 $existing=@(Get-CimInstance Win32_Process -Filter "name='mysqld.exe'" | Where-Object {$_.ExecutablePath -eq $server.executable -and $_.CommandLine -like ('*'+$server.configPath+'*')})
 # On Windows mysqld may spawn a child; either process means a start is already in progress.
 if(!$existing.Count){
  $logs='C:\ias-local\Develop\IAS-MySQL84\logs'
  if(!(Test-Path -LiteralPath $logs -PathType Container)){throw 'MySQL log directory missing'}
  $suffix=[datetime]::UtcNow.ToString('yyyyMMddHHmmssfff')+'-'+[guid]::NewGuid().ToString('N')
  Start-Process -FilePath $server.executable -ArgumentList ('--defaults-file="'+$server.configPath+'"') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logs ('local-start-'+$suffix+'.out.log')) -RedirectStandardError (Join-Path $logs ('local-start-'+$suffix+'.err.log')) | Out-Null
 }
 $deadline=[datetime]::UtcNow.AddSeconds(120)
 do {
  if(Get-NetTCPConnection -LocalPort 3307 -State Listen -ErrorAction SilentlyContinue){Confirm-IasMysqlInstance $Config;return}
  Start-Sleep -Seconds 1
 } while([datetime]::UtcNow -lt $deadline)
 throw 'Project MySQL startup timed out; inspect project MySQL logs before retrying'
}
function Read-IasLocalConfig($Path){
 $value=Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
 if($value.schemaVersion -ne 1 -or $value.databasePort -ne 3307){throw 'Only schema v1 and independent MySQL 3307 supported'}
 foreach($schema in $value.schemas){if($schema -notmatch '^ias_[a-z]+_test$'){throw 'Unexpected source schema'}}
 if(@($value.schemas | Select-Object -Unique).Count -ne 7){throw 'Seven unique owner schemas required'}
 return $value
}
function Test-IasLocalConfig($Config){
 if($Config.databasePort -ne 3307){throw 'Only independent MySQL 3307 supported'}
 if($Config.gitSha -notmatch '^[a-fA-F0-9]{40}$'){throw 'Fixed Git SHA required'}
 $server=Get-IasOptional $Config 'mysqlServer'
 if(!$server -or $server.executable -cne 'C:\ias-local\Develop\mysql8.4\main\bin\mysqld.exe' -or $server.configPath -cne 'C:\ias-local\Develop\IAS-MySQL84\my.ini'){throw 'Pinned project MySQL server configuration required'}
 Assert-IasFileHash $server.executable $server.sha256
 Assert-IasFileHash $server.configPath $server.configSha256
 Assert-IasExactVersions $Config
 foreach($adapter in @($Config.readinessAdapter,$Config.bootstrapAdapter,$Config.restoreReviewAdapter)){
  Assert-IasFileHash $adapter.script $adapter.sha256
  if(!(Test-Path -LiteralPath $adapter.configPath -PathType Leaf)){throw 'External restricted adapter configuration required'}
 }
 $roles=@('identity','asset','ticket','approval','stock','notification','finance','gateway')
 $ports=@{identity=8441;asset=8442;ticket=8443;approval=8444;stock=8445;notification=8446;finance=8447;gateway=8080}
 if(@($Config.services).Count -ne 8){throw 'Exactly eight services required'}
 if(@($Config.services.role | Select-Object -Unique).Count -ne 8){throw 'Duplicate service role'}
 if(@($Config.migrations).Count -eq 0){throw 'Explicit reviewed migration/verification adapters required'}
 Assert-IasMigrationCoverage $Config.schemas $Config.migrations
 foreach($service in $Config.services){
  if($service.role -notin $roles -or $service.port -ne $ports[$service.role]){throw 'Unexpected role or port'}
  Assert-IasFileHash $service.jar $service.sha256
 }
 Assert-IasFileHash $Config.launcher $Config.launcherSha256
 Assert-IasRuntimeReferences $Config.runtimeReferences
 if(!(Test-Path -LiteralPath $Config.javaExecutable -PathType Leaf)){throw 'Java executable missing'}
 $version=(& $Config.javaExecutable -version 2>&1 | Out-String)
 if($version -notmatch 'version "21\.') {throw 'Java 21 required'}
 foreach($step in $Config.migrations){Assert-IasFileHash $step.script $step.sha256}
 Assert-IasFrontends $Config
 foreach($entry in $Config.privateFileRoots){if(!(Test-Path -LiteralPath $entry -PathType Container)){throw 'Private file root missing'}}
 foreach($schema in $Config.schemas){if($schema -notmatch '^ias_[a-z]+_test$'){throw 'Unexpected source schema'}}
 if(@($Config.schemas).Count -ne 7){throw 'Seven owner schemas required'}
}
function Assert-IasRuntimeReferences($References){
 if(!$References){throw 'Explicit runtime configuration references required'}
 foreach($name in @('environmentScript','mysqlCredentialsConfig','domainRuntimeConfig')){
  $value=$References.$name
  if(!$value -or $value.Contains('"') -or ![IO.Path]::IsPathFullyQualified($value) -or !(Test-Path -LiteralPath $value -PathType Leaf)){throw 'Invalid runtime file reference'}
 }
 $directory=$References.chainDirectory
 if(!$directory -or $directory.Contains('"') -or ![IO.Path]::IsPathFullyQualified($directory) -or !(Test-Path -LiteralPath $directory -PathType Container)){throw 'Invalid runtime chain directory'}
 Assert-IasFileHash $References.environmentScript $References.environmentScriptSha256
 $restore=Get-IasOptional $References 'restoreConfig'
 if($restore){if($restore.Contains('"') -or ![IO.Path]::IsPathFullyQualified($restore)){throw 'Invalid restore configuration reference'};Assert-IasFileHash $restore (Get-IasOptional $References 'restoreConfigSha256')}
 foreach($role in @('identity','asset','ticket','approval','stock','notification','finance','gateway')){
  if(!(Test-Path -LiteralPath (Join-Path $directory ($role+'.json')) -PathType Leaf)){throw 'Missing runtime role configuration'}
 }
}
function Assert-IasFrontends($Config){
 if(@($Config.frontends).Count -ne 2 -or @($Config.frontends.role | Select-Object -Unique).Count -ne 2){throw 'Two pinned frontend roles required'}
 $ports=@{staff=4173;customer=4174}
 Assert-IasFileHash $Config.frontendServer $Config.frontendServerSha256
 foreach($front in $Config.frontends){
  if($front.role -notin @('staff','customer') -or $front.port -ne $ports[$front.role]){throw 'Unexpected frontend role or port'}
  Assert-IasFileHash $front.configPath $front.configSha256
  $frontConfig=Get-Content -LiteralPath $front.configPath -Raw | ConvertFrom-Json
  if($frontConfig.role -cne $front.role -or $frontConfig.port -ne $front.port -or @($frontConfig.files).Count -eq 0){throw 'Frontend artifact config mismatch'}
 }
}
function Get-IasFrontendProcessMatch($Entry){
 $process=Get-CimInstance Win32_Process -Filter ('ProcessId='+[int]$Entry.pid)
 if(!$process -or $process.CreationDate.ToUniversalTime().ToString('o') -ne ([datetime]$Entry.creationUtc).ToUniversalTime().ToString('o')){return $false}
 $tokenPattern='(?:^|\s)(?:"'+[regex]::Escape($Entry.server)+'"|'+[regex]::Escape($Entry.server)+')\s+(?:"'+[regex]::Escape($Entry.configPath)+'"|'+[regex]::Escape($Entry.configPath)+')(?:\s|$)'
 if($process.ExecutablePath -ne $Entry.node -or $process.CommandLine -notmatch $tokenPattern){return $false}
 Assert-IasFileHash $Entry.node $Entry.nodeSha256
 Assert-IasFileHash $Entry.server $Entry.serverSha256
 Assert-IasFileHash $Entry.configPath $Entry.configSha256
 return $true
}
function Start-IasFrontends($Config){
 $manifest=$Config.runtimeManifest+'.frontends'
 if(Test-Path -LiteralPath $manifest){throw 'Frontend manifest already exists'}
 foreach($front in $Config.frontends){if(Get-NetTCPConnection -LocalPort $front.port -State Listen -ErrorAction SilentlyContinue){throw 'Frontend port occupied'}}
 $node=@($Config.tools | Where-Object name -eq 'node')[0]
 $entries=[System.Collections.Generic.List[object]]::new()
 foreach($front in $Config.frontends){
  $nodeArguments='"'+$Config.frontendServer+'" "'+$front.configPath+'"'
  $proc=Start-Process -FilePath $node.executable -ArgumentList $nodeArguments -WindowStyle Hidden -PassThru
  # Use the same Windows process provider for recording and later ownership verification.
  # Process.StartTime and CIM CreationDate can differ by sub-microsecond rounding.
  $processInfo=Get-CimInstance Win32_Process -Filter ('ProcessId='+[int]$proc.Id)
  if(!$processInfo){throw 'Frontend process identity unavailable'}
  $entry=[pscustomobject]@{role=$front.role;port=$front.port;pid=$proc.Id;creationUtc=$processInfo.CreationDate.ToUniversalTime().ToString('o');node=$node.executable;nodeSha256=$node.sha256;server=$Config.frontendServer;serverSha256=$Config.frontendServerSha256;configPath=$front.configPath;configSha256=$front.configSha256}
  $entries.Add($entry);$entries.ToArray() | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $manifest -Encoding utf8
  $deadline=[datetime]::UtcNow.AddSeconds(30);$listener=$null
  do { if($proc.HasExited){throw 'Frontend exited; inspect local process diagnostics'}; $listener=Get-NetTCPConnection -LocalPort $front.port -State Listen -ErrorAction SilentlyContinue | Where-Object OwningProcess -eq $proc.Id | Select-Object -First 1; if(!$listener){Start-Sleep -Milliseconds 300} }while(!$listener -and [datetime]::UtcNow -lt $deadline)
  if(!$listener -or !(Get-IasFrontendProcessMatch $entry)){throw 'Pinned frontend did not start; manifest retained for inspection'}
  $response=Invoke-WebRequest -Uri ('http://127.0.0.1:'+ $front.port +'/'+$front.role+'/') -TimeoutSec 10
  if($response.StatusCode -ne 200 -or $response.Content -notmatch '<!doctype html|<html'){throw 'Frontend HTTP readiness failed'}
 }
}
function Get-IasProcessMatch($Entry){
 $process=Get-CimInstance Win32_Process -Filter ('ProcessId='+[int]$Entry.pid)
 if(!$process){return $false}
 $created=$process.CreationDate.ToUniversalTime().ToString('o')
 $expected=([datetime]$Entry.creationUtc).ToUniversalTime().ToString('o')
 if($created -ne $expected){return $false}
 # Exact quoted/unquoted -jar token, never substring PID or filename matching.
 $pattern='(?:^|\s)-jar\s+(?:"'+[regex]::Escape($Entry.jar)+'"|'+[regex]::Escape($Entry.jar)+')(?:\s|$)'
 if($process.CommandLine -notmatch $pattern){return $false}
 Assert-IasFileHash $Entry.jar $Entry.sha256
 return $true
}
function Get-IasLocalStatus($Config){
 $entries=if(Test-Path -LiteralPath $Config.runtimeManifest){@(Get-Content -LiteralPath $Config.runtimeManifest -Raw | ConvertFrom-Json)}else{@()}
 foreach($service in $Config.services){
  $entry=@($entries | Where-Object role -eq $service.role)
  $owned=$entry.Count -eq 1 -and (Get-IasProcessMatch $entry[0])
  $listener=@(Get-NetTCPConnection -LocalPort $service.port -State Listen -ErrorAction SilentlyContinue)
  [pscustomobject]@{role=$service.role;port=$service.port;manifestOwned=$owned;listening=($listener.Count -gt 0)}
 }
}
function Assert-IasNoServices($Config){
 foreach($service in $Config.services){if(Get-NetTCPConnection -LocalPort $service.port -State Listen -ErrorAction SilentlyContinue){throw 'Project service port occupied; coordinated stopped-service window required'}}
}
function Archive-IasRebootManifests($Config){
 $paths=@($Config.runtimeManifest,($Config.runtimeManifest+'.frontends'),($Config.runtimeManifest+'.starting')) | Where-Object {Test-Path -LiteralPath $_}
 if(!$paths){return}
 $boot=(Get-CimInstance Win32_OperatingSystem).LastBootUpTime.ToUniversalTime()
 foreach($front in $Config.frontends){if(Get-NetTCPConnection -LocalPort $front.port -State Listen -ErrorAction SilentlyContinue){throw 'Frontend port occupied; prior ownership must be inspected'}}
 foreach($path in $paths){
  $file=Get-Item -LiteralPath $path
  if($file.LastWriteTimeUtc -ge $boot){throw 'Current-boot manifest requires manual ownership inspection'}
  try {$entries=@(Get-Content -LiteralPath $path -Raw | ConvertFrom-Json)}catch{throw 'Invalid runtime manifest; manual inspection required'}
  if(!$entries.Count -or !$entries[0]){throw 'Empty runtime manifest; manual inspection required'}
  foreach($entry in $entries){
   $property=if($path.EndsWith('.starting')){'launcherPid'}else{'pid'}
   $pidValue=$entry.PSObject.Properties[$property]
   if(!$pidValue -or "$($pidValue.Value)" -notmatch '^[1-9][0-9]*$'){throw 'Invalid manifest PID; manual inspection required'}
   $process=Get-CimInstance Win32_Process -Filter ('ProcessId='+[int]$pidValue.Value)
   if($process){
    $timeProperty=if($path.EndsWith('.starting')){'launcherStartedUtc'}else{'creationUtc'}
    $recorded=$entry.PSObject.Properties[$timeProperty]
    try {$prior=if($recorded){([datetime]$recorded.Value).ToUniversalTime()}else{throw 'Missing timestamp'}}catch{throw 'Manifest PID exists; manual ownership inspection required'}
    if(!$process.CreationDate -or $prior -ge $boot -or $process.CreationDate.ToUniversalTime() -lt $boot){throw 'Manifest PID exists; manual ownership inspection required'}
   }
  }
 }
 $suffix='.stale-after-reboot-'+[datetime]::UtcNow.ToString('yyyyMMddHHmmssfff')+'-'+[guid]::NewGuid().ToString('N')
 foreach($path in $paths){Move-Item -LiteralPath $path -Destination ($path+$suffix)}
}
function Start-IasLocal($Config){
 Assert-IasNoServices $Config
 Archive-IasRebootManifests $Config
 if(Test-Path -LiteralPath $Config.runtimeManifest){throw 'Runtime manifest already exists; inspect prior ownership before starting'}
 Assert-IasRuntimeReferences $Config.runtimeReferences
 $restoreConfig=Get-IasOptional $Config.runtimeReferences 'restoreConfig'
 if($restoreConfig){
  $restore=Get-Content -LiteralPath $restoreConfig -Raw -Encoding utf8 | ConvertFrom-Json
  if(!$restore.backupDirectory -or !$restore.prefix -or !$restore.privateRoot){throw 'Incomplete restore configuration'}
  Assert-IasFileHash $restore.credentialsPath $restore.credentialsSha256
  $restoreAccounts=Get-Content -LiteralPath $restore.credentialsPath -Raw -Encoding utf8 | ConvertFrom-Json
  $reviewConfig=Get-Content -LiteralPath $Config.restoreReviewAdapter.configPath -Raw -Encoding utf8 | ConvertFrom-Json
  foreach($role in @('identity','asset','ticket','approval','stock','notification','finance')){
   $source='ias_'+$role+'_test';$account=$restoreAccounts.PSObject.Properties[$source].Value;$reviewed=@($reviewConfig.principals | Where-Object {$_.schema -ceq $source})
   if(!$account -or $reviewed.Count -ne 1 -or $account.user -cne $reviewed[0].restoreUser -or $account.schema -cne ($restore.prefix+'_'+$role)){throw 'Restore credential mapping differs from reviewed principals'}
  }
  Remove-Variable restoreAccounts,reviewConfig,account,reviewed
  $expectedRoot=[IO.Path]::GetFullPath((Join-Path $restore.backupDirectory ($restore.prefix+'-private-restored\private-0')))
  if(![IO.Path]::GetFullPath($restore.privateRoot).Equals($expectedRoot,[StringComparison]::OrdinalIgnoreCase)){throw 'Restore files are not the reviewed copy'}
 }
 Start-IasProjectMysql $Config
 if($restoreConfig){Test-IasRestoreReview $Config $restore.backupDirectory $restore.prefix | Out-Null}
 $entries=[System.Collections.Generic.List[object]]::new()
 foreach($role in @('identity','asset','ticket','approval','stock','notification','finance','gateway')){
  $service=$Config.services | Where-Object role -eq $role
  # Existing external launcher retains private configuration loading; no secret body enters this tool.
  $refs=$Config.runtimeReferences
  $arguments='-NoProfile -File "'+$Config.launcher+'" -Service '+$role+' -ArtifactDirectory "'+(Split-Path $service.jar)+'" -EnvironmentScript "'+$refs.environmentScript+'" -ChainDirectory "'+$refs.chainDirectory+'" -MysqlCredentialsConfig "'+$refs.mysqlCredentialsConfig+'" -DomainRuntimeConfig "'+$refs.domainRuntimeConfig+'"'
  if($restoreConfig){$arguments+=' -RestoreConfig "'+$restoreConfig+'"'}
  $process=Start-Process -FilePath $Config.powershellExecutable -ArgumentList $arguments -WindowStyle Hidden -PassThru
  [pscustomobject]@{role=$role;launcherPid=$process.Id;launcherStartedUtc=$process.StartTime.ToUniversalTime().ToString('o');expectedJar=$service.jar;expectedSha256=$service.sha256;state='STARTING'} | ConvertTo-Json | Set-Content -LiteralPath ($Config.runtimeManifest+'.starting') -Encoding utf8
  $deadline=[datetime]::UtcNow.AddSeconds(60)
  $java=$null
  do {
   if($process.HasExited){throw 'Service launcher exited before ready; inspect private local logs'}
   $listener=Get-NetTCPConnection -LocalPort $service.port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
   if($listener){$java=Get-CimInstance Win32_Process -Filter ('ProcessId='+$listener.OwningProcess);break}
   Start-Sleep -Milliseconds 300
  }while([datetime]::UtcNow -lt $deadline)
  if(!$java){throw 'Service listen timeout; previously started services remain owned in runtime manifest'}
  $entry=[pscustomobject]@{role=$role;port=$service.port;pid=$java.ProcessId;creationUtc=$java.CreationDate.ToUniversalTime().ToString('o');jar=$service.jar;sha256=$service.sha256}
  if(!(Get-IasProcessMatch $entry)){throw 'Listener is not the pinned service'}
  $entries.Add($entry)
  $entries.ToArray() | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $Config.runtimeManifest -Encoding utf8
  Move-Item -LiteralPath ($Config.runtimeManifest+'.starting') -Destination ($Config.runtimeManifest+'.started-'+$role+'-'+[datetime]::UtcNow.ToString('yyyyMMddHHmmssfff'))
 }
 # A listener may precede application readiness; repeat the complete authenticated probe.
 $ready=$false
 for($attempt=1;$attempt -le 4;$attempt++){
  try {Test-IasReadiness $Config; $ready=$true; break}
  catch {if($attempt -eq 4){throw}; Start-Sleep -Seconds 5}
 }
 if(!$ready){throw 'Authenticated readiness did not pass'}
 Start-IasFrontends $Config
}
function Stop-IasLocal($Config){
 if(!(Test-Path -LiteralPath $Config.runtimeManifest)){throw 'Runtime ownership manifest missing'}
 $entries=@(Get-Content -LiteralPath $Config.runtimeManifest -Raw | ConvertFrom-Json)
 if($entries.Count -eq 0){throw 'Runtime ownership manifest is empty; no stop performed'}
 if(@($entries.role | Select-Object -Unique).Count -ne $entries.Count -or @($entries.pid | Select-Object -Unique).Count -ne $entries.Count){throw 'Duplicate service role or PID; no stop performed'}
 $frontManifest=$Config.runtimeManifest+'.frontends'
 $frontEntries=@(if(Test-Path -LiteralPath $frontManifest){Get-Content -LiteralPath $frontManifest -Raw | ConvertFrom-Json})
 if($frontEntries.Count -and (@($frontEntries.role | Select-Object -Unique).Count -ne $frontEntries.Count -or @($frontEntries.pid | Select-Object -Unique).Count -ne $frontEntries.Count)){throw 'Duplicate frontend role or PID; no stop performed'}
 if($frontEntries.Count -and @($frontEntries | Where-Object { $_.pid -in @($entries.pid) }).Count){throw 'PID appears in frontend and service manifests; no stop performed'}
 foreach($entry in $frontEntries){
  $front=@($Config.frontends | Where-Object role -eq $entry.role)
  $node=@($Config.tools | Where-Object name -eq 'node')
  if($front.Count -ne 1 -or $node.Count -ne 1 -or $entry.port -ne $front[0].port -or $entry.configPath -ne $front[0].configPath -or $entry.configSha256 -ne $front[0].configSha256 -or $entry.server -ne $Config.frontendServer -or $entry.serverSha256 -ne $Config.frontendServerSha256 -or $entry.node -ne $node[0].executable -or $entry.nodeSha256 -ne $node[0].sha256 -or !(Get-IasFrontendProcessMatch $entry)){throw 'Frontend ownership mismatch; no stop performed'}
 }
 foreach($entry in $entries){
  if($entry.role -notin @($Config.services.role) -or $entry.port -eq 8081){throw 'Unknown service ownership'}
  $service=$Config.services | Where-Object role -eq $entry.role
  if($entry.jar -ne $service.jar -or $entry.sha256 -ne $service.sha256 -or $entry.port -ne $service.port){throw 'Manifest is not current release'}
  if(!(Get-IasProcessMatch $entry)){throw 'PID/start time/absolute JAR/SHA ownership mismatch; no stop performed'}
 }
 foreach($entry in $frontEntries){Stop-Process -Id $entry.pid -ErrorAction Stop}
 if($frontEntries.Count){Move-Item -LiteralPath $frontManifest -Destination ($frontManifest+'.stopped-'+[datetime]::UtcNow.ToString('yyyyMMddHHmmssfff'))}
 foreach($entry in ($entries | Sort-Object port)){Stop-Process -Id $entry.pid -ErrorAction Stop}
 Move-Item -LiteralPath $Config.runtimeManifest -Destination ($Config.runtimeManifest+'.stopped-'+[datetime]::UtcNow.ToString('yyyyMMddHHmmssfff'))
}
function Invoke-IasBackup($Config,$Directory){
 Assert-IasNoServices $Config
 if(!$Directory -or (Test-Path -LiteralPath $Directory)){throw 'New backup directory required; overwrite prohibited'}
 Confirm-IasMysqlInstance $Config
 New-Item -ItemType Directory -Path $Directory | Out-Null
 # Backup can contain customer private files. Remove inherited access before copying data.
 $acl=Get-Acl -LiteralPath $Directory
 $acl.SetAccessRuleProtection($true,$false)
 $identity=[System.Security.Principal.WindowsIdentity]::GetCurrent().Name
 $rule=[System.Security.AccessControl.FileSystemAccessRule]::new($identity,'FullControl','ContainerInherit,ObjectInherit','None','Allow')
 $acl.SetAccessRule($rule)
 Set-Acl -LiteralPath $Directory -AclObject $acl
 $files=[System.Collections.Generic.List[object]]::new()
 foreach($schema in $Config.schemas){
  $path=Join-Path $Directory ($schema+'.sql')
  & $Config.mysqldumpExecutable ('--defaults-extra-file='+$Config.mysqlOptionFile) --host=127.0.0.1 --port=3307 --protocol=TCP --single-transaction --routines --triggers --hex-blob --no-tablespaces --set-gtid-purged=OFF ('--result-file='+$path) $schema 2>$null
  if($LASTEXITCODE -ne 0){throw 'Database dump failed; incomplete backup retained'}
  $files.Add([pscustomobject]@{name=($schema+'.sql');sha256=(Get-FileHash -LiteralPath $path).Hash})
 }
 $index=0
 foreach($root in $Config.privateFileRoots){
  if((Get-Item -LiteralPath $root).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Private root links prohibited'}
  if(Get-ChildItem -LiteralPath $root -Recurse -Force | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }){throw 'Private file links prohibited'}
  $target=Join-Path $Directory ('private-'+$index)
  Copy-Item -LiteralPath $root -Destination $target -Recurse -Force
  foreach($file in Get-ChildItem -LiteralPath $target -Recurse -Force -File){$files.Add([pscustomobject]@{name=[IO.Path]::GetRelativePath($Directory,$file.FullName);sha256=(Get-FileHash -LiteralPath $file.FullName).Hash})}
  $index++
 }
 [pscustomobject]@{schemaVersion=1;gitSha=$Config.gitSha;schemas=@($Config.schemas);privateRootCount=$index;files=$files.ToArray();completedUtc=[datetime]::UtcNow.ToString('o')} | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $Directory 'backup-complete.json') -Encoding utf8
}
function Assert-IasRestoreDumpSafe([string]$Path){
 # A dump imported into a new default schema must not change schemas, specify a
 # privileged definer, or refer to another schema. Refuse ambiguous input for
 # operator review before any target database is created; do not rewrite SQL.
 $reader=[IO.StreamReader]::new($Path,[Text.Encoding]::UTF8,$true)
 try {
  while(($line=$reader.ReadLine()) -ne $null){
   if($line -match '^\s*(?:--(?:\s|$)|#)'){continue}
   # mysqldump quotes data and CHECK literals; retain SQL outside single-quoted
   # strings. Unclosed strings and double quotes remain ambiguous and fail closed.
   $code=[regex]::Replace($line,"'(?:\\.|''|[^'\\])*'",'')
   if($code.Contains("'") -or $code.Contains('"')){throw 'Ambiguous quoted SQL requires manual isolation review'}
   if($code -match '(?i)\b(?:CREATE|ALTER|DROP)\s+(?:DATABASE|SCHEMA)\b|(?:^|[;\s])USE\s+`?[a-z0-9_]+`?\s*;|\bDEFINER\s*=|`?ias_[a-z0-9_]+`?\s*\.\s*`?[a-z0-9_]|`[^`]+`\s*\.\s*`[^`]+`|\b[a-z_][a-z0-9_$]*\s*\.\s*(?:`[^`]+`|[a-z_][a-z0-9_$]*)|`[^`]+`\s*\.\s*[a-z_][a-z0-9_$]*'){
    throw 'Restore dump contains a schema reference, database command or DEFINER; manual isolation review required'
   }
  }
 } finally { $reader.Dispose() }
}
function Assert-IasRestoreTargetsAvailable($Config,$Schemas,[string]$Prefix){
 if($Prefix -notmatch '^ias_restore_[a-z0-9_]{1,30}$'){throw 'Invalid restore prefix'}
 $names=@(foreach($schema in $Schemas){
  if($schema -cnotin @('ias_identity_test','ias_asset_test','ias_ticket_test','ias_approval_test','ias_stock_test','ias_notification_test','ias_finance_test')){throw 'Invalid restore source'}
  $Prefix+'_'+($schema -replace '^ias_|_test$','')
 })
 if($names.Count -ne 7 -or @($names | Select-Object -Unique).Count -ne 7){throw 'Seven distinct restore targets required'}
 $quoted=@($names | ForEach-Object {"'"+$_+"'"}) -join ','
 $query='SELECT COUNT(*) FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME IN ('+$quoted+')'
 $result=@(& $Config.mysqlExecutable ('--defaults-extra-file='+$Config.mysqlOptionFile) --host=127.0.0.1 --port=3307 --protocol=TCP --batch --skip-column-names ('--execute='+$query) 2>$null)
 if($LASTEXITCODE -ne 0 -or $result.Count -ne 1 -or [string]$result[0] -cne '0'){throw 'Restore target already exists or availability could not be verified'}
}
function Invoke-IasRestore($Config,$Directory,$Prefix){
 Assert-IasNoServices $Config
 if($Prefix -notmatch '^ias_restore_[a-z0-9_]{1,30}$'){throw 'Explicit new ias_restore_* schema prefix required'}
 $backup=Get-Content -LiteralPath (Join-Path $Directory 'backup-complete.json') -Raw | ConvertFrom-Json
 if($backup.schemaVersion -ne 1 -or $backup.gitSha -ne $Config.gitSha){throw 'Backup release mismatch'}
 $base=[IO.Path]::GetFullPath($Directory)+[IO.Path]::DirectorySeparatorChar
 $target=Join-Path $Directory ($Prefix+'-private-restored')
 if(Test-Path -LiteralPath $target){throw 'Restore private-file target exists'}
 $seen=[System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
 foreach($file in $backup.files){
  $path=[IO.Path]::GetFullPath((Join-Path $Directory $file.name))
  if(!$path.StartsWith($base,[StringComparison]::OrdinalIgnoreCase)){throw 'Backup file escapes target'}
  if(!$seen.Add($path)){throw 'Duplicate backup file path'}
  if((Get-Item -LiteralPath $path -Force).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Backup file links prohibited'}
  Assert-IasFileHash $path $file.sha256
 }
 # Validate the complete source set and every dump before the first mutation.
 if(@($backup.schemas).Count -ne 7 -or @($backup.schemas | Select-Object -Unique).Count -ne 7 -or $backup.privateRootCount -ne @($Config.privateFileRoots).Count){throw 'Backup source set is incomplete'}
 foreach($schema in $backup.schemas){
  if($schema -notin @($Config.schemas)){throw 'Unexpected backup schema'}
  $name=$schema+'.sql'
  if(@($backup.files | Where-Object { $_.name -ceq $name }).Count -ne 1){throw 'Missing or duplicate dump in backup manifest'}
  Assert-IasRestoreDumpSafe (Join-Path $Directory $name)
 }
 # Compare the actual private trees against the pinned manifest before creating any schema.
 # An omitted, added, renamed or linked file must never be silently copied into a restore.
 for($index=0;$index -lt $backup.privateRootCount;$index++){
  $root=Join-Path $Directory ('private-'+$index)
  if(!(Test-Path -LiteralPath $root -PathType Container)){throw 'Private backup root missing'}
  if((Get-Item -LiteralPath $root -Force).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Private backup root links prohibited'}
  foreach($item in Get-ChildItem -LiteralPath $root -Recurse -Force){
   if($item.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Private backup links prohibited'}
   if(!$item.PSIsContainer -and !$seen.Contains([IO.Path]::GetFullPath($item.FullName))){throw 'Unpinned private backup file'}
  }
 }
 Confirm-IasMysqlInstance $Config
 # Check the entire target set before creating the first schema. CREATE without
 # IF NOT EXISTS below remains the race-safe guard against a concurrent creator.
 Assert-IasRestoreTargetsAvailable $Config $backup.schemas $Prefix
 foreach($schema in $backup.schemas){
  if($schema -notin @($Config.schemas)){throw 'Unexpected backup schema'}
  $destination=$Prefix+'_'+($schema -replace '^ias_|_test$','')
  # CREATE DATABASE without IF NOT EXISTS intentionally refuses an existing target.
  & $Config.mysqlExecutable ('--defaults-extra-file='+$Config.mysqlOptionFile) --host=127.0.0.1 --port=3307 --protocol=TCP ('--execute=CREATE DATABASE `'+$destination+'`') 2>$null
  if($LASTEXITCODE -ne 0){throw 'New restore schema creation failed; existing targets never overwritten'}
  Get-Content -LiteralPath (Join-Path $Directory ($schema+'.sql')) -Raw | & $Config.mysqlExecutable ('--defaults-extra-file='+$Config.mysqlOptionFile) --host=127.0.0.1 --port=3307 --protocol=TCP --default-character-set=utf8mb4 $destination 2>$null
  if($LASTEXITCODE -ne 0){throw 'Restore failed; partial new schemas retained for diagnosis'}
 }
 New-Item -ItemType Directory -Path $target | Out-Null
 for($index=0;$index -lt $backup.privateRootCount;$index++){Copy-Item -LiteralPath (Join-Path $Directory ('private-'+$index)) -Destination $target -Recurse -Force}
 Write-Output 'Restored into new isolated schemas/private roots; configuration switch and business verification remain explicit release steps.'
}
