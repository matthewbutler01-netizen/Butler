param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-999 BLOCKED: staged Butler core not found at $CorePath"
}

function Get-Bf999ParsedAst {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Contract
    )

    $tokens = $null
    $errors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseInput($Text, [ref]$tokens, [ref]$errors)
    if (@($errors).Count -gt 0) {
        $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
        throw "BF-999 BLOCKED: $Contract failed PowerShell parse: $summary"
    }
    return $ast
}

function Get-Bf999OneFunction {
    param(
        [Parameter(Mandatory = $true)]$Ast,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Contract
    )

    $matches = @($Ast.FindAll({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq $Name
    }, $true))
    if ($matches.Count -ne 1) {
        throw "BF-999 BLOCKED: $Contract expected one $Name function, found $($matches.Count)."
    }
    return $matches[0]
}

function Replace-Bf999ExactlyOnce {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Old,
        [Parameter(Mandatory = $true)][string]$New,
        [Parameter(Mandatory = $true)][string]$Contract
    )

    $count = [regex]::Matches($Text, [regex]::Escape($Old)).Count
    if ($count -ne 1) {
        throw "BF-999 BLOCKED: $Contract expected one match, found $count."
    }
    return $Text.Replace($Old, $New)
}

$core = [IO.File]::ReadAllText($CorePath)
$ast = Get-Bf999ParsedAst -Text $core -Contract 'pre-transform staged core'
$searchFn = Get-Bf999OneFunction -Ast $ast -Name 'ConvertTo-PlayerSearchHtml' -Contract 'Player Search renderer'
$search = $searchFn.Extent.Text

foreach ($required in @(
    '$searchReturnHref = [System.Uri]::EscapeDataString([string]$Query)',
    '$resultWaiverAction',
    'href=`"/player?id=$hrefId&from=players&q=$searchReturnHref`">View Player Detail</a>',
    'href=`"/compare?left=$hrefId`">Compare this player</a>',
    'Scout franchise',
    'Quick position searches',
    'Free agents remain on Waiver Board.'
)) {
    if ($search.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-999 BLOCKED: finalized Player Search capability is missing: $required"
    }
}

$actionsOld = '<div class=`"button-row`" style=`"margin-top:12px`"><a class=`"btn btn-secondary`" href=`"/player?id=$hrefId&from=players&q=$searchReturnHref`">View Player Detail</a><a class=`"btn btn-secondary`" href=`"/compare?left=$hrefId`">Compare this player</a><a class=`"btn btn-secondary`" href=`"/franchise?id=$([System.Uri]::EscapeDataString([string]$player.OwnerTeamId))`">Scout franchise</a>$resultWaiverAction</div></article>'
$actionsNew = '<div class=`"player-search-primary-action`"><div><span class=`"eyebrow`">Primary drill-down</span><strong>Open this exact player</strong><p class=`"meta`">Player Detail keeps the exact player ID and returns to this search query.</p></div><a class=`"btn btn-primary`" href=`"/player?id=$hrefId&from=players&q=$searchReturnHref`">View Player Detail</a></div><div class=`"button-row player-search-secondary-actions`"><a class=`"btn btn-secondary`" href=`"/compare?left=$hrefId`">Compare this player</a><a class=`"btn btn-secondary`" href=`"/franchise?id=$([System.Uri]::EscapeDataString([string]$player.OwnerTeamId))`">Scout franchise</a>$resultWaiverAction</div></article>'

$search = Replace-Bf999ExactlyOnce -Text $search -Old $actionsOld -New $actionsNew -Contract 'Player Search result action hierarchy'
$core = $core.Substring(0, $searchFn.Extent.StartOffset) + $search + $core.Substring($searchFn.Extent.EndOffset)

$cssStart = $core.IndexOf('function Get-AppCss {', [System.StringComparison]::Ordinal)
$cssEnd = $core.IndexOf('function Get-AppNav {', $cssStart, [System.StringComparison]::Ordinal)
if ($cssStart -lt 0 -or $cssEnd -le $cssStart) {
    throw 'BF-999 BLOCKED: manager CSS boundary is missing.'
}
$cssBlock = $core.Substring($cssStart, $cssEnd - $cssStart)
$cssTerminator = $cssBlock.LastIndexOf("'@", [System.StringComparison]::Ordinal)
if ($cssTerminator -lt 0) {
    throw 'BF-999 BLOCKED: manager CSS terminator is missing.'
}

$bf999Css = @'
/* BF-999 Player Search result hierarchy. */
.player-search-primary-action{display:flex;justify-content:space-between;gap:14px;align-items:center;margin-top:14px;padding:12px 13px;border:1px solid var(--line);border-left:3px solid var(--turf);border-radius:10px;background:var(--surface)}.player-search-primary-action strong{display:block;margin:3px 0 2px;font-family:var(--font-display);font-size:16px;color:var(--ink)}.player-search-primary-action p{margin:0}.player-search-secondary-actions{margin-top:8px}.player-search-secondary-actions .btn{font-size:11px}@media(max-width:760px){.player-search-primary-action{display:grid;grid-template-columns:1fr}.player-search-primary-action .btn{width:100%;text-align:center}.player-search-secondary-actions{display:grid;grid-template-columns:1fr}.player-search-secondary-actions .btn{width:100%;text-align:center}}
'@

$cssBlock = $cssBlock.Substring(0, $cssTerminator) + [Environment]::NewLine + $bf999Css.TrimEnd() + [Environment]::NewLine + $cssBlock.Substring($cssTerminator)
$core = $core.Substring(0, $cssStart) + $cssBlock + $core.Substring($cssEnd)

$finalAst = Get-Bf999ParsedAst -Text $core -Contract 'generated staged core'
$installed = Get-Bf999OneFunction -Ast $finalAst -Name 'ConvertTo-PlayerSearchHtml' -Contract 'action-first Player Search renderer'
$installedText = $installed.Extent.Text

foreach ($required in @(
    '<div class=`"player-search-primary-action`">',
    '<span class=`"eyebrow`">Primary drill-down</span>',
    '<strong>Open this exact player</strong>',
    'Player Detail keeps the exact player ID and returns to this search query.',
    'class=`"btn btn-primary`" href=`"/player?id=$hrefId&from=players&q=$searchReturnHref`">View Player Detail</a>',
    '<div class=`"button-row player-search-secondary-actions`">',
    'href=`"/compare?left=$hrefId`">Compare this player</a>',
    'Scout franchise',
    '$resultWaiverAction'
)) {
    if ($installedText.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-999 BLOCKED: Player Search result-hierarchy marker is missing: $required"
    }
}

if ($core.IndexOf('BF-999 Player Search result hierarchy.', [System.StringComparison]::Ordinal) -lt 0) {
    throw 'BF-999 BLOCKED: Player Search result-hierarchy CSS was not installed.'
}

$surface = $installedText + [Environment]::NewLine + $bf999Css
if ($surface -match 'Invoke-RestMethod|Invoke-WebRequest|Invoke-ButlerReadOnly|Invoke-Bf742DashboardWorkerRead|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|returnUrl|redirectUrl|javascript:') {
    throw 'BF-999 BLOCKED: Player Search result hierarchy introduced provider, backend-read, optimizer, write, or open-redirect behavior.'
}

[IO.File]::WriteAllText($CorePath, $core, [Text.UTF8Encoding]::new($false))
Write-Host 'BF-999 Player Search result hierarchy applied.'
