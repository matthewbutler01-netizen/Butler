param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-1000 BLOCKED: staged Butler core not found at $CorePath"
}

function Replace-Bf1000ExactlyOnce {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Old,
        [Parameter(Mandatory = $true)][string]$New,
        [Parameter(Mandatory = $true)][string]$Contract
    )

    $count = [regex]::Matches($Text, [regex]::Escape($Old)).Count
    if ($count -ne 1) {
        throw "BF-1000 BLOCKED: $Contract expected one match, found $count."
    }
    return $Text.Replace($Old, $New)
}

$core = [IO.File]::ReadAllText($CorePath)
$tokens = $null
$errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseInput($core, [ref]$tokens, [ref]$errors)
if (@($errors).Count -gt 0) {
    $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-1000 BLOCKED: staged core failed PowerShell parse: $summary"
}

$functions = @($ast.FindAll({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
    $node.Name -eq 'ConvertTo-PlayerCompareHtml'
}, $true))
if ($functions.Count -ne 1) {
    throw "BF-1000 BLOCKED: expected one Player Compare renderer, found $($functions.Count)."
}

$start = $functions[0].Extent.StartOffset
$end = $functions[0].Extent.EndOffset
$compare = $core.Substring($start, $end - $start)

$old = '<section class="panel hero-panel"><div class="manager-head"><div><div class="eyebrow">League tool</div><h1 class="headline">Player Compare</h1><p class="lede">Side-by-side neutral evidence for two exact rostered players. Butler does not choose a winner.</p></div><span class="status done">NOT A RANKING</span></div><div class="button-row"><a class="btn btn-primary" href="$swapHref">Swap sides</a>$returnToLineupAction<a class="btn btn-secondary" href="/players">Compare different players</a></div></section>'
$new = '<section class="panel hero-panel"><div class="manager-head"><div><div class="eyebrow">Completed comparison</div><h1 class="headline">Player Compare</h1><p class="lede">Two exact rostered players are loaded side by side. Butler does not choose a winner. Continue from either player card, or start a different comparison.</p></div><span class="status done">NOT A RANKING</span></div><div class="button-row"><a class="btn btn-primary" href="/players">Compare different players</a>$returnToLineupAction<a class="btn btn-secondary" href="$swapHref">Swap sides</a></div></section>'

$compare = Replace-Bf1000ExactlyOnce -Text $compare -Old $old -New $new -Contract 'completed Player Compare hero hierarchy'
$core = $core.Substring(0, $start) + $compare + $core.Substring($end)

$tokens = $null
$errors = $null
[void][System.Management.Automation.Language.Parser]::ParseInput($core, [ref]$tokens, [ref]$errors)
if (@($errors).Count -gt 0) {
    $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-1000 BLOCKED: generated staged core failed PowerShell parse: $summary"
}

foreach ($required in @(
    '<div class="eyebrow">Completed comparison</div>',
    'Two exact rostered players are loaded side by side.',
    '<a class="btn btn-primary" href="/players">Compare different players</a>',
    '<a class="btn btn-secondary" href="$swapHref">Swap sides</a>',
    '$returnToLineupAction',
    'Back to Lineup Review',
    'Butler does not choose a winner',
    'NOT A RANKING'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-1000 BLOCKED: completed Player Compare hierarchy marker is missing: $required"
    }
}

$surface = $compare
if ($surface -match 'Invoke-RestMethod|Invoke-WebRequest|Invoke-ButlerReadOnly|Invoke-Bf742DashboardWorkerRead|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|returnUrl|redirectUrl|javascript:') {
    throw 'BF-1000 BLOCKED: Player Compare completed hierarchy introduced provider, backend-read, optimizer, write, or open-redirect behavior.'
}

[IO.File]::WriteAllText($CorePath, $core, [Text.UTF8Encoding]::new($false))
Write-Host 'BF-1000 Player Compare completed hierarchy applied.'
