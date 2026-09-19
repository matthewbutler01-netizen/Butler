Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$sourceScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$sourceRepoRoot = Split-Path -Parent $sourceScriptDir
$git = (Get-Command git.exe -ErrorAction Stop).Source
$powershell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$taskkill = Join-Path $env:SystemRoot 'System32\taskkill.exe'
$loopback = [System.Net.IPAddress]::Parse('127.0.0.1')

function Get-FreePort {
    $listener = [System.Net.Sockets.TcpListener]::new($loopback, 0)
    try {
        $listener.Start()
        return ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
    }
    finally {
        $listener.Stop()
    }
}

function Get-ElapsedMs {
    param([Parameter(Mandatory = $true)][long]$StartedTicks)
    $elapsed = [System.Diagnostics.Stopwatch]::GetTimestamp() - $StartedTicks
    return ([double]$elapsed * 1000.0) / [double][System.Diagnostics.Stopwatch]::Frequency
}

function Test-Health {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][int]$TimeoutMs
    )
    $request = [System.Net.HttpWebRequest]::Create($Root + '/health')
    $request.Method = 'GET'
    $request.Timeout = $TimeoutMs
    $request.ReadWriteTimeout = $TimeoutMs
    $request.Proxy = $null
    $request.KeepAlive = $false
    $response = $null
    try {
        try {
            $response = $request.GetResponse()
        }
        catch {
            return $false
        }
        return [int]$response.StatusCode -eq 200
    }
    finally {
        if ($null -ne $response) { $response.Close() }
    }
}

function Parse-Bf856Header {
    param([Parameter(Mandatory = $true)][string]$Header)

    if ([string]::IsNullOrWhiteSpace($Header)) {
        throw 'BF-856 BLOCKED: Dashboard response is missing X-Butler-BF856-Timing.'
    }
    $result = @{}
    foreach ($pair in ($Header -split ';')) {
        $parts = $pair.Split('=')
        if ($parts.Length -ne 2) {
            throw "BF-856 BLOCKED: malformed timing pair: $pair"
        }
        $result[$parts[0]] = [double]::Parse(
            $parts[1],
            [Globalization.CultureInfo]::InvariantCulture)
    }
    foreach ($required in @(
        'cache_hit',
        'mutex_wait_ms',
        'semaphore_wait_ms',
        'core_proxy_ms',
        'singleflight_total_ms',
        'navigation_ms',
        'presentation_ms',
        'server_before_write_ms'
    )) {
        if (-not $result.ContainsKey($required)) {
            throw "BF-856 BLOCKED: timing header is missing $required."
        }
    }
    return $result
}

function Complete-TimedRequest {
    param(
        [Parameter(Mandatory = $true)]$Pending
    )

    $response = $null
    try {
        $response = $Pending.Task.Result
        $responseWallMs = Get-ElapsedMs -StartedTicks $Pending.StartedTicks
        $header = [string]$response.Headers['X-Butler-BF856-Timing']
        $timing = Parse-Bf856Header -Header $header

        $reader = [System.IO.StreamReader]::new($response.GetResponseStream(), [System.Text.Encoding]::UTF8)
        try { [void]$reader.ReadToEnd() } finally { $reader.Dispose() }

        return [pscustomobject]@{
            Id = $Pending.Id
            CacheHit = [int][Math]::Round([double]$timing.cache_hit)
            MutexWaitMs = [double]$timing.mutex_wait_ms
            SemaphoreWaitMs = [double]$timing.semaphore_wait_ms
            CoreProxyMs = [double]$timing.core_proxy_ms
            SingleFlightMs = [double]$timing.singleflight_total_ms
            NavigationMs = [double]$timing.navigation_ms
            PresentationMs = [double]$timing.presentation_ms
            ServerBeforeWriteMs = [double]$timing.server_before_write_ms
            ClientWallMs = [double]$responseWallMs
            ClientResidualMs = [Math]::Max(0.0, [double]$responseWallMs - [double]$timing.server_before_write_ms)
        }
    }
    finally {
        if ($null -ne $response) { $response.Close() }
    }
}

function Start-TimedRequest {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$Id
    )

    $request = [System.Net.HttpWebRequest]::Create($Root + '/')
    $request.Method = 'GET'
    $request.Timeout = 180000
    $request.ReadWriteTimeout = 180000
    $request.Proxy = $null
    $request.KeepAlive = $false
    $started = [System.Diagnostics.Stopwatch]::GetTimestamp()
    $task = $request.GetResponseAsync()
    return [pscustomobject]@{
        Id = $Id
        StartedTicks = [long]$started
        Task = $task
    }
}

function Write-TimingResult {
    param([Parameter(Mandatory = $true)]$Result)

    Write-Host (
        "{0,-10} cache={1} mutex={2,7:N1}ms sem={3,6:N1}ms core={4,7:N1}ms sf={5,7:N1}ms nav={6,6:N1}ms present={7,6:N1}ms server={8,7:N1}ms wall={9,7:N1}ms residual={10,6:N1}ms" -f
        $Result.Id,
        $Result.CacheHit,
        $Result.MutexWaitMs,
        $Result.SemaphoreWaitMs,
        $Result.CoreProxyMs,
        $Result.SingleFlightMs,
        $Result.NavigationMs,
        $Result.PresentationMs,
        $Result.ServerBeforeWriteMs,
        $Result.ClientWallMs,
        $Result.ClientResidualMs)
}

function Stop-OwnedTree {
    param([AllowNull()]$Process)
    if ($null -eq $Process) { return }
    try {
        if ($Process.HasExited) { return }
    }
    catch {
        return
    }

    & $taskkill /PID $Process.Id /T /F 2>$null | Out-Null
    try { [void]$Process.WaitForExit(5000) } catch {}
}

Push-Location $sourceRepoRoot
try {
    $head = (& $git rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0 -or $head -notmatch '^[0-9a-f]{40}$') {
        throw 'BF-856 BLOCKED: unable to resolve exact source commit.'
    }
}
finally {
    Pop-Location
}

$worktree = Join-Path ([IO.Path]::GetTempPath()) ('Butler-bf856-route-' + [Guid]::NewGuid().ToString('N'))
$port = Get-FreePort
$root = "http://127.0.0.1:$port"
$process = $null
$oldDiagnostic = $env:BUTLER_APP_BF856_ROUTE_TIMING

Write-Host 'Butler Dashboard outer-route timing diagnostic (BF-856)'
Write-Host "Commit: $head"
Write-Host "Worktree: $worktree"
Write-Host "Target: $root/"
Write-Host 'Boundary: read-only Dashboard GET only; diagnostic timing header enabled only for this owned process.'

try {
    Push-Location $sourceRepoRoot
    try {
        & $git worktree add --detach $worktree $head
        if ($LASTEXITCODE -ne 0) {
            throw "BF-856 BLOCKED: git worktree add failed with exit code $LASTEXITCODE."
        }
    }
    finally {
        Pop-Location
    }

    $appLauncher = Join-Path $worktree 'scripts\butler-app.ps1'
    if (-not (Test-Path -LiteralPath $appLauncher -PathType Leaf)) {
        throw "BF-856 BLOCKED: app launcher missing at $appLauncher"
    }

    $env:BUTLER_APP_BF856_ROUTE_TIMING = '1'
    $start = [System.Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $powershell
    $start.Arguments = "-NoLogo -NoProfile -ExecutionPolicy Bypass -File `"$appLauncher`" -Port $port -NoBrowser"
    $start.WorkingDirectory = $worktree
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true

    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $start
    if (-not $process.Start()) {
        throw 'BF-856 BLOCKED: unable to launch owned Butler process.'
    }
    $process | Add-Member -NotePropertyName ButlerStdoutTask -NotePropertyValue $process.StandardOutput.ReadToEndAsync()
    $process | Add-Member -NotePropertyName ButlerStderrTask -NotePropertyValue $process.StandardError.ReadToEndAsync()

    $deadline = [DateTime]::UtcNow.AddSeconds(180)
    $healthy = $false
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($process.HasExited) {
            $stdout = if ($process.ButlerStdoutTask.IsCompleted) { [string]$process.ButlerStdoutTask.Result } else { '' }
            $stderr = if ($process.ButlerStderrTask.IsCompleted) { [string]$process.ButlerStderrTask.Result } else { '' }
            throw "BF-856 BLOCKED: Butler exited during startup. stdout=$stdout stderr=$stderr"
        }
        if (Test-Health -Root $root -TimeoutMs 1000) {
            $healthy = $true
            break
        }
        Start-Sleep -Milliseconds 250
    }
    if (-not $healthy) {
        throw 'BF-856 BLOCKED: Butler did not become healthy within 180 seconds.'
    }

    Write-Host ''
    Write-Host 'Cold Dashboard request'
    $coldPending = Start-TimedRequest -Root $root -Id 'cold'
    $cold = Complete-TimedRequest -Pending $coldPending
    Write-TimingResult -Result $cold

    Start-Sleep -Seconds 6

    Write-Host ''
    Write-Host 'Three simultaneous Dashboard requests after cache expiry'
    $pending = @(
        (Start-TimedRequest -Root $root -Id 'c3-1'),
        (Start-TimedRequest -Root $root -Id 'c3-2'),
        (Start-TimedRequest -Root $root -Id 'c3-3')
    )
    $results = @()
    foreach ($item in $pending) {
        $results += Complete-TimedRequest -Pending $item
    }
    foreach ($result in ($results | Sort-Object Id)) {
        Write-TimingResult -Result $result
    }

    $leaders = @($results | Where-Object { $_.CacheHit -eq 0 })
    $followers = @($results | Where-Object { $_.CacheHit -eq 1 })
    Write-Host ''
    Write-Host ("Leader misses: {0}; cache followers: {1}" -f $leaders.Count, $followers.Count)
    if ($leaders.Count -ne 1 -or $followers.Count -ne 2) {
        throw 'BF-856 BLOCKED: expected exactly one concurrent cache miss leader and two single-flight cache followers.'
    }

    Write-Host 'BF-856 RESULT: COMPLETE'
}
finally {
    Stop-OwnedTree -Process $process

    if ($null -eq $oldDiagnostic) {
        Remove-Item Env:BUTLER_APP_BF856_ROUTE_TIMING -ErrorAction SilentlyContinue
    }
    else {
        $env:BUTLER_APP_BF856_ROUTE_TIMING = $oldDiagnostic
    }

    Push-Location $sourceRepoRoot
    try {
        if (Test-Path -LiteralPath $worktree) {
            & $git worktree remove --force $worktree 2>$null
        }
    }
    finally {
        Pop-Location
    }
}
