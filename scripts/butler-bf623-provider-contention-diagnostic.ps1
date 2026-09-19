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
    throw 'BF-855 BLOCKED: LocalApplicationData is unavailable.'
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
        throw "BF-855 BLOCKED: required diagnostic component not found at $required"
    }
}

$leagueId = [IO.File]::ReadAllText($configPath, [Text.Encoding]::ASCII).Trim().ToLowerInvariant()
$parsedLeagueId = [Guid]::Empty
if (-not [Guid]::TryParse($leagueId, [ref]$parsedLeagueId) -or
    $parsedLeagueId.ToString('D').ToLowerInvariant() -cne $leagueId) {
    throw 'BF-855 BLOCKED: configured Butler league id is not an exact canonical UUID.'
}

$isolatedBuildDir = Join-Path ([IO.Path]::GetTempPath()) ('Butler-bf855-bet-cli-build-' + [Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($isolatedBuildDir) | Out-Null

Push-Location $repoRoot
try {
    $isolatedProperty = '-PbutlerIsolatedBuildDir=' + $isolatedBuildDir
    & $gradle '--no-daemon' $isolatedProperty ':bet:bet-cli:installDist' '--quiet'
    if ($LASTEXITCODE -ne 0) {
        throw "BF-855 BLOCKED: isolated installDist failed with exit code $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}

$runtimeLibDir = Join-Path $isolatedBuildDir 'install\bet-cli\lib'
$jars = @(Get-ChildItem -LiteralPath $runtimeLibDir -Filter '*.jar' -File -ErrorAction Stop)
if ($jars.Count -eq 0) {
    throw "BF-855 BLOCKED: prepared runtime contains no jars at $runtimeLibDir"
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
        throw "BF-855 BLOCKED: timed out waiting for $Context."
    }
    if ($null -eq $task.Result) {
        throw "BF-855 BLOCKED: worker closed stdout during $Context."
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
        throw 'BF-855 BLOCKED: unable to start persistent diagnostic worker.'
    }
    $process.StandardInput.AutoFlush = $true
    $process | Add-Member -NotePropertyName ButlerStderrTask -NotePropertyValue $process.StandardError.ReadToEndAsync()

    $ready = Read-ProtocolLine -Worker $process -Context 'worker readiness' -TimeoutMs 10000
    if ($ready -cne ('READY' + $tab + 'BF739' + $tab + '1')) {
        try { if (-not $process.HasExited) { $process.Kill() } } catch {}
        try { $process.Dispose() } catch {}
        throw "BF-855 BLOCKED: worker readiness frame was invalid: $ready"
    }
    return $process
}

function Send-DiagnosticRequest {
    param(
        [Parameter(Mandatory = $true)]$Worker,
        [Parameter(Mandatory = $true)][string]$RequestId
    )
    $Worker.StandardInput.WriteLine('TARGET_VERIFY_DIAGNOSTIC' + $tab + $RequestId + $tab + $leagueId)
}

function Read-DiagnosticResult {
    param(
        [Parameter(Mandatory = $true)]$Worker,
        [Parameter(Mandatory = $true)][string]$RequestId
    )

    $line = Read-ProtocolLine -Worker $Worker -Context "diagnostic request $RequestId"
    $fields = @($line -split $tab, 5)
    if ($fields.Count -ne 5 -or $fields[0] -cne 'RESULT' -or $fields[1] -cne $RequestId) {
        throw "BF-855 BLOCKED: worker returned an invalid result frame for $RequestId."
    }

    $stdout = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($fields[3]))
    $stderr = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($fields[4]))
    if ($fields[2] -cne '0') {
        throw "BF-855 BLOCKED: diagnostic worker failed with exit code $($fields[2]). stdout=$stdout stderr=$stderr"
    }
    if ($stdout.IndexOf('BF855_BOUNDARY read_only=true; persistent_jvm=true; refresh=false; sleeper_write=false',
            [System.StringComparison]::Ordinal) -lt 0) {
        throw 'BF-855 BLOCKED: diagnostic output is missing the read-only boundary marker.'
    }

    $result = @{}
    foreach ($lineText in ($stdout -split '\r?\n')) {
        $match = [regex]::Match($lineText, '^BF855_STAGE (?<stage>[a-z0-9_]+)_ms=(?<ms>[0-9]+(?:\.[0-9]+)?)$')
        if (-not $match.Success) { continue }
        $result[$match.Groups['stage'].Value] = [double]::Parse(
            $match.Groups['ms'].Value,
            [Globalization.CultureInfo]::InvariantCulture)
    }

    foreach ($requiredStage in @('user','user_leagues','league','rosters','league_users','verify_total')) {
        if (-not $result.ContainsKey($requiredStage)) {
            throw "BF-855 BLOCKED: diagnostic output is missing stage $requiredStage."
        }
    }
    return $result
}

function Invoke-Diagnostic {
    param(
        [Parameter(Mandatory = $true)]$Worker,
        [Parameter(Mandatory = $true)][string]$RequestId
    )
    Send-DiagnosticRequest -Worker $Worker -RequestId $RequestId
    return Read-DiagnosticResult -Worker $Worker -RequestId $RequestId
}

function Get-P50 {
    param([Parameter(Mandatory = $true)][double[]]$Values)
    if ($Values.Count -eq 0) { throw 'BF-855 BLOCKED: timing sample is empty.' }
    $sorted = @($Values | Sort-Object)
    $middle = [int][Math]::Floor($sorted.Count / 2)
    if (($sorted.Count % 2) -eq 1) { return [double]$sorted[$middle] }
    return [double](($sorted[$middle - 1] + $sorted[$middle]) / 2.0)
}

function Stop-DiagnosticWorker {
    param([AllowNull()]$Worker)
    if ($null -eq $Worker) { return }
    try {
        if (-not $Worker.HasExited) {
            $Worker.StandardInput.WriteLine('QUIT')
            $bye = Read-ProtocolLine -Worker $Worker -Context 'worker shutdown' -TimeoutMs 3000
            if ($bye -cne ('BYE' + $tab + 'BF739')) {
                throw "BF-855 BLOCKED: worker shutdown frame was invalid: $bye"
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

Write-Host 'Butler BF-623 provider contention diagnostic (BF-855)'
Write-Host "League: $leagueId"
Write-Host "Data: $dataDir"
Write-Host 'Boundary: exact BF-623 read-only verification only; two persistent JVM workers match BF-851 companion-heavy capacity=2.'

try {
    $workers.Add((Start-DiagnosticWorker))
    $workers.Add((Start-DiagnosticWorker))

    [void](Invoke-Diagnostic -Worker $workers[0] -RequestId 'warm-0')
    [void](Invoke-Diagnostic -Worker $workers[1] -RequestId 'warm-1')

    $sequential = @{}
    for ($sample = 1; $sample -le 5; $sample++) {
        $result = Invoke-Diagnostic -Worker $workers[0] -RequestId ("seq-{0}" -f $sample)
        foreach ($stage in $result.Keys) {
            if (-not $sequential.ContainsKey($stage)) {
                $sequential[$stage] = New-Object System.Collections.Generic.List[double]
            }
            $sequential[$stage].Add([double]$result[$stage])
        }
    }

    $concurrent = @{}
    $c2Wall = New-Object System.Collections.Generic.List[double]
    for ($round = 1; $round -le 5; $round++) {
        $id0 = "c2-{0}-0" -f $round
        $id1 = "c2-{0}-1" -f $round
        $watch = [System.Diagnostics.Stopwatch]::StartNew()
        Send-DiagnosticRequest -Worker $workers[0] -RequestId $id0
        Send-DiagnosticRequest -Worker $workers[1] -RequestId $id1
        $result0 = Read-DiagnosticResult -Worker $workers[0] -RequestId $id0
        $result1 = Read-DiagnosticResult -Worker $workers[1] -RequestId $id1
        $watch.Stop()
        $c2Wall.Add([double]$watch.Elapsed.TotalMilliseconds)

        foreach ($result in @($result0, $result1)) {
            foreach ($stage in $result.Keys) {
                if (-not $concurrent.ContainsKey($stage)) {
                    $concurrent[$stage] = New-Object System.Collections.Generic.List[double]
                }
                $concurrent[$stage].Add([double]$result[$stage])
            }
        }
    }

    Write-Host ''
    Write-Host 'BF-623 provider stage p50'
    Write-Host ("{0,-18} {1,12} {2,12} {3,12}" -f 'stage','seq_p50_ms','c2_p50_ms','delta_ms')
    foreach ($stage in @('user','user_leagues','league','rosters','league_users','verify_total')) {
        $seq = Get-P50 -Values $sequential[$stage].ToArray()
        $c2 = Get-P50 -Values $concurrent[$stage].ToArray()
        Write-Host ("{0,-18} {1,12:N1} {2,12:N1} {3,12:N1}" -f $stage, $seq, $c2, ($c2 - $seq))
    }

    $wall = Get-P50 -Values $c2Wall.ToArray()
    Write-Host ''
    Write-Host ("c2_wall_p50_ms={0:N1}; sequential_samples=5; concurrent_worker_samples=10; concurrent_rounds=5" -f $wall)
    Write-Host 'BF-855 RESULT: COMPLETE'
}
finally {
    for ($index = $workers.Count - 1; $index -ge 0; $index--) {
        try { Stop-DiagnosticWorker -Worker $workers[$index] } catch {}
    }
    if (Test-Path -LiteralPath $isolatedBuildDir) {
        Remove-Item -LiteralPath $isolatedBuildDir -Recurse -Force -ErrorAction SilentlyContinue
    }
}
