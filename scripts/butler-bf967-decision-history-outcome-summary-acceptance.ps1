Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$historyPath = Join-Path $PSScriptRoot 'butler-decision-history.ps1'
$tokens = $null
$errors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($historyPath, [ref]$tokens, [ref]$errors)
if (@($errors).Count -ne 0) {
    $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-967 BLOCKED: Decision History script failed PowerShell parse: $summary"
}

. $historyPath

function ConvertTo-HtmlText {
    param([AllowNull()]$Value)
    return [System.Net.WebUtility]::HtmlEncode([string]$Value)
}

function Get-AppCss {
    return 'body{font-family:sans-serif}'
}

$entries = @(
    [pscustomobject]@{
        AuditId = 'audit-old'
        Captured = '2026-09-01T12:00:00Z'
        ProviderSeason = '2026'
        ProviderStatus = 'REGULAR'
        ProviderLeg = '1'
        MarketSnapshotId = 'market-old'
        WaiverSnapshotId = 'waiver-old'
        SelectionState = 'SELECTED'
        RecommendationState = 'RECOMMEND_ADD_DROP'
        AddSleeperId = '1001'
        DropSleeperId = '2001'
        IntegrityState = 'VERIFIED'
    },
    [pscustomobject]@{
        AuditId = 'audit-middle'
        Captured = '2026-09-08T12:00:00Z'
        ProviderSeason = '2026'
        ProviderStatus = 'REGULAR'
        ProviderLeg = '2'
        MarketSnapshotId = 'market-middle'
        WaiverSnapshotId = 'waiver-middle'
        SelectionState = 'NO_SELECTION'
        RecommendationState = 'NO_GOVERNED_TRANSACTION'
        AddSleeperId = '-'
        DropSleeperId = '-'
        IntegrityState = 'VERIFIED'
    },
    [pscustomobject]@{
        AuditId = 'audit-new'
        Captured = '2026-09-15T12:00:00Z'
        ProviderSeason = '2026'
        ProviderStatus = 'REGULAR'
        ProviderLeg = '3'
        MarketSnapshotId = 'market-new'
        WaiverSnapshotId = 'waiver-new'
        SelectionState = 'RECORDED'
        RecommendationState = 'OTHER_RECORDED_STATE'
        AddSleeperId = '-'
        DropSleeperId = '-'
        IntegrityState = 'VERIFIED'
    }
)

$history = [pscustomobject]@{
    Policy = 'TEST'
    LeagueId = 'league-test'
    SleeperOwnerId = 'owner-test'
    SleeperLeagueId = 'sleeper-test'
    RosterId = 1
    State = 'READY'
    RecordCount = 3
    Entries = $entries
}

$html = ConvertTo-DecisionHistoryHtml -History $history

foreach ($required in @(
    '<strong>Moves recorded</strong><span>1</span>',
    '<strong>No-move records</strong><span>1</span>',
    '<strong>Other states</strong><span>1</span>',
    '<strong>Recorded decisions</strong><span>3</span>',
    'Latest recorded waiver decision',
    'View older decisions (2)',
    'Review Waiver Board',
    'Back to Dashboard',
    'READ ONLY.'
)) {
    if ($html.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-967 BLOCKED: rendered Decision History summary marker is missing: $required"
    }
}

$scriptText = [IO.File]::ReadAllText($historyPath)
foreach ($required in @(
    '$moveCount = 0',
    '$noMoveCount = 0',
    '$otherDecisionCount = 0',
    '''RECOMMEND_ADD_DROP'' { $moveCount++ }',
    '''NO_GOVERNED_TRANSACTION'' { $noMoveCount++ }',
    'default { $otherDecisionCount++ }'
)) {
    if ($scriptText.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-967 BLOCKED: Decision History outcome-count marker is missing: $required"
    }
}

if ($scriptText -match 'submitTransaction|setFaab|Method = "POST"|https://api\.sleeper\.app') {
    throw 'BF-967 BLOCKED: Decision History script contains a forbidden transaction/provider write marker.'
}

Write-Host 'BF-967 DECISION HISTORY OUTCOME SUMMARY ACCEPTANCE: PASS'
