param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-923 BLOCKED: staged Butler core not found at $CorePath"
}

function Replace-ExactlyOnce {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Old,
        [Parameter(Mandatory = $true)][string]$New,
        [Parameter(Mandatory = $true)][string]$Contract
    )

    $matches = [regex]::Matches($Text, [regex]::Escape($Old)).Count
    if ($matches -ne 1) {
        throw "BF-923 BLOCKED: $Contract expected one match, found $matches."
    }
    return $Text.Replace($Old, $New)
}

$core = [System.IO.File]::ReadAllText($CorePath)

$cardIdsOld = @'
    $hrefId = [System.Uri]::EscapeDataString([string]$Player.PlayerId)
    $teamHrefId = [System.Uri]::EscapeDataString([string]$Player.TeamId)
'@
$cardIdsNew = @'
    $hrefId = [System.Uri]::EscapeDataString([string]$Player.PlayerId)
    $teamHrefId = [System.Uri]::EscapeDataString([string]$Player.TeamId)
    $positionHref = [System.Uri]::EscapeDataString([string]$Player.Position)
'@
$core = Replace-ExactlyOnce -Text $core -Old $cardIdsOld.TrimEnd() -New $cardIdsNew.TrimEnd() -Contract 'Player Compare card position href'

$cardActionsOld = '<div class="button-row"><a class="btn btn-secondary" href="/player?id=$hrefId">View Player Detail</a><a class="btn btn-secondary" href="/franchise?id=$teamHrefId">Scout franchise</a></div>'
$cardActionsNew = '<div class="button-row"><a class="btn btn-primary" href="/compare?left=$hrefId&q=$positionHref">Compare with another $(ConvertTo-HtmlText $Player.Position)</a><a class="btn btn-secondary" href="/player?id=$hrefId">View Player Detail</a><a class="btn btn-secondary" href="/franchise?id=$teamHrefId">Scout franchise</a></div>'
$core = Replace-ExactlyOnce -Text $core -Old $cardActionsOld -New $cardActionsNew -Contract 'Player Compare card loop action'

$resultIdsOld = @'
        $leftHref = [System.Uri]::EscapeDataString([string]$Request.LeftPlayerId)
        $rightHref = [System.Uri]::EscapeDataString([string]$Request.RightPlayerId)
        $supportHref = "/compare?left=$leftHref&right=$rightHref&support=1#supporting-evidence"
'@
$resultIdsNew = @'
        $leftHref = [System.Uri]::EscapeDataString([string]$Request.LeftPlayerId)
        $rightHref = [System.Uri]::EscapeDataString([string]$Request.RightPlayerId)
        $supportHref = "/compare?left=$leftHref&right=$rightHref&support=1#supporting-evidence"
        $swapSuffix = if ($Request.LoadSupportingEvidence) { '&support=1#supporting-evidence' } else { '' }
        $swapHref = "/compare?left=$rightHref&right=$leftHref$swapSuffix"
'@
$core = Replace-ExactlyOnce -Text $core -Old $resultIdsOld.TrimEnd() -New $resultIdsNew.TrimEnd() -Contract 'Player Compare exact swap href'

$resultHeroOld = '<section class="panel hero-panel"><div class="manager-head"><div><div class="eyebrow">League tool</div><h1 class="headline">Player Compare</h1><p class="lede">Side-by-side neutral evidence for two exact rostered players. Butler does not choose a winner.</p></div><span class="status done">NOT A RANKING</span></div><div class="button-row"><a class="btn btn-secondary" href="/players">Compare different players</a></div></section>'
$resultHeroNew = '<section class="panel hero-panel"><div class="manager-head"><div><div class="eyebrow">League tool</div><h1 class="headline">Player Compare</h1><p class="lede">Side-by-side neutral evidence for two exact rostered players. Butler does not choose a winner.</p></div><span class="status done">NOT A RANKING</span></div><div class="button-row"><a class="btn btn-primary" href="$swapHref">Swap sides</a><a class="btn btn-secondary" href="/players">Compare different players</a></div></section>'
$core = Replace-ExactlyOnce -Text $core -Old $resultHeroOld -New $resultHeroNew -Contract 'Player Compare result loop actions'

foreach ($required in @(
    '$positionHref = [System.Uri]::EscapeDataString([string]$Player.Position)',
    'href="/compare?left=$hrefId&q=$positionHref">Compare with another $(ConvertTo-HtmlText $Player.Position)</a>',
    '$swapSuffix = if ($Request.LoadSupportingEvidence)',
    '$swapHref = "/compare?left=$rightHref&right=$leftHref$swapSuffix"',
    'href="$swapHref">Swap sides</a>'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-923 BLOCKED: required compare-loop marker is missing: $required"
    }
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($CorePath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $parseSummary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-923 BLOCKED: generated staged core failed PowerShell parse: $parseSummary"
}

Write-Host 'BF-923 Player Compare loop polish applied.'
