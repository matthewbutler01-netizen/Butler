Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$workerPath = Join-Path $PSScriptRoot 'butler-app-request-worker.ps1'
if (-not (Test-Path -LiteralPath $workerPath -PathType Leaf)) {
    throw "BF-1031 BLOCKED: request worker missing at $workerPath"
}

$text = [IO.File]::ReadAllText($workerPath)
$tokens = $null
$errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($workerPath, [ref]$tokens, [ref]$errors)
if (@($errors).Count -ne 0) {
    $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-1031 BLOCKED: request worker parse failed: $summary"
}

$matches = @($ast.FindAll({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
    $node.Name -eq 'ConvertTo-V04StartSitRouteHtml'
}, $true))
if ($matches.Count -ne 1) {
    throw "BF-1031 BLOCKED: expected one ConvertTo-V04StartSitRouteHtml function, found $($matches.Count)."
}
. ([scriptblock]::Create($matches[0].Extent.Text))

$fixture = @'
<!doctype html><html lang="en"><head><title>Butler - Weekly Matchup</title></head><body>
<main class="shell">
<nav class="nav" aria-label="Butler sections"><a href="/">Dashboard</a><a class="active" href="/matchup">Matchup</a></nav>
<section class="panel hero-panel"><h1>Duplicate matchup hero</h1></section>
<section class="panel recommendation-panel start-sit-assistant">
<div class="eyebrow">Start/Sit Assistant</div>
<h2>Lineup decision blocked by an evidence gap</h2>
<div class="button-row"><a class="btn btn-primary" href="/matchup/autofill">Retry Lineup Review</a><a class="btn btn-secondary" href="/team/autofill">Refresh projection</a><a class="btn btn-secondary" href="/matchup">Back to Matchup</a></div>
</section>
</main></body></html>
'@

$route = ConvertTo-V04StartSitRouteHtml -Html $fixture -RequestTarget '/matchup/autofill'

foreach ($forbidden in @(
    'Retry Lineup Review',
    'Refresh projection',
    'Duplicate matchup hero'
)) {
    if ($route.IndexOf($forbidden, [System.StringComparison]::Ordinal) -ge 0) {
        throw "BF-1031 BLOCKED: legacy manual retry/reload marker remains: $forbidden"
    }
}

foreach ($required in @(
    'class="active" href="/matchup/autofill">Start/Sit Assistant</a>',
    'start-sit-auto-recheck',
    'This page reruns current read-only lineup evidence every time it loads.',
    'Reloading the page is enough; no manual retry is required.',
    'href="/matchup">Back to Matchup</a>'
)) {
    if ($route.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-1031 BLOCKED: Start/Sit auto-recheck marker missing: $required"
    }
}

$ordinary = ConvertTo-V04StartSitRouteHtml -Html $fixture -RequestTarget '/matchup'
if ($ordinary -cne $fixture) {
    throw 'BF-1031 BLOCKED: ordinary Matchup presentation changed.'
}

foreach ($required in @(
    "elseif (`$requestTarget -ceq '/matchup/autofill')",
    'Invoke-AppCoreGet -Port $InnerPort -RequestTarget $requestTarget',
    'Cache-Control: no-store'
)) {
    if ($text.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-1031 BLOCKED: reload freshness contract missing: $required"
    }
}

$start = $text.IndexOf('function ConvertTo-V04StartSitRouteHtml {', [System.StringComparison]::Ordinal)
$end = $text.IndexOf('function Get-ButlerBlockedPageHtml {', $start, [System.StringComparison]::Ordinal)
$surface = $text.Substring($start, $end - $start)
if ($surface -match 'Invoke-DecisionRefreshRunner|Method = "POST"|submitTransaction|setFaab|create_transaction') {
    throw 'BF-1031 BLOCKED: Start/Sit reload path introduced local refresh writes or Sleeper execution.'
}

Write-Host 'BF-1031 V0.4 START/SIT AUTO-RECHECK ACCEPTANCE: PASS'
Write-Host 'Coverage: every exact Start/Sit reload goes to the current read-only core path, no-store response contract, legacy retry/refresh links removed, ordinary Matchup unchanged, and no write behavior.'