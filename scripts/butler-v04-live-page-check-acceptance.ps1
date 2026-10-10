Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'butler-decision-refresh.ps1')

$path = Join-Path $PSScriptRoot 'butler-v04-live-page-check.ps1'
$launcher = Join-Path $PSScriptRoot 'butler-v04-live-page-check.cmd'
$doubleClick = Join-Path $PSScriptRoot 'butler-v04-live-page-check-open.cmd'
foreach ($requiredPath in @($path, $launcher, $doubleClick)) {
    if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) { throw "BF-1040 BLOCKED: missing $requiredPath" }
}
$source = [IO.File]::ReadAllText($path)
$wrapper = [IO.File]::ReadAllText($launcher)
$doubleClickWrapper = [IO.File]::ReadAllText($doubleClick)
foreach ($required in @('call "%~dp0butler-v04-live-page-check.cmd"', 'pause >nul', 'exit /b %butlerExit%')) {
    if ($doubleClickWrapper.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-1049 BLOCKED: the double-click tester lost its safe visible-result behavior: $required"
    }
}
foreach ($forbidden in @('taskkill', 'Stop-Process', 'git reset', 'git checkout', 'submitTransaction')) {
    if ($doubleClickWrapper.IndexOf($forbidden, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
        throw 'BF-1049 BLOCKED: the double-click tester may modify a frozen Butler instance.'
    }
}
$tokens = $null
$errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($path, [ref]$tokens, [ref]$errors)
if (@($errors).Count -ne 0) { throw 'BF-1040 BLOCKED: diagnostic does not parse on Windows PowerShell 5.1.' }
foreach ($name in @('Invoke-ButlerLocalGet', 'Test-ButlerLocalHealth', 'Test-ButlerLivePage')) {
    $node = $ast.Find({
        param($item)
        $item -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $item.Name -ceq $name
    }, $true)
    if ($null -eq $node) { throw "BF-1040 BLOCKED: missing smoke function $name" }
    . ([scriptblock]::Create($node.Extent.Text))
}

$health = [pscustomobject]@{
    Status = 200; Type = 'application/json; charset=utf-8'
    Body = '{"status":"ok","service":"butler-app-shell","featureSet":"v04-audited-onopen-freshness-bf1048","bind":"127.0.0.1"}'
}
if (-not (Test-ButlerLocalHealth -Response $health)) {
    throw 'BF-1040 BLOCKED: exact Butler loopback health was rejected.'
}
$health.Body = '{"status":"ok","service":"different-app","bind":"127.0.0.1"}'
if (Test-ButlerLocalHealth -Response $health) {
    throw 'BF-1040 BLOCKED: unrelated loopback service accepted.'
}
# Old frozen Butler health must not pass as a v0.4 app even when
# hosted on the expected port and bound to loopback.
$health.Body = '{"status":"ok","service":"butler-app-shell","bind":"127.0.0.1"}'
if (Test-ButlerLocalHealth -Response $health) {
    throw 'BF-1048 BLOCKED: unversioned/old Butler instance passed as v0.4.'
}
$health.Body = '{"status":"ok","service":"butler-app-shell","featureSet":"unknown","bind":"127.0.0.1"}'
if (Test-ButlerLocalHealth -Response $health) {
    throw 'BF-1048 BLOCKED: incompatible development build passed v0.4 identity gate.'
}
$health.Body = '{"status":"ok","service":"butler-app-shell","featureSet":"v04-audited-onopen-freshness-bf1048","bind":"0.0.0.0"}'
if (Test-ButlerLocalHealth -Response $health) {
    throw 'BF-1040 BLOCKED: public bind accepted.'
}

$page = [pscustomobject]@{
    Status = 200
    Type = 'text/html; charset=utf-8'
    Cache = 'no-store'
    Csp = "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'"
    Body = '<html><body><div class="dashboard-summary-row"></div><div>Decision state: CURRENT_AND_ACTIONABLE</div><div>BF-629: LIVE_ACTIONABLE_VERIFIED</div><div>BF-631: LATEST_EVIDENCE_LINEAGE_VERIFIED</div></body></html>'
}
$check = Test-ButlerLivePage -Route '/' -Response $page
if ($check.Status -cne 'PASS' -or $check.Evidence -cne 'AUDIT CURRENT' -or $check.AutoCheck -cne 'NOT NEEDED/GATED') {
    throw 'BF-1040 BLOCKED: eligible Dashboard baseline was rejected.'
}
$page.Body = $page.Body.Replace('</body>', '<div id="butler-auto-refresh-status"></div><script nonce="' + ('a' * 64) + '">safe diagnostic</script></body>')
$page.Csp += "; script-src 'nonce-" + ('a' * 64) + "'; connect-src 'self'"
$check = Test-ButlerLivePage -Route '/' -Response $page
if ($check.Status -cne 'PASS' -or $check.AutoCheck -cne 'ARMED') {
    throw 'BF-1040 BLOCKED: nonce-gated automatic refresh HTML/CSP rejected.'
}
$page.Csp = "default-src 'none'; frame-ancestors 'none'"
$check = Test-ButlerLivePage -Route '/' -Response $page
if ($check.Status -cne 'FAIL' -or $check.Evidence -cne 'AUTO CSP') {
    throw 'BF-1040 BLOCKED: missing nonce CSP incorrectly accepted.'
}
# BF-1047: a perfectly rendered Dashboard can still carry an outdated,
# unknown, or contradictory audited decision. Never report it as PASS/current.
$page.Csp += "; script-src 'nonce-" + ('a' * 64) + "'; connect-src 'self'"
$validDashboardMarkup = $page.Body
foreach ($unsafeAudit in @(
    ($validDashboardMarkup.Replace('CURRENT_AND_ACTIONABLE', 'STALE_DO_NOT_ACT')),
    ($validDashboardMarkup.Replace('CURRENT_AND_ACTIONABLE', 'CURRENT_REFRESH_RECOMMENDED')),
    ($validDashboardMarkup.Replace('BF-629: LIVE_ACTIONABLE_VERIFIED', 'BF-629: BLOCKED')),
    ($validDashboardMarkup.Replace('BF-631: LATEST_EVIDENCE_LINEAGE_VERIFIED', 'BF-631: MARKET_LINEAGE_SUPERSEDED')),
    ($validDashboardMarkup.Replace('<div>BF-629: LIVE_ACTIONABLE_VERIFIED</div>', '')),
    ($validDashboardMarkup.Replace('<div>BF-631: LATEST_EVIDENCE_LINEAGE_VERIFIED</div>', '')),
    ($validDashboardMarkup.Replace('BF-629: LIVE_ACTIONABLE_VERIFIED</div>', 'BF-629: LIVE_ACTIONABLE_VERIFIED</div><div>BF-629: LIVE_ACTIONABLE_VERIFIED</div>'))
)) {
    $page.Body = $unsafeAudit
    $check = Test-ButlerLivePage -Route '/' -Response $page
    if ($check.Status -cne 'WARN' -or
        @('AUDIT STALE', 'AUDIT UNVERIFIED') -cnotcontains $check.Evidence -or
        $check.AutoCheck -cne 'ARMED') {
        throw 'BF-1047 BLOCKED: a stale or ambiguous Dashboard was reported current.'
    }
}
$page.Body = '<html><body><div class="dashboard-summary-row"></div><div>Decision state: NO_TRANSACTION_TO_ACT_ON</div><div>BF-629: NO_TRANSACTION_TO_REVALIDATE</div><div>BF-631: LATEST_EVIDENCE_LINEAGE_VERIFIED</div></body></html>'
$page.Csp = "default-src 'none'; frame-ancestors 'none'"
$check = Test-ButlerLivePage -Route '/' -Response $page
if ($check.Status -cne 'PASS' -or $check.Evidence -cne 'AUDIT CURRENT') {
    throw 'BF-1047 BLOCKED: current audited no-transaction Dashboard was rejected.'
}
foreach ($lineage in @('MARKET_LINEAGE_SUPERSEDED', 'WAIVER_LINEAGE_SUPERSEDED', 'MARKET_AND_WAIVER_LINEAGE_SUPERSEDED')) {
    $page.Body = $page.Body.Replace('LATEST_EVIDENCE_LINEAGE_VERIFIED', $lineage)
    $check = Test-ButlerLivePage -Route '/' -Response $page
    if ($check.Status -cne 'WARN' -or $check.Evidence -cne 'AUDIT STALE') {
        throw 'BF-1047 BLOCKED: superseded no-transaction lineage was mislabeled current.'
    }
    $page.Body = $page.Body.Replace($lineage, 'LATEST_EVIDENCE_LINEAGE_VERIFIED')
}
$page.Body = '<html><body><div class="dashboard-summary-row"></div></body></html>'
$check = Test-ButlerLivePage -Route '/' -Response $page
if ($check.Status -cne 'WARN' -or $check.Evidence -cne 'AUDIT UNVERIFIED') {
    throw 'BF-1047 BLOCKED: Dashboard with no audited evidence was reported current.'
}

$page.Body = '<html><body>broken manager page</body></html>'
$check = Test-ButlerLivePage -Route '/' -Response $page
if ($check.Status -cne 'FAIL' -or $check.Evidence -cne 'PAGE CONTRACT') {
    throw 'BF-1040 BLOCKED: missing Dashboard markers accepted.'
}
$page.Body = '<html><body>CURRENT WEEKLY WATCH - WATCH DATA UNAVAILABLE</body></html>'
$page.Csp = "default-src 'none'; frame-ancestors 'none'"
$check = Test-ButlerLivePage -Route '/autopilot' -Response $page
if ($check.Status -cne 'WARN' -or $check.Evidence -cne 'WATCH INCOMPLETE') {
    throw 'BF-1040 BLOCKED: incomplete Auto-Pilot watch treated as current.'
}
$page.Body = '<html><body>CURRENT WEEKLY WATCH - EVIDENCE NEEDS REFRESH</body></html>'
$check = Test-ButlerLivePage -Route '/autopilot' -Response $page
if ($check.Status -cne 'WARN' -or $check.Evidence -cne 'WATCH INCOMPLETE') {
    throw 'BF-1042 BLOCKED: stale Auto-Pilot watch was mislabeled current.'
}
$page.Body = '<html><body>CURRENT WEEKLY WATCH <span>CURRENT SNAPSHOT</span><div id="butler-auto-refresh-status"></div><script nonce="' + ('a' * 64) + '">safe diagnostic</script></body></html>'
$page.Csp = "default-src 'none'; frame-ancestors 'none'; script-src 'nonce-" + ('a' * 64) + "'; connect-src 'self'"
$check = Test-ButlerLivePage -Route '/autopilot' -Response $page
if ($check.Status -cne 'PASS' -or $check.AutoCheck -cne 'ARMED') {
    throw 'BF-1040 BLOCKED: eligible Auto-Pilot nonce or healthy watch was rejected.'
}

# BF-1049: menu labels can appear on a normal-looking shell even when the
# actual roster/matchup/waiver evidence did not render. Require page-specific
# content, and warn on an empty roster or unconfirmed opponent.
$page.Csp = "default-src 'none'; frame-ancestors 'none'"
foreach ($fixture in @(
    @{ Route = '/team'; Body = '<html><body>My Team Current roster Roster players <article class="roster-card">safe</article></body></html>'; Expected = 'PASS'; Evidence = 'PAGE RESPONSE' },
    @{ Route = '/team'; Body = '<html><body>My Team Current roster Roster players</body></html>'; Expected = 'WARN'; Evidence = 'ROSTER NOT SHOWN' },
    @{ Route = '/team'; Body = '<html><body>My Team navigation only</body></html>'; Expected = 'FAIL'; Evidence = 'PAGE CONTENT' },
    @{ Route = '/waivers'; Body = '<html><body>Waiver Board waiver-decision-hero Butler waiver decision</body></html>'; Expected = 'PASS'; Evidence = 'PAGE RESPONSE' },
    @{ Route = '/waivers'; Body = '<html><body>Waiver Board navigation only</body></html>'; Expected = 'FAIL'; Evidence = 'PAGE CONTENT' },
    @{ Route = '/matchup'; Body = '<html><body>Matchup Weekly matchup hero-panel <h1>Week 5</h1></body></html>'; Expected = 'PASS'; Evidence = 'PAGE RESPONSE' },
    @{ Route = '/matchup'; Body = '<html><body>Matchup Weekly matchup hero-panel <h1>Opponent not confirmed</h1></body></html>'; Expected = 'WARN'; Evidence = 'OPPONENT UNKNOWN' },
    @{ Route = '/matchup'; Body = '<html><body>Matchup navigation only</body></html>'; Expected = 'FAIL'; Evidence = 'PAGE CONTENT' },
    @{ Route = '/matchup/autofill'; Body = '<html><body>Start/Sit Assistant <section class="panel recommendation-panel start-sit-assistant">safe</section></body></html>'; Expected = 'PASS'; Evidence = 'PAGE RESPONSE' },
    @{ Route = '/matchup/autofill'; Body = '<html><body>Start/Sit Assistant navigation only</body></html>'; Expected = 'FAIL'; Evidence = 'PAGE CONTENT' },
    @{ Route = '/league'; Body = '<html><body>League League intelligence Governed guidance</body></html>'; Expected = 'PASS'; Evidence = 'PAGE RESPONSE' },
    @{ Route = '/league'; Body = '<html><body>League navigation only</body></html>'; Expected = 'FAIL'; Evidence = 'PAGE CONTENT' }
)) {
    $page.Body = $fixture.Body
    $check = Test-ButlerLivePage -Route $fixture.Route -Response $page
    if ($check.Status -cne $fixture.Expected -or $check.Evidence -cne $fixture.Evidence) {
        throw "BF-1049 BLOCKED: real page vs navigation-only $($fixture.Route) expected $($fixture.Expected)/$($fixture.Evidence), got $($check.Status)/$($check.Evidence)."
    }
}

# Validate input is rejected before opening an external or invalid connection.
foreach ($probe in @(
    @{ SelectedPort = 443; Route = '/team' },
    @{ SelectedPort = 18080; Route = '/refresh' },
    @{ SelectedPort = 18080; Route = 'http://example.com/' }
)) {
    $rejected = $false
    try {
        [void](Invoke-ButlerLocalGet -SelectedPort $probe.SelectedPort -Route $probe.Route -Seconds 5)
    }
    catch { $rejected = $true }
    if (-not $rejected) { throw 'BF-1040 BLOCKED: invalid local probe was accepted.' }
}
foreach ($required in @(
    "http://127.0.0.1:",
    '$request.Method = ''GET''',
    '$request.Proxy = $null',
    '$request.AllowAutoRedirect = $false'
)) {
    if ($source.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-1040 BLOCKED: missing safe GET contract $required"
    }
}
foreach ($forbidden in @('''POST''', 'Invoke-RestMethod', 'submitTransaction', 'setFaab', 'create_transaction')) {
    if ($source.IndexOf($forbidden, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
        throw "BF-1040 BLOCKED: live diagnostic introduced forbidden provider or transaction action $forbidden"
    }
}
if ($wrapper.IndexOf('butler-v04-live-page-check.ps1', [System.StringComparison]::Ordinal) -lt 0 -or
    $wrapper.IndexOf('-ExecutionPolicy Bypass', [System.StringComparison]::Ordinal) -lt 0) {
    throw 'BF-1040 BLOCKED: one-command Windows launcher missing.'
}
Write-Host 'BF-1040 V0.4 LIVE PAGE SMOKE FIXTURES: PASS'
Write-Host 'Coverage: exact loopback health, page response/security, current/stale/unknown Dashboard audits, stale no-transaction lineage, auto-refresh CSP, Auto-Pilot watch, GET-only command.'
