param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-1001 BLOCKED: staged Butler core not found at $CorePath"
}

function Replace-Bf1001ExactlyOnce {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Old,
        [Parameter(Mandatory = $true)][string]$New,
        [Parameter(Mandatory = $true)][string]$Contract
    )

    $count = [regex]::Matches($Text, [regex]::Escape($Old)).Count
    if ($count -ne 1) {
        throw "BF-1001 BLOCKED: $Contract expected one match, found $count."
    }
    return $Text.Replace($Old, $New)
}

$core = [IO.File]::ReadAllText($CorePath)
$tokens = $null
$errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseInput($core, [ref]$tokens, [ref]$errors)
if (@($errors).Count -gt 0) {
    $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-1001 BLOCKED: staged core failed PowerShell parse: $summary"
}

$functions = @($ast.FindAll({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
    $node.Name -eq 'Add-PlayerHubPresentation'
}, $true))
if ($functions.Count -ne 1) {
    throw "BF-1001 BLOCKED: expected one Player Hub presentation function, found $($functions.Count)."
}

$start = $functions[0].Extent.StartOffset
$end = $functions[0].Extent.EndOffset
$hub = $core.Substring($start, $end - $start)

$old = '<div class="button-row"><a class="btn btn-primary" href="/matchup">Review Matchup</a><a class="btn btn-secondary" href="/compare?left=$hrefId">Compare this player</a><a class="btn btn-secondary" href="/players?q=$positionHref">Find more $(ConvertTo-HtmlText $View.Position)</a><a class="btn btn-secondary" href="/franchise?id=$teamHrefId">Scout franchise</a><a class="btn btn-secondary" href="/trade">Open Trade Analyzer</a>$waiverAction</div>'
$new = '<div class="player-hub-primary-action"><div><span class="eyebrow">Primary player action</span><strong>Compare this exact player</strong><p class="meta">Use this player as the fixed first side of a neutral Butler comparison.</p></div><a class="btn btn-primary" href="/compare?left=$hrefId">Compare this player</a></div><div class="button-row player-hub-secondary-actions"><a class="btn btn-secondary" href="/players?q=$positionHref">Find more $(ConvertTo-HtmlText $View.Position)</a>$waiverAction<a class="btn btn-secondary" href="/franchise?id=$teamHrefId">Scout franchise</a><a class="btn btn-secondary" href="/trade">Open Trade Analyzer</a><a class="btn btn-secondary" href="/matchup">Review Matchup</a></div>'

$hub = Replace-Bf1001ExactlyOnce -Text $hub -Old $old -New $new -Contract 'Player Hub action hierarchy'
$core = $core.Substring(0, $start) + $hub + $core.Substring($end)

$cssStart = $core.IndexOf('function Get-AppCss {', [System.StringComparison]::Ordinal)
$cssEnd = $core.IndexOf('function Get-AppNav {', $cssStart, [System.StringComparison]::Ordinal)
if ($cssStart -lt 0 -or $cssEnd -le $cssStart) {
    throw 'BF-1001 BLOCKED: manager CSS boundary is missing.'
}
$cssBlock = $core.Substring($cssStart, $cssEnd - $cssStart)
$cssTerminator = $cssBlock.LastIndexOf("'@", [System.StringComparison]::Ordinal)
if ($cssTerminator -lt 0) {
    throw 'BF-1001 BLOCKED: manager CSS terminator is missing.'
}

$bf1001Css = @'
/* BF-1001 Player Hub action hierarchy. */
.player-hub-primary-action{display:flex;justify-content:space-between;gap:14px;align-items:center;margin-top:14px;padding:13px 14px;border:1px solid var(--line);border-left:3px solid var(--turf);border-radius:10px;background:var(--surface-2)}.player-hub-primary-action strong{display:block;margin:3px 0 2px;font-family:var(--font-display);font-size:17px;color:var(--ink)}.player-hub-primary-action p{margin:0}.player-hub-secondary-actions{margin-top:9px}.player-hub-secondary-actions .btn{font-size:11px}@media(max-width:760px){.player-hub-primary-action{display:grid;grid-template-columns:1fr}.player-hub-primary-action .btn{width:100%;text-align:center}.player-hub-secondary-actions{display:grid;grid-template-columns:1fr}.player-hub-secondary-actions .btn{width:100%;text-align:center}}
'@

$cssBlock = $cssBlock.Substring(0, $cssTerminator) + [Environment]::NewLine + $bf1001Css.TrimEnd() + [Environment]::NewLine + $cssBlock.Substring($cssTerminator)
$core = $core.Substring(0, $cssStart) + $cssBlock + $core.Substring($cssEnd)

$tokens = $null
$errors = $null
[void][System.Management.Automation.Language.Parser]::ParseInput($core, [ref]$tokens, [ref]$errors)
if (@($errors).Count -gt 0) {
    $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-1001 BLOCKED: generated staged core failed PowerShell parse: $summary"
}

foreach ($required in @(
    '<div class="player-hub-primary-action">',
    'Primary player action',
    'Compare this exact player',
    'href="/compare?left=$hrefId">Compare this player</a>',
    '<div class="button-row player-hub-secondary-actions">',
    'href="/players?q=$positionHref">Find more $(ConvertTo-HtmlText $View.Position)</a>',
    '$waiverAction',
    'href="/franchise?id=$teamHrefId">Scout franchise</a>',
    'href="/trade">Open Trade Analyzer</a>',
    'href="/matchup">Review Matchup</a>',
    'BF-1001 Player Hub action hierarchy.'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-1001 BLOCKED: Player Hub hierarchy marker is missing: $required"
    }
}

$surface = $hub + [Environment]::NewLine + $bf1001Css
if ($surface -match 'Invoke-RestMethod|Invoke-WebRequest|Invoke-ButlerReadOnly|Invoke-Bf742DashboardWorkerRead|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|returnUrl|redirectUrl|javascript:') {
    throw 'BF-1001 BLOCKED: Player Hub action hierarchy introduced provider, backend-read, optimizer, write, or open-redirect behavior.'
}

[IO.File]::WriteAllText($CorePath, $core, [Text.UTF8Encoding]::new($false))
Write-Host 'BF-1001 Player Hub action hierarchy applied.'
