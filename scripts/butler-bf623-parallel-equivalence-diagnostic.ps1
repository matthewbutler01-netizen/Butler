Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $scriptDir
$gradle = Join-Path $repoRoot 'gradlew.bat'
$localAppData = $env:LOCALAPPDATA
if ([string]::IsNullOrWhiteSpace($localAppData)) {
    $localAppData = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)
}
if ([string]::IsNullOrWhiteSpace($localAppData)) {
    throw 'BF-862 BLOCKED: LocalApplicationData is unavailable.'
}

$configDir = Join-Path $localAppData 'Butler'
$configPath = Join-Path $configDir 'app-league.txt'
$dataDir = if ([string]::IsNullOrWhiteSpace([string]$env:BUTLER_APP_DATA_DIR)) {
    Join-Path $configDir 'data'
} else {
    [string]$env:BUTLER_APP_DATA_DIR
}
$dataDir = [IO.Path]::GetFullPath($dataDir)
$databasePath = Join-Path $dataDir 'butler.db'

foreach ($required in @($gradle, $configPath, $databasePath)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "BF-862 BLOCKED: required diagnostic component not found at $required"
    }
}

$leagueId = [IO.File]::ReadAllText($configPath, [Text.Encoding]::ASCII).Trim().ToLowerInvariant()
$parsedLeagueId = [Guid]::Empty
if (-not [Guid]::TryParse($leagueId, [ref]$parsedLeagueId) -or
    $parsedLeagueId.ToString('D').ToLowerInvariant() -cne $leagueId) {
    throw 'BF-862 BLOCKED: configured Butler league id is not an exact canonical UUID.'
}

$isolatedBuildDir = Join-Path ([IO.Path]::GetTempPath()) ('Butler-bf862-bet-cli-build-' + [Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($isolatedBuildDir) | Out-Null

Push-Location $repoRoot
try {
    $isolatedProperty = '-PbutlerIsolatedBuildDir=' + $isolatedBuildDir
    & $gradle '--no-daemon' $isolatedProperty ':bet:bet-cli:installDist' '--quiet'
    if ($LASTEXITCODE -ne 0) {
        throw "BF-862 BLOCKED: isolated installDist failed with exit code $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}

$runtimeLibDir = Join-Path $isolatedBuildDir 'install\bet-cli\lib'
$jars = @(Get-ChildItem -LiteralPath $runtimeLibDir -Filter '*.jar' -File -ErrorAction Stop)
if ($jars.Count -eq 0) {
    throw "BF-862 BLOCKED: prepared runtime contains no jars at $runtimeLibDir"
}

$java = (Get-Command java.exe -ErrorAction Stop).Source
$classPath = Join-Path $runtimeLibDir '*'
$workerClass = 'io.butler.bet.cli.ButlerReadOnlyJvmWorker'
$tab = [char]9
$workers = New-Object System.Collections.Generic.List[object]

function Read-ProtocolLine {
    param(
        [Parameter(Mandatory = $true)]$Worker,
        [Parameter(Mandatory = $true)][string]$Context,
        [ValidateRange(1, 300000)][int]$TimeoutMs = 180000
    )
    $task = $Worker.StandardOutput.ReadLineAsync()
    if (-not $task.Wait($TimeoutMs)) {
        try { if (-not $Worker.HasExited) { $Worker.Kill() } } catch {}
        throw "BF-862 BLOCKED: timed out waiting for $Context."
    }
    if ($null -eq $task.Result) {
        throw "BF-862 BLOCKED: worker closed stdout during $Context."
    }
    return [string]$task.Result
}

function Start-DiagnosticWorker {
    $start = [System.Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $java
    $start.Arguments = '--enable-native-access=ALL-UNNAMED -cp "' + $classPath + '" ' + $workerClass
    $start.WorkingDirectory = $dataDir
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardInput = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true

    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $start
    if (-not $process.Start()) {
        throw 'BF-862 BLOCKED: unable to start persistent diagnostic worker.'
    }
    $process.StandardInput.AutoFlush = $true
    $process | Add-Member -NotePropertyName ButlerStderrTask -NotePropertyValue $process.StandardError.ReadToEndAsync()

    $ready = Read-ProtocolLine -Worker $process -Context 'worker readiness' -TimeoutMs 10000
    if ($ready -cne ('READY' + $tab + 'BF739' + $tab + '1')) {
        try { if (-not $process.HasExited) { $process.Kill() } } catch {}
        try { $process.Dispose() } catch {}
        throw "BF-862 BLOCKED: worker readiness frame was invalid: $ready"
    }
    return $process
}

function Send-Request {
    param(
        [Parameter(Mandatory = $true)]$Worker,
        [Parameter(Mandatory = $true)][string]$Operation,
        [Parameter(Mandatory = $true)][string]$RequestId
    )
    $Worker.StandardInput.WriteLine($Operation + $tab + $RequestId + $tab + $leagueId)
}

function Read-Result {
    param(
        [Parameter(Mandatory = $true)]$Worker,
        [Parameter(Mandatory = $true)][string]$RequestId,
        [Parameter(Mandatory = $true)][ValidateSet('SERIAL','PARALLEL')][string]$Mode
    )

    $line = Read-ProtocolLine -Worker $Worker -Context "$Mode request $RequestId"
    $fields = @($line -split $tab, 5)
    if ($fields.Count -ne 5 -or $fields[0] -cne 'RESULT' -or $fields[1] -cne $RequestId) {
        throw "BF-862 BLOCKED: worker returned an invalid result frame for $RequestId."
    }

    $stdout = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($fields[3]))
    $stderr = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($fields[4]))
    if ($fields[2] -cne '0') {
        throw "BF-862 BLOCKED: $Mode worker failed with exit code $($fields[2]). stdout=$stdout stderr=$stderr"
    }

    $prefix = if ($Mode -ceq 'SERIAL') { 'BF855' } else { 'BF862' }
    $boundary = if ($Mode -ceq 'SERIAL') {
        'BF855_BOUNDARY read_only=true; persistent_jvm=true; refresh=false; sleeper_write=false'
    } else {
        'BF862_BOUNDARY read_only=true; persistent_jvm=true; serial_reference_available=true; production_parallel=true; refresh=false; sleeper_write=false'
    }
    if ($stdout.IndexOf($boundary, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-862 BLOCKED: $Mode output is missing the read-only boundary marker."
    }

    $result = @{}
    $target = $null
    foreach ($lineText in ($stdout -split '\r?\n')) {
        $match = [regex]::Match(
            $lineText,
            ('^' + $prefix + '_STAGE (?<stage>[a-z0-9_]+)_ms=(?<ms>[0-9]+(?:\.[0-9]+)?)$'))
        if ($match.Success) {
            $result[$match.Groups['stage'].Value] = [double]::Parse(
                $match.Groups['ms'].Value,
                [Globalization.CultureInfo]::InvariantCulture)
            continue
        }
        $targetMatch = [regex]::Match($lineText, ('^' + $prefix + '_TARGET (?<target>.+)$'))
        if ($targetMatch.Success) {
            $target = $targetMatch.Groups['target'].Value
        }
    }

    foreach ($requiredStage in @('user','user_leagues','league','rosters','league_users','verify_total')) {
        if (-not $result.ContainsKey($requiredStage)) {
            throw "BF-862 BLOCKED: $Mode output is missing stage $requiredStage."
        }
    }
    if ([string]::IsNullOrWhiteSpace($target)) {
        throw "BF-862 BLOCKED: $Mode output is missing the exact target signature."
    }
    return [pscustomobject]@{ Stages = $result; Target = $target }
}

function Invoke-Request {
    param(
        [Parameter(Mandatory = $true)]$Worker,
        [Parameter(Mandatory = $true)][string]$Operation,
        [Parameter(Mandatory = $true)][string]$RequestId,
        [Parameter(Mandatory = $true)][ValidateSet('SERIAL','PARALLEL')][string]$Mode
    )
    Send-Request -Worker $Worker -Operation $Operation -RequestId $RequestId
    return Read-Result -Worker $Worker -RequestId $RequestId -Mode $Mode
}

function Get-P50 {
    param([Parameter(Mandatory = $true)][double[]]$Values)
    if ($Values.Count -eq 0) { throw 'BF-862 BLOCKED: timing sample is empty.' }
    $sorted = @($Values | Sort-Object)
    $middle = [int][Math]::Floor($sorted.Count / 2)
    if (($sorted.Count % 2) -eq 1) { return [double]$sorted[$middle] }
    return [double](($sorted[$middle - 1] + $sorted[$middle]) / 2.0)
}

function Add-Stages {
    param(
        [Parameter(Mandatory = $true)]$Destination,
        [Parameter(Mandatory = $true)]$Stages
    )
    foreach ($stage in $Stages.Keys) {
        if (-not $Destination.ContainsKey($stage)) {
            $Destination[$stage] = New-Object System.Collections.Generic.List[double]
        }
        $Destination[$stage].Add([double]$Stages[$stage])
    }
}

function Stop-DiagnosticWorker {
    param([AllowNull()]$Worker)
    if ($null -eq $Worker) { return }
    try {
        if (-not $Worker.HasExited) {
            $Worker.StandardInput.WriteLine('QUIT')
            $bye = Read-ProtocolLine -Worker $Worker -Context 'worker shutdown' -TimeoutMs 3000
            if ($bye -cne ('BYE' + $tab + 'BF739')) {
                throw "BF-862 BLOCKED: worker shutdown frame was invalid: $bye"
            }
            if (-not $Worker.WaitForExit(3000)) {
                $Worker.Kill()
                [void]$Worker.WaitForExit(3000)
            }
        }
    }
    finally {
        try { if (-not $Worker.HasExited) { $Worker.Kill() } } catch {}
        try { $Worker.Dispose() } catch {}
    }
}

Write-Host 'Butler BF-623 parallel equivalence diagnostic (BF-862)'
Write-Host "League: $leagueId"
Write-Host "Data: $dataDir"
Write-Host 'Boundary: diagnostic-only exact BF-623 comparison; serial reference retained; production uses proven parallel verification; no refresh or write path.'

try {
    $workers.Add((Start-DiagnosticWorker))
    $workers.Add((Start-DiagnosticWorker))

    $serialWarm = Invoke-Request -Worker $workers[0] -Operation 'TARGET_VERIFY_DIAGNOSTIC' -RequestId 'warm-serial' -Mode 'SERIAL'
    $parallelWarm = Invoke-Request -Worker $workers[1] -Operation 'TARGET_VERIFY_PARALLEL_DIAGNOSTIC' -RequestId 'warm-parallel' -Mode 'PARALLEL'
    if ($serialWarm.Target -cne $parallelWarm.Target) {
        throw 'BF-862 BLOCKED: serial and parallel warm target signatures differ.'
    }
    $expectedTarget = $serialWarm.Target

    $serial = @{}
    $parallel = @{}
    for ($sample = 1; $sample -le 5; $sample++) {
        $serialResult = Invoke-Request -Worker $workers[0] -Operation 'TARGET_VERIFY_DIAGNOSTIC' -RequestId ("serial-{0}" -f $sample) -Mode 'SERIAL'
        $parallelResult = Invoke-Request -Worker $workers[0] -Operation 'TARGET_VERIFY_PARALLEL_DIAGNOSTIC' -RequestId ("parallel-{0}" -f $sample) -Mode 'PARALLEL'
        if ($serialResult.Target -cne $expectedTarget -or $parallelResult.Target -cne $expectedTarget) {
            throw "BF-862 BLOCKED: exact target signature drifted during sequential sample $sample."
        }
        Add-Stages -Destination $serial -Stages $serialResult.Stages
        Add-Stages -Destination $parallel -Stages $parallelResult.Stages
    }

    $parallelC2 = @{}
    $c2Wall = New-Object System.Collections.Generic.List[double]
    for ($round = 1; $round -le 5; $round++) {
        $id0 = "c2-{0}-0" -f $round
        $id1 = "c2-{0}-1" -f $round
        $watch = [System.Diagnostics.Stopwatch]::StartNew()
        Send-Request -Worker $workers[0] -Operation 'TARGET_VERIFY_PARALLEL_DIAGNOSTIC' -RequestId $id0
        Send-Request -Worker $workers[1] -Operation 'TARGET_VERIFY_PARALLEL_DIAGNOSTIC' -RequestId $id1
        $result0 = Read-Result -Worker $workers[0] -RequestId $id0 -Mode 'PARALLEL'
        $result1 = Read-Result -Worker $workers[1] -RequestId $id1 -Mode 'PARALLEL'
        $watch.Stop()
        $c2Wall.Add([double]$watch.Elapsed.TotalMilliseconds)

        foreach ($result in @($result0, $result1)) {
            if ($result.Target -cne $expectedTarget) {
                throw "BF-862 BLOCKED: exact target signature drifted during concurrency-2 round $round."
            }
            Add-Stages -Destination $parallelC2 -Stages $result.Stages
        }
    }

    Write-Host ''
    Write-Host 'BF-623 serial vs parallel provider stage p50'
    Write-Host ("{0,-18} {1,12} {2,14} {3,14}" -f 'stage','serial_ms','parallel_ms','parallel_c2_ms')
    foreach ($stage in @('user','user_leagues','league','rosters','league_users','verify_total')) {
        $serialP50 = Get-P50 -Values $serial[$stage].ToArray()
        $parallelP50 = Get-P50 -Values $parallel[$stage].ToArray()
        $c2P50 = Get-P50 -Values $parallelC2[$stage].ToArray()
        Write-Host ("{0,-18} {1,12:N1} {2,14:N1} {3,14:N1}" -f $stage, $serialP50, $parallelP50, $c2P50)
    }

    $serialVerify = Get-P50 -Values $serial['verify_total'].ToArray()
    $parallelVerify = Get-P50 -Values $parallel['verify_total'].ToArray()
    $improvement = if ($serialVerify -gt 0.0) {
        (($serialVerify - $parallelVerify) / $serialVerify) * 100.0
    } else { 0.0 }
    $wall = Get-P50 -Values $c2Wall.ToArray()

    Write-Host ''
    Write-Host ("serial_verify_p50_ms={0:N1}; parallel_verify_p50_ms={1:N1}; improvement_pct={2:N1}; c2_wall_p50_ms={3:N1}" -f
        $serialVerify, $parallelVerify, $improvement, $wall)
    Write-Host 'target_equivalence=EXACT'
    Write-Host 'BF-862 RESULT: COMPLETE'
}
finally {
    for ($index = $workers.Count - 1; $index -ge 0; $index--) {
        try { Stop-DiagnosticWorker -Worker $workers[$index] } catch {}
    }
    if (Test-Path -LiteralPath $isolatedBuildDir) {
        Remove-Item -LiteralPath $isolatedBuildDir -Recurse -Force -ErrorAction SilentlyContinue
    }
}
