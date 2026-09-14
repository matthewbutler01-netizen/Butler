param(
    [ValidateRange(1, 5)]
    [int]$Samples = 3
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-Bf749MedianMilliseconds {
    param(
        [Parameter(Mandatory = $true)]
        [long[]]$Values
    )

    if ($null -eq $Values -or $Values.Count -eq 0) {
        throw 'BF-749 BLOCKED: median requires at least one timing sample.'
    }

    $ordered = @($Values | Sort-Object)
    $count = $ordered.Count
    $middle = [int][Math]::Floor($count / 2.0)
    if (($count % 2) -eq 1) {
        return [long]$ordered[$middle]
    }

    $lower = [long]$ordered[$middle - 1]
    $upper = [long]$ordered[$middle]
    return [long][Math]::Round(($lower + $upper) / 2.0, [MidpointRounding]::AwayFromZero)
}

function Invoke-Bf749TimedWorkerOperation {
    param(
        [Parameter(Mandatory = $true)]
        [ValidateSet('TEAM_BUNDLE', 'WAIVER_DASHBOARD_BUNDLE', 'LEAGUE_OVERVIEW')]
        [string]$Operation
    )

    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    $null = Invoke-Bf740PersistentCoreWorker `
        -Operation $Operation `
        -BoundaryName ("BF-749 {0}" -f $Operation)
    $stopwatch.Stop()
    return [long]$stopwatch.ElapsedMilliseconds
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $scriptDir
$betCliDir = Join-Path $repoRoot 'bet\bet-cli'
$runtimeLibDir = Join-Path $betCliDir 'build\install\bet-cli\lib'
$workerHelper = Join-Path $scriptDir 'butler-persistent-core-worker.ps1'

$localAppData = $env:LOCALAPPDATA
if ([string]::IsNullOrWhiteSpace($localAppData)) {
    $localAppData = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)
}
if ([string]::IsNullOrWhiteSpace($localAppData)) {
    throw 'BF-749 BLOCKED: LocalApplicationData is unavailable.'
}

$configPath = Join-Path (Join-Path $localAppData 'Butler') 'app-league.txt'
if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
    throw 'BF-749 BLOCKED: Butler app league configuration is unavailable.'
}

$leagueId = [IO.File]::ReadAllText($configPath, [Text.Encoding]::ASCII).Trim()
$parsedLeagueId = [Guid]::Empty
if ([string]::IsNullOrWhiteSpace($leagueId) -or -not [Guid]::TryParse($leagueId, [ref]$parsedLeagueId)) {
    throw 'BF-749 BLOCKED: Butler app league configuration is invalid.'
}
$leagueId = $parsedLeagueId.ToString('D').ToLowerInvariant()

if (-not (Test-Path -LiteralPath $betCliDir -PathType Container)) {
    throw "BF-749 BLOCKED: bet-cli working directory not found at $betCliDir"
}
if (-not (Test-Path -LiteralPath $runtimeLibDir -PathType Container)) {
    throw "BF-749 BLOCKED: prepared Butler runtime library directory not found at $runtimeLibDir"
}
if (-not (Test-Path -LiteralPath $workerHelper -PathType Leaf)) {
    throw "BF-749 BLOCKED: persistent worker helper not found at $workerHelper"
}

$previousWorkerFlag = [Environment]::GetEnvironmentVariable('BUTLER_APP_PERSISTENT_CORE_WORKER', 'Process')
$previousRuntimeLib = [Environment]::GetEnvironmentVariable('BUTLER_APP_RUNTIME_LIB', 'Process')
$previousRepoRoot = [Environment]::GetEnvironmentVariable('BUTLER_APP_REPO_ROOT', 'Process')

$workerStarted = $false
try {
    $env:BUTLER_APP_PERSISTENT_CORE_WORKER = '1'
    $env:BUTLER_APP_RUNTIME_LIB = $runtimeLibDir
    $env:BUTLER_APP_REPO_ROOT = $repoRoot
    $script:LeagueId = $leagueId

    . $workerHelper
    $null = Start-Bf740PersistentCoreWorker
    $workerStarted = $true

    $primeMs = Invoke-Bf749TimedWorkerOperation -Operation 'LEAGUE_OVERVIEW'

    $teamSamples = @()
    $waiverSamples = @()
    $leagueSamples = @()

    for ($sample = 1; $sample -le $Samples; $sample++) {
        $teamMs = Invoke-Bf749TimedWorkerOperation -Operation 'TEAM_BUNDLE'
        $waiverMs = Invoke-Bf749TimedWorkerOperation -Operation 'WAIVER_DASHBOARD_BUNDLE'
        $leagueMs = Invoke-Bf749TimedWorkerOperation -Operation 'LEAGUE_OVERVIEW'

        $teamSamples += [long]$teamMs
        $waiverSamples += [long]$waiverMs
        $leagueSamples += [long]$leagueMs

        Write-Host ("BF-749 sample {0}: team_bundle_ms={1}; waiver_dashboard_bundle_ms={2}; league_overview_ms={3}" -f `
            $sample, $teamMs, $waiverMs, $leagueMs)
    }

    $teamMedian = Get-Bf749MedianMilliseconds -Values $teamSamples
    $waiverMedian = Get-Bf749MedianMilliseconds -Values $waiverSamples
    $leagueMedian = Get-Bf749MedianMilliseconds -Values $leagueSamples

    Write-Host ''
    Write-Host ("BF-749 persistent worker command timing: prime_ms={0}; team_bundle_ms={1}; waiver_dashboard_bundle_ms={2}; league_overview_ms={3}; samples={4}" -f `
        $primeMs, $teamMedian, $waiverMedian, $leagueMedian, $Samples)
    Write-Host 'BF-749 diagnostic boundary: exact existing persistent worker read operations only; /refresh excluded; no Butler or Sleeper write path is invoked; no provider concurrency, response caching, or production behavior change.'
}
finally {
    if ($workerStarted) {
        try {
            Stop-Bf740PersistentCoreWorker
        }
        catch {
        }
    }

    if ($null -eq $previousWorkerFlag) {
        Remove-Item Env:BUTLER_APP_PERSISTENT_CORE_WORKER -ErrorAction SilentlyContinue
    }
    else {
        $env:BUTLER_APP_PERSISTENT_CORE_WORKER = $previousWorkerFlag
    }

    if ($null -eq $previousRuntimeLib) {
        Remove-Item Env:BUTLER_APP_RUNTIME_LIB -ErrorAction SilentlyContinue
    }
    else {
        $env:BUTLER_APP_RUNTIME_LIB = $previousRuntimeLib
    }

    if ($null -eq $previousRepoRoot) {
        Remove-Item Env:BUTLER_APP_REPO_ROOT -ErrorAction SilentlyContinue
    }
    else {
        $env:BUTLER_APP_REPO_ROOT = $previousRepoRoot
    }
}
