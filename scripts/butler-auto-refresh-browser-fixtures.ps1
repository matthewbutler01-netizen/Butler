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
$noMove = $dashboard.Replace('STALE_DO_NOT_ACT', 'NO_TRANSACTION_TO_ACT_ON').
    Replace('LIVE_ACTIONABLE_VERIFIED', 'NO_TRANSACTION_TO_REVALIDATE').
    Replace('MARKET_LINEAGE_SUPERSEDED', 'LATEST_EVIDENCE_LINEAGE_VERIFIED')
$renderedBlocked = Add-AutomaticAutoPilotRefresh -Html $autoPilot -RequestTarget '/autopilot' -DashboardHtml $noMove -Token $token

foreach ($fixture in @($renderedDashboard, $renderedWaivers, $renderedAutoPilot)) {
    if ($fixture.Nonce -cnotmatch '^[0-9a-f]{64}$') {
        throw 'BF-1043 BLOCKED: eligible renderer failed to emit a nonce.'
    }
}
if ($renderedBlocked.Nonce -ne '') {
    throw 'BF-1043 BLOCKED: no-transaction Auto-Pilot emitted a script.'
}
[pscustomobject]@{
    audit = $audit
    token = $token
    dashboard = $renderedDashboard
    waivers = $renderedWaivers
    autopilot = $renderedAutoPilot
    blockedAutopilot = $renderedBlocked
} | ConvertTo-Json -Depth 6 -Compress
