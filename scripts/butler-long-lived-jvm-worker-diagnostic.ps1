Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $scriptDir
$betCliDir = Join-Path $repoRoot 'bet\bet-cli'
$runtimeLibDir = Join-Path $betCliDir 'build\install\bet-cli\lib'

foreach ($required in @($betCliDir, $runtimeLibDir)) {
    if (-not (Test-Path -LiteralPath $required)) {
        throw "BF-739 BLOCKED: required worker-diagnostic component not found at $required"
    }
}

$jars = @(Get-ChildItem -LiteralPath $runtimeLibDir -Filter '*.jar' -File -ErrorAction Stop)
if ($jars.Count -eq 0) {
    throw "BF-739 BLOCKED: prepared Butler runtime contains no jars at $runtimeLibDir"
}

$javaCommand = Get-Command java.exe -ErrorAction Stop
$java = [string]$javaCommand.Source
if ([string]::IsNullOrWhiteSpace($java)) {
    throw 'BF-739 BLOCKED: java.exe could not be resolved.'
}

$classPath = Join-Path $runtimeLibDir '*'
$workerClass = 'io.butler.bet.cli.ButlerReadOnlyJvmWorker'
$workers = New-Object System.Collections.Generic.List[object]

function Start-Bf739Worker {
    $start = [System.Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $java
    $start.Arguments = "--enable-native-access=ALL-UNNAMED -cp `"$classPath`" $workerClass"
    $start.WorkingDirectory = $betCliDir
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardInput = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true

    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $start
    if (-not $process.Start()) {
        throw 'BF-739 BLOCKED: unable to start long-lived JVM worker.'
    }
    $process.StandardInput.AutoFlush = $true
    $ready = $process.StandardOutput.ReadLine()
    if ($ready -cne "READY`tBF739`t1") {
        try { if (-not $process.HasExited) { $process.Kill() } } catch {}
        try { $process.Dispose() } catch {}
        throw "BF-739 BLOCKED: worker readiness frame was invalid: $ready"
    }
    return $process
}

function Read-Bf739WorkerResult {
    param(
        [Parameter(Mandatory = $true)]$Worker,
        [Parameter(Mandatory = $true)][string]$RequestId
    )

    $line = $Worker.StandardOutput.ReadLine()
    if ($null -eq $line) {
        throw "BF-739 BLOCKED: worker ended before request $RequestId returned."
    }
    $fields = @($line -split "`t", 5)
    if ($fields.Count -ne 5 -or $fields[0] -cne 'RESULT' -or $fields[1] -cne $RequestId) {
        throw "BF-739 BLOCKED: worker returned an invalid result frame for request $RequestId."
    }
    if ($fields[2] -cne '0') {
        $stderr = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($fields[4]))
        throw "BF-739 BLOCKED: help request $RequestId failed inside worker: $stderr"
    }
    try {
        $stdout = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($fields[3]))
        [void][Convert]::FromBase64String($fields[4])
    }
    catch {
        throw "BF-739 BLOCKED: worker result frame for request $RequestId contained invalid Base64 payload."
    }
    if ([string]::IsNullOrWhiteSpace($stdout) -or $stdout.IndexOf('Governed lineup evidence:', [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-739 BLOCKED: worker help output for request $RequestId was incomplete."
    }
}

function Invoke-Bf739WorkerHelp {
    param(
        [Parameter(Mandatory = $true)]$Worker,
        [Parameter(Mandatory = $true)][string]$RequestId
    )

    $timer = [System.Diagnostics.Stopwatch]::StartNew()
    $Worker.StandardInput.WriteLine("HELP`t$RequestId")
    Read-Bf739WorkerResult -Worker $Worker -RequestId $RequestId
    $timer.Stop()
    return [double]$timer.Elapsed.TotalMilliseconds
}

function Stop-Bf739Worker {
    param([Parameter(Mandatory = $true)]$Worker)

    try {
        if (-not $Worker.HasExited) {
            $Worker.StandardInput.WriteLine('QUIT')
            $bye = $Worker.StandardOutput.ReadLine()
            if ($bye -cne "BYE`tBF739") {
                throw "BF-739 BLOCKED: worker shutdown frame was invalid: $bye"
            }
            if (-not $Worker.WaitForExit(2000)) {
                $Worker.Kill()
                [void]$Worker.WaitForExit(2000)
            }
        }
    }
    finally {
        try { $Worker.Dispose() } catch {}
    }
}

function Get-P50Ms {
    param([Parameter(Mandatory = $true)][double[]]$Values)
    if ($Values.Count -eq 0) { throw 'BF-739 BLOCKED: timing sample is empty.' }
    $sorted = @($Values | Sort-Object)
    $middle = [int][Math]::Floor($sorted.Count / 2)
    if (($sorted.Count % 2) -eq 1) { return [double]$sorted[$middle] }
    return [double](($sorted[$middle - 1] + $sorted[$middle]) / 2.0)
}

try {
    for ($index = 0; $index -lt 6; $index++) {
        $workers.Add((Start-Bf739Worker))
    }

    # Warm every persistent JVM once before timing so BF-739 measures request reuse, not JVM startup.
    for ($index = 0; $index -lt $workers.Count; $index++) {
        [void](Invoke-Bf739WorkerHelp -Worker $workers[$index] -RequestId ("warm-{0}" -f $index))
    }

    $sequential = New-Object System.Collections.Generic.List[double]
    for ($sample = 0; $sample -lt 9; $sample++) {
        $sequential.Add((Invoke-Bf739WorkerHelp -Worker $workers[0] -RequestId ("seq-{0}" -f $sample)))
    }

    $concurrentWall = New-Object System.Collections.Generic.List[double]
    for ($round = 0; $round -lt 5; $round++) {
        $requestIds = New-Object System.Collections.Generic.List[string]
        $timer = [System.Diagnostics.Stopwatch]::StartNew()
        for ($index = 0; $index -lt $workers.Count; $index++) {
            $requestId = "c6-{0}-{1}" -f $round, $index
            $requestIds.Add($requestId)
            $workers[$index].StandardInput.WriteLine("HELP`t$requestId")
        }
        for ($index = 0; $index -lt $workers.Count; $index++) {
            Read-Bf739WorkerResult -Worker $workers[$index] -RequestId $requestIds[$index]
        }
        $timer.Stop()
        $concurrentWall.Add([double]$timer.Elapsed.TotalMilliseconds)
    }

    $sequentialP50 = Get-P50Ms -Values $sequential.ToArray()
    $concurrentWallP50 = Get-P50Ms -Values $concurrentWall.ToArray()

    Write-Host ''
    Write-Host ("Long-lived JVM worker timing (diagnostic): warm_worker_seq_p50_ms={0:N1}; warm_worker_c6_wall_p50_ms={1:N1}; warm_worker_seq_samples=9; warm_worker_c6_rounds=5" -f $sequentialP50, $concurrentWallP50)
    Write-Host 'BF-739 diagnostic boundary: six persistent JVM workers; Butler global help only; production app-shell routing unchanged; no database or provider read, no /refresh, and no Butler or Sleeper write is invoked.'
}
finally {
    for ($index = $workers.Count - 1; $index -ge 0; $index--) {
        try { Stop-Bf739Worker -Worker $workers[$index] } catch {}
    }
}