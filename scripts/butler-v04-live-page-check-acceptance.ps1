Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$path = Join-Path $PSScriptRoot 'butler-v04-live-page-check.ps1'
$launcher = Join-Path $PSScriptRoot 'butler-v04-live-page-check.cmd'
foreach ($requiredPath in @($path, $launcher)) {
    if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) { throw "BF-1040 BLOCKED: missing $requiredPath" }
}
$source = [IO.File]::ReadAllText($path)
$wrapper = [IO.File]::ReadAllText($launcher)
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
    Body = '{"status":"ok","service":"butler-app-shell","bind":"127.0.0.1"}'
}
if (-not (Test-ButlerLocalHealth -Response $health)) {
    throw 'BF-1040 BLOCKED: exact Butler loopback health was rejected.'
}
$health.Body = '{"status":"ok","service":"different-app","bind":"127.0.0.1"}'
if (Test-ButlerLocalHealth -Response $health) {
    throw 'BF-1040 BLOCKED: unrelated loopback service accepted.'
}
$health.Body = '{"status":"ok","service":"butler-app-shell","bind":"0.0.0.0"}'
if (Test-ButlerLocalHealth -Response $health) {
    throw 'BF-1040 BLOCKED: public bind accepted.'
}

$page = [pscustomobject]@{
    Status = 200
    Type = 'text/html; charset=utf-8'
    Cache = 'no-store'
    Csp = "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'"
    Body = '<html><body><div class="dashboard-summary-row"></div></body></html>'
}
$check = Test-ButlerLivePage -Route '/' -Response $page
if ($check.Status -cne 'PASS' -or $check.AutoCheck -cne 'NOT NEEDED/GATED') {
    throw 'BF-1040 BLOCKED: eligible Dashboard baseline was rejected.'
}
$page.Body = '<html><body><div class="dashboard-summary-row"></div><div id="butler-auto-refresh-status"></div><script nonce="' + ('a' * 64) + '">safe diagnostic</script></body></html>'
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
$page.Body = '<html><body>CURRENT WEEKLY WATCH <div id="butler-auto-refresh-status"></div><script nonce="' + ('a' * 64) + '">safe diagnostic</script></body></html>'
$page.Csp = "default-src 'none'; frame-ancestors 'none'; script-src 'nonce-" + ('a' * 64) + "'; connect-src 'self'"
$check = Test-ButlerLivePage -Route '/autopilot' -Response $page
if ($check.Status -cne 'PASS' -or $check.AutoCheck -cne 'ARMED') {
    throw 'BF-1040 BLOCKED: eligible Auto-Pilot nonce or healthy watch was rejected.'
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
Write-Host 'Coverage: exact loopback health, request scope, page presence, auto-refresh CSP, Auto-Pilot unavailable warning, GET-only command.'
