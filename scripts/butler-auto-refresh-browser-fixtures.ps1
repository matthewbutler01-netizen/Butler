Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'butler-decision-refresh.ps1')

# Real PowerShell renderer -> JSON -> isolated Node browser executor. Never
# starts Butler, sends HTTP, or runs a refresh transaction.
$token = 'a' * 64
$audit = '11111111-1111-1111-1111-111111111111'
$dashboard = '<html><body><nav class="nav" aria-label="Butler sections"></nav><div>Decision state: STALE_DO_NOT_ACT</div><div>BF-629: LIVE_ACTIONABLE_VERIFIED</div><div>BF-631: MARKET_LINEAGE_SUPERSEDED</div><div>Audit ID: ' + $audit + '</div></body></html>'
$eligible = Add-DecisionRefreshControl -Html $dashboard -RequestTarget '/'
if ($eligible.IndexOf('href="/refresh"', [StringComparison]::Ordinal) -lt 0) {
    throw 'BF-1043 BLOCKED: fixture has no exact governed Dashboard refresh eligibility.'
}

$renderedDashboard = Add-AutomaticDashboardRefresh -Html $eligible -RequestTarget '/' -Token $token
$waivers = '<html><body><span hidden data-butler-auto-waiver="' + $audit + '"></span></body></html>'
$renderedWaivers = Add-AutomaticWaiverRefresh -Html $waivers -RequestTarget '/waivers' -Token $token
$autoPilot = '<html><body><section>CURRENT WEEKLY WATCH</section></body></html>'
$renderedAutoPilot = Add-AutomaticAutoPilotRefresh -Html $autoPilot -RequestTarget '/autopilot' -DashboardHtml $dashboard -Token $token
# BF-1104: independent, blocked Start/Sit page reuses the audited stale
# Dashboard. A healthy page remains script-free.
$blockedStartSit = '<html><body><section class="panel recommendation-panel start-sit-assistant"><div class="callout callout-danger start-sit-blocker">Source unavailable</div></section></body></html>'
$renderedStartSit = Add-AutomaticStartSitRefresh -Html $blockedStartSit -RequestTarget '/matchup/autofill' -DashboardHtml $dashboard -Token $token
$renderedHealthyStartSit = Add-AutomaticStartSitRefresh -Html ($blockedStartSit.Replace('callout callout-danger start-sit-blocker', 'callout callout-info')) -RequestTarget '/matchup/autofill' -DashboardHtml $dashboard -Token $token
$noMove = $dashboard.Replace('STALE_DO_NOT_ACT', 'NO_TRANSACTION_TO_ACT_ON').
    Replace('LIVE_ACTIONABLE_VERIFIED', 'NO_TRANSACTION_TO_REVALIDATE').
    Replace('MARKET_LINEAGE_SUPERSEDED', 'LATEST_EVIDENCE_LINEAGE_VERIFIED')
$renderedBlocked = Add-AutomaticAutoPilotRefresh -Html $autoPilot -RequestTarget '/autopilot' -DashboardHtml $noMove -Token $token

if ($renderedHealthyStartSit.Nonce -ne '') { throw 'BF-1104 BLOCKED: healthy Start/Sit unexpectedly armed an on-open POST.' }
foreach ($fixture in @($renderedDashboard, $renderedWaivers, $renderedAutoPilot, $renderedStartSit)) {
    if ($fixture.Nonce -cnotmatch '^[0-9a-f]{64}$') {
        throw 'BF-1043 BLOCKED: eligible renderer failed to emit a nonce.'
    }
}
if ($renderedBlocked.Nonce -ne '') {
    throw 'BF-1043 BLOCKED: no-transaction Auto-Pilot emitted a script.'
}
# Exercise all old-week/ambiguous states through each real source-based
# automatic renderer. An adjacent-week manual recovery must never inherit
# the ordinary stale-evidence automatic refresh capability.
$rolloverPages = @()
foreach ($actionability in @('AUDITED_WEEK_ROLLOVER_DO_NOT_ACT',
        'AUDITED_WEEK_MISMATCH_DO_NOT_ACT','AUDITED_WEEK_UNVERIFIED_DO_NOT_ACT',
        'AUDITED_TRANSACTION_PENDING','AUDITED_TRANSACTION_COMPLETE','')) {
    foreach ($lineage in @('LATEST_EVIDENCE_LINEAGE_VERIFIED',
            'MARKET_LINEAGE_SUPERSEDED','WAIVER_LINEAGE_SUPERSEDED',
            'MARKET_AND_WAIVER_LINEAGE_SUPERSEDED','UNKNOWN','')) {
        $oldWeek = $dashboard.Replace('LIVE_ACTIONABLE_VERIFIED', $actionability).
            Replace('MARKET_LINEAGE_SUPERSEDED', $lineage)
        $governed = Add-DecisionRefreshControl -Html $oldWeek -RequestTarget '/'
        $rolloverPages += [pscustomobject]@{
            Actionability = $actionability
            Lineage = $lineage
            Dashboard = Add-AutomaticDashboardRefresh -Html $governed -RequestTarget '/' -Token $token
            AutoPilot = Add-AutomaticAutoPilotRefresh -Html $autoPilot -RequestTarget '/autopilot' -DashboardHtml $oldWeek -Token $token
            StartSit = Add-AutomaticStartSitRefresh -Html $blockedStartSit -RequestTarget '/matchup/autofill' -DashboardHtml $oldWeek -Token $token
        }
    }
}
# Only presentation dependencies are stubbed. The production confirmation
# renderer must emit a manual token-only form; no runner or HTTP is invoked.
function Get-AppCss { return '' }
function Get-AppNav { param([string]$Active) return '<nav></nav>' }
function ConvertTo-HtmlText { param([string]$Text) return [Net.WebUtility]::HtmlEncode($Text) }
$confirmation = Get-DecisionRefreshConfirmationHtml -LeagueId '00000000-0000-0000-0000-000000000001' -Token $token
[pscustomobject]@{
    audit = $audit
    token = $token
    dashboard = $renderedDashboard
    waivers = $renderedWaivers
    autopilot = $renderedAutoPilot
    startsit = $renderedStartSit
    blockedStartSit = $renderedHealthyStartSit
    blockedAutopilot = $renderedBlocked
    rolloverPages = $rolloverPages
    confirmation = $confirmation
} | ConvertTo-Json -Depth 6 -Compress
