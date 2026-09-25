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

$core = [System.IO.File]::ReadAllText($CorePath)

$teamStart = $core.IndexOf('function ConvertTo-TeamHtml {', [System.StringComparison]::Ordinal)
if ($teamStart -lt 0) {
    throw 'BF-929 BLOCKED: My Team renderer start is missing.'
}

$positionStart = $core.IndexOf('$positionHtml', $teamStart, [System.StringComparison]::Ordinal)
$seasonStart = $core.IndexOf('$seasonHtml', $positionStart, [System.StringComparison]::Ordinal)
if ($positionStart -lt 0 -or $seasonStart -le $positionStart) {
    throw 'BF-929 BLOCKED: final My Team position-card block is missing.'
}

$positionBlock = $core.Substring($positionStart, $seasonStart - $positionStart)

$loopOld = @'
foreach ($position in $Pressure) {
        if ($position.Available) {
'@
$loopNew = @'
foreach ($position in $Pressure) {
        $positionHref = [System.Uri]::EscapeDataString([string]$position.Position)
        if ($position.Available) {
'@
$loopCount = [regex]::Matches($positionBlock, [regex]::Escape($loopOld.TrimEnd())).Count
if ($loopCount -ne 1) {
    throw "BF-929 BLOCKED: My Team position loop expected one match, found $loopCount."
}
$positionBlock = $positionBlock.Replace($loopOld.TrimEnd(), $loopNew.TrimEnd())

$cardEnd = '</article>"'
$cardEndCount = [regex]::Matches($positionBlock, [regex]::Escape($cardEnd)).Count
if ($cardEndCount -ne 2) {
    throw "BF-929 BLOCKED: My Team position block expected two pressure-card endings, found $cardEndCount."
}

$cardEndNew = '<div class=`"button-row`" style=`"margin-top:12px`"><a class=`"btn btn-secondary`" href=`"/players?q=$positionHref`">Browse $(ConvertTo-HtmlText $position.Position) players</a></div></article>"'
$positionBlock = $positionBlock.Replace($cardEnd, $cardEndNew)

$core = $core.Substring(0, $positionStart) + $positionBlock + $core.Substring($seasonStart)

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
