$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'LocalLifecycle.ps1')
$dir=Join-Path ([IO.Path]::GetTempPath()) ('ias-reboot-test-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $dir | Out-Null
try {
 $config=[pscustomobject]@{runtimeManifest=(Join-Path $dir 'runtime.json');frontends=@()}
 $path=$config.runtimeManifest
 [IO.File]::WriteAllText($path,'[{"pid":2147483647}]')
 try { Archive-IasRebootManifests $config; throw 'Current boot file was archived' } catch {if($_.Exception.Message -notlike '*Current-boot manifest*'){throw}}
 (Get-Item -LiteralPath $path).LastWriteTimeUtc=[datetime]::UtcNow.AddYears(-2)
 [IO.File]::WriteAllText(($path+'.frontends'),'[{"pid":0}]')
 (Get-Item -LiteralPath ($path+'.frontends')).LastWriteTimeUtc=[datetime]::UtcNow.AddYears(-2)
 try { Archive-IasRebootManifests $config; throw 'Invalid PID accepted' } catch {if($_.Exception.Message -notlike '*Invalid manifest PID*'){throw}}
 if(!(Test-Path -LiteralPath $path)){throw 'Partial archive before validation'}
 [IO.File]::WriteAllText(($path+'.frontends'),('[{"pid":'+$PID+'}]'))
 (Get-Item -LiteralPath ($path+'.frontends')).LastWriteTimeUtc=[datetime]::UtcNow.AddYears(-2)
 try { Archive-IasRebootManifests $config; throw 'Live PID accepted' } catch {if($_.Exception.Message -notlike '*Manifest PID exists*'){throw}}
 # A current process with the same PID is unrelated only when both timestamps prove reuse after reboot.
 [IO.File]::WriteAllText(($path+'.frontends'),('[{"pid":'+$PID+',"creationUtc":"2020-01-01T00:00:00Z"}]'))
 (Get-Item -LiteralPath ($path+'.frontends')).LastWriteTimeUtc=[datetime]::UtcNow.AddYears(-2)
 Archive-IasRebootManifests $config
 if((Test-Path -LiteralPath $path) -or @(Get-ChildItem -LiteralPath $dir -File).Count -ne 2){throw 'Proven reused PID not safely archived'}
 [IO.File]::WriteAllText($path,'[{"pid":2147483647}]')
 (Get-Item -LiteralPath $path).LastWriteTimeUtc=[datetime]::UtcNow.AddYears(-2)
 [IO.File]::WriteAllText(($path+'.frontends'),'[{"pid":2147483647}]')
 (Get-Item -LiteralPath ($path+'.frontends')).LastWriteTimeUtc=[datetime]::UtcNow.AddYears(-2)
 Archive-IasRebootManifests $config
 if((Test-Path -LiteralPath $path) -or @(Get-ChildItem -LiteralPath $dir -File).Count -ne 4){throw 'Expected four preserved archives'}
 Write-Output 'PASS: reboot manifest safety and archival checks'
} finally {
 $base=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar)+[IO.Path]::DirectorySeparatorChar
 $resolved=[IO.Path]::GetFullPath($dir)
 if(!$resolved.StartsWith($base,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($resolved) -notmatch '^ias-reboot-test-[a-f0-9]{32}$'){throw 'Unexpected fixture path'}
 Remove-Item -LiteralPath $resolved -Recurse -Force
}
