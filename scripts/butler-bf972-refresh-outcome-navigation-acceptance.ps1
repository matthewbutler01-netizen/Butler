Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$tradeHost = Join-Path $PSScriptRoot 'butler-trade-lab-host.ps1'
$tradeLab = Join-Path $PSScriptRoot 'butler-trade-lab.ps1'
$history = Join-Path $PSScriptRoot 'butler-decision-history.ps1'
$detail = Join-Path $PSScriptRoot 'butler-decision-detail.ps1'
$refresh = Join-Path $PSScriptRoot 'butler-decision-refresh.ps1'

foreach ($path in @($tradeHost, $tradeLab, $history, $detail, $refresh)) {
    $tokens = $null
    $errors = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile($path, [ref]$tokens, [ref]$errors)
    if (@($errors).Count -ne 0) {
        $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
        throw "BF-972 BLOCKED: module failed PowerShell parse: $path :: $summary"
    }
}

# Match production companion module order so Get-AppCss/Get-AppNav are real.
. $tradeHost
. $tradeLab
. $history
. $detail
. $refresh

$success = Get-DecisionRefreshSuccessHtml -LeagueId 'league-test' -ResultText 'BF-972 TEST RESULT'
foreach ($required in @(
    'Butler is up to date',
    'href="/">Return to Dashboard</a>',
    'href="/waivers">Review Waiver Board</a>',
    'href="/team">Review My Team</a>',
    'href="/history?load=1">View History</a>',
    'No changes were submitted to Sleeper.',
    'href="/players">Player Search</a>',
    'href="/compare">Player Compare</a>'
)) {
    if ($success.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-972 BLOCKED: refresh success marker is missing: $required"
    }
}
if ($success -match 'href="/history">') {
    throw 'BF-972 BLOCKED: refresh success page still contains the stale unloaded History route.'
}

$failure = Get-DecisionRefreshFailureHtml -Message 'BF-972 synthetic blocked refresh'
foreach ($required in @(
    '<div class="brand"><h1>BUTLER</h1>',
    '<div class="target">Refresh blocked safely</div>',
    '<span class="status warn">STOPPED SAFELY</span>',
    'BF-972 synthetic blocked refresh',
    'href="/refresh">Return to refresh confirmation</a>',
    'href="/waivers">Review Waiver Board</a>',
    'href="/">Dashboard</a>',
    'href="/history?load=1">History</a>'
)) {
    if ($failure.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-972 BLOCKED: refresh failure marker is missing: $required"
    }
}

$token = ('a' * 64)
$confirmation = Get-DecisionRefreshConfirmationHtml -LeagueId 'league-test' -Token $token
foreach ($required in @(
    'Refresh Butler''s data?',
    '<form method="post" action="/refresh">',
    '<input type="hidden" name="token" value="' + $token + '">',
    '<button class="refresh-button" type="submit">Confirm refresh</button>',
    '<a class="refresh-cancel" href="/">Cancel</a>',
    'Nothing will be submitted to Sleeper.',
    'CONFIRMATION REQUIRED'
)) {
    if ($confirmation.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-972 BLOCKED: refresh confirmation/token contract marker is missing: $required"
    }
}

$source = [IO.File]::ReadAllText($refresh)
foreach ($required in @(
    'function Get-DecisionRefreshSubmittedToken',
    'if ($values.Count -ne 1 -or -not $values.ContainsKey(''token''))',
    'BF-676 BLOCKED: refresh POST must contain only the one-use token.',
    'function Invoke-DecisionRefreshRunner',
    'BF-823 PROBE: RECOVERY_REQUIRED',
    'BF-823 PROBE: NO_RECOVERY_REQUIRED',
    'BF-823 PROBE: DEFER_TO_BF676'
)) {
    if ($source.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-972 BLOCKED: governed refresh safety marker is missing: $required"
    }
}

Write-Host 'BF-972 REFRESH OUTCOME NAVIGATION PARITY ACCEPTANCE: PASS'
