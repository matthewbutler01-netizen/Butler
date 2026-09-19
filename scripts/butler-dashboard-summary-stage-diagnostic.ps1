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
    throw 'BF-854 BLOCKED: LocalApplicationData is unavailable.'
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
        throw "BF-854 BLOCKED: required diagnostic component not found at $required"
    }
}

$leagueId = [IO.File]::ReadAllText($configPath, [Text.Encoding]::ASCII).Trim().ToLowerInvariant()
$parsedLeagueId = [Guid]::Empty
if (-not [Guid]::TryParse($leagueId, [ref]$parsedLeagueId) -or
    $parsedLeagueId.ToString('D').ToLowerInvariant() -cne $leagueId) {
    throw 'BF-854 BLOCKED: configured Butler league id is not an exact canonical UUID.'
}

$isolatedBuildDir = Join-Path ([IO.Path]::GetTempPath()) ('Butler-bf854-bet-cli-build-' + [Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($isolatedBuildDir) | Out-Null

Push-Location $repoRoot
try {
    $isolatedProperty = '-PbutlerIsolatedBuildDir=' + $isolatedBuildDir
    & $gradle '--no-daemon' $isolatedProperty ':bet:bet-cli:installDist' '--quiet'
    if ($LASTEXITCODE -ne 0) {
        throw "BF-854 BLOCKED: isolated installDist failed with exit code $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}

$runtimeLibDir = Join-Path $isolatedBuildDir 'install\bet-cli\lib'
$jars = @(Get-ChildItem -LiteralPath $runtimeLibDir -Filter '*.jar' -File -ErrorAction Stop)
if ($jars.Count -eq 0) {
    throw "BF-854 BLOCKED: prepared runtime contains no jars at $runtimeLibDir"
}

$java = (Get-Command java.exe -ErrorAction Stop).Source
$classPath = Join-Path $runtimeLibDir '*'
$workerClass = 'io.butler.bet.cli.ButlerReadOnlyJvmWorker'
$tab = [char]9

function Read-ProtocolLine {
    param(
        [Parameter(Mandatory = $true)]$Worker,
        [Parameter(Mandatory = $true)][string]$Context,
        [ValidateRange(1, 300000)][int]$TimeoutMs = 180000
    )
    $task = $Worker.StandardOutput.ReadLineAsync()
    if (-not $task.Wait($TimeoutMs)) {
        try { if (-not $Worker.HasExited) { $Worker.Kill() } } catch {}
        throw "BF-854 BLOCKED: timed out waiting for $Context."
    }
    if ($null -eq $task.Result) {
        throw "BF-854 BLOCKED: worker closed stdout during $Context."
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
        throw 'BF-854 BLOCKED: unable to start persistent diagnostic worker.'
    }
    $process.StandardInput.AutoFlush = $true
    $process | Add-Member -NotePropertyName ButlerStderrTask -NotePropertyValue $process.StandardError.ReadToEndAsync()

    $ready = Read-ProtocolLine -Worker $process -Context 'worker readiness' -TimeoutMs 10000
    if ($ready -cne ('READY' + $tab + 'BF739' + $tab + '1')) {
        try { if (-not $process.HasExited) { $process.Kill() } } catch {}
        try { $process.Dispose() } catch {}
        throw "BF-854 BLOCKED: worker readiness frame was invalid: $ready"
    }
    return $process
}

function Invoke-Diagnostic {
    param(
        [Parameter(Mandatory = $true)]$Worker,
        [Parameter(Mandatory = $true)][string]$RequestId
    )

    $Worker.StandardInput.WriteLine('LATEST_SUMMARY_DIAGNOSTIC' + $tab + $RequestId + $tab + $leagueId)
    $line = Read-ProtocolLine -Worker $Worker -Context "diagnostic request $RequestId"
    $fields = @($line -split $tab, 5)
    if ($fields.Count -ne 5 -or $fields[0] -cne 'RESULT' -or $fields[1] -cne $RequestId) {
        throw "BF-854 BLOCKED: worker returned an invalid result frame for $RequestId."
    }

    $stdout = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($fields[3]))
    $stderr = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($fields[4]))
    if ($fields[2] -cne '0') {
        throw "BF-854 BLOCKED: diagnostic worker failed with exit code $($fields[2]). stdout=$stdout stderr=$stderr"
    }
    if ($stdout.IndexOf('BF854_BOUNDARY read_only=true; persistent_jvm=true; refresh=false; sleeper_write=false',
            [System.StringComparison]::Ordinal) -lt 0) {
        throw 'BF-854 BLOCKED: diagnostic output is missing the read-only boundary marker.'
    }

    $result = @{}
    foreach ($lineText in ($stdout -split '\r?\n')) {
        $match = [regex]::Match($lineText, '^BF854_STAGE (?<stage>[a-z0-9_]+)_ms=(?<ms>[0-9]+(?:\.[0-9]+)?)$')
        if (-not $match.Success) { continue }
        $result[$match.Groups['stage'].Value] = [double]::Parse(
            $match.Groups['ms'].Value,
            [Globalization.CultureInfo]::InvariantCulture)
    }
    foreach ($requiredStage in @(
        'database_initialize',
        'bf623_target_verify',
        'bf629_actionability',
        'bf631_lineage',
        'bf633_age',
        'assembly',
        'summary_total',
        'bf639_convergence',
        'summary_print',
        'total'
    )) {
        if (-not $result.ContainsKey($requiredStage)) {
            throw "BF-854 BLOCKED: diagnostic output is missing stage $requiredStage."
        }
    }
    return $result
}

function Get-P50 {
    param([Parameter(Mandatory = $true)][double[]]$Values)
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
                throw "BF-854 BLOCKED: worker shutdown frame was invalid: $bye"
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

Write-Host 'Butler Dashboard summary stage diagnostic (BF-854)'
Write-Host "League: $leagueId"
Write-Host "Data: $dataDir"
Write-Host 'Boundary: diagnostic-only read path in one persistent JVM; no /refresh and no Butler or Sleeper write.'

$worker = $null
try {
    $worker = Start-DiagnosticWorker
    [void](Invoke-Diagnostic -Worker $worker -RequestId 'warmup')

    $samples = @{}
    for ($sample = 1; $sample -le 5; $sample++) {
        $result = Invoke-Diagnostic -Worker $worker -RequestId ("sample-{0}" -f $sample)
        foreach ($stage in $result.Keys) {
            if (-not $samples.ContainsKey($stage)) {
                $samples[$stage] = New-Object System.Collections.Generic.List[double]
            }
            $samples[$stage].Add([double]$result[$stage])
        }
    }

    Write-Host ''
    Write-Host 'Stage p50 (5 warm persistent-JVM samples)'
    foreach ($stage in @(
        'database_initialize',
        'bf623_target_verify',
        'bf629_actionability',
        'bf631_lineage',
        'bf633_age',
        'assembly',
        'summary_total',
        'bf639_convergence',
        'summary_print',
        'total'
    )) {
        $p50 = Get-P50 -Values $samples[$stage].ToArray()
        Write-Host ("{0,-24} {1,8:N1} ms" -f $stage, $p50)
    }
    Write-Host ''
    Write-Host 'BF-854 RESULT: COMPLETE'
}
finally {
    Stop-DiagnosticWorker -Worker $worker
    if (Test-Path -LiteralPath $isolatedBuildDir) {
        Remove-Item -LiteralPath $isolatedBuildDir -Recurse -Force -ErrorAction SilentlyContinue
    }
}
