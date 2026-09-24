param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

foreach ($required in @($CorePath, $DashboardPath)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "BF-852 BLOCKED: staged Butler file not found at $required"
    }
}

$core = [System.IO.File]::ReadAllText($CorePath)
$dashboard = [System.IO.File]::ReadAllText($DashboardPath)

$coreInternalGuardOriginal = @'
    if ($Path -cne "/__butler/internal/team-bundle" -and
        $Path -cne "/__butler/internal/league-overview" -and
        -not $Path.StartsWith("/__butler/internal/player-detail?", [System.StringComparison]::Ordinal) -and
        -not $Path.StartsWith("/__butler/internal/player-detail-summary?", [System.StringComparison]::Ordinal) -and
        -not $Path.StartsWith("/__butler/internal/player-search?", [System.StringComparison]::Ordinal) -and
        -not $Path.StartsWith("/__butler/internal/player-compare?", [System.StringComparison]::Ordinal) -and
        -not $Path.StartsWith("/__butler/internal/player-compare-summary?", [System.StringComparison]::Ordinal)) {
        throw "$BoundaryName BLOCKED: BF-742 internal dashboard path is not authorized."
    }
'@
$coreInternalGuardReplacement = @'
    if ($Path -cne "/__butler/internal/team-bundle" -and
        $Path -cne "/__butler/internal/league-overview" -and
        $Path -cne "/__butler/internal/matchup-bundle" -and
        -not $Path.StartsWith("/__butler/internal/player-detail?", [System.StringComparison]::Ordinal) -and
        -not $Path.StartsWith("/__butler/internal/player-detail-summary?", [System.StringComparison]::Ordinal) -and
        -not $Path.StartsWith("/__butler/internal/player-search?", [System.StringComparison]::Ordinal) -and
        -not $Path.StartsWith("/__butler/internal/player-compare?", [System.StringComparison]::Ordinal) -and
        -not $Path.StartsWith("/__butler/internal/player-compare-summary?", [System.StringComparison]::Ordinal)) {
        throw "$BoundaryName BLOCKED: BF-742 internal dashboard path is not authorized."
    }
'@

$coreBundleOriginal = @'
                    $bundleText = Invoke-ButlerReadOnlyTask -Task ":bet:bet-cli:sleeperLiveWaiverTargetRosterContextAudit" -Arguments $bundleArguments -BoundaryName "BF-849"
'@
$coreBundleReplacement = @'
                    $bundleText = if ($requestAutoFill) {
                        Invoke-ButlerReadOnlyTask -Task ":bet:bet-cli:sleeperLiveWaiverTargetRosterContextAudit" -Arguments $bundleArguments -BoundaryName "BF-849"
                    }
                    else {
                        Invoke-Bf742DashboardWorkerRead -Path "/__butler/internal/matchup-bundle" -BoundaryName "BF-852"
                    }
'@

$dashboardInternalOriginal = @'
            elseif ($path -ceq "/__butler/internal/league-overview") {
                $bf742InternalOperation = 'LEAGUE_OVERVIEW'
                $bf742InternalBoundary = 'BF-667'
            }
'@
$dashboardInternalReplacement = @'
            elseif ($path -ceq "/__butler/internal/league-overview") {
                $bf742InternalOperation = 'LEAGUE_OVERVIEW'
                $bf742InternalBoundary = 'BF-667'
            }
            elseif ($path -ceq "/__butler/internal/matchup-bundle") {
                $bf742InternalOperation = 'MATCHUP_BUNDLE'
                $bf742InternalBoundary = 'BF-852'
            }
'@

$coreContracts = @(
    [pscustomobject]@{ Name = 'internal path authorization'; Original = $coreInternalGuardOriginal; Replacement = $coreInternalGuardReplacement },
    [pscustomobject]@{ Name = 'passive matchup worker routing'; Original = $coreBundleOriginal; Replacement = $coreBundleReplacement }
)
foreach ($contract in $coreContracts) {
    $count = [regex]::Matches($core, [regex]::Escape([string]$contract.Original)).Count
    if ($count -ne 1) {
        throw "BF-852 BLOCKED: expected exactly one $($contract.Name) contract, found $count."
    }
    $core = $core.Replace([string]$contract.Original, [string]$contract.Replacement)
}

$dashboardCount = [regex]::Matches($dashboard, [regex]::Escape($dashboardInternalOriginal)).Count
if ($dashboardCount -ne 1) {
    throw "BF-852 BLOCKED: expected exactly one internal dashboard operation contract, found $dashboardCount."
}
$dashboard = $dashboard.Replace($dashboardInternalOriginal, $dashboardInternalReplacement)

foreach ($required in @(
    '/__butler/internal/matchup-bundle',
    'Invoke-Bf742DashboardWorkerRead -Path "/__butler/internal/matchup-bundle" -BoundaryName "BF-852"',
    '$bundleText = if ($requestAutoFill)',
    '--weekly-matchup-bundle-autofill',
    'Invoke-ButlerReadOnlyTask -Task ":bet:bet-cli:sleeperLiveWaiverTargetRosterContextAudit"'
)) {
    if (-not $core.Contains($required)) {
        throw "BF-852 BLOCKED: staged core is missing required passive Matchup worker contract: $required"
    }
}
foreach ($required in @(
    '/__butler/internal/matchup-bundle',
    "'MATCHUP_BUNDLE'",
    "'BF-852'",
    'X-Butler-Internal-Token'
)) {
    if (-not $dashboard.Contains($required)) {
        throw "BF-852 BLOCKED: staged dashboard is missing required Matchup worker contract: $required"
    }
}


[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))
[System.IO.File]::WriteAllText($DashboardPath, $dashboard, [System.Text.UTF8Encoding]::new($false))

$coreTokens = $null
$coreParseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($CorePath, [ref]$coreTokens, [ref]$coreParseErrors)
if (@($coreParseErrors).Count -gt 0) {
    $summary = (@($coreParseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-852 BLOCKED: generated staged core failed PowerShell parse: $summary"
}

$dashboardTokens = $null
$dashboardParseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($DashboardPath, [ref]$dashboardTokens, [ref]$dashboardParseErrors)
if (@($dashboardParseErrors).Count -gt 0) {
    $summary = (@($dashboardParseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-852 BLOCKED: generated staged dashboard failed PowerShell parse: $summary"
}
