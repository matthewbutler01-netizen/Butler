Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$workerPath = Join-Path $PSScriptRoot 'butler-app-request-worker.ps1'
. (Join-Path $PSScriptRoot 'butler-decision-refresh.ps1')
if (-not (Test-Path -LiteralPath $workerPath -PathType Leaf)) {
    throw "BF-1024 BLOCKED: request worker missing at $workerPath"
}

$text = [IO.File]::ReadAllText($workerPath)
$tokens = $null
$errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($workerPath, [ref]$tokens, [ref]$errors)
if (@($errors).Count -ne 0) {
    $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-1024 BLOCKED: request worker parse failed: $summary"
}

foreach ($functionName in @('Get-V04AutoPilotWatchState','Get-V04AutoPilotApprovalPolicy','Get-V04AutoPilotApprovalQueue','Get-V04AutoPilotHtml')) {
    $matches = @($ast.FindAll({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $functionName
    }, $true))
    if ($matches.Count -ne 1) { throw "BF-1024 BLOCKED: expected one $functionName function, found $($matches.Count)." }
    . ([scriptblock]::Create($matches[0].Extent.Text))
}

# Get-AppCss is supplied to the request worker at runtime by the staged app
# core. The focused acceptance only needs a deterministic CSS value so the
# Auto-Pilot renderer can be exercised without dot-sourcing the entire app.
function Get-AppCss { return '' }

$fixture = '<div class="butler-refresh-contract" hidden><div>Decision state: CURRENT_AND_ACTIONABLE</div><div>BF-629: LIVE_ACTIONABLE_VERIFIED</div><div>BF-631: LATEST_EVIDENCE_LINEAGE_VERIFIED</div></div><section class="panel"><div class="dashboard-summary-row"><div class="dashboard-summary-card"><span>Attention</span><strong>2 NEED ATTENTION</strong></div><div class="dashboard-summary-card"><span>Start/Sit</span><strong>REFRESH</strong></div><div class="dashboard-summary-card"><span>Waivers</span><strong>DO NOT ACT</strong></div><div class="dashboard-summary-card dashboard-summary-team"><span>Roster</span><strong>Hard(CORE)-Dynasty | nuke the whales | roster 6</strong></div></div></section>'

$state = Get-V04AutoPilotWatchState -DashboardHtml $fixture
if (-not $state.Ready) { throw 'BF-1024 BLOCKED: complete Dashboard watch fixture did not become ready.' }
if ($state.Attention -cne '2 NEED ATTENTION') { throw 'BF-1024 BLOCKED: Attention snapshot mismatch.' }
if ($state.StartSit -cne 'REFRESH') { throw 'BF-1024 BLOCKED: Start/Sit snapshot mismatch.' }
if ($state.Waivers -cne 'DO NOT ACT') { throw 'BF-1024 BLOCKED: Waiver snapshot mismatch.' }
if ($state.Roster -cne 'Hard(CORE)-Dynasty | nuke the whales | roster 6') { throw 'BF-1024 BLOCKED: roster snapshot mismatch.' }

# BF-1042: a complete manager card is NOT a current, approved watch if
# the governing decision is stale, refresh-required, missing or ambiguous.
foreach ($stale in @(
    ($fixture.Replace('CURRENT_AND_ACTIONABLE', 'STALE_DO_NOT_ACT')),
    ($fixture.Replace('CURRENT_AND_ACTIONABLE', 'CURRENT_REFRESH_RECOMMENDED')),
    ($fixture.Replace('CURRENT_AND_ACTIONABLE', 'NO_AUDITED_DECISION')),
    ($fixture.Replace('Decision state: CURRENT_AND_ACTIONABLE', 'Decision state: UNKNOWN')),
    ($fixture.Replace('<div>Decision state: CURRENT_AND_ACTIONABLE</div>', '')),
    ($fixture.Replace('</div></div><section', '</div><div>Decision state: CURRENT_AND_ACTIONABLE</div></div><section'))
)) {
    $blockedWatch = Get-V04AutoPilotWatchState -DashboardHtml $stale
    $expectedStartSit = if ($blockedWatch.EvidenceStatus -ceq 'STALE') { 'REFRESH' } else { 'UNAVAILABLE' }
    if ($blockedWatch.Ready -or $blockedWatch.StartSit -cne $expectedStartSit) {
        throw 'BF-1042 BLOCKED: stale/ambiguous Dashboard data authorized Auto-Pilot.'
    }
    $blockedPolicy = Get-V04AutoPilotApprovalPolicy
    $blockedQueue = Get-V04AutoPilotApprovalQueue -WatchState $blockedWatch -ApprovalPolicy $blockedPolicy
    $blockedHtml = Get-V04AutoPilotHtml -WatchState $blockedWatch -ApprovalPolicy $blockedPolicy -ApprovalQueue $blockedQueue
    if ($blockedHtml -match 'READY FOR MANAGER REVIEW' -or
        $blockedQueue.StartSitNext -notmatch '^Blocked until') {
        throw 'BF-1042 BLOCKED: stale watch produced an actionable approval.'
    }
    if ($blockedWatch.EvidenceStatus -ceq 'STALE' -and $blockedHtml -notmatch 'EVIDENCE NEEDS REFRESH') {
        throw 'BF-1042 BLOCKED: stale watch failed to display its refresh warning.'
    }
}
# BF-1044: don't trust CURRENT state without unique matching actionability
# and lineage. Never pass a stale card's specific start/sit or waiver advice
# through to the rendered Auto-Pilot decision queue.
foreach ($badProof in @(
    ($fixture.Replace('BF-629: LIVE_ACTIONABLE_VERIFIED', 'BF-629: BLOCKED')),
    ($fixture.Replace('BF-631: LATEST_EVIDENCE_LINEAGE_VERIFIED', 'BF-631: MARKET_LINEAGE_SUPERSEDED')),
    ($fixture.Replace('BF-631: LATEST_EVIDENCE_LINEAGE_VERIFIED', 'BF-631: UNKNOWN')),
    ($fixture.Replace('<div>BF-629: LIVE_ACTIONABLE_VERIFIED</div>', '')),
    ($fixture.Replace('<div>BF-631: LATEST_EVIDENCE_LINEAGE_VERIFIED</div>', '')),
    ($fixture.Replace('BF-629: LIVE_ACTIONABLE_VERIFIED</div>', 'BF-629: LIVE_ACTIONABLE_VERIFIED</div><div>BF-629: LIVE_ACTIONABLE_VERIFIED</div>'))
)) {
    $blocked = Get-V04AutoPilotWatchState -DashboardHtml $badProof
    if ($blocked.Ready -or $blocked.EvidenceStatus -cne 'UNVERIFIED' -or
        $blocked.StartSit -cne 'UNAVAILABLE' -or $blocked.Waivers -cne 'UNAVAILABLE') {
        throw 'BF-1044 BLOCKED: unproven current label created a manager recommendation.'
    }
}
$staleSpecificAdvice = $fixture.Replace('CURRENT_AND_ACTIONABLE', 'STALE_DO_NOT_ACT').
    Replace('<strong>REFRESH</strong>', '<strong>START 1 / SIT 1</strong>').
    Replace('<strong>DO NOT ACT</strong>', '<strong>ADD 1 / DROP 1</strong>')
$masked = Get-V04AutoPilotWatchState -DashboardHtml $staleSpecificAdvice
if ($masked.Ready -or $masked.EvidenceStatus -cne 'STALE' -or
    $masked.StartSit -cne 'REFRESH' -or $masked.Waivers -cne 'DO NOT ACT' -or
    $masked.Attention -cne 'NEEDS REFRESH') {
    throw 'BF-1044 BLOCKED: stale actionable advice was not masked.'
}
$maskedPolicy = Get-V04AutoPilotApprovalPolicy
$maskedQueue = Get-V04AutoPilotApprovalQueue -WatchState $masked -ApprovalPolicy $maskedPolicy
$maskedHtml = Get-V04AutoPilotHtml -WatchState $masked -ApprovalPolicy $maskedPolicy -ApprovalQueue $maskedQueue
if ($maskedHtml -match 'START 1 / SIT 1|ADD 1 / DROP 1|READY FOR MANAGER REVIEW') {
    throw 'BF-1044 BLOCKED: renderer leaked a stale lineup or waiver recommendation.'
}

$noMoveHtml = $fixture.Replace('CURRENT_AND_ACTIONABLE', 'NO_TRANSACTION_TO_ACT_ON').
    Replace('BF-629: LIVE_ACTIONABLE_VERIFIED', 'BF-629: NO_TRANSACTION_TO_REVALIDATE')
$noMove = Get-V04AutoPilotWatchState -DashboardHtml $noMoveHtml
if (-not $noMove.Ready) { throw 'BF-1042 BLOCKED: exact audited no-transaction watch should remain reviewable.' }
$badNoMove = Get-V04AutoPilotWatchState -DashboardHtml ($noMoveHtml.Replace('BF-629: NO_TRANSACTION_TO_REVALIDATE', 'BF-629: BLOCKED'))
if ($badNoMove.Ready) { throw 'BF-1044 BLOCKED: unproven no-transaction lineage passed.' }

# BF-1045: malformed or duplicated summary cards must never select
# the first plausible lineup/waiver value by accident.
foreach ($badCards in @(
    ($fixture.Replace('<div class="dashboard-summary-card"><span>Start/Sit</span><strong>REFRESH</strong></div>', '<div class="dashboard-summary-card"><span>Start/Sit</span><strong>REFRESH</strong></div><div class="dashboard-summary-card"><span>Start/Sit</span><strong>START 1 / SIT 1</strong></div>')),
    ($fixture.Replace('<div class="dashboard-summary-card"><span>Waivers</span><strong>DO NOT ACT</strong></div>', '<div class="dashboard-summary-card"><span>Waivers</span><strong> </strong></div>')),
    ($fixture.Replace('<div class="dashboard-summary-card"><span>Attention</span><strong>2 NEED ATTENTION</strong></div>', '<div class="dashboard-summary-card"><span>Attention</span><strong></strong></div>'))
)) {
    $badCardWatch = Get-V04AutoPilotWatchState -DashboardHtml $badCards
    if ($badCardWatch.Ready) {
        throw 'BF-1045 BLOCKED: duplicate or empty manager summary authorized Auto-Pilot.'
    }
}
$policy = Get-V04AutoPilotApprovalPolicy
$queue = Get-V04AutoPilotApprovalQueue -WatchState $state -ApprovalPolicy $policy
$html = Get-V04AutoPilotHtml -WatchState $state -ApprovalPolicy $policy -ApprovalQueue $queue
foreach ($required in @(
    'CURRENT WEEKLY WATCH',
    'What Butler sees right now',
    'CURRENT SNAPSHOT',
    '2 NEED ATTENTION',
    'REFRESH',
    'DO NOT ACT',
    'Hard(CORE)-Dynasty | nuke the whales | roster 6',
    'Open Waiver Board',
    'The watch snapshot reuses Butler''s current read-only manager state.'
)) {
    if ($html.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-1024 BLOCKED: Auto-Pilot weekly-watch marker missing: $required"
    }
}

$partial = Get-V04AutoPilotWatchState -DashboardHtml '<div class="dashboard-summary-card"><span>Attention</span><strong>1 NEED ATTENTION</strong></div>'
if ($partial.Ready) { throw 'BF-1024 BLOCKED: incomplete watch snapshot was incorrectly marked ready.' }

foreach ($required in @(
    'Invoke-ExpensiveReadSingleFlightGet -Port $InnerPort -RequestTarget ''/'' -League $LeagueId',
    'Get-V04AutoPilotWatchState -DashboardHtml',
    'Get-V04AutoPilotApprovalPolicy',
    'Get-V04AutoPilotApprovalQueue -WatchState $watchState -ApprovalPolicy $approvalPolicy',
    'Get-V04AutoPilotHtml -WatchState $watchState -ApprovalPolicy $approvalPolicy -ApprovalQueue $approvalQueue',
    'Attention = ''UNAVAILABLE''',
    'StartSit = ''UNAVAILABLE''',
    'Waivers = ''UNAVAILABLE'''
)) {
    if ($text.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-1024 BLOCKED: governed Auto-Pilot route marker missing: $required"
    }
}

$start = $text.IndexOf('function Get-V04AutoPilotWatchState {', [System.StringComparison]::Ordinal)
$end = $text.IndexOf('function Send-HttpResponse {', $start, [System.StringComparison]::Ordinal)
$surface = $text.Substring($start, $end - $start)
if ($surface -match 'Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|https://api\.sleeper\.app|Invoke-RestMethod|Invoke-WebRequest') {
    throw 'BF-1024 BLOCKED: Auto-Pilot weekly watch introduced provider, optimizer, or write behavior.'
}

# Regression: the governed single-flight helper requires the shared refresh
# state. A missing parameter silently made Auto-Pilot show UNAVAILABLE because
# the route catches watch read errors and falls back to a blank watch.
$calls = @($ast.FindAll({
    param($node)
    $node -is [System.Management.Automation.Language.CommandAst] -and
    $node.GetCommandName() -ceq 'Invoke-ExpensiveReadSingleFlightGet'
}, $true))
if ($calls.Count -ne 2) {
    throw "BF-1024 BLOCKED: expected Auto-Pilot and manager single-flight calls, found $($calls.Count)."
}
foreach ($call in $calls) {
    $parameters = @($call.CommandElements |
        Where-Object { $_ -is [System.Management.Automation.Language.CommandParameterAst] } |
        ForEach-Object { $_.ParameterName })
    foreach ($requiredParameter in @('Port', 'RequestTarget', 'League', 'RefreshState')) {
        if ($parameters -cnotcontains $requiredParameter) {
            throw "BF-1024 BLOCKED: single-flight caller missing mandatory $requiredParameter at line $($call.Extent.StartLineNumber)."
        }
    }
}
$autopilotRegionStart = $text.IndexOf('if ($path -eq ''/autopilot'') {', [System.StringComparison]::Ordinal)
$autopilotRegionEnd = $text.IndexOf('if ($path -eq ''/history'') {', $autopilotRegionStart, [System.StringComparison]::Ordinal)
if ($autopilotRegionStart -lt 0 -or $autopilotRegionEnd -le $autopilotRegionStart) {
    throw 'BF-1024 BLOCKED: bounded Auto-Pilot request region is missing.'
}
$autopilotRegion = $text.Substring($autopilotRegionStart, $autopilotRegionEnd - $autopilotRegionStart)
if ($autopilotRegion.IndexOf('-RefreshState $RefreshState', [System.StringComparison]::Ordinal) -lt 0) {
    throw 'BF-1024 BLOCKED: Auto-Pilot did not receive the shared evidence cache generation.'
}

Write-Host 'BF-1024 V0.4 AUTO-PILOT WEEKLY WATCH ACCEPTANCE: PASS'
Write-Host 'Coverage: real Dashboard snapshot reuse, Attention/Start-Sit/Waiver/roster watch state, fail-visible unavailable fallback, and no new provider/write behavior.'