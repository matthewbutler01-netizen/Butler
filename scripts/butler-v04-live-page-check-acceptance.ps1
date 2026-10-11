Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'butler-decision-refresh.ps1')

$path = Join-Path $PSScriptRoot 'butler-v04-live-page-check.ps1'
$launcher = Join-Path $PSScriptRoot 'butler-v04-live-page-check.cmd'
$doubleClick = Join-Path $PSScriptRoot 'butler-v04-live-page-check-open.cmd'
$publicWeekCheck = Join-Path $PSScriptRoot 'butler-v04-week-check-open.cmd'
$realLeagueCheck = Join-Path $PSScriptRoot 'butler-v04-real-league-readiness-open.cmd'
foreach ($requiredPath in @($path, $launcher, $doubleClick, $publicWeekCheck, $realLeagueCheck)) {
    if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) { throw "BF-1040 BLOCKED: missing $requiredPath" }
}
$source = [IO.File]::ReadAllText($path)
$wrapper = [IO.File]::ReadAllText($launcher)
$doubleClickWrapper = [IO.File]::ReadAllText($doubleClick)
$publicWeekWrapper = [IO.File]::ReadAllText($publicWeekCheck)
$realLeagueWrapper = [IO.File]::ReadAllText($realLeagueCheck)
# BF-1077: no user PowerShell required, a single private local diagnostics
# text report is captured without storing player/roster HTML, tokens or data.
foreach ($required in @(
    'call "%~dp0butler-v04-live-page-check.cmd" -CheckSleeperWeek -RequireReady -TimeoutSeconds 30',
    'v04-real-league-readiness-latest.txt',
    'set "butlerReportDir=%LOCALAPPDATA%\Butler\diagnostics"',
    '> "%butlerReport%" 2>&1',
    'type "%butlerReport%"',
    'start "" notepad.exe "%butlerReport%"',
    'exit /b %butlerExit%',
    'may invoke Butler'
)) {
    if ($realLeagueWrapper.IndexOf($required, [StringComparison]::OrdinalIgnoreCase) -lt 0) {
        throw "BF-1077 BLOCKED: one-click real league report contract missing: $required"
    }
}
foreach ($forbidden in @(
    'taskkill', 'Stop-Process', 'git reset', 'git checkout',
    'Invoke-RestMethod', 'POST /refresh', 'submitTransaction',
    '.db', '.sqlite', 'app-league.txt', 'start butler-app'
)) {
    if ($realLeagueWrapper.IndexOf($forbidden, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
        throw "BF-1077 BLOCKED: one-click report may modify local runtime or reveal private evidence: $forbidden"
    }
}

foreach ($required in @('butler-v04-live-page-check.cmd', '-CheckSleeperWeek', 'pause >nul')) {
    if ($publicWeekWrapper.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-1053 BLOCKED: optional public NFL week launcher is missing: $required"
    }
}
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
foreach ($name in @('Invoke-ButlerLocalGet', 'Get-ButlerPublicNflState', 'Test-ButlerSleeperWeekMatch', 'Test-ButlerLocalHealth', 'Test-ButlerLivePage', 'Test-ButlerCrossRouteWeek')) {
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

# BF-1051: exact eligible stale decisions may not quietly lose their
# on-open browser script while retaining otherwise healthy page markup.
$auditId = '11111111-1111-1111-1111-111111111111'
$baseCsp = "default-src 'none'; frame-ancestors 'none'"
$eligibleDashboard = '<html><body><div class="dashboard-summary-row"></div>' +
    '<div>Decision state: STALE_DO_NOT_ACT</div>' +
    '<div>BF-629: LIVE_ACTIONABLE_VERIFIED</div>' +
    '<div>BF-631: MARKET_LINEAGE_SUPERSEDED</div>' +
    '<div>Audit ID: ' + $auditId + '</div></body></html>'
$page.Body = $eligibleDashboard
$page.Csp = $baseCsp
$check = Test-ButlerLivePage -Route '/' -Response $page
if ($check.Status -cne 'FAIL' -or $check.Evidence -cne 'AUTO MISSING') {
    throw 'BF-1051 BLOCKED: eligible stale Dashboard silently omitted automatic update.'
}
$page.Body = $eligibleDashboard.Replace('</body>', '<div id="butler-auto-refresh-status"></div><script nonce="' + ('a' * 64) + '">synthetic inert script</script></body>')
$page.Csp = $baseCsp + "; script-src 'nonce-" + ('a' * 64) + "'; connect-src 'self'"
$check = Test-ButlerLivePage -Route '/' -Response $page
if ($check.Status -cne 'WARN' -or $check.Evidence -cne 'AUDIT STALE' -or
    $check.AutoCheck -cne 'ARMED') {
    throw 'BF-1051 BLOCKED: eligible stale Dashboard with correct auto script was rejected.'
}
$recommended = $eligibleDashboard.Replace('STALE_DO_NOT_ACT', 'CURRENT_REFRESH_RECOMMENDED').
    Replace('MARKET_LINEAGE_SUPERSEDED', 'LATEST_EVIDENCE_LINEAGE_VERIFIED').
    Replace('</body>', '<div>BF-636 plan state: MANUAL_REFRESH_PLAN_READY</div><div>BF-636 plan policy: sleeper-live-waiver-manual-refresh-plan-v1-bf635-explicit-operator-only-no-execution</div><div>Governed step count: 9</div></body>')
$page.Body = $recommended
$page.Csp = $baseCsp
$check = Test-ButlerLivePage -Route '/' -Response $page
if ($check.Status -cne 'FAIL' -or $check.Evidence -cne 'AUTO MISSING') {
    throw 'BF-1051 BLOCKED: governed nine-stage refresh recommendation lost automatic update.'
}
$page.Body = $recommended.Replace('Governed step count: 9', 'Governed step count: 10')
$check = Test-ButlerLivePage -Route '/' -Response $page
if ($check.Status -cne 'WARN' -or $check.Evidence -cne 'AUDIT STALE' -or
    $check.AutoCheck -cne 'NOT NEEDED/GATED') {
    throw 'BF-1051 BLOCKED: invalid nine-stage plan incorrectly demanded an automatic update.'
}
$waiverBase = '<html><body>Waiver Board <section class="waiver-decision-hero">Butler waiver decision</section><span hidden data-butler-auto-waiver="' + $auditId + '"></span></body></html>'
$page.Body = $waiverBase
$check = Test-ButlerLivePage -Route '/waivers' -Response $page
if ($check.Status -cne 'FAIL' -or $check.Evidence -cne 'AUTO MISSING') {
    throw 'BF-1051 BLOCKED: eligible Waiver Board silently omitted automatic update.'
}
$page.Body = $waiverBase.Replace('</body>', '<div id="butler-auto-refresh-status"></div><script nonce="' + ('a' * 64) + '">synthetic inert script</script></body>')
$page.Csp = $baseCsp + "; script-src 'nonce-" + ('a' * 64) + "'; connect-src 'self'"
$check = Test-ButlerLivePage -Route '/waivers' -Response $page
if ($check.Status -cne 'PASS' -or $check.AutoCheck -cne 'ARMED') {
    throw 'BF-1051 BLOCKED: eligible Waiver Board with correct auto script was rejected.'
}
$page.Csp = $baseCsp
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
    @{ Route = '/team'; Body = '<html><body>My Team Roster hub Lineup and depth at a glance <section id="roster-starters"><div class="player-row">safe</div></section></body></html>'; Expected = 'PASS'; Evidence = 'PAGE RESPONSE' },
    @{ Route = '/team'; Body = '<html><body>My Team Roster hub Lineup and depth at a glance <section id="roster-starters"></section></body></html>'; Expected = 'WARN'; Evidence = 'ROSTER NOT SHOWN' },
    @{ Route = '/team'; Body = '<html><body>My Team navigation only</body></html>'; Expected = 'FAIL'; Evidence = 'PAGE CONTENT' },
    @{ Route = '/waivers'; Body = '<html><body>Waiver Board waiver-decision-hero Butler waiver decision</body></html>'; Expected = 'PASS'; Evidence = 'PAGE RESPONSE' },
    @{ Route = '/waivers'; Body = '<html><body>Waiver Board navigation only</body></html>'; Expected = 'FAIL'; Evidence = 'PAGE CONTENT' },
    @{ Route = '/matchup'; Body = '<html><body>Matchup Weekly matchup hero-panel <section class="panel butler-live-week-status" role="status" data-butler-week-state="MATCH" data-butler-week-season="2026" data-butler-week-number="5"></section><div class="target" data-butler-matchup-season="2026">League &middot; Week 5</div><h1>Team A vs Team B</h1></body></html>'; Expected = 'PASS'; Evidence = 'PAIRING VERIFIED' },
    @{ Route = '/matchup'; Body = '<html><body>Matchup Weekly matchup hero-panel <section data-butler-week-state="MATCH"></section><h1>Week 5</h1></body></html>'; Expected = 'WARN'; Evidence = 'PAIRING UNVERIFIED' },
    @{ Route = '/matchup'; Body = '<html><body>Matchup Weekly matchup hero-panel <section class="panel butler-live-week-status" role="status" data-butler-week-state="MATCH" data-butler-week-season="2026" data-butler-week-number="5"></section><div class="target" data-butler-matchup-season="2026">League &middot; Week 4</div></body></html>'; Expected = 'FAIL'; Evidence = 'PAIRING CONFLICT' },
    @{ Route = '/matchup'; Body = '<html><body>Matchup Weekly matchup hero-panel <section class="panel butler-live-week-status" role="status" data-butler-week-state="MATCH" data-butler-week-season="2026" data-butler-week-number="5"></section><div class="target" data-butler-matchup-season="2026">League &middot; Week 5</div><div class="target" data-butler-matchup-season="2026">Fake duplicate &middot; Week 5</div></body></html>'; Expected = 'FAIL'; Evidence = 'PAIRING PROOF' },
    @{ Route = '/matchup'; Body = '<html><body>Matchup Weekly matchup hero-panel <section data-butler-week-state="MATCH"></section><h1>Opponent not confirmed</h1></body></html>'; Expected = 'WARN'; Evidence = 'OPPONENT UNKNOWN' },
    @{ Route = '/matchup'; Body = '<html><body>Matchup Weekly matchup hero-panel <section data-butler-week-state="MISMATCH"></section><h1>Saved matchup not usable</h1></body></html>'; Expected = 'WARN'; Evidence = 'WEEK MISMATCH' },
    @{ Route = '/matchup'; Body = '<html><body>Matchup Weekly matchup hero-panel <section data-butler-week-state="UNVERIFIED"></section><h1>Week not verified</h1></body></html>'; Expected = 'WARN'; Evidence = 'WEEK UNVERIFIED' },
    @{ Route = '/matchup'; Body = '<html><body>Matchup Weekly matchup hero-panel <h1>Week 5</h1></body></html>'; Expected = 'FAIL'; Evidence = 'WEEK PROOF' },
    @{ Route = '/matchup'; Body = '<html><body>Matchup Weekly matchup hero-panel <section data-butler-week-state="MATCH"></section><section data-butler-week-state="MATCH"></section></body></html>'; Expected = 'FAIL'; Evidence = 'WEEK PROOF' },
    @{ Route = '/matchup'; Body = '<html><body>Matchup Weekly matchup hero-panel <section data-butler-week-state="MISMATCH"></section><p>Promote to lineup</p></body></html>'; Expected = 'FAIL'; Evidence = 'HELD ADVICE' },
    @{ Route = '/matchup'; Body = '<html><body>Matchup navigation only</body></html>'; Expected = 'FAIL'; Evidence = 'PAGE CONTENT' },
    @{ Route = '/matchup/autofill'; Body = '<html><body>Start/Sit Assistant <section class="panel butler-live-week-status" role="status" data-butler-week-state="MATCH" data-butler-week-season="2026" data-butler-week-number="5"></section><div class="target" data-butler-matchup-season="2026">League &middot; Week 5</div><section class="panel recommendation-panel start-sit-assistant">safe</section></body></html>'; Expected = 'PASS'; Evidence = 'PAIRING VERIFIED' },
    @{ Route = '/matchup/autofill'; Body = '<html><body>Start/Sit Assistant <section class="panel butler-live-week-status" role="status" data-butler-week-state="MATCH" data-butler-week-season="2026" data-butler-week-number="5"></section><div class="target" data-butler-matchup-season="2026">League &middot; Week 5</div><section class="panel recommendation-panel start-sit-assistant"><div class="callout callout-danger start-sit-blocker"><strong>Blocking evidence:</strong> BF-1066 BLOCKED: player status missing</div></section></body></html>'; Expected = 'WARN'; Evidence = 'START/SIT BLOCKED' },
    @{ Route = '/matchup/autofill'; Body = '<html><body>Start/Sit Assistant <section class="panel butler-live-week-status" role="status" data-butler-week-state="MATCH" data-butler-week-season="2026" data-butler-week-number="5"></section><div class="target" data-butler-matchup-season="2026">League &middot; Week 5</div><section class="panel recommendation-panel start-sit-assistant"><p class="lede">Butler could not prove a complete weekly lineup recommendation.</p></section></body></html>'; Expected = 'WARN'; Evidence = 'START/SIT BLOCKED' },
    @{ Route = '/matchup/autofill'; Body = '<html><body>Start/Sit Assistant <section data-butler-week-state="MATCH"></section><section class="panel recommendation-panel start-sit-assistant">safe</section></body></html>'; Expected = 'WARN'; Evidence = 'PAIRING UNVERIFIED' },
    @{ Route = '/matchup/autofill'; Body = '<html><body>Start/Sit Assistant <section data-butler-week-state="UNVERIFIED"></section><section class="panel recommendation-panel start-sit-assistant">held</section></body></html>'; Expected = 'WARN'; Evidence = 'WEEK UNVERIFIED' },
    @{ Route = '/matchup/autofill'; Body = '<html><body>Start/Sit Assistant <section data-butler-week-state="MISMATCH"></section><section class="panel recommendation-panel start-sit-assistant">Recommended starter</section></body></html>'; Expected = 'FAIL'; Evidence = 'HELD ADVICE' },
    @{ Route = '/matchup/autofill'; Body = '<html><body>Start/Sit Assistant <section class="panel recommendation-panel start-sit-assistant">safe</section></body></html>'; Expected = 'FAIL'; Evidence = 'WEEK PROOF' },
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

# BF-1072: independently valid route pages cannot describe different
# leagues/weeks while the all-pages smoke claims everything agrees.
$verified = [pscustomobject]@{ Status = 'PASS'; Evidence = 'PAIRING VERIFIED' }
$held = [pscustomobject]@{ Status = 'WARN'; Evidence = 'WEEK UNVERIFIED' }
$matchupPage = '<div class="target" data-butler-matchup-season="2026">Test league &middot; Week 5</div>'
$startSitPage = '<div class="target" data-butler-matchup-season="2026">Test league &middot; Week 5</div>'
$aligned = Test-ButlerCrossRouteWeek -MatchupHtml $matchupPage -StartSitHtml $startSitPage -MatchupCheck $verified -StartSitCheck $verified
if ($aligned.Status -cne 'PASS' -or $aligned.Evidence -cne 'WEEK/LEAGUE ALIGNED') {
    throw 'BF-1072 BLOCKED: two equal, independently verified league/week pages were not aligned.'
}
foreach ($bad in @(
    ($startSitPage.Replace('Week 5', 'Week 4')),
    ($startSitPage.Replace('2026', '2025')),
    ($startSitPage.Replace('Test league', 'Other league')),
    ($startSitPage + $startSitPage),
    ($startSitPage.Replace('data-butler-matchup-season="2026"', '')),
    ($startSitPage.Replace('Test league', ''))
)) {
    $difference = Test-ButlerCrossRouteWeek -MatchupHtml $matchupPage -StartSitHtml $bad -MatchupCheck $verified -StartSitCheck $verified
    if ($difference.Status -cne 'FAIL') {
        throw 'BF-1072 BLOCKED: conflicting, duplicate or incomplete pairing appeared cross-route consistent.'
    }
}
$notBoth = Test-ButlerCrossRouteWeek -MatchupHtml $matchupPage -StartSitHtml $startSitPage -MatchupCheck $verified -StartSitCheck $held
if ($notBoth.Status -cne 'SKIP' -or $notBoth.Evidence -cne 'PAGE NOT VERIFIED') {
    throw 'BF-1072 BLOCKED: a held Start/Sit page was certified aligned.'
}
if ($source.IndexOf('Test-ButlerCrossRouteWeek -MatchupHtml', [StringComparison]::Ordinal) -lt 0 -or
    $source.IndexOf('$weekPair.Status -ceq', [StringComparison]::Ordinal) -lt 0) {
    throw 'BF-1072 BLOCKED: cross-route frame check is not enforced by the real loopback diagnostic.'
}

# BF-1053: compare the actual BF-840 rendered matchup header with only
# Sleeper's small public NFL state payload. No real Internet is used here.
$matchingMatchup = '<html><body><section class="panel butler-live-week-status" role="status" data-butler-week-state="MATCH" data-butler-week-season="2026" data-butler-week-number="5"></section><div class="target" data-butler-matchup-season="2026">Synthetic league &middot; Week 5</div><h1>Team A vs Team B</h1></body></html>'
$liveWeekFixture = '{"season":"2026","season_type":"regular","week":5,"leg":5,"display_week":5}'
$matchingWeek = Test-ButlerSleeperWeekMatch -MatchupHtml $matchingMatchup -PublicNflState $liveWeekFixture
if ($matchingWeek.Status -cne 'PASS' -or $matchingWeek.Evidence -cne 'WEEK MATCH') {
    throw 'BF-1053 BLOCKED: matching external NFL week and exact matchup header were rejected.'
}
foreach ($badComparison in @(
    @{ Html = $matchingMatchup.Replace('Week 5', 'Week 4'); Json = $liveWeekFixture; Expected = 'WEEK UNVERIFIED' },
    @{ Html = $matchingMatchup.Replace('data-butler-matchup-season="2026"', 'data-butler-matchup-season="2025"'); Json = $liveWeekFixture; Expected = 'WEEK UNVERIFIED' },
    @{ Html = $matchingMatchup.Replace('data-butler-week-number="5"', 'data-butler-week-number="4"'); Json = $liveWeekFixture; Expected = 'WEEK UNVERIFIED' },
    @{ Html = $matchingMatchup.Replace('data-butler-week-state="MATCH"', 'data-butler-week-state="UNVERIFIED"'); Json = $liveWeekFixture; Expected = 'WEEK UNVERIFIED' },
    @{ Html = $matchingMatchup.Replace('data-butler-week-season="2026"', 'data-butler-week-season="2025"'); Json = $liveWeekFixture; Expected = 'WEEK UNVERIFIED' },
    @{ Html = $matchingMatchup.Replace(' data-butler-week-number="5"', ''); Json = $liveWeekFixture; Expected = 'WEEK UNVERIFIED' },
    @{ Html = $matchingMatchup.Replace(' data-butler-week-season="2026"', ''); Json = $liveWeekFixture; Expected = 'WEEK UNVERIFIED' },
    @{ Html = $matchingMatchup.Replace(' data-butler-week-state="MATCH"', ''); Json = $liveWeekFixture; Expected = 'WEEK UNVERIFIED' },
    @{ Html = ($matchingMatchup + '<div data-butler-week-number="5"></div>'); Json = $liveWeekFixture; Expected = 'WEEK UNVERIFIED' },
    @{ Html = $matchingMatchup; Json = '{"season":"2026","season_type":"regular","week":6}'; Expected = 'WEEK MISMATCH' },
    @{ Html = $matchingMatchup; Json = '{"season":"2025","season_type":"regular","week":5}'; Expected = 'WEEK MISMATCH' },
    @{ Html = $matchingMatchup.Replace(' data-butler-matchup-season="2026"', ''); Json = $liveWeekFixture; Expected = 'WEEK UNVERIFIED' },
    @{ Html = '<html><body>Sidebar: Week 5</body></html>'; Json = $liveWeekFixture; Expected = 'WEEK UNVERIFIED' },
    @{ Html = '<html><body><div class="target" data-butler-matchup-season="2026">Synthetic league &middot; Week 5</div></body></html>'; Json = $liveWeekFixture; Expected = 'WEEK UNVERIFIED' },
    @{ Html = ($matchingMatchup + $matchingMatchup); Json = $liveWeekFixture; Expected = 'WEEK UNVERIFIED' },
    @{ Html = $matchingMatchup; Json = '{"season":"2026","season_type":"post","week":5}'; Expected = 'WEEK UNVERIFIED' },
    @{ Html = $matchingMatchup; Json = '{"season":"2026","season_type":"regular","week":25}'; Expected = 'WEEK UNVERIFIED' },
    @{ Html = $matchingMatchup; Json = '{"season":"2026","season_type":"regular","week":"5x"}'; Expected = 'WEEK UNVERIFIED' },
    @{ Html = $matchingMatchup; Json = '{"season":"2026","season_type":"regular","week":5'; Expected = 'WEEK UNVERIFIED' },
    @{ Html = $matchingMatchup; Json = '{"season":"2026","season_type":"regular"}'; Expected = 'WEEK UNVERIFIED' }
)) {
    $check = Test-ButlerSleeperWeekMatch -MatchupHtml $badComparison.Html -PublicNflState $badComparison.Json
    if ($check.Status -cne 'WARN' -or $check.Evidence -cne $badComparison.Expected) {
        throw "BF-1053 BLOCKED: invalid/old NFL matchup fixture was incorrectly marked current ($($badComparison.Expected))."
    }
}
foreach ($required in @(
    "'https://api.sleeper.app/v1/state/nfl'",
    '$request.Method = ''GET''',
    '$request.AllowAutoRedirect = $false',
    '[switch]$CheckSleeperWeek',
    'Test-ButlerSleeperWeekMatch -MatchupHtml $html -PublicNflState $nflState'
)) {
    if ($source.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-1053 BLOCKED: opt-in bounded public NFL week contract missing: $required"
    }
}

# BF-1078: default synthetic diagnostics still distinguish FAIL from
# WARN without changing their exit behavior; real-league readiness is
# stricter and signals WARN as OS exit 2, not a false 0/SUCCESS.
foreach ($required in @(
    '[switch]$RequireReady',
    'if ($RequireReady)',
    "Write-Host 'READINESS GATE: BLOCKED BY WARNINGS. No lineup/waiver advice certified.'",
    'exit 2',
    'if ($failed -gt 0)'
)) {
    if ($source.IndexOf($required, [StringComparison]::Ordinal) -lt 0) {
        throw "BF-1078 BLOCKED: optional fail-closed readiness exit contract missing: $required"
    }
}
if ($realLeagueWrapper.IndexOf('-CheckSleeperWeek -RequireReady -TimeoutSeconds 30',
    [StringComparison]::Ordinal) -lt 0) {
    throw 'BF-1078 BLOCKED: real-league one-click diagnostic did not opt into strict readiness.'
}

# BF-1077: diagnostic must measure local GET time without writing response
# bodies, player names, account IDs or any Sleeper payload into the report.
foreach ($required in @(
    '$routeTimer = [Diagnostics.Stopwatch]::StartNew()',
    '$routeTimer.Stop()',
    'get_ms={4}',
    '$localCheckStarted.Elapsed.TotalMilliseconds',
    'data-butler-week-season=',
    'data-butler-matchup-season='
)) {
    if ($source.IndexOf($required, [StringComparison]::Ordinal) -lt 0) {
        throw "BF-1077 BLOCKED: one-click GET timing/source proof contract missing: $required"
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
