# Pinned release adapters consume references only. Never emit adapter stdout or secret-bearing exceptions.
function Get-IasOptional($Value,[string]$Name,$Default=$null){
 if($Value.PSObject.Properties.Name -contains $Name){return $Value.$Name};return $Default
}
function Invoke-IasReleaseAdapter($Adapter,[string]$Action,$Context){
 if(!$Adapter){throw 'Required release adapter not configured'}
 Assert-IasFileHash $Adapter.script $Adapter.sha256
 if(!(Test-Path -LiteralPath $Adapter.configPath -PathType Leaf)){throw 'External adapter configuration missing'}
 try {$output=@(& $Adapter.script -Action $Action -ConfigPath $Adapter.configPath -Context $Context 3>$null 4>$null 5>$null 6>$null)}catch{throw 'Release adapter failed; inspect restricted local diagnostics'}
 if(!$? -or $output.Count -ne 1 -or !$output[0] -or $output[0].ok -ne $true -or $output[0].action -cne $Action){throw 'Release adapter did not return one successful typed receipt'}
 return $output[0]
}
function Assert-IasExactVersions($Config){
 foreach($item in $Config.tools){
  if($item.name -notin @('java','node','mysql') -or $item.expectedVersion -notmatch '^[0-9]+\.[0-9]+\.[0-9]+(?:\.[0-9]+)?$'){throw 'Exact Java/Node/MySQL versions required'}
  Assert-IasFileHash $item.executable $item.sha256
  $arg=if($item.name -eq 'java'){'-version'}else{'--version'}
  $output=(& $item.executable $arg 2>&1 | Out-String)
  if($LASTEXITCODE -ne 0){throw 'Tool version invocation failed'}
  $pattern=switch($item.name){java{'version "(?<v>[0-9.]+)'}node{'^v(?<v>[0-9.]+)'}mysql{'Ver (?<v>[0-9.]+)'}}
  if($output -notmatch $pattern -or $Matches.v -cne $item.expectedVersion){throw 'Exact tool version mismatch'}
 }
 if(@($Config.tools.name | Select-Object -Unique).Count -ne 3 -or @($Config.tools).Count -ne 3){throw 'Exactly Java, Node and MySQL tool pins required'}
}
function Test-IasReadiness($Config){
 # This is an actual HTTP/application probe, not a port-listen check. No token/cookie is returned or logged.
 $handler=[Net.Http.HttpClientHandler]::new();$handler.AllowAutoRedirect=$false;$handler.UseCookies=$false
 $client=[Net.Http.HttpClient]::new($handler);$client.Timeout=[timespan]::FromSeconds(10);$response=$null
 $request=[Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::Get,'http://127.0.0.1:8080/api/auth/csrf')
 $request.Headers.Add('Origin','http://127.0.0.1:4173')
 try{
  $response=$client.SendAsync($request).GetAwaiter().GetResult()
  if([int]$response.StatusCode -ne 200){throw 'Gateway identity CSRF readiness failed'}
  $body=$response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
  if($body.headerName -cne 'X-CSRF-TOKEN' -or !$body.token){throw 'Gateway identity response contract mismatch'}
 }finally{if($response){$response.Dispose()};$request.Dispose();$client.Dispose();$handler.Dispose()}
 # The operator's pinned adapter must use the current role's private TLS configuration, current
 # trust edge, exact method/path and role response. A TCP listener or 401 alone cannot satisfy it.
 $receipt=Invoke-IasReleaseAdapter $Config.readinessAdapter 'Readiness' ([pscustomobject]@{gitSha=$Config.gitSha;services=$Config.services;runtimeManifest=$Config.runtimeManifest})
 $expected=@('identity','asset','ticket','approval','stock','notification','finance')
 if(@($receipt.roles).Count -ne 7 -or @($receipt.roles.role | Select-Object -Unique).Count -ne 7){throw 'Seven TLS role receipts required'}
 foreach($role in $receipt.roles){
  if($role.role -notin $expected -or $role.httpVerified -ne $true -or $role.tlsVerified -ne $true -or $role.roleIdentityVerified -ne $true -or $role.trustAllUsed -ne $false){throw 'HTTP/TLS/current role readiness not proven'}
 }
 if($receipt.gitSha -cne $Config.gitSha){throw 'Readiness release mismatch'}
 return [pscustomobject]@{ok=$true;gatewayIdentityHttp=$true;tlsRoleCount=7;gitSha=$Config.gitSha}
}
function Assert-IasMigrationCoverage($Schemas,$Steps){
 $expected=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
 $owners=@('ias_identity_test','ias_asset_test','ias_ticket_test','ias_approval_test','ias_stock_test','ias_notification_test','ias_finance_test')
 foreach($schema in @($Schemas)){if(!$schema -or $schema -cnotin $owners -or !$expected.Add([string]$schema)){throw 'Unexpected, duplicate or missing source schema'}}
 if($expected.Count -ne 7){throw 'Seven unique migration schemas required'}
 $covered=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
 foreach($step in @($Steps)){
  if(!$step -or !($step.PSObject.Properties.Name -contains 'schemas') -or @($step.schemas).Count -eq 0){throw 'Each migration adapter needs explicit schemas'}
  foreach($schema in @($step.schemas)){if(!$expected.Contains([string]$schema) -or !$covered.Add([string]$schema)){throw 'Migration schema is unexpected or duplicated'}}
 }
 if($covered.Count -ne $expected.Count){throw 'Migration schema coverage incomplete'}
}
function Initialize-IasRelease($Config){
 Assert-IasNoServices $Config
 Assert-IasMigrationCoverage $Config.schemas $Config.migrations
 Start-IasProjectMysql $Config
 Confirm-IasMysqlInstance $Config
 foreach($step in $Config.migrations){
  $receipt=Invoke-IasReleaseAdapter $step 'Migrate' ([pscustomobject]@{gitSha=$Config.gitSha;schemas=$step.schemas;databasePort=3307;services=$Config.services;tools=$Config.tools})
  if($receipt.reentrant -ne $true -or $receipt.gitSha -cne $Config.gitSha){throw 'Migration must prove version-checked reentry for this release'}
  $declared=@($step.schemas | Sort-Object -CaseSensitive);$reported=@($receipt.schemas | Sort-Object -CaseSensitive)
  if($declared.Count -ne $reported.Count -or (Compare-Object -ReferenceObject $declared -DifferenceObject $reported -CaseSensitive)){throw 'Migration receipt schema mismatch'}
 }
 $receipt=Invoke-IasReleaseAdapter $Config.bootstrapAdapter 'Bootstrap' ([pscustomobject]@{gitSha=$Config.gitSha;schemas=$Config.schemas;databasePort=3307;services=$Config.services;tools=$Config.tools})
 if($receipt.reentrant -ne $true -or $receipt.existingPasswordsChanged -ne $false -or $receipt.gitSha -cne $Config.gitSha){throw 'Bootstrap must preserve existing users/passwords and prove reentry'}
}
function Test-IasRestoreReview($Config,[string]$Directory,[string]$Prefix){
 Assert-IasNoServices $Config
 if($Prefix -notmatch '^ias_restore_[a-z0-9_]{1,30}$'){throw 'Explicit isolated restore prefix required'}
 $map=@($Config.schemas | ForEach-Object {[pscustomobject]@{source=$_;target=($Prefix+'_'+($_ -replace '^ias_|_test$',''))}})
 $receipt=Invoke-IasReleaseAdapter $Config.restoreReviewAdapter 'ReviewRestore' ([pscustomobject]@{gitSha=$Config.gitSha;schemaMap=$map;privateRoots=(Join-Path $Directory ($Prefix+'-private-restored'));databasePort=3307})
 foreach($gate in @('allSchemasExist','schemaReferencesVerified','definersVerified','minimumGrantsVerified','privateFilesVerified')){
  if($receipt.$gate -ne $true){throw 'Restore schema/reference/DEFINER/grant/file review not complete'}
 }
 if($receipt.gitSha -cne $Config.gitSha -or (@($receipt.schemaMap | ConvertTo-Json -Depth 4 -Compress) -join '') -cne (@($map | ConvertTo-Json -Depth 4 -Compress) -join '')){throw 'Restore review mapping mismatch'}
 # This writes a non-secret receipt, not datasource files and not an online switch.
 $receipt | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $Directory ($Prefix+'-review.json')) -Encoding utf8
 return $receipt
}
