param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-929 BLOCKED: staged Butler core not found at $CorePath"
}

function Replace-ExactlyOnce {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Old,
        [Parameter(Mandatory = $true)][string]$New,
        [Parameter(Mandatory = $true)][string]$Contract
    )

    $count = [regex]::Matches($Text, [regex]::Escape($Old)).Count
    if ($count -ne 1) {
        throw "BF-929 BLOCKED: $Contract expected one match, found $count."
    }
    return $Text.Replace($Old, $New)
}

$core = [System.IO.File]::ReadAllText($CorePath)

$loopOld = @'
    $pressureHtml = ""
    foreach ($position in $Pressure) {
        if ($position.Available) {
'@
$loopNew = @'
    $pressureHtml = ""
    foreach ($position in $Pressure) {
        $positionHref = [System.Uri]::EscapeDataString([string]$position.Position)
        if ($position.Available) {
'@
$core = Replace-ExactlyOnce -Text $core -Old $loopOld.TrimEnd() -New $loopNew.TrimEnd() -Contract 'position discovery href'

$availableOld = '$pressureHtml += "<article class=`"card`"><div class=`"rank`">$(ConvertTo-HtmlText $position.Position) &middot; $(ConvertTo-HtmlText $position.DirectStarters) direct starter(s)</div><div class=`"pressure-tier`">$(ConvertTo-HtmlText $position.Tier)</div><div class=`"meta`">Starter coverage $(ConvertTo-HtmlText $position.StarterCoverageValue)</div><div class=`"meta`">Total position value $(ConvertTo-HtmlText $position.TotalPositionValue)</div><div class=`"meta`">Players $(ConvertTo-HtmlText $position.Valued)/$(ConvertTo-HtmlText $position.Players) valued &middot; stale $(ConvertTo-HtmlText $position.Stale) &middot; missing $(ConvertTo-HtmlText $position.Missing)</div></article>"'
$availableNew = '$pressureHtml += "<article class=`"card`"><div class=`"rank`">$(ConvertTo-HtmlText $position.Position) &middot; $(ConvertTo-HtmlText $position.DirectStarters) direct starter(s)</div><div class=`"pressure-tier`">$(ConvertTo-HtmlText $position.Tier)</div><div class=`"meta`">Starter coverage $(ConvertTo-HtmlText $position.StarterCoverageValue)</div><div class=`"meta`">Total position value $(ConvertTo-HtmlText $position.TotalPositionValue)</div><div class=`"meta`">Players $(ConvertTo-HtmlText $position.Valued)/$(ConvertTo-HtmlText $position.Players) valued &middot; stale $(ConvertTo-HtmlText $position.Stale) &middot; missing $(ConvertTo-HtmlText $position.Missing)</div><div class=`"button-row`" style=`"margin-top:12px`"><a class=`"btn btn-secondary`" href=`"/players?q=$positionHref`">Browse $(ConvertTo-HtmlText $position.Position) players</a></div></article>"'
$core = Replace-ExactlyOnce -Text $core -Old $availableOld -New $availableNew -Contract 'available position discovery action'

$unavailableOld = '$pressureHtml += "<article class=`"card`"><div class=`"rank`">$(ConvertTo-HtmlText $position.Position)</div><div class=`"pressure-tier`">Unavailable</div><div class=`"meta`">$(ConvertTo-HtmlText $position.Reason)</div></article>"'
$unavailableNew = '$pressureHtml += "<article class=`"card`"><div class=`"rank`">$(ConvertTo-HtmlText $position.Position)</div><div class=`"pressure-tier`">Unavailable</div><div class=`"meta`">$(ConvertTo-HtmlText $position.Reason)</div><div class=`"button-row`" style=`"margin-top:12px`"><a class=`"btn btn-secondary`" href=`"/players?q=$positionHref`">Browse $(ConvertTo-HtmlText $position.Position) players</a></div></article>"'
$core = Replace-ExactlyOnce -Text $core -Old $unavailableOld -New $unavailableNew -Contract 'unavailable position discovery action'

foreach ($required in @(
    '$positionHref = [System.Uri]::EscapeDataString([string]$position.Position)',
    'href=`"/players?q=$positionHref`">Browse $(ConvertTo-HtmlText $position.Position) players</a>'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-929 BLOCKED: required position discovery marker is missing: $required"
    }
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($CorePath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $parseSummary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-929 BLOCKED: generated staged core failed PowerShell parse: $parseSummary"
}

Write-Host 'BF-929 My Team position discovery applied.'
