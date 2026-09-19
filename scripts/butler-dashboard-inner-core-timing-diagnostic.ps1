Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$sourceScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$sourceRepoRoot = Split-Path -Parent $sourceScriptDir
$git = (Get-Command git.exe -ErrorAction Stop).Source
$powershell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$taskkill = Join-Path $env:SystemRoot 'System32\taskkill.exe'
$cmd = Join-Path $env:SystemRoot 'System32\cmd.exe'
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
        try { $response = $request.GetResponse() } catch { return $false }
        return [int]$response.StatusCode -eq 200
    }
    finally {
        if ($null -ne $response) { $response.Close() }
    }
}

function Parse-TimingHeader {
    param(
        [Parameter(Mandatory = $true)][string]$Header,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string[]]$Required
    )
    if ([string]::IsNullOrWhiteSpace($Header)) {
        throw "BF-857 BLOCKED: response is missing $Name."
    }
    $result = @{}
    foreach ($pair in ($Header -split ';')) {
        if ([string]::IsNullOrWhiteSpace($pair)) { continue }
        $parts = $pair.Split('=')
        if ($parts.Length -ne 2) {
            throw "BF-857 BLOCKED: malformed $Name pair: $pair"
        }
        $result[$parts[0]] = [double]::Parse(
            $parts[1],
            [Globalization.CultureInfo]::InvariantCulture)
    }
    foreach ($key in $Required) {
        if (-not $result.ContainsKey($key)) {
            throw "BF-857 BLOCKED: $Name is missing $key."
        }
    }
    return $result
}

function Invoke-TimedDashboard {
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
    $response = $null
    try {
        $response = $request.GetResponse()
        $wallMs = Get-ElapsedMs -StartedTicks $started
        $bf856 = Parse-TimingHeader -Header ([string]$response.Headers['X-Butler-BF856-Timing']) -Name 'X-Butler-BF856-Timing' -Required @(
            'cache_hit','core_proxy_ms','server_before_write_ms'
        )
        $bf857 = Parse-TimingHeader -Header ([string]$response.Headers['X-Butler-BF857-Timing']) -Name 'X-Butler-BF857-Timing' -Required @(
            'pool_backend_ms',
            'preserved_dashboard_ms',
            'dashboard_summary_ms',
            'dashboard_html_ms',
            'dashboard_parse_base_ms',
            'dashboard_snapshot_ms',
            'dashboard_priority_ms',
            'dashboard_decision_ms',
            'dashboard_manager_ms',
            'dashboard_materialize_ms',
            'dashboard_base_fields_ms',
            'dashboard_shell_ms',
            'dashboard_refresh_block_ms',
            'dashboard_next_plan_block_ms',
            'dashboard_explanation_lookup_ms',
            'dashboard_presnapshot_tail_ms'
        )
        $reader = [System.IO.StreamReader]::new($response.GetResponseStream(), [System.Text.Encoding]::UTF8)
        try { [void]$reader.ReadToEnd() } finally { $reader.Dispose() }

        if ([int][Math]::Round([double]$bf856.cache_hit) -ne 0) {
            throw "BF-857 BLOCKED: $Id unexpectedly hit the BF-693 cache; miss-path decomposition is required."
        }

        $core = [double]$bf856.core_proxy_ms
        $pool = [double]$bf857.pool_backend_ms
        $preserved = [double]$bf857.preserved_dashboard_ms
        $summary = [double]$bf857.dashboard_summary_ms
        $html = [double]$bf857.dashboard_html_ms
        $parseBase = [double]$bf857.dashboard_parse_base_ms
        $snapshot = [double]$bf857.dashboard_snapshot_ms
        $priority = [double]$bf857.dashboard_priority_ms
        $decision = [double]$bf857.dashboard_decision_ms
        $manager = [double]$bf857.dashboard_manager_ms
        $materialize = [double]$bf857.dashboard_materialize_ms
        $baseFields = [double]$bf857.dashboard_base_fields_ms
        $shell = [double]$bf857.dashboard_shell_ms
        $refreshBlock = [double]$bf857.dashboard_refresh_block_ms
        $nextPlanBlock = [double]$bf857.dashboard_next_plan_block_ms
        $explanationLookup = [double]$bf857.dashboard_explanation_lookup_ms
        $preSnapshotTail = [double]$bf857.dashboard_presnapshot_tail_ms
        $preSnapshotAccounted = $baseFields + $shell + $refreshBlock + $nextPlanBlock + $explanationLookup + $preSnapshotTail
        $htmlAccounted = $parseBase + $snapshot + $priority + $decision + $manager + $materialize

        return [pscustomobject]@{
            Id = $Id
            CoreProxyMs = $core
            PoolBackendMs = $pool
            PreservedDashboardMs = $preserved
            DashboardSummaryMs = $summary
            DashboardHtmlMs = $html
            DashboardParseBaseMs = $parseBase
            DashboardSnapshotMs = $snapshot
            DashboardPriorityMs = $priority
            DashboardDecisionMs = $decision
            DashboardManagerMs = $manager
            DashboardMaterializeMs = $materialize
            DashboardBaseFieldsMs = $baseFields
            DashboardShellMs = $shell
            DashboardRefreshBlockMs = $refreshBlock
            DashboardNextPlanBlockMs = $nextPlanBlock
            DashboardExplanationLookupMs = $explanationLookup
            DashboardPreSnapshotTailMs = $preSnapshotTail
            DashboardPreSnapshotResidualMs = [Math]::Max(0.0, $parseBase - $preSnapshotAccounted)
            DashboardHtmlResidualMs = [Math]::Max(0.0, $html - $htmlAccounted)
            OuterToPoolResidualMs = [Math]::Max(0.0, $core - $pool)
            PoolToPreservedResidualMs = [Math]::Max(0.0, $pool - $preserved)
            DashboardOtherResidualMs = [Math]::Max(0.0, $preserved - $summary - $html)
            ServerBeforeWriteMs = [double]$bf856.server_before_write_ms
            ClientWallMs = [double]$wallMs
        }
    }
    finally {
        if ($null -ne $response) { $response.Close() }
    }
}

function Write-Result {
    param([Parameter(Mandatory = $true)]$Result)
    Write-Host (
        "{0,-6} core={1,7:N1}ms pool={2,7:N1}ms preserved={3,7:N1}ms summary={4,7:N1}ms html={5,6:N1}ms | outer-res={6,6:N1}ms pool-res={7,6:N1}ms dash-res={8,6:N1}ms server={9,7:N1}ms wall={10,7:N1}ms" -f
        $Result.Id,
        $Result.CoreProxyMs,
        $Result.PoolBackendMs,
        $Result.PreservedDashboardMs,
        $Result.DashboardSummaryMs,
        $Result.DashboardHtmlMs,
        $Result.OuterToPoolResidualMs,
        $Result.PoolToPreservedResidualMs,
        $Result.DashboardOtherResidualMs,
        $Result.ServerBeforeWriteMs,
        $Result.ClientWallMs)
    Write-Host (
        "       html-stages parse={0,6:N1}ms snapshot={1,6:N1}ms priority={2,6:N1}ms decision={3,6:N1}ms manager={4,6:N1}ms materialize={5,6:N1}ms residual={6,6:N1}ms" -f
        $Result.DashboardParseBaseMs,
        $Result.DashboardSnapshotMs,
        $Result.DashboardPriorityMs,
        $Result.DashboardDecisionMs,
        $Result.DashboardManagerMs,
        $Result.DashboardMaterializeMs,
        $Result.DashboardHtmlResidualMs)
    Write-Host (
        "       pre-snapshot fields={0,6:N1}ms shell={1,6:N1}ms refresh={2,6:N1}ms next-plan={3,6:N1}ms explanation={4,6:N1}ms tail={5,6:N1}ms residual={6,6:N1}ms" -f
        $Result.DashboardBaseFieldsMs,
        $Result.DashboardShellMs,
        $Result.DashboardRefreshBlockMs,
        $Result.DashboardNextPlanBlockMs,
        $Result.DashboardExplanationLookupMs,
        $Result.DashboardPreSnapshotTailMs,
        $Result.DashboardPreSnapshotResidualMs)
}

function Get-Median {
    param(
        [Parameter(Mandatory = $true)][object[]]$Items,
        [Parameter(Mandatory = $true)][string]$Property
    )
    $values = @($Items | ForEach-Object { [double]($_.$Property) } | Sort-Object)
    return [double]$values[[int][Math]::Floor($values.Count / 2)]
}

function Stop-OwnedTree {
    param([AllowNull()]$Process)
    if ($null -eq $Process) { return }
    try { if ($Process.HasExited) { return } } catch { return }
    & $taskkill /PID $Process.Id /T /F 2>$null | Out-Null
    try { [void]$Process.WaitForExit(5000) } catch {}
}

Push-Location $sourceRepoRoot
try {
    $head = (& $git rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0 -or $head -notmatch '^[0-9a-f]{40}$') {
        throw 'BF-857 BLOCKED: unable to resolve exact source commit.'
    }
}
finally {
    Pop-Location
}

$worktree = Join-Path ([IO.Path]::GetTempPath()) ('b857-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
$port = Get-FreePort
$root = "http://127.0.0.1:$port"
$process = $null
$oldBf856 = $env:BUTLER_APP_BF856_ROUTE_TIMING
$oldBf857 = $env:BUTLER_APP_BF857_CORE_TIMING

Write-Host 'Butler Dashboard pre-snapshot helper timing diagnostic (BF-860)'
Write-Host "Commit: $head"
Write-Host "Worktree: $worktree"
Write-Host "Target: $root/"
Write-Host 'Boundary: read-only Dashboard GET only; BF-856/BF-857/BF-859/BF-860 timing is diagnostic-only for this owned process.'

try {
    Push-Location $sourceRepoRoot
    try {
        & $git worktree add --detach $worktree $head
        if ($LASTEXITCODE -ne 0) {
            throw "BF-857 BLOCKED: git worktree add failed with exit code $LASTEXITCODE."
        }
    }
    finally {
        Pop-Location
    }

    $appLauncher = Join-Path $worktree 'scripts\butler-app.ps1'
    if (-not (Test-Path -LiteralPath $appLauncher -PathType Leaf)) {
        throw "BF-857 BLOCKED: app launcher missing at $appLauncher"
    }

    $env:BUTLER_APP_BF856_ROUTE_TIMING = '1'
    $env:BUTLER_APP_BF857_CORE_TIMING = '1'

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
        throw 'BF-857 BLOCKED: unable to launch owned Butler process.'
    }
    $process | Add-Member -NotePropertyName ButlerStdoutTask -NotePropertyValue $process.StandardOutput.ReadToEndAsync()
    $process | Add-Member -NotePropertyName ButlerStderrTask -NotePropertyValue $process.StandardError.ReadToEndAsync()

    $deadline = [DateTime]::UtcNow.AddSeconds(180)
    $healthy = $false
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($process.HasExited) {
            $stdout = if ($process.ButlerStdoutTask.IsCompleted) { [string]$process.ButlerStdoutTask.Result } else { '' }
            $stderr = if ($process.ButlerStderrTask.IsCompleted) { [string]$process.ButlerStderrTask.Result } else { '' }
            throw "BF-857 BLOCKED: Butler exited during startup. stdout=$stdout stderr=$stderr"
        }
        if (Test-Health -Root $root -TimeoutMs 1000) {
            $healthy = $true
            break
        }
        Start-Sleep -Milliseconds 250
    }
    if (-not $healthy) {
        throw 'BF-857 BLOCKED: Butler did not become healthy within 180 seconds.'
    }

    $results = @()
    for ($i = 1; $i -le 3; $i++) {
        if ($i -gt 1) { Start-Sleep -Seconds 6 }
        $result = Invoke-TimedDashboard -Root $root -Id ("s{0}" -f $i)
        $results += $result
        Write-Result -Result $result
    }

    Write-Host ''
    Write-Host 'Warm miss-path p50'
    Write-Host (
        "core={0:N1}ms pool={1:N1}ms preserved={2:N1}ms summary={3:N1}ms html={4:N1}ms outer-res={5:N1}ms pool-res={6:N1}ms dash-res={7:N1}ms" -f
        (Get-Median -Items $results -Property 'CoreProxyMs'),
        (Get-Median -Items $results -Property 'PoolBackendMs'),
        (Get-Median -Items $results -Property 'PreservedDashboardMs'),
        (Get-Median -Items $results -Property 'DashboardSummaryMs'),
        (Get-Median -Items $results -Property 'DashboardHtmlMs'),
        (Get-Median -Items $results -Property 'OuterToPoolResidualMs'),
        (Get-Median -Items $results -Property 'PoolToPreservedResidualMs'),
        (Get-Median -Items $results -Property 'DashboardOtherResidualMs'))
    Write-Host (
        "html-stages parse={0:N1}ms snapshot={1:N1}ms priority={2:N1}ms decision={3:N1}ms manager={4:N1}ms materialize={5:N1}ms residual={6:N1}ms" -f
        (Get-Median -Items $results -Property 'DashboardParseBaseMs'),
        (Get-Median -Items $results -Property 'DashboardSnapshotMs'),
        (Get-Median -Items $results -Property 'DashboardPriorityMs'),
        (Get-Median -Items $results -Property 'DashboardDecisionMs'),
        (Get-Median -Items $results -Property 'DashboardManagerMs'),
        (Get-Median -Items $results -Property 'DashboardMaterializeMs'),
        (Get-Median -Items $results -Property 'DashboardHtmlResidualMs'))
    Write-Host (
        "pre-snapshot fields={0:N1}ms shell={1:N1}ms refresh={2:N1}ms next-plan={3:N1}ms explanation={4:N1}ms tail={5:N1}ms residual={6:N1}ms" -f
        (Get-Median -Items $results -Property 'DashboardBaseFieldsMs'),
        (Get-Median -Items $results -Property 'DashboardShellMs'),
        (Get-Median -Items $results -Property 'DashboardRefreshBlockMs'),
        (Get-Median -Items $results -Property 'DashboardNextPlanBlockMs'),
        (Get-Median -Items $results -Property 'DashboardExplanationLookupMs'),
        (Get-Median -Items $results -Property 'DashboardPreSnapshotTailMs'),
        (Get-Median -Items $results -Property 'DashboardPreSnapshotResidualMs'))

    Write-Host 'BF-860 RESULT: COMPLETE'
}
finally {
    Stop-OwnedTree -Process $process

    if ($null -eq $oldBf856) { Remove-Item Env:BUTLER_APP_BF856_ROUTE_TIMING -ErrorAction SilentlyContinue }
    else { $env:BUTLER_APP_BF856_ROUTE_TIMING = $oldBf856 }
    if ($null -eq $oldBf857) { Remove-Item Env:BUTLER_APP_BF857_CORE_TIMING -ErrorAction SilentlyContinue }
    else { $env:BUTLER_APP_BF857_CORE_TIMING = $oldBf857 }

    Push-Location $sourceRepoRoot
    try {
        $previousPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            & $git worktree remove --force $worktree 2>$null | Out-Null
        }
        catch {
        }
        finally {
            $ErrorActionPreference = $previousPreference
        }

        if (Test-Path -LiteralPath $worktree) {
            $extended = '\\?\' + $worktree
            try { & $cmd /d /c "rd /s /q `"$extended`"" 2>$null | Out-Null } catch {}
        }
        try { & $git worktree prune 2>$null | Out-Null } catch {}
    }
    finally {
        Pop-Location
    }
}
