Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'butler-decision-refresh.ps1')

# BF-1104: this is a read-only on-open Start/Sit source blocker and an
# independently eligible stale Dashboard audit. The page may only request
# the existing localhost governed evidence refresh, never a Sleeper move.
$token = 'a' * 64
$blocked = '<html><body><section class="panel recommendation-panel start-sit-assistant"><div class="callout callout-danger start-sit-blocker"><strong>Blocking evidence:</strong> No complete source</div></section></body></html>'
$dashboard = '<html><body><nav class="nav" aria-label="Butler sections"></nav><div>Decision state: STALE_DO_NOT_ACT</div><div>BF-629: LIVE_ACTIONABLE_VERIFIED</div><div>BF-631: MARKET_LINEAGE_SUPERSEDED</div><div>Audit ID: 11111111-1111-1111-1111-111111111111</div></body></html>'
$ok = Add-AutomaticStartSitRefresh -Html $blocked -RequestTarget '/matchup/autofill' -DashboardHtml $dashboard -Token $token
foreach ($necessary in @(
    'id="butler-auto-refresh-status"',
    'butler-auto-refresh:/:11111111-1111-1111-1111-111111111111',
    "fetch('/refresh'",
    "method: 'POST'",
    "credentials: 'same-origin'",
    'token=' + $token,
    "window.location.replace('/matchup/autofill')",
    "setItem(key, 'p:'",
    "setItem(key, 'f:'",
    "setItem(key, 's:'"
)) {
    if ($ok.Html.IndexOf($necessary, [StringComparison]::Ordinal) -lt 0) {
        throw "BF-1104 BLOCKED: missing existing source-governed Start/Sit on-open contract: $necessary"
    }
}
if ($ok.Nonce -cnotmatch '^[0-9a-f]{64}$' -or
    [regex]::Matches($ok.Html, '<script nonce=').Count -ne 1) {
    throw 'BF-1104 BLOCKED: source-governed Start/Sit script nonce absent or repeated.'
}

$keep = $blocked.Replace('class="callout callout-danger start-sit-blocker"', 'class="callout callout-info"')
if (Test-AutomaticStartSitRefreshCandidate -Html $keep -RequestTarget '/matchup/autofill') {
    throw 'BF-1104 BLOCKED: unblocked Start/Sit became eligible for automatic read/write.'
}
foreach ($source in @(
    $keep,
    ($blocked + $blocked),
    ($blocked.Replace('start-sit-assistant', 'other-page')),
    ($blocked.Replace('callout callout-danger start-sit-blocker', 'callout callout-warn')),
    ($blocked.Replace('<body>', '<BODY>')),
    ($blocked.Replace('</body>', '</BODY>')),
    ($blocked.Replace('<body>', '<body><body>'))
)) {
    $denied = Add-AutomaticStartSitRefresh -Html $source -RequestTarget '/matchup/autofill' -DashboardHtml $dashboard -Token $token
    if ($denied.Nonce -ne '' -or $denied.Html -cne $source) {
        throw 'BF-1104 BLOCKED: missing, duplicate, or invalid Start/Sit source armed an update.'
    }
}
foreach ($bad in @(
    '',
    ($dashboard.Replace('STALE_DO_NOT_ACT', 'NO_TRANSACTION_TO_ACT_ON').Replace('LIVE_ACTIONABLE_VERIFIED', 'NO_TRANSACTION_TO_REVALIDATE').Replace('MARKET_LINEAGE_SUPERSEDED','LATEST_EVIDENCE_LINEAGE_VERIFIED')),
    ($dashboard.Replace('LIVE_ACTIONABLE_VERIFIED', 'BLOCKED')),
    ($dashboard.Replace('MARKET_LINEAGE_SUPERSEDED', 'UNKNOWN')),
    ($dashboard.Replace('Audit ID: 11111111-1111-1111-1111-111111111111', 'Audit ID: UNKNOWN')),
    ($dashboard.Replace('</body>','<div>Audit ID: 11111111-1111-1111-1111-111111111111</div></body>'))
)) {
    $denied = Add-AutomaticStartSitRefresh -Html $blocked -RequestTarget '/matchup/autofill' -DashboardHtml $bad -Token $token
    if ($denied.Nonce -ne '' -or $denied.Html -cne $blocked) {
        throw 'BF-1104 BLOCKED: unsupported Dashboard audit authorized Start/Sit update.'
    }
}
foreach ($route in @('/matchup', '/matchup/autofill?refresh=1', '/team', '/autopilot')) {
    $denied = Add-AutomaticStartSitRefresh -Html $blocked -RequestTarget $route -DashboardHtml $dashboard -Token $token
    if ($denied.Nonce -ne '' -or $denied.Html -cne $blocked) {
        throw 'BF-1104 BLOCKED: a noncanonical route armed the optional Start/Sit script.'
    }
}
$invalid = Add-AutomaticStartSitRefresh -Html $blocked -RequestTarget '/matchup/autofill' -DashboardHtml $dashboard -Token ('z' * 64)
if ($invalid.Nonce -ne '' -or $invalid.Html -cne $blocked) {
    throw 'BF-1104 BLOCKED: malformed one-use token enabled source recovery.'
}

$worker = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'butler-app-request-worker.ps1'))
foreach ($necessary in @(
    'Test-AutomaticStartSitRefreshCandidate -Html ([string]$body)',
    'Invoke-AppCoreGet -Port $InnerPort -RequestTarget ''/'' -TimeoutMs 12000',
    'Add-AutomaticStartSitRefresh -Html ([string]$body)',
    '-ScriptNonce $autoRefresh.Nonce'
)) {
    if ($worker.IndexOf($necessary, [StringComparison]::Ordinal) -lt 0) {
        throw "BF-1104 BLOCKED: optional production Start/Sit route is missing: $necessary"
    }
}
Write-Host 'BF-1104 SOURCED START/SIT ON-OPEN REFRESH: PASS'
Write-Host 'Coverage: exact blocked source, independent governed Dashboard eligibility, shared audit, one-use token, nonce, bounded read, no other routes/healthy page/Sleeper writes.'
