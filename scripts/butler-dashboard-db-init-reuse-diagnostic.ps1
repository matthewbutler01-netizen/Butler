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
    throw 'BF-866 BLOCKED: LocalApplicationData is unavailable.'
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
        throw "BF-866 BLOCKED: required diagnostic component not found at $required"
    }
}

$leagueId = [IO.File]::ReadAllText($configPath, [Text.Encoding]::ASCII).Trim().ToLowerInvariant()
$parsedLeagueId = [Guid]::Empty
if (-not [Guid]::TryParse($leagueId, [ref]$parsedLeagueId) -or
    $parsedLeagueId.ToString('D').ToLowerInvariant() -cne $leagueId) {
    throw 'BF-866 BLOCKED: configured Butler league id is not an exact canonical UUID.'
}

$isolatedBuildDir = Join-Path ([IO.Path]::GetTempPath()) ('Butler-bf866-bet-cli-build-' + [Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($isolatedBuildDir) | Out-Null

Push-Location $repoRoot
try {
    $isolatedProperty = '-PbutlerIsolatedBuildDir=' + $isolatedBuildDir
    & $gradle '--no-daemon' $isolatedProperty ':bet:bet-cli:installDist' '--quiet'
    if ($LASTEXITCODE -ne 0) {
        throw "BF-866 BLOCKED: isolated installDist failed with exit code $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}

$runtimeLibDir = Join-Path $isolatedBuildDir 'install\bet-cli\lib'
$jars = @(Get-ChildItem -LiteralPath $runtimeLibDir -Filter '*.jar' -File -ErrorAction Stop)
if ($jars.Count -eq 0) {
    throw "BF-866 BLOCKED: prepared runtime contains no jars at $runtimeLibDir"
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
        throw "BF-866 BLOCKED: timed out waiting for $Context."
    }
    if ($null -eq $task.Result) {
        throw "BF-866 BLOCKED: worker closed stdout during $Context."
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
        throw 'BF-866 BLOCKED: unable to start persistent diagnostic worker.'
    }
    $process.StandardInput.AutoFlush = $true
    $process | Add-Member -NotePropertyName ButlerStderrTask -NotePropertyValue $process.StandardError.ReadToEndAsync()

    $ready = Read-ProtocolLine -Worker $process -Context 'worker readiness' -TimeoutMs 10000
    if ($ready -cne ('READY' + $tab + 'BF739' + $tab + '1')) {
        try { if (-not $process.HasExited) { $process.Kill() } } catch {}
        try { $process.Dispose() } catch {}
        throw "BF-866 BLOCKED: worker readiness frame was invalid: $ready"
    }
    return $process
}

function Invoke-Diagnostic {
    param(
        [Parameter(Mandatory = $true)]$Worker,
        [Parameter(Mandatory = $true)][ValidateSet('NORMAL','REUSE')][string]$Mode,
        [Parameter(Mandatory = $true)][string]$RequestId
    )

    $operation = if ($Mode -ceq 'NORMAL') { 'LATEST_SUMMARY_DIAGNOSTIC' } else { 'LATEST_SUMMARY_REUSE_DIAGNOSTIC' }
    $prefix = if ($Mode -ceq 'NORMAL') { 'BF854' } else { 'BF866' }
    $Worker.StandardInput.WriteLine($operation + $tab + $RequestId + $tab + $leagueId)

    $line = Read-ProtocolLine -Worker $Worker -Context "$Mode request $RequestId"
    $fields = @($line -split $tab, 5)
    if ($fields.Count -ne 5 -or $fields[0] -cne 'RESULT' -or $fields[1] -cne $RequestId) {
        throw "BF-866 BLOCKED: worker returned an invalid result frame for $RequestId."
    }

    $stdout = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($fields[3]))
    $stderr = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($fields[4]))
    if ($fields[2] -cne '0') {
        throw "BF-866 BLOCKED: $Mode worker failed with exit code $($fields[2]). stdout=$stdout stderr=$stderr"
    }

    $boundary = if ($Mode -ceq 'NORMAL') {
        'BF854_BOUNDARY read_only=true; persistent_jvm=true; refresh=false; sleeper_write=false'
    } else {
        'BF866_BOUNDARY read_only=true; persistent_jvm=true; db_init_reuse_diagnostic=true; shared_connection=false; refresh=false; sleeper_write=false'
    }
    if ($stdout.IndexOf($boundary, [StringComparison]::Ordinal) -lt 0) {
        throw "BF-866 BLOCKED: $Mode output is missing the read-only boundary marker."
    }

    $stages = @{}
    $signature = $null
    $initializedThisCall = $null
    foreach ($lineText in ($stdout -split '\r?\n')) {
        $stageMatch = [regex]::Match(
            $lineText,
            ('^' + $prefix + '_STAGE (?<stage>[a-z0-9_]+)_ms=(?<ms>[0-9]+(?:\.[0-9]+)?)$'))
        if ($stageMatch.Success) {
            $stages[$stageMatch.Groups['stage'].Value] = [double]::Parse(
                $stageMatch.Groups['ms'].Value,
                [Globalization.CultureInfo]::InvariantCulture)
            continue
        }

        $signatureMatch = [regex]::Match($lineText, ('^' + $prefix + '_SIGNATURE (?<signature>.+)$'))
        if ($signatureMatch.Success) {
            $signature = $signatureMatch.Groups['signature'].Value
            continue
        }

        if ($Mode -ceq 'REUSE') {
            $initMatch = [regex]::Match($lineText, '^BF866_INIT initialized_this_call=(?<value>true|false)$')
            if ($initMatch.Success) {
                $initializedThisCall = [bool]::Parse($initMatch.Groups['value'].Value)
            }
        }
    }

    foreach ($requiredStage in @(
        'database_initialize',
        'bf623_target_verify',
        'bf629_actionability',
        'bf631_lineage',
        'bf633_age',
        'summary_total',
        'total'
    )) {
        if (-not $stages.ContainsKey($requiredStage)) {
            throw "BF-866 BLOCKED: $Mode output is missing stage $requiredStage."
        }
    }
    if ([string]::IsNullOrWhiteSpace([string]$signature)) {
        throw "BF-866 BLOCKED: $Mode output is missing the exact decision signature."
    }
    if ($Mode -ceq 'REUSE' -and $null -eq $initializedThisCall) {
        throw 'BF-866 BLOCKED: reuse output is missing initialization state.'
    }

    return [pscustomobject]@{
        Stages = $stages
        Signature = $signature
        InitializedThisCall = $initializedThisCall
    }
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

function Get-P50 {
    param([Parameter(Mandatory = $true)][double[]]$Values)
    if ($Values.Count -eq 0) { throw 'BF-866 BLOCKED: timing sample is empty.' }
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
                throw "BF-866 BLOCKED: worker shutdown frame was invalid: $bye"
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

Write-Host 'Butler persistent-worker DB initialization reuse diagnostic (BF-866)'
Write-Host "League: $leagueId"
Write-Host "Data: $dataDir"
Write-Host 'Boundary: diagnostic comparison of repeated vs reused DB initialization; no shared JDBC connection; production persistent-worker LATEST_SUMMARY uses proven reuse; direct CLI remains unchanged; no refresh or Butler/Sleeper write.'

$worker = $null
$restartWorker = $null
try {
    $worker = Start-DiagnosticWorker

    $normalWarm = Invoke-Diagnostic -Worker $worker -Mode 'NORMAL' -RequestId 'normal-warm'
    $reuseFirst = Invoke-Diagnostic -Worker $worker -Mode 'REUSE' -RequestId 'reuse-first'
    if (-not $reuseFirst.InitializedThisCall) {
        throw 'BF-866 BLOCKED: first reuse call did not initialize the database.'
    }
    if ($normalWarm.Signature -cne $reuseFirst.Signature) {
        throw 'BF-866 BLOCKED: normal and first reuse decision signatures differ.'
    }

    $reuseWarm = Invoke-Diagnostic -Worker $worker -Mode 'REUSE' -RequestId 'reuse-warm'
    if ($reuseWarm.InitializedThisCall) {
        throw 'BF-866 BLOCKED: second reuse call repeated database initialization.'
    }
    if ($normalWarm.Signature -cne $reuseWarm.Signature) {
        throw 'BF-866 BLOCKED: normal and warmed reuse decision signatures differ.'
    }
    $expectedSignature = $normalWarm.Signature

    $normalSamples = @{}
    $reuseSamples = @{}
    for ($sample = 1; $sample -le 5; $sample++) {
        $normal = Invoke-Diagnostic -Worker $worker -Mode 'NORMAL' -RequestId ("normal-{0}" -f $sample)
        $reuse = Invoke-Diagnostic -Worker $worker -Mode 'REUSE' -RequestId ("reuse-{0}" -f $sample)

        if ($normal.Signature -cne $expectedSignature -or $reuse.Signature -cne $expectedSignature) {
            throw "BF-866 BLOCKED: exact decision signature drifted during paired sample $sample."
        }
        if ($reuse.InitializedThisCall) {
            throw "BF-866 BLOCKED: reuse sample $sample repeated database initialization."
        }

        Add-Stages -Destination $normalSamples -Stages $normal.Stages
        Add-Stages -Destination $reuseSamples -Stages $reuse.Stages
    }

    $restartWorker = Start-DiagnosticWorker
    $restartFirst = Invoke-Diagnostic -Worker $restartWorker -Mode 'REUSE' -RequestId 'restart-first'
    if (-not $restartFirst.InitializedThisCall) {
        throw 'BF-866 BLOCKED: new worker process did not initialize the database.'
    }
    if ($restartFirst.Signature -cne $expectedSignature) {
        throw 'BF-866 BLOCKED: restarted worker decision signature differs.'
    }

    Write-Host ''
    Write-Host 'Normal vs DB-reuse p50 (5 paired warm persistent-JVM samples)'
    Write-Host ("{0,-24} {1,12} {2,12}" -f 'stage','normal_ms','reuse_ms')
    foreach ($stage in @(
        'database_initialize',
        'bf623_target_verify',
        'bf629_actionability',
        'bf631_lineage',
        'bf633_age',
        'summary_total',
        'total'
    )) {
        $normalP50 = Get-P50 -Values $normalSamples[$stage].ToArray()
        $reuseP50 = Get-P50 -Values $reuseSamples[$stage].ToArray()
        Write-Host ("{0,-24} {1,12:N1} {2,12:N1}" -f $stage, $normalP50, $reuseP50)
    }

    $normalTotal = Get-P50 -Values $normalSamples['total'].ToArray()
    $reuseTotal = Get-P50 -Values $reuseSamples['total'].ToArray()
    $improvement = if ($normalTotal -gt 0.0) {
        (($normalTotal - $reuseTotal) / $normalTotal) * 100.0
    } else { 0.0 }

    Write-Host ''
    Write-Host ("normal_total_p50_ms={0:N1}; reuse_total_p50_ms={1:N1}; improvement_pct={2:N1}" -f
        $normalTotal, $reuseTotal, $improvement)
    Write-Host 'decision_equivalence=EXACT'
    Write-Host 'first_reuse_initialized=TRUE'
    Write-Host 'warm_reuse_reinitialized=FALSE'
    Write-Host 'restart_reinitialized=TRUE'
    Write-Host 'BF-866 RESULT: COMPLETE'
}
finally {
    try { Stop-DiagnosticWorker -Worker $restartWorker } catch {}
    try { Stop-DiagnosticWorker -Worker $worker } catch {}
    if (Test-Path -LiteralPath $isolatedBuildDir) {
        Remove-Item -LiteralPath $isolatedBuildDir -Recurse -Force -ErrorAction SilentlyContinue
    }
}
