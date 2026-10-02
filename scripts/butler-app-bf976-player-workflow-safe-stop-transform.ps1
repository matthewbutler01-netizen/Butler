param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-976 BLOCKED: staged Butler core not found at $CorePath"
}

function Replace-BlockedLine {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Title,
        [Parameter(Mandatory = $true)][string]$Replacement
    )

    $pattern = '(?m)^\s*\$errorHtml = "<!doctype html><html><body><h1>Butler ' +
        [regex]::Escape($Title) +
        '</h1>.*$'
    $matches = [regex]::Matches($Text, $pattern)
    if ($matches.Count -lt 1) {
        throw "BF-976 BLOCKED: $Title legacy error line expected at least one match, found $($matches.Count)."
    }

    for ($matchIndex = $matches.Count - 1; $matchIndex -ge 0; $matchIndex--) {
        $match = $matches[$matchIndex]
        $Text = $Text.Substring(0, $match.Index) +
            $Replacement +
            $Text.Substring($match.Index + $match.Length)
    }
    return $Text
}

$core = [System.IO.File]::ReadAllText($CorePath)

$playerSearchRouteCount = [regex]::Matches(
    $core,
    [regex]::Escape('            if ($path -eq "/players") {')
).Count
if ($playerSearchRouteCount -ne 1) {
    throw "BF-976 BLOCKED: expected exactly one Player Search route before safe-stop normalization, found $playerSearchRouteCount."
}

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseInput($core, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $summary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-976 BLOCKED: staged core failed pre-transform parse: $summary"
}

$helperMarker = 'function Get-PlayerCompareRequest {'
$helperIndex = $core.IndexOf($helperMarker, [System.StringComparison]::Ordinal)
if ($helperIndex -lt 0) {
    throw 'BF-976 BLOCKED: Player Compare helper insertion marker is missing.'
}

$helper = @'
function Get-PlayerWorkflowBlockedHtml {
    param(
        [Parameter(Mandatory = $true)][string]$Title,
        [Parameter(Mandatory = $true)][string]$Message,
        [Parameter(Mandatory = $true)][string]$Active,
        [Parameter(Mandatory = $true)][string]$PrimaryHref,
        [Parameter(Mandatory = $true)][string]$PrimaryLabel,
        [string]$SecondaryHref = '/',
        [string]$SecondaryLabel = 'Dashboard'
    )

    $css = Get-AppCss
    $nav = Get-AppNav -Active $Active
    $safeTitle = ConvertTo-HtmlText $Title
    $safeMessage = ConvertTo-HtmlText $Message
    $safePrimaryHref = ConvertTo-HtmlText $PrimaryHref
    $safePrimaryLabel = ConvertTo-HtmlText $PrimaryLabel
    $safeSecondaryHref = ConvertTo-HtmlText $SecondaryHref
    $safeSecondaryLabel = ConvertTo-HtmlText $SecondaryLabel

    return @"
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="color-scheme" content="dark">
<title>Butler - $safeTitle</title>
<style>$css
.player-blocked-actions{display:flex;gap:12px;align-items:center;flex-wrap:wrap;margin-top:18px}.player-blocked-actions a{display:inline-block;border:1px solid var(--line);border-radius:10px;padding:10px 14px;text-decoration:none;font-weight:700}.player-blocked-detail{margin-top:16px;white-space:pre-wrap;overflow-wrap:anywhere}
</style>
</head>
<body>
<main class="shell">
<header class="top"><div class="brand"><h1>BUTLER</h1><p>We're here to serve you. Less Research. Better Decisions.</p></div><div class="target">Player workflow stopped safely</div></header>
$nav
<section class="panel">
<div class="eyebrow">Safe stop</div>
<div class="statusrow"><div><h1 class="headline">$safeTitle</h1><p class="lede">Butler stopped this request instead of guessing or continuing with an invalid player context.</p></div><span class="status warn">STOPPED SAFELY</span></div>
<pre class="player-blocked-detail">$safeMessage</pre>
<p>No provider refresh, Butler write, or Sleeper write was executed.</p>
<div class="player-blocked-actions"><a href="$safePrimaryHref">$safePrimaryLabel</a><a href="$safeSecondaryHref">$safeSecondaryLabel</a></div>
</section>
</main>
</body>
</html>
"@
}

'@

$core = $core.Insert($helperIndex, $helper)

$detailReplacement = @'
                    $errorHtml = Get-PlayerWorkflowBlockedHtml -Title 'Player Detail blocked' -Message $_.Exception.Message -Active 'players' -PrimaryHref '/players' -PrimaryLabel 'Back to Player Search' -SecondaryHref '/team' -SecondaryLabel 'My Team'
'@.TrimEnd()
$core = Replace-BlockedLine -Text $core -Title 'Player Detail blocked' -Replacement $detailReplacement

$searchReplacement = @'
                    $errorHtml = Get-PlayerWorkflowBlockedHtml -Title 'Player Search blocked' -Message $_.Exception.Message -Active 'players' -PrimaryHref '/players' -PrimaryLabel 'Back to Player Search'
'@.TrimEnd()
$core = Replace-BlockedLine -Text $core -Title 'Player Search blocked' -Replacement $searchReplacement

$compareReplacement = @'
                    $errorHtml = Get-PlayerWorkflowBlockedHtml -Title 'Player Compare blocked' -Message $_.Exception.Message -Active 'compare' -PrimaryHref '/compare' -PrimaryLabel 'Back to Player Compare' -SecondaryHref '/players' -SecondaryLabel 'Player Search'
'@.TrimEnd()
$core = Replace-BlockedLine -Text $core -Title 'Player Compare blocked' -Replacement $compareReplacement

$tokens = $null
$parseErrors = $null
$finalAst = [System.Management.Automation.Language.Parser]::ParseInput($core, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $summary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-976 BLOCKED: generated staged core failed PowerShell parse: $summary"
}

$helperDefinitionCount = [regex]::Matches(
    $core,
    '(?m)^function Get-PlayerWorkflowBlockedHtml\s*\{'
).Count
if ($helperDefinitionCount -ne 1) {
    throw "BF-976 BLOCKED: expected exactly one installed Player workflow blocked renderer, found $helperDefinitionCount."
}

foreach ($required in @(
    "Get-PlayerWorkflowBlockedHtml -Title 'Player Detail blocked'",
    "Get-PlayerWorkflowBlockedHtml -Title 'Player Search blocked'",
    "Get-PlayerWorkflowBlockedHtml -Title 'Player Compare blocked'",
    'STOPPED SAFELY',
    'No provider refresh, Butler write, or Sleeper write was executed.'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-976 BLOCKED: installed player-workflow safe-stop marker is missing: $required"
    }
}

foreach ($legacy in @(
    '<!doctype html><html><body><h1>Butler Player Detail blocked</h1>',
    '<!doctype html><html><body><h1>Butler Player Search blocked</h1>',
    '<!doctype html><html><body><h1>Butler Player Compare blocked</h1>'
)) {
    if ($core.IndexOf($legacy, [System.StringComparison]::Ordinal) -ge 0) {
        throw "BF-976 BLOCKED: legacy bare player-workflow error page remains: $legacy"
    }
}

$finalPlayerSearchRouteCount = [regex]::Matches(
    $core,
    [regex]::Escape('            if ($path -eq "/players") {')
).Count
if ($finalPlayerSearchRouteCount -ne 1) {
    throw "BF-976 BLOCKED: Player Search route count changed during safe-stop normalization; found $finalPlayerSearchRouteCount."
}

if ($helper -match 'Invoke-RestMethod|Invoke-WebRequest|Invoke-ButlerReadOnly|Invoke-Bf742DashboardWorkerRead|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
    throw 'BF-976 BLOCKED: Player workflow safe-stop renderer introduced provider, backend-read, optimizer, FAAB, or write behavior.'
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))
Write-Host 'BF-976 Player workflow safe-stop pages applied.'

$bf977Transform = Join-Path $PSScriptRoot 'butler-app-bf977-player-compare-return-context-transform.ps1'
if (-not (Test-Path -LiteralPath $bf977Transform -PathType Leaf)) {
    throw "BF-977 BLOCKED: Player Compare return-context transform not found at $bf977Transform"
}
& $bf977Transform -CorePath $CorePath
