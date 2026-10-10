Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'butler-decision-refresh.ps1')

$token = 'a' * 64
$dashboard = '<html><body><nav class="nav" aria-label="Butler sections"></nav><div>Decision state: STALE_DO_NOT_ACT</div><div>BF-629: LIVE_ACTIONABLE_VERIFIED</div><div>BF-631: MARKET_LINEAGE_SUPERSEDED</div><div>Audit ID: 11111111-1111-1111-1111-111111111111</div></body></html>'

# Production presentation gate must explicitly grant the same guarded recovery.
$eligible = Add-DecisionRefreshControl -Html $dashboard -RequestTarget '/'
if ($eligible -notmatch 'href="/refresh"') {
    throw 'Governed stale Dashboard refresh eligibility missing.'
}
$ok = Add-AutomaticDashboardRefresh -Html $eligible -RequestTarget '/' -Token $token
if ($ok.Nonce -cnotmatch '^[0-9a-f]{64}$' -or
    $ok.Html -notmatch "window.location.replace\('/'\)" -or
    $ok.Html -notmatch "method: 'POST'" -or
    $ok.Html -notmatch "credentials: 'same-origin'" -or
    $ok.Html -notmatch 'now - previous < 300000') {
    throw 'Eligible stale Dashboard is missing governed automatic recovery.'
}

# No-transaction decisions still allow explicit manual checks, not repeated
# automatic nine-stage refreshes just because someone opens the Dashboard.
foreach ($bad in @(
    $dashboard,
    ($eligible.Replace('Decision state: STALE_DO_NOT_ACT', 'Decision state: NO_TRANSACTION_TO_ACT_ON')),
    ($eligible.Replace('Audit ID: 11111111-1111-1111-1111-111111111111', 'Audit ID: UNKNOWN')),
    ($eligible.Replace('</body>', '<div>Audit ID: 11111111-1111-1111-1111-111111111111</div></body>')),
    ($eligible.Replace('<body>', '<body><body>')),
    ($eligible.Replace('</body>', '')),
    ($eligible.Replace('<body>', '<body class="unsupported">')),
    ($eligible.Replace('<body>', '<BODY>')),
    ($eligible.Replace('</body>', '</BODY>'))
)) {
    $blocked = Add-AutomaticDashboardRefresh -Html $bad -RequestTarget '/' -Token $token
    if ($blocked.Nonce -ne '' -or $blocked.Html -cne $bad) {
        throw 'Unsafe Dashboard automatic refresh accepted.'
    }
}

$wrong = Add-AutomaticDashboardRefresh -Html $eligible -RequestTarget '/team' -Token $token
if ($wrong.Nonce -ne '' -or $wrong.Html -cne $eligible) {
    throw 'Non-Dashboard route initiated automatic refresh.'
}
$invalid = Add-AutomaticDashboardRefresh -Html $eligible -RequestTarget '/' -Token ('z' * 64)
if ($invalid.Nonce -ne '' -or $invalid.Html -cne $eligible) {
    throw 'Malformed one-use token initiated automatic refresh.'
}
Write-Host 'AUTOMATIC DASHBOARD REFRESH ACCEPTANCE: PASS (governance, state, audit, route, markup, token, cooldown)'
