param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-918 BLOCKED: staged Butler core not found at $CorePath"
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
        throw "BF-918 BLOCKED: $Contract expected one match, found $matches."
    }
    return $Text.Replace($Old, $New)
}

$core = [System.IO.File]::ReadAllText($CorePath)

$franchiseScoutFunctions = @'
function Add-FranchiseScoutPresentation {
    param(
        [Parameter(Mandatory = $true)][string]$Html,
        [Parameter(Mandatory = $true)]$View
    )

    $snapshotOld = '<div class="eyebrow">Current asset frame</div><h2>Franchise composition</h2>'
    $snapshotNew = '<div class="eyebrow">Franchise snapshot</div><h2>What this team owns</h2>'
    $snapshotMatches = [regex]::Matches($Html, [regex]::Escape($snapshotOld)).Count
    if ($snapshotMatches -ne 1) {
        throw "BF-918 BLOCKED: Franchise snapshot heading expected one match, found $snapshotMatches."
    }
    $Html = $Html.Replace($snapshotOld, $snapshotNew)

    $teamHrefId = [System.Uri]::EscapeDataString([string]$View.TeamId)

    $scout = @"
<section class="panel"><div class="section-head"><div><div class="eyebrow">Manager actions</div><h2>Scout this franchise</h2><p class="lede">Use this snapshot to decide where to go next. Butler keeps the franchise evidence neutral and leaves the actual decision to the dedicated workflow.</p></div></div><div class="button-row"><a class="btn btn-primary" href="/trade?opponent=$teamHrefId">Open Trade Analyzer</a><a class="btn btn-secondary" href="/players">Find a player</a><a class="btn btn-secondary" href="/compare">Compare players</a><a class="btn btn-secondary" href="/league">Back to League</a></div></section>
"@

    $anchor = '<section class="panel"><div class="section-head"><div><div class="eyebrow">Evidence quality</div>'
    $anchorMatches = [regex]::Matches($Html, [regex]::Escape($anchor)).Count
    if ($anchorMatches -ne 1) {
        throw "BF-918 BLOCKED: Franchise Scout insertion expected one Evidence quality anchor, found $anchorMatches."
    }

    return $Html.Replace($anchor, $scout.TrimEnd() + [Environment]::NewLine + $anchor)
}
'@

$insertMarker = 'function ConvertTo-LeagueHtml {'
$insertIndex = $core.IndexOf($insertMarker, [System.StringComparison]::Ordinal)
if ($insertIndex -lt 0) {
    throw 'BF-918 BLOCKED: Franchise Scout function insertion marker is missing.'
}
$core = $core.Insert($insertIndex, $franchiseScoutFunctions.TrimEnd() + [Environment]::NewLine + [Environment]::NewLine)

$routeOld = '                    $html = ConvertTo-FranchiseDetailHtml -View $franchiseDetail'
$routeNew = @'
                    $html = ConvertTo-FranchiseDetailHtml -View $franchiseDetail
                    $html = Add-FranchiseScoutPresentation -Html $html -View $franchiseDetail
'@
$core = Replace-ExactlyOnce -Text $core -Old $routeOld -New $routeNew.TrimEnd() -Contract 'Franchise Detail Scout presentation'

foreach ($required in @(
    'function Add-FranchiseScoutPresentation',
    'Franchise snapshot',
    'What this team owns',
    'Scout this franchise',
    '$teamHrefId = [System.Uri]::EscapeDataString([string]$View.TeamId)',
    'href="/trade?opponent=$teamHrefId">Open Trade Analyzer</a>',
    'href="/players">Find a player</a>',
    'href="/compare">Compare players</a>',
    'href="/league">Back to League</a>',
    'Add-FranchiseScoutPresentation -Html $html -View $franchiseDetail'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-918 BLOCKED: required Franchise Scout marker is missing: $required"
    }
}

$installedStart = $core.IndexOf('function Add-FranchiseScoutPresentation', [System.StringComparison]::Ordinal)
$installedEnd = $core.IndexOf('function ConvertTo-LeagueHtml {', $installedStart, [System.StringComparison]::Ordinal)
if ($installedStart -lt 0 -or $installedEnd -le $installedStart) {
    throw 'BF-918 BLOCKED: installed Franchise Scout function boundary is missing.'
}
$installed = $core.Substring($installedStart, $installedEnd - $installedStart)

foreach ($forbidden in @(
    'Invoke-RestMethod',
    'Invoke-WebRequest',
    'Invoke-ButlerReadOnly',
    'Invoke-Bf742DashboardWorkerRead',
    'https://api.sleeper.app',
    'Method = "POST"',
    'submitTransaction',
    'setFaab',
    'franchise-rank',
    'contender',
    'rebuilder'
)) {
    if ($installed.IndexOf($forbidden, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
        throw "BF-918 BLOCKED: Franchise Scout introduced provider, backend-read, write, rank, or strategy behavior: $forbidden"
    }
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($CorePath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $parseSummary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-918 BLOCKED: generated staged core failed PowerShell parse: $parseSummary"
}

Write-Host 'BF-918 Franchise Scout applied.'
