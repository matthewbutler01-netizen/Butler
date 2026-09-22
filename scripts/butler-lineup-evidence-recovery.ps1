param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$LeagueId,

    [switch]$ProbeOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $scriptDir
$runtimeLibDir = Join-Path $repoRoot 'bet\bet-cli\build\install\bet-cli\lib'

$localAppData = [string]$env:LOCALAPPDATA
if ([string]::IsNullOrWhiteSpace($localAppData)) {
    $localAppData = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)
}
if ([string]::IsNullOrWhiteSpace($localAppData)) {
    throw 'BF-823 BLOCKED: LocalApplicationData is unavailable.'
}
$configDir = Join-Path $localAppData 'Butler'

function Resolve-ButlerRuntimeDataDir {
    $configured = [string]$env:BUTLER_APP_DATA_DIR
    if ([string]::IsNullOrWhiteSpace($configured)) {
        $candidate = Join-Path $configDir 'data'
    }
    else {
        if (-not [IO.Path]::IsPathRooted($configured)) {
            throw 'BF-823 BLOCKED: BUTLER_APP_DATA_DIR must be an absolute path.'
        }
        $candidate = $configured
    }

    $resolved = [IO.Path]::GetFullPath($candidate)
    $sourceRoot = [IO.Path]::GetFullPath($repoRoot).TrimEnd('\')
    $sourcePrefix = $sourceRoot + '\'
    if ($resolved.Equals($sourceRoot, [System.StringComparison]::OrdinalIgnoreCase) -or
        $resolved.StartsWith($sourcePrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'BF-823 BLOCKED: Butler runtime data directory must be outside the source/package tree.'
    }
    if (-not (Test-Path -LiteralPath $resolved -PathType Container)) {
        throw "BF-823 BLOCKED: governed Butler runtime data directory not found at $resolved"
    }
    if (-not (Test-Path -LiteralPath (Join-Path $resolved 'butler.db') -PathType Leaf)) {
        throw "BF-823 BLOCKED: governed Butler runtime database not found at $resolved"
    }
    return $resolved
}

function Get-ButlerJavaExecutable {
    if (-not [string]::IsNullOrWhiteSpace([string]$env:JAVA_HOME)) {
        $candidate = Join-Path $env:JAVA_HOME 'bin\java.exe'
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
    }
    try {
        return [string](Get-Command java.exe -ErrorAction Stop).Source
    }
    catch {
        throw 'BF-823 BLOCKED: java.exe is unavailable for governed recovery.'
    }
}

function Get-BoundedTail {
    param([AllowNull()][string]$Text, [int]$Limit = 2200)

    if ([string]::IsNullOrWhiteSpace($Text)) { return 'no task output' }
    $normalized = [regex]::Replace($Text, '\s+', ' ').Trim()
    if ($normalized.Length -le $Limit) { return $normalized }
    return '...' + $normalized.Substring($normalized.Length - $Limit)
}

function Invoke-ButlerRuntimeCommand {
    param(
        [Parameter(Mandatory = $true)][string]$MainClass,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    $previousPreference = $ErrorActionPreference
    $lines = $null
    $exitCode = $null
    $javaArguments = @('--enable-native-access=ALL-UNNAMED', '-cp', $script:classPath, $MainClass) + @($Arguments)
    Push-Location $script:dataDir
    try {
        try {
            $ErrorActionPreference = 'Continue'
            $lines = & $script:java $javaArguments 2>&1
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
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    Write-Host ("BF-823: running {0}..." -f $Label)
    $result = Invoke-ButlerRuntimeCommand -MainClass $MainClass -Arguments $Arguments
    if ($result.ExitCode -ne 0) {
        $tail = Get-BoundedTail -Text $result.Text
        throw "BF-823 BLOCKED: $Label failed with runtime exit code $($result.ExitCode); output=$tail"
    }
    Write-Host ("BF-823: {0} complete." -f $Label)
    return $result
}

function Get-HydrationAudit {
    $result = Invoke-ButlerRuntimeCommand -MainClass 'io.butler.bet.cli.ButlerSleeperCurrentSeasonHydrationEligibilityAuditCli' -Arguments @($LeagueId)
    if ($result.ExitCode -ne 0) {
        $tail = Get-BoundedTail -Text $result.Text
        throw "BF-823 BLOCKED: BF-599 hydration eligibility audit failed with runtime exit code $($result.ExitCode); output=$tail"
    }

    $linked = [regex]::Match($result.Text, '(?m)^Linked Sleeper league:\s+(?<value>\S+)\s*$')
    $unmapped = [regex]::Match($result.Text, '(?m)^Current player identities to bootstrap:\s+(?<value>\d+)\s*$')
    $state = [regex]::Match($result.Text, '(?m)^Hydration eligibility:\s+(?<value>\S+)\s*$')
    if (-not $linked.Success -or -not $unmapped.Success -or -not $state.Success) {
        throw 'BF-823 BLOCKED: BF-599 hydration audit output is missing required recovery fields.'
    }

    return [pscustomobject]@{
        SleeperLeagueId = $linked.Groups['value'].Value.Trim()
        UnmappedCount = [int]$unmapped.Groups['value'].Value
        State = $state.Groups['value'].Value.Trim()
        Text = $result.Text
    }
}

$parsedLeagueId = [Guid]::Empty
if (-not [Guid]::TryParse($LeagueId, [ref]$parsedLeagueId)) {
    throw 'BF-823 BLOCKED: Butler league id is invalid.'
}
$LeagueId = $parsedLeagueId.ToString('D').ToLowerInvariant()

$dataDir = Resolve-ButlerRuntimeDataDir
$java = Get-ButlerJavaExecutable
$classPath = Join-Path $runtimeLibDir '*'
if (-not (Test-Path -LiteralPath $runtimeLibDir -PathType Container)) {
    throw "BF-823 BLOCKED: prepared Butler runtime not found at $runtimeLibDir"
}
$jars = @(Get-ChildItem -LiteralPath $runtimeLibDir -Filter '*.jar' -File -ErrorAction Stop)
if ($jars.Count -eq 0) {
    throw "BF-823 BLOCKED: prepared Butler runtime contains no jars at $runtimeLibDir"
}

$bf610Class = 'io.butler.bet.cli.ButlerSleeperLiveWaiverTargetRosterContextAuditCli'
$comparisonClass = 'io.butler.bet.cli.ButlerSleeperLiveWaiverComparisonBundleCli'
$driftPrefix = 'BF-610 BLOCKED: current roster membership drifted from BF-603/BF-602 frame; added='
$driftSuffix = '; refresh BF-602/BF-603 and downstream live evidence before target-roster review'
$marketCanonicalGapPattern = '(?m)^Error: BF-608 BLOCKED: BF-604 has \d+ unmapped canonical candidate\(s\)\s*$'

$audit = Get-HydrationAudit
if ($audit.State -cne 'READY_TO_HYDRATE') {
    $tail = Get-BoundedTail -Text $audit.Text
    throw "BF-823 BLOCKED: BF-599 did not authorize current-season hydration or roster recovery. output=$tail"
}

if ($audit.UnmappedCount -gt 0) {
    if ($ProbeOnly) {
        Write-Output 'BF-823 PROBE: RECOVERY_REQUIRED'
        Write-Output 'BF-823 PROBE REASON: PLAYER_MAPPING'
        return
    }
}
else {
    $preflight = Invoke-ButlerRuntimeCommand -MainClass $bf610Class -Arguments @($LeagueId)
    if ($preflight.ExitCode -eq 0) {
        if ($ProbeOnly) {
            Write-Output 'BF-823 PROBE: NO_RECOVERY_REQUIRED'
            return
        }
    }
    elseif ($preflight.Text.IndexOf($driftPrefix, [System.StringComparison]::Ordinal) -ge 0 -and
            $preflight.Text.IndexOf($driftSuffix, [System.StringComparison]::Ordinal) -ge 0) {
        if ($ProbeOnly) {
            Write-Output 'BF-823 PROBE: RECOVERY_REQUIRED'
            Write-Output 'BF-823 PROBE REASON: ROSTER_DRIFT'
            return
        }
    }
    elseif ([regex]::IsMatch($preflight.Text, $marketCanonicalGapPattern)) {
        if ($ProbeOnly) {
            Write-Output 'BF-823 PROBE: DEFER_TO_BF676'
            Write-Output 'BF-823 PROBE REASON: MARKET_CANONICAL_GAP'
            return
        }
        throw 'BF-823 BLOCKED: market-active canonical coverage must be repaired by the governed BF-676 refresh chain.'
    }
    else {
        $tail = Get-BoundedTail -Text $preflight.Text
        throw "BF-823 BLOCKED: BF-610 failed for a reason other than exact roster drift. No Butler evidence write was attempted. output=$tail"
    }
}

if ($ProbeOnly) {
    throw 'BF-823 BLOCKED: recovery probe reached an ambiguous state.'
}

Write-Output 'Butler governed lineup evidence recovery (BF-823)'
Write-Output "League: $LeagueId"
Write-Output "Data: $dataDir"
Write-Output 'Boundary: this action may refresh Butler local roster/player and lineup-supporting evidence only.'
Write-Output 'Boundary: it does not submit, cancel, or replace a Sleeper transaction; it does not mutate a Sleeper lineup, roster, FAAB, waiver, or trade.'

if ($audit.UnmappedCount -gt 0) {
    Write-Output ("BF-823: BF-599 found {0} current player identity mapping(s) to bootstrap." -f $audit.UnmappedCount)
    $bootstrap = Invoke-RequiredSuccess -MainClass 'io.butler.bet.cli.ButlerSleeperCurrentSeasonRosterBootstrapCli' -Label 'BF-600 current-season roster/player bootstrap' -Arguments @($LeagueId, $audit.SleeperLeagueId)
    if ($bootstrap.Text.IndexOf('Bootstrap state: HYDRATED_VERIFIED', [System.StringComparison]::Ordinal) -lt 0) {
        $tail = Get-BoundedTail -Text $bootstrap.Text
        throw "BF-823 BLOCKED: BF-600 completed without HYDRATED_VERIFIED. output=$tail"
    }

    $postBootstrapAudit = Get-HydrationAudit
    if ($postBootstrapAudit.State -cne 'READY_TO_HYDRATE' -or $postBootstrapAudit.UnmappedCount -ne 0) {
        $tail = Get-BoundedTail -Text $postBootstrapAudit.Text
        throw "BF-823 BLOCKED: post-bootstrap BF-599 verification is not fully mapped and ready. output=$tail"
    }
    Write-Output 'BF-823: current roster player identities are fully mapped.'
}

$rosterCheck = Invoke-ButlerRuntimeCommand -MainClass $bf610Class -Arguments @($LeagueId)
$rebuildEvidence = $false
if ($rosterCheck.ExitCode -eq 0) {
    Write-Output 'BF-823: BF-610 roster evidence is already current; downstream evidence writes are not required.'
}
elseif ($rosterCheck.Text.IndexOf($driftPrefix, [System.StringComparison]::Ordinal) -ge 0 -and
        $rosterCheck.Text.IndexOf($driftSuffix, [System.StringComparison]::Ordinal) -ge 0) {
    $rebuildEvidence = $true
    Write-Output 'BF-823: exact BF-610 roster drift verified; governed downstream evidence recovery is authorized.'
}
else {
    $tail = Get-BoundedTail -Text $rosterCheck.Text
    throw "BF-823 BLOCKED: BF-610 failed for a reason other than exact roster drift after mapping verification. output=$tail"
}

if ($rebuildEvidence) {
    $stages = @(
        [pscustomobject]@{ MainClass = 'io.butler.bet.cli.ButlerSleeperLiveWaiverSnapshotSyncCli'; Label = 'BF-602 waiver snapshot sync' },
        [pscustomobject]@{ MainClass = 'io.butler.bet.cli.ButlerSleeperLiveWaiverMarketAttentionSyncCli'; Label = 'BF-603 market-attention sync' },
        [pscustomobject]@{ MainClass = 'io.butler.bet.cli.ButlerSleeperLiveWaiverProductionHydrationCli'; Label = 'BF-605 production hydration' },
        [pscustomobject]@{ MainClass = 'io.butler.bet.cli.ButlerSleeperLiveWaiverAvailabilitySyncCli'; Label = 'BF-606 availability sync' },
        [pscustomobject]@{ MainClass = 'io.butler.bet.cli.ButlerSleeperLiveWaiverCurrentWeekStatSyncCli'; Label = 'BF-607 current-week stat sync' },
        [pscustomobject]@{ MainClass = 'io.butler.bet.cli.ButlerSleeperLiveWaiverTargetRosterProductionHydrationCli'; Label = 'BF-612 target-roster production hydration' }
    )
    foreach ($stage in $stages) {
        [void](Invoke-RequiredSuccess -MainClass $stage.MainClass -Label $stage.Label -Arguments @($LeagueId))
    }
}

[void](Invoke-RequiredSuccess -MainClass $bf610Class -Label 'BF-610 post-recovery target-roster verification' -Arguments @($LeagueId))
[void](Invoke-RequiredSuccess -MainClass $comparisonClass -Label 'BF-615/BF-617 post-recovery waiver comparison verification' -Arguments @($LeagueId))

Write-Output 'BF-823 RECOVERY: VERIFIED'
Write-Output 'BF-823 RESULT: COMPLETE'
