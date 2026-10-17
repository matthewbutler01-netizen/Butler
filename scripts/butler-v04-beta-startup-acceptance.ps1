# Isolated startup behavior: no app, network or provider call.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$source = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'butler-v04-beta-one-click.ps1')).Replace("`r`n", "`n")
$start = $source.IndexOf('# Startup polling is bounded', [StringComparison]::Ordinal)
$finish = $source.IndexOf('# BF-1527: verify the checkout', [StringComparison]::Ordinal)
if ($start -lt 0 -or $finish -le $start) { throw 'Startup helper boundary missing' }
. ([scriptblock]::Create($source.Substring($start, $finish-$start)))
$script:probeCount = 0
$script:readyAfter = 3
function Test-BetaHealth {
    param([int]$Port)
    if ($Port -ne 18080) { throw 'Wrong probe port' }
    $script:probeCount++
    return $script:probeCount -ge $script:readyAfter
}
function Start-Sleep { param([int]$Milliseconds) [Threading.Thread]::Sleep(10) }
$running = [pscustomobject]@{ HasExited = $false }
if (-not (Wait-BetaStartup -Port 18080 -TimeoutSeconds 1 -StartedProcess $running)) { throw 'Delayed readiness rejected' }
if ($script:probeCount -ne 3) { throw 'Readiness polling did not stop at success' }
$script:readyAfter = [int]::MaxValue
$script:probeCount = 0
if (Wait-BetaStartup -Port 18080 -TimeoutSeconds 1 -StartedProcess ([pscustomobject]@{HasExited=$true})) { throw 'Exited starter accepted' }
if ($script:probeCount -ne 1) { throw 'Exited starter was not detected promptly' }
$clock = [Diagnostics.Stopwatch]::StartNew()
if (Wait-BetaStartup -Port 18080 -TimeoutSeconds 1 -StartedProcess $running) { throw 'Timeout accepted as healthy' }
if ($clock.Elapsed.TotalSeconds -gt 3) { throw 'Startup wait exceeded test bound' }
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('butler-beta-startup-' + [guid]::NewGuid().ToString('N'))
$originalLocalAppData = $env:LOCALAPPDATA
try {
    $env:LOCALAPPDATA = $testRoot
    Write-BetaStartupReport -Status PENDING
    $path = Join-Path $testRoot 'Butler\diagnostics\v04-beta-one-click-latest.txt'
    if ([IO.File]::ReadAllText($path) -notmatch 'BETA STARTUP: PENDING') { throw 'Pending report missing' }
    Write-BetaStartupReport -Status HELD
    $report = [IO.File]::ReadAllText($path)
    if ($report -notmatch 'BETA STARTUP: HELD' -or $report -notmatch 'Browser acceptance: NOT RUN') { throw 'Held report missing' }
    if ($report -match '(?m)^BETA STARTUP: PENDING|^BETA SOURCE CHECKOUT: VERIFIED') { throw 'Held report retained stale success or pending status' }
}
finally {
    $env:LOCALAPPDATA = $originalLocalAppData
    $resolved = [IO.Path]::GetFullPath($testRoot)
    $expectedParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
    if ([IO.Path]::GetDirectoryName($resolved) -ne $expectedParent -or [IO.Path]::GetFileName($resolved) -notlike 'butler-beta-startup-*') { throw 'Unsafe fixture cleanup path' }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
Write-Host 'BETA STARTUP ACCEPTANCE: PASS (delayed health, exited starter, bounded timeout, safe saved reports)'
