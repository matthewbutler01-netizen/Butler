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

# Exercise release acceptance against the final user-facing form, where raw
# technical disclosures are deliberately removed but saved-decision links stay.
. (Join-Path $PSScriptRoot 'butler-decision-detail.ps1')
$cacheAst = [System.Management.Automation.Language.Parser]::ParseFile(
    (Join-Path $PSScriptRoot 'butler-app-request-worker-cache.ps1'), [ref]$tokens, [ref]$errors)
if (@($errors).Count) { throw 'Public history renderer has parse errors.' }
$injected = @($cacheAst.FindAll({param($node)
    $node -is [System.Management.Automation.Language.StringConstantExpressionAst] -and
    $node.Value.StartsWith('function ConvertTo-ButlerDisplayToken', [StringComparison]::Ordinal)
}, $true))
if ($injected.Count -ne 1) { throw 'Expected one installed public presentation block.' }
$cacheAst = [System.Management.Automation.Language.Parser]::ParseInput($injected[0].Value, [ref]$tokens, [ref]$errors)
if (@($errors).Count) { throw 'Installed public presentation block has parse errors.' }
foreach ($name in @('ConvertTo-ButlerDisplayToken','ConvertTo-ButlerUserFacingHtml')) {
    $fn = @($cacheAst.FindAll({param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name}, $true))
    if ($fn.Count -ne 1) { throw "Missing public renderer: $name" }
    . ([scriptblock]::Create($fn[0].Extent.Text))
}
$guardrailAst = [System.Management.Automation.Language.Parser]::ParseFile(
    (Join-Path $PSScriptRoot 'butler-manager-guardrail-acceptance.ps1'), [ref]$tokens, [ref]$errors)
$check = $guardrailAst.Find({param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Assert-HistoryDecisionAccess'}, $true)
if ($null -eq $check -or @($errors).Count) { throw 'Missing history release assertion.' }
. ([scriptblock]::Create($check.Extent.Text))
$pages = @()
foreach ($count in @(0,1,3)) {
    $fixture = $history.PSObject.Copy()
    $fixture.Entries = @($entries | Select-Object -First $count)
    $fixture.RecordCount = $count
    $native = ConvertTo-DecisionHistoryHtml -History $fixture
    $linked = Add-DecisionDetailLinks -Html $native -History $fixture
    $page = ConvertTo-ButlerUserFacingHtml -Html $linked
    Assert-HistoryDecisionAccess -Html $page
    $pages += $page
}
if ($pages[1].Contains('<details')) { throw 'Single-record regression must exercise the public page without technical disclosures.' }
foreach ($bad in @(
    $pages[1].Replace('>View decision</a>', '>Unavailable</a>'),
    $pages[1].Replace('/history?audit=', 'https://example.invalid/?audit='),
    $pages[2].Replace('View older decisions (2)', 'View older decisions (1)'),
    $pages[2].Replace('<details class="history-older-list">', '<details class="history-older-list" open>'),
    ($pages[1] + '<div class="empty">No recorded waiver decisions are available for this league yet.</div>'),
    '<nav>View decision; Decision details</nav>')) {
    $rejected = $false
    try { Assert-HistoryDecisionAccess -Html $bad } catch { $rejected = $true }
    if (-not $rejected) { throw 'History release assertion accepted missing/wrong links, undisclosed older records or contradictory state.' }
}

Write-Host 'BF-967 DECISION HISTORY OUTCOME SUMMARY ACCEPTANCE: PASS'
