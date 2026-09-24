param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-922 BLOCKED: staged Butler core not found at $CorePath"
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
        throw "BF-922 BLOCKED: $Contract expected one match, found $matches."
    }
    return $Text.Replace($Old, $New)
}

$core = [System.IO.File]::ReadAllText($CorePath)

$searchActionsOld = '$form<div class="button-row"><a class="btn btn-secondary" href="/team">Back to My Team</a><a class="btn btn-secondary" href="/league">Back to League</a></div></section>'
$searchActionsNew = '$form<div class="button-row" style="margin-top:12px"><span class="meta">Quick position searches</span><a class="btn btn-secondary" href="/players?q=QB">QB</a><a class="btn btn-secondary" href="/players?q=RB">RB</a><a class="btn btn-secondary" href="/players?q=WR">WR</a><a class="btn btn-secondary" href="/players?q=TE">TE</a></div><div class="button-row"><a class="btn btn-secondary" href="/team">Back to My Team</a><a class="btn btn-secondary" href="/league">Back to League</a></div></section>'
$core = Replace-ExactlyOnce -Text $core -Old $searchActionsOld -New $searchActionsNew -Contract 'Player Search quick position actions'

$playerHubIdsOld = @'
    $hrefId = [System.Uri]::EscapeDataString([string]$View.PlayerId)
    $teamHrefId = [System.Uri]::EscapeDataString([string]$View.TeamId)
'@
$playerHubIdsNew = @'
    $hrefId = [System.Uri]::EscapeDataString([string]$View.PlayerId)
    $teamHrefId = [System.Uri]::EscapeDataString([string]$View.TeamId)
    $positionHref = [System.Uri]::EscapeDataString([string]$View.Position)
'@
$core = Replace-ExactlyOnce -Text $core -Old $playerHubIdsOld.TrimEnd() -New $playerHubIdsNew.TrimEnd() -Contract 'Player Hub position href'

$playerHubActionsOld = '<div class="button-row"><a class="btn btn-primary" href="/matchup">Review Matchup</a><a class="btn btn-secondary" href="/compare?left=$hrefId">Compare this player</a><a class="btn btn-secondary" href="/franchise?id=$teamHrefId">Scout franchise</a><a class="btn btn-secondary" href="/trade">Open Trade Analyzer</a><a class="btn btn-secondary" href="/waivers">Check Waiver Board</a></div>'
$playerHubActionsNew = '<div class="button-row"><a class="btn btn-primary" href="/matchup">Review Matchup</a><a class="btn btn-secondary" href="/compare?left=$hrefId">Compare this player</a><a class="btn btn-secondary" href="/players?q=$positionHref">Find more $(ConvertTo-HtmlText $View.Position)</a><a class="btn btn-secondary" href="/franchise?id=$teamHrefId">Scout franchise</a><a class="btn btn-secondary" href="/trade">Open Trade Analyzer</a><a class="btn btn-secondary" href="/waivers">Check Waiver Board</a></div>'
$core = Replace-ExactlyOnce -Text $core -Old $playerHubActionsOld -New $playerHubActionsNew -Contract 'Player Hub same-position action'

$compareLeftOld = '    $leftHref = [System.Uri]::EscapeDataString([string]$SelectedLeft.PlayerId)'
$compareLeftNew = @'
    $leftHref = [System.Uri]::EscapeDataString([string]$SelectedLeft.PlayerId)
    $leftPositionHref = [System.Uri]::EscapeDataString([string]$SelectedLeft.Position)
'@
$core = Replace-ExactlyOnce -Text $core -Old $compareLeftOld -New $compareLeftNew.TrimEnd() -Contract 'Player Compare position href'

$compareActionsOld = '<div class="button-row"><a class="btn btn-secondary" href="/players">Choose a different first player</a></div>'
$compareActionsNew = '<div class="button-row"><a class="btn btn-primary" href="/compare?left=$leftHref&q=$leftPositionHref">Find same-position players</a><a class="btn btn-secondary" href="/players">Choose a different first player</a></div>'
$core = Replace-ExactlyOnce -Text $core -Old $compareActionsOld -New $compareActionsNew -Contract 'Player Compare same-position action'

foreach ($required in @(
    'Quick position searches',
    'href="/players?q=QB">QB</a>',
    'href="/players?q=RB">RB</a>',
    'href="/players?q=WR">WR</a>',
    'href="/players?q=TE">TE</a>',
    '$positionHref = [System.Uri]::EscapeDataString([string]$View.Position)',
    'href="/players?q=$positionHref">Find more $(ConvertTo-HtmlText $View.Position)</a>',
    '$leftPositionHref = [System.Uri]::EscapeDataString([string]$SelectedLeft.Position)',
    'href="/compare?left=$leftHref&q=$leftPositionHref">Find same-position players</a>'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-922 BLOCKED: required player-discovery marker is missing: $required"
    }
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($CorePath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $parseSummary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-922 BLOCKED: generated staged core failed PowerShell parse: $parseSummary"
}

Write-Host 'BF-922 player discovery shortcuts applied.'
