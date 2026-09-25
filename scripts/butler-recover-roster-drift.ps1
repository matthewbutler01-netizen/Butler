param([string]$JavaHome = $env:JAVA_HOME)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $scriptDir
$betCliDir = Join-Path $repoRoot 'bet\bet-cli'
$runtimeLibDir = Join-Path $betCliDir 'build\install\bet-cli\lib'
$acceptancePreflight = Join-Path $scriptDir 'butler-acceptance-preflight.ps1'
$acceptanceCmd = Join-Path $scriptDir 'butler-acceptance.cmd'
& (Join-Path $scriptDir 'butler-verification-environment.ps1') -JavaHome $JavaHome

$localAppData = $env:LOCALAPPDATA
if ([string]::IsNullOrWhiteSpace($localAppData)) {
    $localAppData = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)
}
if ([string]::IsNullOrWhiteSpace($localAppData)) {
    throw 'BF-723 BLOCKED: LocalApplicationData is unavailable.'
}
$configDir = Join-Path $localAppData 'Butler'

function Resolve-ButlerRuntimeDataDir {
    $configured = [string]$env:BUTLER_APP_DATA_DIR
    if ([string]::IsNullOrWhiteSpace($configured)) {
        $candidate = Join-Path $configDir 'data'
    }
    else {
        if (-not [IO.Path]::IsPathRooted($configured)) {
            throw 'BF-723 BLOCKED: BUTLER_APP_DATA_DIR must be an absolute path.'
        }
        $candidate = $configured
    }

    $resolved = [IO.Path]::GetFullPath($candidate)
    $sourceRoot = [IO.Path]::GetFullPath($repoRoot).TrimEnd('\')
    $sourcePrefix = $sourceRoot + '\'
    if ($resolved.Equals($sourceRoot, [System.StringComparison]::OrdinalIgnoreCase) -or
        $resolved.StartsWith($sourcePrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'BF-723 BLOCKED: Butler runtime data directory must be outside the source/package tree.'
    }
    if (-not (Test-Path -LiteralPath $resolved -PathType Container)) {
        throw "BF-723 BLOCKED: governed Butler runtime data directory not found at $resolved"
    }

    $databasePath = Join-Path $resolved 'butler.db'
    if (-not (Test-Path -LiteralPath $databasePath -PathType Leaf)) {
        throw "BF-723 BLOCKED: governed Butler runtime database not found at $databasePath"
    }
    return $resolved
}

function Get-ConfiguredLeagueId {
    $configPath = Join-Path $configDir 'app-league.txt'
    if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
        throw 'BF-723 BLOCKED: Butler app league is not configured.'
    }

    $raw = [IO.File]::ReadAllText($configPath, [Text.Encoding]::ASCII).Trim()
    $parsed = [Guid]::Empty
    if ([string]::IsNullOrWhiteSpace($raw) -or -not [Guid]::TryParse($raw, [ref]$parsed)) {
        throw 'BF-723 BLOCKED: configured Butler app league id is invalid.'
    }
    return $parsed.ToString('D').ToLowerInvariant()
}

function Get-ButlerJavaExecutable {
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
        throw 'BF-723 BLOCKED: java.exe is unavailable for governed recovery.'
    }
}

function Get-BoundedTail {
    param([AllowNull()][string]$Text, [int]$Limit = 2200)

    if ([string]::IsNullOrWhiteSpace($Text)) { return 'no task output' }
    $normalized = [regex]::Replace($Text, '\s+', ' ').Trim()
    if ($normalized.Length -le $Limit) { return $normalized }
    return '...' + $normalized.Substring($normalized.Length - $Limit)
}

$dataDir = Resolve-ButlerRuntimeDataDir
$databasePath = Join-Path $dataDir 'butler.db'
$java = Get-ButlerJavaExecutable
$classPath = Join-Path $runtimeLibDir '*'

foreach ($required in @($betCliDir, $acceptancePreflight, $acceptanceCmd, $databasePath)) {
    if (-not (Test-Path -LiteralPath $required)) {
        throw "BF-723 BLOCKED: required recovery component not found at $required"
    }
}

function Invoke-ButlerRuntimeCommand {
    param(
        [Parameter(Mandatory = $true)][string]$MainClass,
        [Parameter(Mandatory = $true)][string]$LeagueId
    )

    $previousPreference = $ErrorActionPreference
    $lines = $null
    $exitCode = $null
    Push-Location $dataDir
    try {
        try {
            $ErrorActionPreference = 'Continue'
            $lines = & $java '--enable-native-access=ALL-UNNAMED' '-cp' $classPath $MainClass $LeagueId 2>&1
            $exitCode = $LASTEXITCODE
        }
        finally {
            $ErrorActionPreference = $previousPreference
        }
    }
    finally {
        Pop-Location
    }

    return [pscustomobject]@{
        ExitCode = [int]$exitCode
        Text = (($lines | ForEach-Object { "$_" }) -join "`n")
    }
}

function Invoke-RequiredSuccess {
    param(
        [Parameter(Mandatory = $true)][string]$MainClass,
        [Parameter(Mandatory = $true)][string]$Label,
        [Parameter(Mandatory = $true)][string]$LeagueId
    )

    Write-Host ("BF-723: running {0}..." -f $Label)
    $result = Invoke-ButlerRuntimeCommand -MainClass $MainClass -LeagueId $LeagueId
    if ($result.ExitCode -ne 0) {
        $tail = Get-BoundedTail -Text $result.Text
        throw "BF-723 BLOCKED: $Label failed with runtime exit code $($result.ExitCode); output=$tail"
    }
    Write-Host ("BF-723: {0} complete." -f $Label)
    return $result
}

$leagueId = Get-ConfiguredLeagueId
$driftPrefix = 'BF-610 BLOCKED: current roster membership drifted from BF-603/BF-602 frame; added='
$driftSuffix = '; refresh BF-602/BF-603 and downstream live evidence before target-roster review'
$bf610Class = 'io.butler.bet.cli.ButlerSleeperLiveWaiverTargetRosterContextAuditCli'
$comparisonClass = 'io.butler.bet.cli.ButlerSleeperLiveWaiverComparisonBundleCli'

Write-Host 'Butler governed roster-drift evidence recovery (BF-723)'
Write-Host "League: $leagueId"
Write-Host "Data: $dataDir"
Write-Host 'Boundary: this command may write fresh Butler evidence for BF-602/BF-603/BF-605/BF-606/BF-607/BF-612.'
Write-Host 'Boundary: it does not submit, cancel, or replace a Sleeper transaction; it does not mutate a Sleeper roster, FAAB, or trade.'

& $acceptancePreflight

Write-Host 'BF-723: checking exact BF-610 roster state before any Butler evidence write...'
$preflight = Invoke-ButlerRuntimeCommand -MainClass $bf610Class -LeagueId $leagueId
$recoveryNeeded = $false
if ($preflight.ExitCode -eq 0) {
    Write-Host 'BF-723: BF-610 is already current; skipping evidence writes.'
}
elseif ($preflight.Text.IndexOf($driftPrefix, [System.StringComparison]::Ordinal) -ge 0 -and
        $preflight.Text.IndexOf($driftSuffix, [System.StringComparison]::Ordinal) -ge 0) {
    $recoveryNeeded = $true
    Write-Host 'BF-723: exact BF-610 roster drift verified; fixed governed evidence recovery is authorized.'
}
else {
    $tail = Get-BoundedTail -Text $preflight.Text
    throw "BF-723 BLOCKED: BF-610 failed for a reason other than exact roster drift. No Butler evidence write was attempted. output=$tail"
}

if ($recoveryNeeded) {
    $stages = @(
        [pscustomobject]@{ MainClass = 'io.butler.bet.cli.ButlerSleeperLiveWaiverSnapshotSyncCli'; Label = 'BF-602 waiver snapshot sync' },
        [pscustomobject]@{ MainClass = 'io.butler.bet.cli.ButlerSleeperLiveWaiverMarketAttentionSyncCli'; Label = 'BF-603 market-attention sync' },
        [pscustomobject]@{ MainClass = 'io.butler.bet.cli.ButlerSleeperLiveWaiverProductionHydrationCli'; Label = 'BF-605 production hydration' },
        [pscustomobject]@{ MainClass = 'io.butler.bet.cli.ButlerSleeperLiveWaiverAvailabilitySyncCli'; Label = 'BF-606 availability sync' },
        [pscustomobject]@{ MainClass = 'io.butler.bet.cli.ButlerSleeperLiveWaiverCurrentWeekStatSyncCli'; Label = 'BF-607 current-week stat sync' },
        [pscustomobject]@{ MainClass = 'io.butler.bet.cli.ButlerSleeperLiveWaiverTargetRosterProductionHydrationCli'; Label = 'BF-612 target-roster production hydration' }
    )

    foreach ($stage in $stages) {
        [void](Invoke-RequiredSuccess -MainClass $stage.MainClass -Label $stage.Label -LeagueId $leagueId)
    }
}

[void](Invoke-RequiredSuccess -MainClass $bf610Class -Label 'BF-610 post-recovery target-roster verification' -LeagueId $leagueId)
[void](Invoke-RequiredSuccess -MainClass $comparisonClass -Label 'BF-615/BF-617 post-recovery waiver comparison verification' -LeagueId $leagueId)

Write-Host 'BF-723 RECOVERY: VERIFIED'
Write-Host 'BF-723: starting unchanged BF-698 GET-only acceptance.'
& $acceptanceCmd
if ($LASTEXITCODE -ne 0) {
    throw "BF-723 FAILED: recovery verified, but BF-698 acceptance exited with code $LASTEXITCODE."
}

Write-Host 'BF-723 RESULT: COMPLETE'
