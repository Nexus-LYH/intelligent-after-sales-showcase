$ErrorActionPreference='Stop'
# Run with the local services stopped. Preserve backups and never print edge secrets.
if (@(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object LocalPort -in 8441,8445).Count) { throw 'Stop identity and stock before changing their chain configuration.' }
$dir='C:\ias-local\Develop\IAS-MySQL84\secrets\chain'
$identity=Join-Path $dir 'identity.json';$stock=Join-Path $dir 'stock.json';$finance=Join-Path $dir 'finance.json'
foreach($p in @($identity,$stock,$finance)){if(!(Test-Path -LiteralPath $p -PathType Leaf)){throw 'Chain reference missing'}}
$a=Get-Content -Raw -LiteralPath $identity|ConvertFrom-Json;$b=Get-Content -Raw -LiteralPath $stock|ConvertFrom-Json;$f=Get-Content -Raw -LiteralPath $finance|ConvertFrom-Json
if($a.role -cne 'identity' -or $b.role -cne 'stock' -or $f.role -cne 'finance' -or $a.storePassword -cne $f.storePassword -or !$f.peers.stock.trustStore -or !(Test-Path -LiteralPath $f.peers.stock.trustStore -PathType Leaf)){throw 'Chain role or stock trust reference invalid'}
$left=$a.edges.PSObject.Properties['identity-stock'];$right=$b.edges.PSObject.Properties['identity-stock']
if($left -or $right){if(!$left -or !$right -or $left.Value -cne $right.Value -or $left.Value -notmatch '^[A-Za-z0-9_-]{43}$' -or !$a.peers.stock -or $a.peers.stock.trustStore -cne $f.peers.stock.trustStore){throw 'Existing edge requires manual reconciliation'};Write-Output 'IDENTITY_STOCK_EDGE_ALREADY_CONFIGURED';exit 0}
$bytes=[Security.Cryptography.RandomNumberGenerator]::GetBytes(32);$token=[Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_')
$a.edges|Add-Member -NotePropertyName 'identity-stock' -NotePropertyValue $token;$b.edges|Add-Member -NotePropertyName 'identity-stock' -NotePropertyValue $token
$a.peers|Add-Member -NotePropertyName 'stock' -NotePropertyValue $f.peers.stock -Force
$stamp=[guid]::NewGuid().ToString('N');$tempA=Join-Path $dir ('identity-edge-'+$stamp+'.tmp');$tempB=Join-Path $dir ('stock-edge-'+$stamp+'.tmp');$backupA=Join-Path $dir ('identity-before-edge-'+$stamp+'.json');$backupB=Join-Path $dir ('stock-before-edge-'+$stamp+'.json')
Copy-Item -LiteralPath $identity -Destination $tempA;Copy-Item -LiteralPath $stock -Destination $tempB
$a|ConvertTo-Json -Depth 30|Set-Content -LiteralPath $tempA -Encoding UTF8;$b|ConvertTo-Json -Depth 30|Set-Content -LiteralPath $tempB -Encoding UTF8
$replacedA=$false;$replacedB=$false
try {
 [IO.File]::Replace($tempA,$identity,$backupA);$replacedA=$true
 [IO.File]::Replace($tempB,$stock,$backupB);$replacedB=$true
 $leftCheck=Get-Content -Raw -LiteralPath $identity|ConvertFrom-Json;$rightCheck=Get-Content -Raw -LiteralPath $stock|ConvertFrom-Json
 if($leftCheck.edges.'identity-stock' -cne $token -or $rightCheck.edges.'identity-stock' -cne $token){throw 'Edge write not confirmed'}
} catch {
 $failure=$_
 try {
  if($replacedB){Copy-Item -LiteralPath $backupB -Destination $stock -Force}
  if($replacedA){Copy-Item -LiteralPath $backupA -Destination $identity -Force}
 } catch { throw 'Edge update and rollback failed; retain backups and reconcile manually.' }
 throw "Edge update failed and prior configurations were restored: $($failure.Exception.Message)"
}
Remove-Variable token,bytes,a,b,f,leftCheck,rightCheck -ErrorAction SilentlyContinue
Write-Output 'IDENTITY_STOCK_EDGE_CONFIGURED'
