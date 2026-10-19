Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = Join-Path ([IO.Path]::GetTempPath()) ('Butler-lifetime-' + [guid]::NewGuid().ToString('N'))
$savedLocal = $env:LOCALAPPDATA
$savedMode = $env:BUTLER_LIFETIME_FIXTURE_MODE
$savedReady = $env:BUTLER_LIFETIME_FIXTURE_READY
$owned = @()
try {
    [IO.Directory]::CreateDirectory($root) | Out-Null
    foreach ($name in @('butler-app-supervisor.ps1','butler-child-tree-watchdog.ps1','butler-runtime-lifetime.ps1')) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination $root
    }
    # Real supervisor + watchdog, inert guard. No Butler runtime, database,
    # listener, provider request or transaction is started by this fixture.
    [IO.File]::WriteAllText((Join-Path $root 'butler-app-guard.ps1'), @'
param([int]$Port, [switch]$NoBrowser)
if ($env:BUTLER_LIFETIME_FIXTURE_MODE -eq 'wait') {
    [IO.File]::WriteAllText($env:BUTLER_LIFETIME_FIXTURE_READY, [string]$PID)
    while ($true) { Start-Sleep -Milliseconds 100 }
}
exit ([int]$env:BUTLER_LIFETIME_FIXTURE_MODE)
'@)
    foreach ($mode in @('0','37','wait','storage-blocked')) {
        $profile = Join-Path $root $mode
        if ($mode -eq 'storage-blocked') { [IO.File]::WriteAllText($profile, 'not a directory') }
        else { [IO.Directory]::CreateDirectory($profile) | Out-Null }
        $env:LOCALAPPDATA = $profile
        $env:BUTLER_LIFETIME_FIXTURE_MODE = if ($mode -eq 'storage-blocked') { '37' } else { $mode }
        $env:BUTLER_LIFETIME_FIXTURE_READY = Join-Path $root 'guard-ready.txt'
        $supervisor = Join-Path $root 'butler-app-supervisor.ps1'
        $arguments = '-NoProfile -ExecutionPolicy Bypass -File "' + $supervisor + '" -Port 18089 -NoBrowser'
        $process = Start-Process -FilePath (Join-Path $PSHOME 'powershell.exe') -ArgumentList $arguments -WindowStyle Hidden -PassThru
        $owned += $process
        if ($mode -eq 'wait') {
            $deadline = [DateTime]::UtcNow.AddSeconds(20)
            while (-not [IO.File]::Exists($env:BUTLER_LIFETIME_FIXTURE_READY) -and [DateTime]::UtcNow -lt $deadline) { Start-Sleep -Milliseconds 100 }
            if (-not [IO.File]::Exists($env:BUTLER_LIFETIME_FIXTURE_READY)) { throw 'Inert guard did not become ready' }
            $guardId = [int][IO.File]::ReadAllText($env:BUTLER_LIFETIME_FIXTURE_READY)
            $guard = Get-Process -Id $guardId -ErrorAction Stop
            $owned += $guard
            # Wait for the actual watchdog to exist before stopping its exact
            # owned supervisor. Its production identity checks do the cleanup.
            $watch = @()
            while ($watch.Count -ne 1 -and [DateTime]::UtcNow -lt $deadline) {
                $watch = @(Get-CimInstance Win32_Process -Filter "ParentProcessId = $($process.Id)" | Where-Object { $_.CommandLine -and $_.CommandLine.Contains((Join-Path $root 'butler-child-tree-watchdog.ps1')) })
                if ($watch.Count -ne 1) { Start-Sleep -Milliseconds 100 }
            }
            if ($watch.Count -ne 1) { throw 'Owned watchdog was not created' }
            $owned += (Get-Process -Id $watch[0].ProcessId)
            $process.Kill()
            if (-not $guard.WaitForExit(15000)) { throw 'Watchdog did not stop the owned inert guard' }
        }
        elseif (-not $process.WaitForExit(30000)) { throw 'Fixture supervisor timed out' }
        if ($mode -ne 'wait') {
            $expected = if ($mode -eq '0') { 0 } else { 37 }
            if ($process.ExitCode -ne $expected) { throw "Diagnostic changed guard exit result for $mode" }
        }
        if ($mode -eq 'storage-blocked') { continue }
        $receipts = @(Get-ChildItem -LiteralPath (Join-Path $profile 'Butler\diagnostics') -Filter 'runtime-lifetime-*.txt' -File)
        if ($receipts.Count -ne 1) { throw 'Expected one per-process receipt' }
        $text = [IO.File]::ReadAllText($receipts[0].FullName)
        if ($text -notmatch '^BUTLER_RUNTIME_LIFETIME_V1' -or $text -notmatch 'event=SUPERVISOR_STARTED' -or $text -notmatch 'event=GUARD_STARTED') { throw 'Missing start observations' }
        if ($mode -eq 'wait') {
            if ($text -notmatch 'event=SUPERVISOR_DISAPPEARED' -or $text -match 'event=GUARD_EXITED') { throw 'Abrupt supervisor loss was misclassified' }
        } elseif ($text -notmatch ('event=GUARD_EXITED code=' + $mode + '(\r?\n|$)')) { throw 'Observed guard exit code missing' }
        $lines = @($text -split '\r?\n' | Where-Object { $_ })
        if ($lines.Count -gt 8) { throw 'Unbounded lifetime receipt' }
        foreach ($line in $lines | Select-Object -Skip 1) {
            if ($line -cnotmatch '^utc=[0-9T:Z.+-]+ event=(SUPERVISOR_STARTED|GUARD_STARTED|GUARD_EXITED|SUPERVISOR_FAILED|SUPERVISOR_FINALIZING|SUPERVISOR_DISAPPEARED|GUARD_DISAPPEARED)( code=-?[0-9]+)?$') { throw 'Receipt contains unallowlisted data' }
        }
        Write-Host "Lifetime receipt fixture ${mode}: PASS"
    }
    # Exercise guard loss while a separate inert supervisor is still alive.
    # The real watchdog must clean up its exact core tree, not an unrelated
    # guard child. No Butler runtime, database, listener or provider is used.
    $profile = Join-Path $root 'guard-loss'
    [IO.Directory]::CreateDirectory($profile) | Out-Null
    $env:LOCALAPPDATA = $profile
    . (Join-Path $root 'butler-runtime-lifetime.ps1')
    $idle = Join-Path $root 'inert-idle.ps1'
    [IO.File]::WriteAllText($idle, '$deadline = [DateTime]::UtcNow.AddSeconds(90); while ([DateTime]::UtcNow -lt $deadline) { Start-Sleep -Milliseconds 100 }')
    $core = Join-Path $root 'butler-app-shell-core.ps1'
    [IO.File]::WriteAllText($core, @'
$child = Start-Process powershell.exe -ArgumentList ('-NoProfile -File "' + (Join-Path $PSScriptRoot 'inert-idle.ps1') + '"') -WindowStyle Hidden -PassThru
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'core-descendant.txt'), [string]$child.Id)
$deadline = [DateTime]::UtcNow.AddSeconds(90)
while ([DateTime]::UtcNow -lt $deadline) { Start-Sleep -Milliseconds 100 }
'@)
    $guardFixture = Join-Path $root 'inert-guard-tree.ps1'
    [IO.File]::WriteAllText($guardFixture, @'
$core = Start-Process powershell.exe -ArgumentList ('-NoProfile -File "' + (Join-Path $PSScriptRoot 'butler-app-shell-core.ps1') + '"') -WindowStyle Hidden -PassThru
$sentinel = Start-Process powershell.exe -ArgumentList ('-NoProfile -File "' + (Join-Path $PSScriptRoot 'inert-idle.ps1') + '"') -WindowStyle Hidden -PassThru
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'guard-tree-ready.txt'), ([string]$core.Id + ',' + [string]$sentinel.Id))
$deadline = [DateTime]::UtcNow.AddSeconds(90)
while ([DateTime]::UtcNow -lt $deadline) { Start-Sleep -Milliseconds 100 }
'@)
    $ps = Join-Path $PSHOME 'powershell.exe'
    $inertSupervisor = Start-Process $ps -ArgumentList ('-NoProfile -File "' + $idle + '"') -WindowStyle Hidden -PassThru
    $owned += $inertSupervisor
    $inertGuard = Start-Process $ps -ArgumentList ('-NoProfile -File "' + $guardFixture + '"') -WindowStyle Hidden -PassThru
    $owned += $inertGuard
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    $readyPath = Join-Path $root 'guard-tree-ready.txt'
    $descendantPath = Join-Path $root 'core-descendant.txt'
    while ((-not [IO.File]::Exists($readyPath) -or -not [IO.File]::Exists($descendantPath)) -and [DateTime]::UtcNow -lt $deadline) { Start-Sleep -Milliseconds 100 }
    if (-not [IO.File]::Exists($readyPath) -or -not [IO.File]::Exists($descendantPath)) { throw 'Inert guard tree did not become ready' }
    $treeIds = [IO.File]::ReadAllText($readyPath).Split(',')
    $trackedCore = Get-Process -Id ([int]$treeIds[0]) -ErrorAction Stop
    $sentinel = Get-Process -Id ([int]$treeIds[1]) -ErrorAction Stop
    $descendant = Get-Process -Id ([int][IO.File]::ReadAllText($descendantPath)) -ErrorAction Stop
    $owned += @($trackedCore,$sentinel,$descendant)
    $receipt = New-ButlerLifetimeReceipt -Port 18089 -SupervisorProcessId $inertSupervisor.Id
    if (-not $receipt) { throw 'Guard-loss receipt missing' }
    Write-ButlerLifetimeEvent -Path $receipt -Event GUARD_STARTED
    $watchArgs = '-NoProfile -File "' + (Join-Path $root 'butler-child-tree-watchdog.ps1') + '" -SupervisorPid ' + $inertSupervisor.Id + ' -SupervisorStartTicks ' + $inertSupervisor.StartTime.ToUniversalTime().Ticks + ' -GuardPid ' + $inertGuard.Id + ' -GuardStartTicks ' + $inertGuard.StartTime.ToUniversalTime().Ticks + ' -LifetimeReceipt "' + $receipt + '"'
    $guardWatch = Start-Process $ps -ArgumentList $watchArgs -WindowStyle Hidden -PassThru
    $owned += $guardWatch
    $inertGuard.Kill()
    if (-not $inertGuard.WaitForExit(5000)) { throw 'Owned inert guard did not stop' }
    if (-not $guardWatch.WaitForExit(20000) -or $guardWatch.ExitCode -ne 0) { throw 'Guard-loss watchdog failed' }
    if (-not $trackedCore.WaitForExit(5000) -or -not $descendant.WaitForExit(5000)) { throw 'Owned core tree survived guard loss' }
    if ($inertSupervisor.HasExited -or $sentinel.HasExited) { throw 'Watchdog stopped unrelated fixture process' }
    $events = [IO.File]::ReadAllText($receipt)
    if ($events -notmatch 'event=GUARD_DISAPPEARED' -or $events -match 'event=SUPERVISOR_DISAPPEARED|event=GUARD_EXITED') { throw 'Guard loss was misclassified' }
    Write-Host 'Lifetime guard-loss fixture: PASS (core descendant cleanup, unrelated child preserved, exact observation)'

    # A caller-supplied receipt path must not append outside diagnostics.
    $env:LOCALAPPDATA = Join-Path $root 'outside-profile'
    . (Join-Path $root 'butler-runtime-lifetime.ps1')
    $outside = Join-Path $root 'unrelated.txt'
    [IO.File]::WriteAllText($outside, 'preserve')
    Write-ButlerLifetimeEvent -Path $outside -Event SUPERVISOR_STARTED
    if ([IO.File]::ReadAllText($outside) -cne 'preserve') { throw 'Unrelated file changed' }
    Write-Host 'BUTLER RUNTIME LIFETIME ACCEPTANCE: PASS (exit codes, abrupt loss, storage failure, bounded private observations)'
} finally {
    foreach ($process in $owned) {
        try { if (-not $process.HasExited) { $process.Kill(); [void]$process.WaitForExit(5000) } } catch { }
        try { $process.Dispose() } catch { }
    }
    $env:LOCALAPPDATA = $savedLocal
    $env:BUTLER_LIFETIME_FIXTURE_MODE = $savedMode
    $env:BUTLER_LIFETIME_FIXTURE_READY = $savedReady
    $resolved = [IO.Path]::GetFullPath($root)
    $parent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if (-not $resolved.StartsWith($parent,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($resolved) -notmatch '^Butler-lifetime-[0-9a-f]{32}$') { throw 'Unexpected cleanup path' }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
