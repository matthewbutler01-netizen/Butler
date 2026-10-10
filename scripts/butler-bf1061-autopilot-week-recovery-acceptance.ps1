Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$worker = Join-Path $PSScriptRoot 'butler-app-request-worker.ps1'
$text = [IO.File]::ReadAllText($worker)
$tokens = $null
$errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($worker, [ref]$tokens, [ref]$errors)
if (@($errors).Count -ne 0) { throw 'BF-1061 BLOCKED: request worker PowerShell parse failed.' }

foreach ($name in @(
    'Test-AutomaticWeekRecoveryCandidate',
    'Get-V04AutoPilotWatchState',
    'Limit-V04AutoPilotToVerifiedWeek',
    'Resolve-V04AutoPilotOnOpenWeek'
)) {
    $nodes = @($ast.FindAll({
        param($n)
        $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq $name
    }, $true))
    if ($nodes.Count -ne 1) { throw "BF-1061 BLOCKED: unique $name function absent." }
    . ([scriptblock]::Create($nodes[0].Extent.Text))
}

. (Join-Path $PSScriptRoot 'butler-decision-refresh.ps1')

$script:oldDashboard = '<div class="butler-refresh-contract" hidden><div>Decision state: CURRENT_AND_ACTIONABLE</div><div>BF-629: LIVE_ACTIONABLE_VERIFIED</div><div>BF-631: LATEST_EVIDENCE_LINEAGE_VERIFIED</div></div><div class="dashboard-summary-row"><div class="dashboard-summary-card"><span>Attention</span><strong>2 NEED ATTENTION</strong></div><div class="dashboard-summary-card"><span>Start/Sit</span><strong>START 1 / SIT 1</strong></div><div class="dashboard-summary-card"><span>Waivers</span><strong>ADD 1 / DROP 1</strong></div><div class="dashboard-summary-card dashboard-summary-team"><span>Roster</span><strong>Test roster</strong></div></div>'
$script:match = '<section class="panel butler-live-week-status" role="status" data-butler-week-state="MATCH" data-butler-week-season="2026" data-butler-week-number="5"><strong>WEEK MATCHES SLEEPER</strong></section><div class="target" data-butler-matchup-season="2026">Test league &middot; Week 5</div>'
$script:mismatch = '<section class="panel butler-live-week-status" role="status" data-butler-week-state="MISMATCH"><strong>SAVED MATCHUP OUTDATED</strong></section><h1>Saved matchup not usable</h1><span>DO NOT ACT</span>'
$script:attempts = 0
$script:claims = 0
$script:completed = 0
$script:reads = @()
$script:denyClaim = $false
$script:failSync = $false
$script:failRead = $false
$script:badDashboard = $false
$script:badMatchup = $false

function Claim-AutomaticWeekRecovery {
    param([hashtable]$State)
    $script:claims++
    return (-not $script:denyClaim)
}
function Invoke-AutomaticWeekRecovery {
    param([string]$Root,[string]$League)
    $script:attempts++
    if ($script:failSync) { throw 'synthetic sync failed' }
}
function Complete-DecisionRefreshAttempt {
    param([hashtable]$State)
    $script:completed++
}
function Invoke-AppCoreGet {
    param([int]$Port,[string]$RequestTarget)
    $script:reads += $RequestTarget
    if ($script:failRead -and $RequestTarget -ceq '/matchup') {
        throw 'synthetic page read failed'
    }
    $html = if ($RequestTarget -ceq '/') { 
        if ($script:badDashboard) { '' } else { $script:oldDashboard }
    }
    elseif ($RequestTarget -ceq '/matchup') { 
        if ($script:badMatchup) { $script:mismatch } else { $script:match }
    }
    else { throw 'BF-1061 unexpected unapproved core route' }
    return [pscustomobject]@{ StatusCode = 200; ContentType = 'text/html'; Body = $html }
}

function Invoke-Fixture {
    param([string]$MatchupHtml)
    $initial = Get-V04AutoPilotWatchState -DashboardHtml $script:oldDashboard
    return Resolve-V04AutoPilotOnOpenWeek -InitialWatchState $initial -DashboardHtml $script:oldDashboard -MatchupHtml $MatchupHtml -InnerPort 18080 -League 'synthetic-league' -Root 'C:\synthetic' -RefreshState @{ SyncRoot = (New-Object object); InProgress = $false }
}

$good = Invoke-Fixture -MatchupHtml $script:mismatch
if (-not $good.WatchState.Ready -or
    $good.WatchState.StartSit -cne 'START 1 / SIT 1' -or
    $script:attempts -ne 1 -or $script:claims -ne 1 -or
    $script:completed -ne 1 -or
    ($script:reads -join ',') -cne '/,/matchup') {
    throw 'BF-1061 BLOCKED: proved mismatch did not recover, reread both sources, and independently re-audit.'
}

foreach ($bad in @(
    $script:match,
    '',
    ($script:mismatch + $script:mismatch),
    ($script:mismatch.Replace('MISMATCH', 'UNVERIFIED')),
    '<div data-butler-week-state="MISMATCH">spoof</div>'
)) {
    $prior = $script:attempts
    $result = Invoke-Fixture -MatchupHtml $bad
    if ($script:attempts -ne $prior) {
        throw 'BF-1061 BLOCKED: unsupported or duplicate week evidence initiated a write.'
    }
    if ($bad -cne $script:match -and $result.WatchState.Ready) {
        throw 'BF-1061 BLOCKED: missing/ambiguous week proof permitted prepared manager actions.'
    }
}

foreach ($mode in @('failSync','failRead','badDashboard','badMatchup','denyClaim')) {
    Set-Variable -Scope Script -Name $mode -Value $true
    $before = $script:completed
    $result = Invoke-Fixture -MatchupHtml $script:mismatch
    if ($result.WatchState.Ready -or
        $result.WatchState.StartSit -ceq 'START 1 / SIT 1' -or
        $result.WatchState.Waivers -ceq 'ADD 1 / DROP 1') {
        throw "BF-1061 BLOCKED: $mode bypassed independent current-week and Dashboard gates."
    }
    if ($mode -cne 'denyClaim' -and $script:completed -ne ($before + 1)) {
        throw "BF-1061 BLOCKED: $mode failed to release governed writer state."
    }
    Set-Variable -Scope Script -Name $mode -Value $false
}

$start = $text.IndexOf("if ($([char]36)path -eq '/autopilot') {", [StringComparison]::Ordinal)
$end = $text.IndexOf("if ($([char]36)path -eq '/history') {", $start, [StringComparison]::Ordinal)
if ($start -lt 0 -or $end -le $start) { throw 'BF-1061 BLOCKED: Auto-Pilot exact route not found.' }
$flow = $text.Substring($start, $end - $start)
foreach ($required in @(
    "if ($([char]36)requestTarget -cne '/autopilot')",
    'Invoke-ExpensiveReadSingleFlightGet -Port $InnerPort -RequestTarget ''/matchup''',
    'Resolve-V04AutoPilotOnOpenWeek -InitialWatchState $watchState',
    '$watchState = $resolvedWatch.WatchState',
    '$dashboardHtmlForRefresh = [string]$resolvedWatch.DashboardHtml',
    'Get-V04AutoPilotApprovalQueue -WatchState $watchState'
)) {
    if ($flow.IndexOf($required, [StringComparison]::Ordinal) -lt 0) {
        throw "BF-1061 BLOCKED: Auto-Pilot route lost governed on-open proof flow: $required"
    }
}
Write-Host 'BF-1061 AUTO-PILOT WEEK RECOVERY: PASS'
Write-Host 'Coverage: week rollover on Auto-Pilot open, fresh independent Dashboard/Matchup audits, failed sync/read hold, mutual exclusion, and no forged/offline provider writes.'
