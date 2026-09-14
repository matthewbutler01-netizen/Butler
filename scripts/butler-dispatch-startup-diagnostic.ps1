Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $scriptDir
$betCliDir = Join-Path $repoRoot 'bet\bet-cli'
$runtimeLibDir = Join-Path $betCliDir 'build\install\bet-cli\lib'
$dispatchSource = Join-Path $scriptDir 'butler-direct-java-dispatch.ps1'
$proxySource = Join-Path $scriptDir 'butler-direct-java-gradle-proxy.cmd'
$localAppData = $env:LOCALAPPDATA
if ([string]::IsNullOrWhiteSpace($localAppData)) {
    $localAppData = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)
}
if ([string]::IsNullOrWhiteSpace($localAppData)) {
    throw 'BF-738 BLOCKED: LocalApplicationData is unavailable.'
}

foreach ($required in @($betCliDir, $runtimeLibDir, $dispatchSource, $proxySource)) {
    if (-not (Test-Path -LiteralPath $required)) {
        throw "BF-738 BLOCKED: required startup-diagnostic component not found at $required"
    }
}

$jars = @(Get-ChildItem -LiteralPath $runtimeLibDir -Filter '*.jar' -File -ErrorAction Stop)
if ($jars.Count -eq 0) {
    throw "BF-738 BLOCKED: prepared Butler runtime contains no jars at $runtimeLibDir"
}

$javaCommand = Get-Command java.exe -ErrorAction Stop
$java = [string]$javaCommand.Source
if ([string]::IsNullOrWhiteSpace($java)) {
    throw 'BF-738 BLOCKED: java.exe could not be resolved.'
}
$comspec = [string]$env:ComSpec
if ([string]::IsNullOrWhiteSpace($comspec) -or -not (Test-Path -LiteralPath $comspec -PathType Leaf)) {
    throw 'BF-738 BLOCKED: cmd.exe could not be resolved.'
}

$tempRoot = Join-Path (Join-Path $localAppData 'Butler') ("dispatch-diagnostic-{0}" -f $PID)
$tempScripts = Join-Path $tempRoot 'scripts'
$tempProxy = Join-Path $tempRoot 'gradlew.bat'
$tempDispatch = Join-Path $tempScripts 'butler-direct-java-dispatch.ps1'
$originalRuntimeLib = $env:BUTLER_APP_RUNTIME_LIB
$originalRepoRoot = $env:BUTLER_APP_REPO_ROOT

function Restore-DiagnosticEnvironment {
    if ($null -eq $originalRuntimeLib) { Remove-Item Env:BUTLER_APP_RUNTIME_LIB -ErrorAction SilentlyContinue }
    else { $env:BUTLER_APP_RUNTIME_LIB = $originalRuntimeLib }

    if ($null -eq $originalRepoRoot) { Remove-Item Env:BUTLER_APP_REPO_ROOT -ErrorAction SilentlyContinue }
    else { $env:BUTLER_APP_REPO_ROOT = $originalRepoRoot }
}

function New-ProbeStartInfo {
    param([Parameter(Mandatory = $true)][ValidateSet('RAW_JAVA', 'PROXY_CHAIN')][string]$Mode)

    $start = [System.Diagnostics.ProcessStartInfo]::new()
    if ($Mode -ceq 'RAW_JAVA') {
        $classPath = Join-Path $runtimeLibDir '*'
        $start.FileName = $java
        $start.Arguments = "--enable-native-access=ALL-UNNAMED -cp `"$classPath`" io.butler.bet.cli.ButlerCommandRouter help"
        $start.WorkingDirectory = $betCliDir
    }
    else {
        $start.FileName = $comspec
        $start.Arguments = '/d /s /c ""' + $tempProxy + '" ":bet:bet-cli:run" "--args=help""'
        $start.WorkingDirectory = $tempRoot
    }
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    return $start
}

function Invoke-ProbeBatch {
    param(
        [Parameter(Mandatory = $true)][ValidateSet('RAW_JAVA', 'PROXY_CHAIN')][string]$Mode,
        [Parameter(Mandatory = $true)][ValidateRange(1, 6)][int]$Count
    )

    $batch = [System.Diagnostics.Stopwatch]::StartNew()
    $records = New-Object System.Collections.Generic.List[object]
    try {
        for ($index = 0; $index -lt $Count; $index++) {
            $process = [System.Diagnostics.Process]::new()
            $process.StartInfo = New-ProbeStartInfo -Mode $Mode
            if (-not $process.Start()) {
                throw "BF-738 BLOCKED: unable to start $Mode probe process."
            }
            $stdoutTask = $process.StandardOutput.ReadToEndAsync()
            $stderrTask = $process.StandardError.ReadToEndAsync()
            $records.Add([pscustomobject]@{
                Process = $process
                StdoutTask = $stdoutTask
                StderrTask = $stderrTask
            })
        }

        $durations = New-Object System.Collections.Generic.List[long]
        foreach ($record in $records) {
            $record.Process.WaitForExit()
            $stdout = [string]$record.StdoutTask.Result
            $stderr = [string]$record.StderrTask.Result
            if ($record.Process.ExitCode -ne 0) {
                $detail = [regex]::Replace(($stdout + ' ' + $stderr), '\s+', ' ').Trim()
                if ($detail.Length -gt 800) { $detail = '...' + $detail.Substring($detail.Length - 800) }
                throw "BF-738 BLOCKED: $Mode help probe exited with code $($record.Process.ExitCode); output=$detail"
            }
            if ([string]::IsNullOrWhiteSpace($stdout)) {
                throw "BF-738 BLOCKED: $Mode help probe produced no stdout."
            }
            $elapsed = [long][Math]::Round(($record.Process.ExitTime.ToUniversalTime() - $record.Process.StartTime.ToUniversalTime()).TotalMilliseconds)
            if ($elapsed -lt 0) {
                throw "BF-738 BLOCKED: $Mode probe reported a negative elapsed time."
            }
            $durations.Add($elapsed)
        }
        $batch.Stop()
        return [pscustomobject]@{
            Durations = @($durations.ToArray())
            WallMs = [long][Math]::Round($batch.Elapsed.TotalMilliseconds)
        }
    }
    finally {
        foreach ($record in $records) {
            try { $record.Process.Dispose() } catch {}
        }
    }
}

function Get-P50Ms {
    param([Parameter(Mandatory = $true)][long[]]$Values)
    if ($Values.Count -eq 0) { throw 'BF-738 BLOCKED: timing sample is empty.' }
    $sorted = @($Values | Sort-Object)
    $middle = [int][Math]::Floor($sorted.Count / 2)
    if (($sorted.Count % 2) -eq 1) { return [long]$sorted[$middle] }
    return [long][Math]::Round(($sorted[$middle - 1] + $sorted[$middle]) / 2.0)
}

try {
    if (Test-Path -LiteralPath $tempRoot) {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction Stop
    }
    New-Item -ItemType Directory -Path $tempScripts -Force | Out-Null
    Copy-Item -LiteralPath $dispatchSource -Destination $tempDispatch -Force
    Copy-Item -LiteralPath $proxySource -Destination $tempProxy -Force
    $env:BUTLER_APP_RUNTIME_LIB = $runtimeLibDir
    $env:BUTLER_APP_REPO_ROOT = $repoRoot

    # Warm OS/JAR file cache without touching Butler data or any provider.
    [void](Invoke-ProbeBatch -Mode RAW_JAVA -Count 1)
    [void](Invoke-ProbeBatch -Mode PROXY_CHAIN -Count 1)

    $rawSequential = New-Object System.Collections.Generic.List[long]
    $proxySequential = New-Object System.Collections.Generic.List[long]
    for ($sample = 0; $sample -lt 3; $sample++) {
        $raw = Invoke-ProbeBatch -Mode RAW_JAVA -Count 1
        $proxy = Invoke-ProbeBatch -Mode PROXY_CHAIN -Count 1
        $rawSequential.Add([long]$raw.Durations[0])
        $proxySequential.Add([long]$proxy.Durations[0])
    }

    $rawConcurrent = Invoke-ProbeBatch -Mode RAW_JAVA -Count 6
    $proxyConcurrent = Invoke-ProbeBatch -Mode PROXY_CHAIN -Count 6

    $rawSequentialP50 = Get-P50Ms -Values $rawSequential.ToArray()
    $proxySequentialP50 = Get-P50Ms -Values $proxySequential.ToArray()
    $rawConcurrentP50 = Get-P50Ms -Values ([long[]]$rawConcurrent.Durations)
    $proxyConcurrentP50 = Get-P50Ms -Values ([long[]]$proxyConcurrent.Durations)
    $proxySequentialOverhead = [Math]::Max(0L, $proxySequentialP50 - $rawSequentialP50)
    $proxyConcurrentOverhead = [Math]::Max(0L, $proxyConcurrentP50 - $rawConcurrentP50)

    Write-Host ''
    Write-Host ("Dispatch startup timing (diagnostic): raw_java_seq_p50_ms={0}; proxy_chain_seq_p50_ms={1}; raw_java_c6_p50_ms={2}; proxy_chain_c6_p50_ms={3}; proxy_wrapper_seq_overhead_ms={4}; proxy_wrapper_c6_overhead_ms={5}; raw_java_c6_wall_ms={6}; proxy_chain_c6_wall_ms={7}" -f
        $rawSequentialP50, $proxySequentialP50, $rawConcurrentP50, $proxyConcurrentP50,
        $proxySequentialOverhead, $proxyConcurrentOverhead, $rawConcurrent.WallMs, $proxyConcurrent.WallMs)
    Write-Host 'BF-738 diagnostic boundary: Butler global help only; no database or provider read, no /refresh, and no Butler or Sleeper write is invoked.'
}
finally {
    Restore-DiagnosticEnvironment
    if (Test-Path -LiteralPath $tempRoot) {
        try { Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction Stop } catch {}
    }
}
