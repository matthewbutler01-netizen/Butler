Set-StrictMode -Version Latest

$script:Bf740PersistentCoreWorkerCanary = ([string]$env:BUTLER_APP_PERSISTENT_CORE_WORKER -ceq '1')
$script:Bf740PersistentCoreWorkerProcess = $null
$script:Bf740PersistentCoreWorkerStderrTask = $null

function Get-Bf740JavaExecutable {
    if (-not [string]::IsNullOrWhiteSpace([string]$env:JAVA_HOME)) {
        $candidate = Join-Path $env:JAVA_HOME 'bin\java.exe'
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return $candidate
        }
    }

    try {
        $command = Get-Command java.exe -ErrorAction Stop
        return [string]$command.Source
    }
    catch {
        throw 'BF-740 BLOCKED: Java executable is unavailable for the persistent read worker.'
    }
}

function Get-Bf740WorkerFailureDetail {
    $detail = ''
    try {
        if ($null -ne $script:Bf740PersistentCoreWorkerStderrTask -and $script:Bf740PersistentCoreWorkerStderrTask.IsCompleted) {
            $detail = [string]$script:Bf740PersistentCoreWorkerStderrTask.Result
        }
    }
    catch {
    }

    if ([string]::IsNullOrWhiteSpace($detail)) { return '' }
    $detail = [regex]::Replace($detail, '\s+', ' ').Trim()
    if ($detail.Length -gt 800) {
        $detail = '...' + $detail.Substring($detail.Length - 800)
    }
    return '; stderr=' + $detail
}

function Read-Bf740WorkerLine {
    param(
        [Parameter(Mandatory = $true)]$Worker,
        [Parameter(Mandatory = $true)][ValidateRange(1, 300000)][int]$TimeoutMs,
        [Parameter(Mandatory = $true)][string]$Context
    )

    if ($Worker.HasExited) {
        throw "BF-740 BLOCKED: persistent read worker exited before $Context$(Get-Bf740WorkerFailureDetail)"
    }

    $task = $Worker.StandardOutput.ReadLineAsync()
    if (-not $task.Wait($TimeoutMs)) {
        try {
            if (-not $Worker.HasExited) {
                $Worker.Kill()
                [void]$Worker.WaitForExit(3000)
            }
        }
        catch {
        }
        throw "BF-740 BLOCKED: persistent read worker timed out during $Context and was terminated to prevent protocol desynchronization."
    }

    $line = [string]$task.Result
    if ($null -eq $task.Result) {
        throw "BF-740 BLOCKED: persistent read worker closed stdout during $Context$(Get-Bf740WorkerFailureDetail)"
    }
    return $line
}

function Start-Bf740PersistentCoreWorker {
    if (-not $script:Bf740PersistentCoreWorkerCanary) { return $null }
    if ($null -ne $script:Bf740PersistentCoreWorkerProcess) {
        throw 'BF-740 BLOCKED: persistent read worker was started more than once for one preserved core.'
    }

    $runtimeLib = [string]$env:BUTLER_APP_RUNTIME_LIB
    if ([string]::IsNullOrWhiteSpace($runtimeLib) -or -not (Test-Path -LiteralPath $runtimeLib -PathType Container)) {
        throw 'BF-740 BLOCKED: prepared Butler runtime library directory is unavailable.'
    }
    $dataDir = [string]$env:BUTLER_APP_DATA_DIR
    if ([string]::IsNullOrWhiteSpace($dataDir)) {
        throw 'BF-771 BLOCKED: BUTLER_APP_DATA_DIR is required for the persistent read worker.'
    }
    if (-not [IO.Path]::IsPathRooted($dataDir)) {
        throw 'BF-771 BLOCKED: BUTLER_APP_DATA_DIR must be an absolute path.'
    }
    $workingDir = [IO.Path]::GetFullPath($dataDir)
    if (-not (Test-Path -LiteralPath $workingDir -PathType Container)) {
        throw 'BF-771 BLOCKED: governed Butler app data directory is unavailable.'
    }

    $java = Get-Bf740JavaExecutable
    $classPath = Join-Path $runtimeLib '*'
    $start = [System.Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $java
    $start.Arguments = "--enable-native-access=ALL-UNNAMED -cp `"$classPath`" io.butler.bet.cli.ButlerReadOnlyJvmWorker"
    $start.WorkingDirectory = $workingDir
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardInput = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true

    $worker = [System.Diagnostics.Process]::new()
    $worker.StartInfo = $start
    if (-not $worker.Start()) {
        $worker.Dispose()
        throw 'BF-740 BLOCKED: unable to start persistent read worker.'
    }

    $worker.StandardInput.AutoFlush = $true
    $script:Bf740PersistentCoreWorkerProcess = $worker
    $script:Bf740PersistentCoreWorkerStderrTask = $worker.StandardError.ReadToEndAsync()

    try {
        $ready = Read-Bf740WorkerLine -Worker $worker -TimeoutMs 10000 -Context 'startup readiness'
        if ($ready -cne "READY`tBF739`t1") {
            throw "BF-740 BLOCKED: persistent read worker readiness frame was invalid: $ready"
        }
    }
    catch {
        try { if (-not $worker.HasExited) { $worker.Kill() } } catch {}
        try { $worker.Dispose() } catch {}
        $script:Bf740PersistentCoreWorkerProcess = $null
        $script:Bf740PersistentCoreWorkerStderrTask = $null
        throw
    }

    return $worker
}

function Invoke-Bf740PersistentCoreWorker {
    param(
        [Parameter(Mandatory = $true)]
        [ValidateSet('LEAGUE_OVERVIEW', 'TEAM_BUNDLE', 'LATEST_SUMMARY', 'LATEST_SUMMARY_DIAGNOSTIC', 'TARGET_VERIFY_DIAGNOSTIC', 'WAIVER_DASHBOARD_BUNDLE', 'MATCHUP_BUNDLE', 'EXPLANATION_LOOKUP')]
        [string]$Operation,

        [Parameter(Mandatory = $true)]
        [ValidateNotNullOrEmpty()]
        [string]$BoundaryName,

        [string]$AuditId
    )

    if (-not $script:Bf740PersistentCoreWorkerCanary) {
        throw "$BoundaryName BLOCKED: BF-742 persistent worker invocation was requested outside enabled runtime staging."
    }
    $worker = $script:Bf740PersistentCoreWorkerProcess
    if ($null -eq $worker -or $worker.HasExited) {
        throw "$BoundaryName BLOCKED: BF-742 persistent read worker is unavailable$(Get-Bf740WorkerFailureDetail)"
    }
    if ([string]::IsNullOrWhiteSpace([string]$LeagueId) -or [string]$LeagueId -notmatch '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$') {
        throw "$BoundaryName BLOCKED: BF-742 league id does not satisfy the exact canonical Butler UUID contract."
    }

    if ($Operation -ceq 'EXPLANATION_LOOKUP') {
        if ([string]::IsNullOrWhiteSpace($AuditId) -or $AuditId -notmatch '^[A-Za-z0-9._:-]{1,128}$') {
            throw "$BoundaryName BLOCKED: BF-742 explanation lookup audit id is missing or malformed."
        }
    }
    elseif (-not [string]::IsNullOrWhiteSpace($AuditId)) {
        throw "$BoundaryName BLOCKED: BF-742 audit id is authorized only for EXPLANATION_LOOKUP."
    }

    $requestId = [Guid]::NewGuid().ToString('N')
    if ($Operation -ceq 'EXPLANATION_LOOKUP') {
        $worker.StandardInput.WriteLine("$Operation`t$requestId`t$LeagueId`t$AuditId")
    }
    else {
        $worker.StandardInput.WriteLine("$Operation`t$requestId`t$LeagueId")
    }
    $line = Read-Bf740WorkerLine -Worker $worker -TimeoutMs 180000 -Context ("$Operation request $requestId")
    $fields = @($line -split "`t", 5)

    if ($fields.Count -eq 2 -and $fields[0] -ceq 'REJECT') {
        try {
            $detail = [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($fields[1]))
        }
        catch {
            $detail = 'invalid rejection payload'
        }
        throw "$BoundaryName BLOCKED: BF-742 worker rejected the authorized request: $detail"
    }
    if ($fields.Count -ne 5 -or $fields[0] -cne 'RESULT' -or $fields[1] -cne $requestId) {
        throw "$BoundaryName BLOCKED: BF-742 worker returned an invalid result frame."
    }

    try {
        $stdout = [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($fields[3]))
        $stderr = [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($fields[4]))
    }
    catch {
        throw "$BoundaryName BLOCKED: BF-742 worker returned an invalid Base64 result payload."
    }

    if ($fields[2] -cne '0') {
        throw "$BoundaryName BLOCKED: governed read-only worker command failed with exit code $($fields[2]).`n$stdout`n$stderr"
    }
    return $stdout
}

function Stop-Bf740PersistentCoreWorker {
    $worker = $script:Bf740PersistentCoreWorkerProcess
    $script:Bf740PersistentCoreWorkerProcess = $null
    if ($null -eq $worker) { return }

    try {
        if (-not $worker.HasExited) {
            $worker.StandardInput.WriteLine('QUIT')
            $bye = Read-Bf740WorkerLine -Worker $worker -TimeoutMs 3000 -Context 'clean shutdown'
            if ($bye -cne "BYE`tBF739") {
                throw "BF-740 BLOCKED: persistent read worker shutdown frame was invalid: $bye"
            }
            if (-not $worker.WaitForExit(3000)) {
                $worker.Kill()
                [void]$worker.WaitForExit(3000)
            }
        }
    }
    finally {
        try { if (-not $worker.HasExited) { $worker.Kill() } } catch {}
        try { $worker.Dispose() } catch {}
        $script:Bf740PersistentCoreWorkerStderrTask = $null
    }
}
