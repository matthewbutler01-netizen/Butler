Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$tradeHost = Join-Path $PSScriptRoot 'butler-trade-lab-host.ps1'
$tradeLab = Join-Path $PSScriptRoot 'butler-trade-lab.ps1'
$historyPath = Join-Path $PSScriptRoot 'butler-decision-history.ps1'
$detailPath = Join-Path $PSScriptRoot 'butler-decision-detail.ps1'

foreach ($path in @($tradeHost, $tradeLab, $historyPath, $detailPath)) {
    $tokens = $null
    $errors = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile($path, [ref]$tokens, [ref]$errors)
    if (@($errors).Count -ne 0) {
        $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
        throw "BF-974 BLOCKED: module failed PowerShell parse: $path :: $summary"
    }
}

# Match production companion load order so shared CSS/nav helpers are realistic.
. $tradeHost
. $tradeLab
. $historyPath
. $detailPath

$historyLoading = Get-DecisionHistoryLoadingHtml -LeagueId 'league-test'
foreach ($required in @(
    'content="1;url=/history?load=1"',
    'Opening Decision History...',
    'href="/history?load=1">Open Decision History now</a>',
    'href="/">Dashboard</a>',
    'READ ONLY'
)) {
    if ($historyLoading.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-974 BLOCKED: Decision History loading fallback marker is missing: $required"
    }
}

$tradeLoading = Get-TradeLabLoadingHtml -LeagueId 'league-test'
foreach ($required in @(
    'content="1;url=/trade?load=1"',
    'Opening Trade Analyzer...',
    'href="/trade?load=1">Open Trade Analyzer now</a>',
    'href="/league">League</a>',
    'No proposal, transaction, or Sleeper write is being executed.'
)) {
    if ($tradeLoading.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-974 BLOCKED: Trade loading fallback marker is missing: $required"
    }
}

$detailLoading = Get-DecisionDetailLoadingHtml -LeagueId 'league-test' -AuditId 'audit-test'
foreach ($required in @(
    'content="1;url=/history?audit=audit-test&amp;detail=1"',
    'Opening Decision Detail...',
    'href="/history?audit=audit-test&amp;detail=1">Open Decision Detail now</a>',
    'href="/history?load=1">Back to Decision History</a>',
    'READ ONLY'
)) {
    if ($detailLoading.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-974 BLOCKED: Decision Detail loading fallback marker is missing: $required"
    }
}

$history = [pscustomobject]@{
    LeagueId = 'league-test'
    RosterId = 1
}
$entry = [pscustomobject]@{
    Captured = '2026-09-15T12:00:00Z'
    SelectionState = 'SELECTED'
    RecommendationState = 'RECOMMEND_ADD_DROP'
    AddSleeperId = '1001'
    DropSleeperId = '2001'
    IntegrityState = 'VERIFIED'
    ProviderSeason = '2026'
    ProviderStatus = 'REGULAR'
    ProviderLeg = '3'
    AuditId = 'audit-test'
    MarketSnapshotId = 'market-test'
    WaiverSnapshotId = 'waiver-test'
}
$explanation = [pscustomobject]@{
    State = 'EXPLANATION_READY'
    Captured = '2026-09-15T12:00:00Z'
    ExplanationType = 'RECORDED'
    ExplanationText = 'Saved explanation text.'
    ExplanationId = 'explanation-test'
    Policy = 'policy-test'
    EvidencePolicy = 'evidence-policy-test'
    EvidenceTrace = 'evidence-trace-test'
}

$detail = ConvertTo-DecisionDetailHtml -History $history -Entry $entry -Explanation $explanation
foreach ($required in @(
    'href="/history?load=1">Decision History</a>',
    'href="/waivers">Review Waiver Board</a>',
    'href="/">Dashboard</a>',
    '&larr; Back to Decision History',
    'READ ONLY.',
    'Saved explanation'
)) {
    if ($detail.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-974 BLOCKED: Decision Detail exit marker is missing: $required"
    }
}

foreach ($rendered in @($historyLoading, $tradeLoading, $detailLoading, $detail)) {
    if ($rendered -match 'submitTransaction|setFaab|Method = "POST"|AutoFillLineupOptimizer') {
        throw 'BF-974 BLOCKED: companion loading/detail presentation introduced transaction, FAAB, POST, or optimizer behavior.'
    }
}

Write-Host 'BF-974 COMPANION LOADING AND DETAIL CLOSEOUT ACCEPTANCE: PASS'
