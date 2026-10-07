Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$workerPath = Join-Path $PSScriptRoot 'butler-app-request-worker.ps1'
if (-not (Test-Path -LiteralPath $workerPath -PathType Leaf)) {
    throw "BF-1029 BLOCKED: request worker missing at $workerPath"
}

$text = [IO.File]::ReadAllText($workerPath)
$tokens = $null
$errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($workerPath, [ref]$tokens, [ref]$errors)
if (@($errors).Count -ne 0) {
    $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-1029 BLOCKED: request worker parse failed: $summary"
}

foreach ($functionName in @('ConvertTo-V04StartSitRouteHtml','Add-ButlerAccessibility')) {
    $matches = @($ast.FindAll({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $functionName
    }, $true))
    if ($matches.Count -ne 1) {
        throw "BF-1029 BLOCKED: expected one $functionName function, found $($matches.Count)."
    }
    . ([scriptblock]::Create($matches[0].Extent.Text))
}

$fixture = @'
<!doctype html>
<html lang="en"><head><title>Butler - Weekly Matchup</title></head><body>
<main class="shell">
<div class="top"><div class="target">Hard(CORE)-Dynasty · Week 4</div></div>
<nav class="nav" aria-label="Butler sections"><a href="/">Dashboard</a><a class="active" href="/matchup">Matchup</a></nav>
<section class="panel hero-panel"><h1>Duplicate matchup hero</h1></section>
<section class="panel recommendation-panel start-sit-assistant">
<div class="eyebrow">Start/Sit Assistant</div>
<h2>Lineup decision blocked by an evidence gap</h2>
<details><summary>View evidence details</summary><div class="callout callout-danger">Usage source HTTP 404: https://example.invalid/usage.csv</div></details>
</section>
</main></body></html>
'@

$route = ConvertTo-V04StartSitRouteHtml -Html $fixture -RequestTarget '/matchup/autofill'

if ($route.IndexOf('Duplicate matchup hero', [System.StringComparison]::Ordinal) -ge 0) {
    throw 'BF-1029 BLOCKED: Start/Sit route still shows the duplicate Matchup hero.'
}
if ($route.IndexOf('<a class="active" href="/matchup/autofill">Start/Sit Assistant</a>', [System.StringComparison]::Ordinal) -lt 0) {
    throw 'BF-1029 BLOCKED: exact Start/Sit active-nav identity is missing.'
}
if ($route.IndexOf('<strong>Blocking evidence:</strong> Usage source HTTP 404: https://example.invalid/usage.csv', [System.StringComparison]::Ordinal) -lt 0) {
    throw 'BF-1029 BLOCKED: exact evidence blocker was not promoted into the visible Start/Sit surface.'
}
if ([regex]::Matches($route, 'Usage source HTTP 404: https://example\.invalid/usage\.csv').Count -ne 2) {
    throw 'BF-1029 BLOCKED: evidence blocker must remain both visible and in the original disclosure.'
}

$public = Add-ButlerAccessibility -Html $route
$current = [regex]::Matches($public, '<a\b[^>]*aria-current="page"[^>]*>([^<]+)</a>')
if ($current.Count -ne 1 -or $current[0].Groups[1].Value -cne 'Start/Sit Assistant') {
    throw 'BF-1029 BLOCKED: Start/Sit must be the sole current Playbook page without cross-scope route state.'
}
if ($public.IndexOf('class="playbook-start-sit active"', [System.StringComparison]::Ordinal) -lt 0) {
    throw 'BF-1029 BLOCKED: shared Playbook did not highlight Start/Sit Assistant.'
}

$ordinary = ConvertTo-V04StartSitRouteHtml -Html $fixture -RequestTarget '/matchup'
if ($ordinary -cne $fixture) {
    throw 'BF-1029 BLOCKED: ordinary Matchup presentation changed.'
}

Write-Host 'BF-1029 V0.4 START/SIT BLOCKER DIAGNOSTICS ACCEPTANCE: PASS'
Write-Host 'Coverage: exact Start/Sit current-page identity, visible exact evidence blocker, preserved disclosure traceability, duplicate hero removal, and unchanged ordinary Matchup.'
