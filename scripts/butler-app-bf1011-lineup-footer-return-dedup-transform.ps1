param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-1011 BLOCKED: staged Butler core not found at $CorePath"
}

$core = [System.IO.File]::ReadAllText($CorePath)
$functionStart = $core.IndexOf('function ConvertTo-AutoFillHtml {', [System.StringComparison]::Ordinal)
$functionEnd = $core.IndexOf('function ConvertTo-TeamHtml {', $functionStart, [System.StringComparison]::Ordinal)
if ($functionStart -lt 0 -or $functionEnd -le $functionStart) {
    throw 'BF-1011 BLOCKED: final Lineup Advisor renderer boundary is missing.'
}

$function = $core.Substring($functionStart, $functionEnd - $functionStart)
$refreshAnchor = '<a class=`"btn btn-secondary`" href=`"/team/autofill`">Refresh projection</a>'
$dashboardAnchor = '<a class=`"btn btn-secondary`" href=`"/`">Back to Dashboard</a>'
$matchupAnchor = '<a class=`"btn btn-secondary`" href=`"/matchup`">Back to Matchup</a>'
$teamAnchor = '<a class=`"btn btn-secondary`" href=`"/team`">Back to My Team</a>'
$buttonRowStart = '<div class=`"button-row`">'
$buttonRowEnd = '</div>'

$refreshCount = [regex]::Matches($function, [regex]::Escape($refreshAnchor)).Count
if ($refreshCount -ne 1) {
    throw "BF-1011 BLOCKED: expected one completed Lineup Review Refresh projection action, found $refreshCount."
}

$refreshIndex = $function.IndexOf($refreshAnchor, [System.StringComparison]::Ordinal)
$footerStart = $function.LastIndexOf($buttonRowStart, $refreshIndex, [System.StringComparison]::Ordinal)
if ($footerStart -lt 0) {
    throw 'BF-1011 BLOCKED: completed Lineup Review footer start was not found.'
}
$footerEnd = $function.IndexOf($buttonRowEnd, $refreshIndex, [System.StringComparison]::Ordinal)
if ($footerEnd -lt 0 -or $footerEnd -le $footerStart) {
    throw 'BF-1011 BLOCKED: completed Lineup Review footer end was not found.'
}
$footerEnd += $buttonRowEnd.Length

$existingFooter = $function.Substring($footerStart, $footerEnd - $footerStart)
$canonicalFooter = $buttonRowStart + $refreshAnchor + $dashboardAnchor + $matchupAnchor + $teamAnchor + $buttonRowEnd
$function = $function.Substring(0, $footerStart) + $canonicalFooter + $function.Substring($footerEnd)

$installedFooter = $function.Substring($footerStart, $canonicalFooter.Length)
foreach ($expected in @(
    [pscustomobject]@{ Label = 'Refresh projection'; Marker = $refreshAnchor },
    [pscustomobject]@{ Label = 'Back to Dashboard'; Marker = $dashboardAnchor },
    [pscustomobject]@{ Label = 'Back to Matchup'; Marker = $matchupAnchor },
    [pscustomobject]@{ Label = 'Back to My Team'; Marker = $teamAnchor }
)) {
    $count = [regex]::Matches($installedFooter, [regex]::Escape($expected.Marker)).Count
    if ($count -ne 1) {
        throw "BF-1011 BLOCKED: canonical footer expected one $($expected.Label) action, found $count."
    }
}

if ($installedFooter -cne $canonicalFooter) {
    throw 'BF-1011 BLOCKED: canonical Lineup Review footer order was not established.'
}

$core = $core.Substring(0, $functionStart) + $function + $core.Substring($functionEnd)

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))
$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($CorePath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $summary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-1011 BLOCKED: generated staged core failed PowerShell parse: $summary"
}

Write-Host 'BF-1011 Lineup Review footer returns normalized.'
