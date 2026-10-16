# BF-1522: historical waiver cards must disclose their own source week.
# Synthetic presentation only; no provider, database, or Sleeper transaction.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$historyPath = Join-Path $PSScriptRoot 'butler-decision-history.ps1'
$tokens = $null
$errors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($historyPath, [ref]$tokens, [ref]$errors)
if (@($errors).Count -ne 0) { throw 'BF-1522 BLOCKED: history source failed parsing.' }

. $historyPath
function Get-AppCss { return 'body{}' }
function Get-AppNav { param([string]$Active); return '<nav></nav>' }
function ConvertTo-HtmlText {
    param([AllowNull()][object]$Value)
    return [System.Net.WebUtility]::HtmlEncode([string]$Value)
}

$older = [pscustomobject]@{
    Captured='2026-09-15T02:00:00Z'; ProviderSeason='2026'; ProviderStatus='REGULAR'
    ProviderLeg='OFFSEASON'; RecommendationState='NO_GOVERNED_TRANSACTION'
    SelectionState='NO_HISTORICAL_FINALIST'; IntegrityState='VERIFIED'
    AddSleeperId='none'; DropSleeperId='none'; AuditId='synthetic-older'
    MarketSnapshotId='market-older'; WaiverSnapshotId='waiver-older'
}
$latest = [pscustomobject]@{
    Captured='2026-10-13T01:32:00Z'; ProviderSeason='2026'; ProviderStatus='REGULAR'
    ProviderLeg='5'; RecommendationState='RECOMMEND_ADD_DROP'
    SelectionState='SELECTED'; IntegrityState='VERIFIED'
    AddSleeperId='11647'; DropSleeperId='11370'; AuditId='synthetic-latest'
    MarketSnapshotId='market-latest'; WaiverSnapshotId='waiver-latest'
}
$history = [pscustomobject]@{
    LeagueId='synthetic-only'; RecordCount=2; Entries=@($older,$latest)
}
if ((Get-DecisionHistoryFrameLabel -Entry $latest) -cne 'Season 2026 / Week 5') {
    throw 'BF-1522 BLOCKED: recorded numeric provider week missing.'
}
if ((Get-DecisionHistoryFrameLabel -Entry $older) -cne 'Recorded source frame: 2026 / REGULAR / OFFSEASON') {
    throw 'BF-1522 BLOCKED: non-week provider leg misclassified.'
}
$page = ConvertTo-DecisionHistoryHtml -History $history
$latestMarker='Season 2026 / Week 5 - HISTORY ONLY.'
$olderMarker='Recorded source frame: 2026 / REGULAR / OFFSEASON - HISTORY ONLY.'
$latestStart=$page.IndexOf('<article class="history-card">', [StringComparison]::Ordinal)
$weekIndex=$page.IndexOf($latestMarker, [StringComparison]::Ordinal)
$latestDetails=$page.IndexOf('<details><summary>Decision details</summary>', [StringComparison]::Ordinal)
$olderStart=$page.IndexOf('<article class="history-card history-card-compact">', [StringComparison]::Ordinal)
$olderIndex=$page.IndexOf($olderMarker, [StringComparison]::Ordinal)
$olderDetails=$page.IndexOf('<details class="history-older-details">', [StringComparison]::Ordinal)
if ($latestStart -lt 0 -or $weekIndex -le $latestStart -or $latestDetails -le $weekIndex -or
    $olderStart -lt 0 -or $olderIndex -le $olderStart -or $olderDetails -le $olderIndex) {
    throw 'BF-1522 BLOCKED: recorded frame not visible before collapsed technical evidence on both cards.'
}
if ($page.Contains('Week OFFSEASON') -or
    -not $page.Contains('This record does not establish current waiver availability.') -or
    -not $page.Contains('READ ONLY.') -or
    -not $page.Contains('Audit: synthetic-latest')) {
    throw 'BF-1522 BLOCKED: historical status or technical evidence regression.'
}
$visibleNewest=$page.Substring($latestStart, $latestDetails-$latestStart)
if ($visibleNewest.Contains('11647') -or $visibleNewest.Contains('11370')) {
    throw 'BF-1522 BLOCKED: raw player ids leaked from advanced details.'
}
Write-Host 'BF-1522 WAIVER HISTORY WEEK LABEL ACCEPTANCE: PASS'
