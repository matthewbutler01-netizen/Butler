Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'butler-decision-refresh.ps1')

$token = 'a' * 64
$watch = '<html><body><nav class="nav" aria-label="Butler sections"><a href="/autopilot">Auto-Pilot</a></nav><section>CURRENT WEEKLY WATCH</section></body></html>'
$dashboard = '<html><body><nav class="nav" aria-label="Butler sections"></nav><div>Decision state: STALE_DO_NOT_ACT</div><div>BF-629: LIVE_ACTIONABLE_VERIFIED</div><div>BF-631: MARKET_LINEAGE_SUPERSEDED</div><div>Audit ID: 11111111-1111-1111-1111-111111111111</div></body></html>'

$armed = Add-AutomaticAutoPilotRefresh -Html $watch -RequestTarget '/autopilot' -DashboardHtml $dashboard -Token $token
if ($armed.Nonce -cnotmatch '^[0-9a-f]{64}$' -or
    $armed.Html -notmatch 'butler-auto-refresh:/:11111111-1111-1111-1111-111111111111' -or
    $armed.Html -notmatch 'id="butler-auto-refresh-status"' -or
    $armed.Html -notmatch "window.location.replace\('/autopilot'\)" -or
    $armed.Html -notmatch "method: 'POST'" -or
    $armed.Html -notmatch 'now - previous < 300000') {
    throw 'BF-1041 BLOCKED: eligible Auto-Pilot is missing governed, shared-cooldown refresh.'
}

$plan = $dashboard.Replace('Decision state: STALE_DO_NOT_ACT', 'Decision state: CURRENT_REFRESH_RECOMMENDED').
    Replace('BF-631: MARKET_LINEAGE_SUPERSEDED', 'BF-631: LATEST_EVIDENCE_LINEAGE_VERIFIED').
    Replace('</body>', '<div>BF-636 plan state: MANUAL_REFRESH_PLAN_READY</div><div>BF-636 plan policy: sleeper-live-waiver-manual-refresh-plan-v1-bf635-explicit-operator-only-no-execution</div><div>Governed step count: 9</div></body>')
$readyPlan = Add-AutomaticAutoPilotRefresh -Html $watch -RequestTarget '/autopilot' -DashboardHtml $plan -Token $token
if ($readyPlan.Nonce -cnotmatch '^[0-9a-f]{64}$') {
    throw 'BF-1041 BLOCKED: exact ready governed nine-step plan was not eligible.'
}

$manualOnly = $dashboard.Replace('Decision state: STALE_DO_NOT_ACT', 'Decision state: NO_TRANSACTION_TO_ACT_ON').
    Replace('BF-629: LIVE_ACTIONABLE_VERIFIED', 'BF-629: NO_TRANSACTION_TO_REVALIDATE').
    Replace('BF-631: MARKET_LINEAGE_SUPERSEDED', 'BF-631: LATEST_EVIDENCE_LINEAGE_VERIFIED')

$spoofedLink = $dashboard.Replace('<nav class="nav" aria-label="Butler sections">',
    '<nav class="nav" aria-label="Butler sections"><a href="/refresh">Refresh Butler data</a>').
    Replace('BF-629: LIVE_ACTIONABLE_VERIFIED', 'BF-629: BLOCKED')

foreach ($badDashboard in @(
    $manualOnly,
    $spoofedLink,
    ($dashboard.Replace('BF-631: MARKET_LINEAGE_SUPERSEDED', 'BF-631: UNVERIFIED')),
    ($dashboard.Replace('Audit ID: 11111111-1111-1111-1111-111111111111', 'Audit ID: UNKNOWN')),
    ($dashboard.Replace('</body>', '<div>Audit ID: 11111111-1111-1111-1111-111111111111</div></body>')),
    ($dashboard.Replace('<body>', '<BODY>')),
    ($plan.Replace('Governed step count: 9', 'Governed step count: 8')),
    '<html><body>Unavailable Dashboard</body></html>'
)) {
    $blocked = Add-AutomaticAutoPilotRefresh -Html $watch -RequestTarget '/autopilot' -DashboardHtml $badDashboard -Token $token
    if ($blocked.Nonce -ne '' -or $blocked.Html -cne $watch) {
        throw 'BF-1041 BLOCKED: unsupported Dashboard proof enabled Auto-Pilot automatic update.'
    }
}
foreach ($route in @('/', '/autopilot?force=1', '/team')) {
    $blocked = Add-AutomaticAutoPilotRefresh -Html $watch -RequestTarget $route -DashboardHtml $dashboard -Token $token
    if ($blocked.Nonce -ne '' -or $blocked.Html -cne $watch) {
        throw 'BF-1041 BLOCKED: unsupported route enabled Auto-Pilot automatic update.'
    }
}
foreach ($badWatch in @(
    ($watch.Replace('<body>', '<BODY>')),
    ($watch.Replace('</body>', '</BODY>')),
    ($watch.Replace('</body>', '')),
    ($watch.Replace('<body>', '<body><body>'))
)) {
    $blocked = Add-AutomaticAutoPilotRefresh -Html $badWatch -RequestTarget '/autopilot' -DashboardHtml $dashboard -Token $token
    if ($blocked.Nonce -ne '' -or $blocked.Html -cne $badWatch) {
        throw 'BF-1041 BLOCKED: malformed Auto-Pilot HTML was armed.'
    }
}
$invalid = Add-AutomaticAutoPilotRefresh -Html $watch -RequestTarget '/autopilot' -DashboardHtml $dashboard -Token ('z' * 64)
if ($invalid.Nonce -ne '' -or $invalid.Html -cne $watch) {
    throw 'BF-1041 BLOCKED: invalid POST token armed Auto-Pilot refresh.'
}

$worker = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'butler-app-request-worker.ps1'))
foreach ($required in @(
    '$dashboardHtmlForRefresh = [string]$dashboard.Body',
    'Add-AutomaticAutoPilotRefresh -Html $html -RequestTarget $requestTarget -DashboardHtml $dashboardHtmlForRefresh',
    '-ScriptNonce $autoRefresh.Nonce',
    '-Body $autoRefresh.Html'
)) {
    if ($worker.IndexOf($required, [StringComparison]::Ordinal) -lt 0) {
        throw "BF-1041 BLOCKED: Auto-Pilot HTTP script/CSP wiring missing $required"
    }
}
Write-Host 'BF-1041 AUTO-PILOT AUTOMATIC FRESHNESS: PASS'
Write-Host 'Coverage: exact audited Dashboard eligibility, no-transaction manual-only, preexisting-link spoof defense, same-tab Dashboard cooldown, CSP nonce wiring, fail-closed routes/markup/token.'
