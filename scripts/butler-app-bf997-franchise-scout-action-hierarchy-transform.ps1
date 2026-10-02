param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-997 BLOCKED: staged Butler core not found at $CorePath"
}

function Get-Bf997ParsedAst {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Contract
    )

    $tokens = $null
    $errors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseInput($Text, [ref]$tokens, [ref]$errors)
    if (@($errors).Count -gt 0) {
        $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
        throw "BF-997 BLOCKED: $Contract failed PowerShell parse: $summary"
    }
    return $ast
}

function Get-Bf997OneFunction {
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
        throw "BF-997 BLOCKED: $Contract expected one $Name function, found $($matches.Count)."
    }
    return $matches[0]
}

function Replace-Bf997ExactlyOnce {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Old,
        [Parameter(Mandatory = $true)][string]$New,
        [Parameter(Mandatory = $true)][string]$Contract
    )

    $count = [regex]::Matches($Text, [regex]::Escape($Old)).Count
    if ($count -ne 1) {
        throw "BF-997 BLOCKED: $Contract expected one match, found $count."
    }
    return $Text.Replace($Old, $New)
}

$core = [IO.File]::ReadAllText($CorePath)
$ast = Get-Bf997ParsedAst -Text $core -Contract 'pre-transform staged core'
$scoutFn = Get-Bf997OneFunction -Ast $ast -Name 'Add-FranchiseScoutPresentation' -Contract 'Franchise Scout presentation'
$scout = $scoutFn.Extent.Text

foreach ($required in @(
    '$teamHrefId = [System.Uri]::EscapeDataString([string]$View.TeamId)',
    '<div class="eyebrow">Manager actions</div><h2>Scout this franchise</h2>',
    'href="/trade?opponent=$teamHrefId">Open Trade Analyzer</a>',
    'href="/players">Find a player</a>',
    'href="/compare">Compare players</a>',
    'href="/league">Back to League</a>'
)) {
    if ($scout.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-997 BLOCKED: finalized Franchise Scout capability is missing: $required"
    }
}

$identityOld = @'
    $teamHrefId = [System.Uri]::EscapeDataString([string]$View.TeamId)

    $scout = @"
'@.TrimEnd()

$identityNew = @'
    $teamHrefId = [System.Uri]::EscapeDataString([string]$View.TeamId)
    $teamNameHtml = ConvertTo-HtmlText ([string]$View.TeamName)

    $scout = @"
'@.TrimEnd()

$scout = Replace-Bf997ExactlyOnce -Text $scout -Old $identityOld -New $identityNew -Contract 'selected-franchise identity'

$panelOld = @'
<section class="panel"><div class="section-head"><div><div class="eyebrow">Manager actions</div><h2>Scout this franchise</h2><p class="lede">Use this snapshot to decide where to go next. Butler keeps the franchise evidence neutral and leaves the actual decision to the dedicated workflow.</p></div></div><div class="button-row"><a class="btn btn-primary" href="/trade?opponent=$teamHrefId">Open Trade Analyzer</a><a class="btn btn-secondary" href="/players">Find a player</a><a class="btn btn-secondary" href="/compare">Compare players</a><a class="btn btn-secondary" href="/league">Back to League</a></div></section>
'@.TrimEnd()

$panelNew = @'
<section class="panel franchise-scout-actions"><div class="section-head"><div><div class="eyebrow">Manager actions</div><h2>Scout this franchise</h2><p class="lede">You are scouting <strong>$teamNameHtml</strong>. Butler keeps the evidence neutral while the actions below preserve this exact league-team context.</p></div></div><div class="franchise-scout-lock"><span>Selected franchise</span><strong>$teamNameHtml</strong><small>Trade Analyzer keeps this exact team as the selected partner through review.</small></div><div class="franchise-scout-action-grid"><article class="franchise-scout-action franchise-scout-primary"><span class="eyebrow">Primary next step</span><h3>Build the exact deal</h3><p>Open the Trade Analyzer with this franchise preselected, then choose the exact assets on both sides.</p><a class="btn btn-primary" href="/trade?opponent=$teamHrefId">Open Trade Analyzer</a></article><article class="franchise-scout-action"><span class="eyebrow">Continue scouting</span><h3>League and player tools</h3><p>Return to the league board or inspect players without changing the selected franchise evidence on this page.</p><div class="button-row"><a class="btn btn-secondary" href="/league">Back to League</a><a class="btn btn-secondary" href="/players">Find a player</a><a class="btn btn-secondary" href="/compare">Compare players</a></div></article></div></section>
'@.TrimEnd()

$scout = Replace-Bf997ExactlyOnce -Text $scout -Old $panelOld -New $panelNew -Contract 'Franchise Scout action hierarchy'
$core = $core.Substring(0, $scoutFn.Extent.StartOffset) + $scout + $core.Substring($scoutFn.Extent.EndOffset)

$cssStart = $core.IndexOf('function Get-AppCss {', [System.StringComparison]::Ordinal)
$cssEnd = $core.IndexOf('function Get-AppNav {', $cssStart, [System.StringComparison]::Ordinal)
if ($cssStart -lt 0 -or $cssEnd -le $cssStart) {
    throw 'BF-997 BLOCKED: manager CSS boundary is missing.'
}
$cssBlock = $core.Substring($cssStart, $cssEnd - $cssStart)
$cssTerminator = $cssBlock.LastIndexOf("'@", [System.StringComparison]::Ordinal)
if ($cssTerminator -lt 0) {
    throw 'BF-997 BLOCKED: manager CSS terminator is missing.'
}

$bf997Css = @'
/* BF-997 Franchise Scout action hierarchy. */
.franchise-scout-actions{scroll-margin-top:18px}.franchise-scout-lock{display:grid;grid-template-columns:auto minmax(0,1fr);gap:3px 10px;align-items:center;margin-top:14px;padding:12px 14px;border:1px solid var(--line);border-left:3px solid var(--turf);border-radius:10px;background:var(--surface-2)}.franchise-scout-lock span{font-size:10px;font-weight:800;text-transform:uppercase;letter-spacing:.08em;color:var(--muted)}.franchise-scout-lock strong{font-family:var(--font-display);font-size:17px;color:var(--ink)}.franchise-scout-lock small{grid-column:2;font-size:11px;color:var(--muted)}.franchise-scout-action-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;margin-top:12px}.franchise-scout-action{padding:16px;border:1px solid var(--line);border-radius:11px;background:var(--surface-2)}.franchise-scout-action h3{margin:5px 0 6px;font-family:var(--font-display);font-size:19px;color:var(--ink)}.franchise-scout-action p{margin:0 0 13px;color:var(--muted);font-size:12px;line-height:1.55}.franchise-scout-primary{border-top:3px solid var(--turf)}@media(max-width:760px){.franchise-scout-lock{grid-template-columns:1fr}.franchise-scout-lock small{grid-column:1}.franchise-scout-action-grid{grid-template-columns:1fr}.franchise-scout-action .btn{width:100%;text-align:center}.franchise-scout-action .button-row{display:grid;grid-template-columns:1fr}}
'@

$cssBlock = $cssBlock.Substring(0, $cssTerminator) + [Environment]::NewLine + $bf997Css.TrimEnd() + [Environment]::NewLine + $cssBlock.Substring($cssTerminator)
$core = $core.Substring(0, $cssStart) + $cssBlock + $core.Substring($cssEnd)

$finalAst = Get-Bf997ParsedAst -Text $core -Contract 'generated staged core'
$installedScout = Get-Bf997OneFunction -Ast $finalAst -Name 'Add-FranchiseScoutPresentation' -Contract 'action-first Franchise Scout'
$installedText = $installedScout.Extent.Text

foreach ($required in @(
    '$teamNameHtml = ConvertTo-HtmlText ([string]$View.TeamName)',
    'You are scouting <strong>$teamNameHtml</strong>.',
    '<span>Selected franchise</span><strong>$teamNameHtml</strong>',
    'Trade Analyzer keeps this exact team as the selected partner through review.',
    '<span class="eyebrow">Primary next step</span>',
    '<h3>Build the exact deal</h3>',
    'href="/trade?opponent=$teamHrefId">Open Trade Analyzer</a>',
    '<span class="eyebrow">Continue scouting</span>',
    'href="/league">Back to League</a>',
    'href="/players">Find a player</a>',
    'href="/compare">Compare players</a>'
)) {
    if ($installedText.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-997 BLOCKED: Franchise Scout action-hierarchy marker is missing: $required"
    }
}

if ($core.IndexOf('BF-997 Franchise Scout action hierarchy.', [System.StringComparison]::Ordinal) -lt 0) {
    throw 'BF-997 BLOCKED: Franchise Scout action-hierarchy CSS was not installed.'
}
if ($core.IndexOf('View franchise evidence', [System.StringComparison]::Ordinal) -lt 0) {
    throw 'BF-997 BLOCKED: compact Franchise Scout evidence disclosure regressed.'
}

$surface = $installedText + [Environment]::NewLine + $bf997Css
if ($surface -match 'Invoke-RestMethod|Invoke-WebRequest|Invoke-ButlerReadOnly|Invoke-Bf742DashboardWorkerRead|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|returnUrl|redirectUrl|javascript:') {
    throw 'BF-997 BLOCKED: Franchise Scout action hierarchy introduced provider, backend-read, optimizer, write, or open-redirect behavior.'
}

[IO.File]::WriteAllText($CorePath, $core, [Text.UTF8Encoding]::new($false))
Write-Host 'BF-997 Franchise Scout action hierarchy applied.'
