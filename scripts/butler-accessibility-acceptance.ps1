Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$tokens = $null
$errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'butler-app-request-worker.ps1'), [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw 'Request worker failed to parse.' }
$function = $ast.Find({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Add-ButlerAccessibility' }, $true)
if ($null -eq $function) { throw 'Accessibility renderer missing.' }
. ([scriptblock]::Create($function.Extent.Text))

foreach ($page in @('Dashboard', 'My Team', 'Matchup', 'Waiver Board', 'League', 'Trade Analyzer', 'History')) {
    $html = '<!doctype html><html><head><title>' + $page + '</title></head><body><main><header>Butler</header><nav class="nav" aria-label="Butler sections"><a class="active" href="/">' + $page + '</a><a href="/team">Other</a></nav><section class="panel"><h1>Decision</h1><a href="/team?x=1&amp;y=2">Details</a><pre>BF-610 BLOCKED: original evidence</pre></section></main></body></html>'
    $rendered = Add-ButlerAccessibility $html
    foreach ($expected in @('<html lang="en">', 'aria-current="page"', 'href="#butler-main-content"', 'id="butler-main-content" tabindex="-1"', '<pre>BF-610 BLOCKED: original evidence</pre>', 'href="/team?x=1&amp;y=2"')) {
        if (-not $rendered.Contains($expected)) { throw "$page missing $expected" }
    }
    if ([regex]::Matches($rendered, 'aria-current="page"').Count -ne 1) { throw "$page announced multiple current pages." }
    if ((Add-ButlerAccessibility $rendered) -cne $rendered) { throw "$page rendered twice is not stable." }
    Write-Host "ACCESSIBILITY FIXTURE PASS: $page"
}
$existing = $html.Replace('<html>', '<html lang="en-GB">').Replace('<section class="panel">', '<section id="decision" class="panel">')
$result = Add-ButlerAccessibility $existing
if (-not $result.Contains('<html lang="en-GB">') -or -not $result.Contains('href="#decision"') -or [regex]::Matches($result, 'id="decision"').Count -ne 1) { throw 'Existing language or fragment target was lost.' }
$bare = '<html><body><h1>Unavailable</h1><pre>BF-610 original detail</pre></body></html>'
if ((Add-ButlerAccessibility $bare) -cne $bare.Replace('<html>', '<html lang="en">')) { throw 'Minimal error page was changed beyond language metadata.' }
$hidden = $html.Replace('</nav>', '</nav><div hidden>Recovery detail</div><section hidden>Hidden section</section>')
$result = Add-ButlerAccessibility $hidden
if (-not $result.Contains('<section class="panel" id="butler-main-content" tabindex="-1">') -or -not $result.Contains('<section hidden>Hidden section</section>')) { throw 'Skip link targeted hidden recovery markup.' }
Write-Host 'ACCESSIBILITY FIXTURES: PASS'
