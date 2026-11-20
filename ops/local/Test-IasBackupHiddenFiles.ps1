$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'LocalLifecycle.ps1')
function Assert-IasNoServices($Config) { }
function Confirm-IasMysqlInstance($Config) { }
$root=Join-Path ([IO.Path]::GetTempPath()) ('ias-backup-hidden-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $root | Out-Null
try {
 $private=Join-Path $root 'private';New-Item -ItemType Directory -Path $private | Out-Null
 $hidden=Join-Path $private 'hidden.txt';[IO.File]::WriteAllText($hidden,'hidden attachment fixture')
 (Get-Item -LiteralPath $hidden -Force).Attributes=[IO.FileAttributes]::Hidden
 $dump=Join-Path $root 'dump.ps1'
 [IO.File]::WriteAllText($dump, '$arg=@($args | Where-Object {$_ -like "--result-file=*"})[0]; [IO.File]::WriteAllText($arg.Substring(14),"CREATE TABLE fixture(id int);"); $global:LASTEXITCODE=0')
 $config=[pscustomobject]@{services=@();gitSha=('a'*40);schemas=@('ias_identity_test');mysqlOptionFile='fixture';mysqldumpExecutable=$dump;privateFileRoots=@($private)}
 $backup=Join-Path $root 'backup';Invoke-IasBackup $config $backup
 $manifest=Get-Content -LiteralPath (Join-Path $backup 'backup-complete.json') -Raw | ConvertFrom-Json
 $entries=@($manifest.files | Where-Object {$_.name -match 'hidden\.txt$'})
 $copy=Join-Path $backup 'private-0\hidden.txt'
 if($entries.Count -ne 1 -or !(Test-Path -LiteralPath $copy -PathType Leaf) -or (Get-FileHash -LiteralPath $copy).Hash -ne $entries[0].sha256){throw 'Hidden attachment missing from backup or manifest'}
 'PASS: hidden attachment included in backup and hash manifest; fixture only.'
} finally {
 $parent=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar)+[IO.Path]::DirectorySeparatorChar
 $resolved=[IO.Path]::GetFullPath($root)
 if(!$resolved.StartsWith($parent,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($resolved) -notmatch '^ias-backup-hidden-[a-f0-9]{32}$'){throw 'Unexpected fixture path'}
 Remove-Item -LiteralPath $resolved -Recurse -Force -ErrorAction SilentlyContinue
}
