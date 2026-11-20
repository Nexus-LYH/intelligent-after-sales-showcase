$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'LocalLifecycle.ps1')
$path=Join-Path ([IO.Path]::GetTempPath()) ('ias-partial-stop-'+[guid]::NewGuid().ToString('N')+'.json')
try {
 $entry=[pscustomobject]@{role='identity';pid=2147483647;port=8441;jar='fixture.jar';sha256='fixture'}
 @($entry) | ConvertTo-Json | Set-Content -LiteralPath $path -Encoding utf8
 $config=[pscustomobject]@{runtimeManifest=$path;services=@([pscustomobject]@{role='identity';port=8441;jar='fixture.jar';sha256='fixture'});frontends=@();tools=@()}
 function Get-IasProcessMatch($Entry){return $true}
 function Stop-Process {param($Id) $script:stopped=$Id}
 Stop-IasLocal $config
 if($script:stopped -ne $entry.pid -or (Test-Path -LiteralPath $path)){throw 'Partial stop failed'}
 Write-Output 'PASS: partial backend stop without a frontend manifest under strict mode.'
} finally {
 $base=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar)+[IO.Path]::DirectorySeparatorChar
 $resolved=[IO.Path]::GetFullPath($path)
 if(!$resolved.StartsWith($base,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($resolved) -notmatch '^ias-partial-stop-[a-f0-9]{32}\.json$'){throw 'Unexpected fixture path'}
 Get-ChildItem -LiteralPath $base -Filter (([IO.Path]::GetFileName($resolved))+'*') | Remove-Item -Force
}
