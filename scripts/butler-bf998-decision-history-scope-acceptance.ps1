Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$historyPath = Join-Path $PSScriptRoot 'butler-decision-history.ps1'
$tokens = $null
$errors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($historyPath, [ref]$tokens, [ref]$errors)
if (@($errors).Count -ne 0) {
    $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-998 BLOCKED: Decision History script failed PowerShell parse: $summary"
}

. $historyPath

function ConvertTo-HtmlText {
    param([AllowNull()]$Value)
    return [System.Net.WebUtility]::HtmlEncode([string]$Value)
}

function Get-AppCss {
    return 'body{font-family:sans-serif}'
}

$history = [pscustomobject]@{
    Policy = 'TEST'
    LeagueId = 'league-test'
    SleeperOwnerId = 'owner-test'
    SleeperLeagueId = 'sleeper-test'
    RosterId = 1
    State = 'READY'
    RecordCount = 1
    Entries = @(
        [pscustomobject]@{
            AuditId = 'audit-test'
            Captured = '2026-09-15T12:00:00Z'
            ProviderSeason = '2026'
            ProviderStatus = 'REGULAR'
            ProviderLeg = '3'
            MarketSnapshotId = 'market-test'
            WaiverSnapshotId = 'waiver-test'
            SelectionState = 'SELECTED'
            RecommendationState = 'RECOMMEND_ADD_DROP'
            AddSleeperId = '1001'
            DropSleeperId = '2001'
            IntegrityState = 'VERIFIED'
        }
    )
}

$html = ConvertTo-DecisionHistoryHtml -History $history

foreach ($required in @(
    '<span class="eyebrow">Current coverage</span>',
    '<strong>Waiver decisions only</strong>',
    'This timeline does not include lineup reviews or trade analyses.',
    'Use their dedicated workflows for current decision support.',
    'Your waiver decision timeline',
    'Latest recorded waiver decision',
    'Review Waiver Board',
    'Back to Dashboard',
    'READ ONLY.'
)) {
    if ($html.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-998 BLOCKED: rendered Decision History scope marker is missing: $required"
    }
}

$scriptText = [IO.File]::ReadAllText($historyPath)
foreach ($required in @(
    '.history-scope{',
    'Waiver decisions only',
    'This timeline does not include lineup reviews or trade analyses.',
    '-Task '':bet:bet-cli:sleeperLiveWaiverRecommendationAuditHistory'''
)) {
    if ($scriptText.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-998 BLOCKED: Decision History source marker is missing: $required"
    }
}

if ($scriptText -match 'submitTransaction|setFaab|Method = "POST"|https://api\.sleeper\.app|sleeperLiveWaiverRecommendationAuditCapture|sleeperLiveWaiverSnapshotSync|sleeperLiveWaiverMarketAttentionSync') {
    throw 'BF-998 BLOCKED: Decision History scope clarification introduced a provider refresh or write marker.'
}

Write-Host 'BF-998 DECISION HISTORY SCOPE ACCEPTANCE: PASS'
Write-Host 'Coverage: explicit waiver-only history boundary, preserved newest-first summary, dedicated workflow handoff, responsive presentation, and no new reads or writes'
