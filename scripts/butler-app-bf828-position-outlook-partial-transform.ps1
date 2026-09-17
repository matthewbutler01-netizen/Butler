param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-828 BLOCKED: staged Butler core not found at $CorePath"
}

$core = [System.IO.File]::ReadAllText($CorePath)

# BF-803 replaces the original My Team renderer before BF-827/BF-828 run in the
# live app staging chain. Match that staged Position Outlook contract directly.
$oldPosition = @'
    $positionHtml = ''
    foreach ($position in $Pressure) {
        if ($position.Available) {
            $positionHtml += "<article class=`"card position-card`"><div class=`"rank`">$(ConvertTo-HtmlText $position.Position) &middot; $(ConvertTo-HtmlText $position.DirectStarters) starter slot(s)</div><div class=`"pressure-tier`">$(ConvertTo-HtmlText $position.Tier)</div><div class=`"meta`">Starter coverage $(ConvertTo-HtmlText $position.StarterCoverageValue) &middot; total value $(ConvertTo-HtmlText $position.TotalPositionValue)</div></article>"
        }
        else {
            $positionHtml += "<article class=`"card position-card`"><div class=`"rank`">$(ConvertTo-HtmlText $position.Position)</div><div class=`"pressure-tier`">Unavailable</div><div class=`"meta`">$(ConvertTo-HtmlText $position.Reason)</div></article>"
        }
    }
'@

$newPosition = @'
    $positionHtml = ''
    foreach ($position in $Pressure) {
        if ($position.Available) {
            $positionHtml += "<article class=`"card position-card`"><div class=`"rank`">$(ConvertTo-HtmlText $position.Position) &middot; $(ConvertTo-HtmlText $position.DirectStarters) direct starter(s)</div><div class=`"pressure-tier`">$(ConvertTo-HtmlText $position.Tier)</div><div class=`"meta`">Starter coverage $(ConvertTo-HtmlText $position.StarterCoverageValue) &middot; total value $(ConvertTo-HtmlText $position.TotalPositionValue)</div><div class=`"meta`">Value coverage $(ConvertTo-HtmlText $position.Valued)/$(ConvertTo-HtmlText $position.Players) &middot; stale $(ConvertTo-HtmlText $position.Stale) &middot; missing $(ConvertTo-HtmlText $position.Missing)</div></article>"
        }
        else {
            $reasonText = if ([string]::IsNullOrWhiteSpace([string]$position.Reason)) { "Complete governed value coverage is required before Butler will assign a position tier." } else { [string]$position.Reason }
            $hasCoverageCounts = -not [string]::IsNullOrWhiteSpace([string]$position.Players) -and
                -not [string]::IsNullOrWhiteSpace([string]$position.Valued) -and
                -not [string]::IsNullOrWhiteSpace([string]$position.Stale) -and
                -not [string]::IsNullOrWhiteSpace([string]$position.Missing)
            $coverageText = if ($hasCoverageCounts) {
                "Value coverage $(ConvertTo-HtmlText $position.Valued)/$(ConvertTo-HtmlText $position.Players) &middot; stale $(ConvertTo-HtmlText $position.Stale) &middot; missing $(ConvertTo-HtmlText $position.Missing)"
            }
            else {
                "Governed player coverage counts were not returned for this position."
            }
            $positionHtml += "<article class=`"card position-card position-partial`"><div class=`"rank`">$(ConvertTo-HtmlText $position.Position) &middot; $(ConvertTo-HtmlText $position.DirectStarters) direct starter(s)</div><div class=`"pressure-tier`">Coverage needed</div><div class=`"meta`">$coverageText</div><div class=`"meta`">$(ConvertTo-HtmlText $reasonText)</div><details><summary>Partial evidence details</summary><div class=`"technical`">No position tier is inferred until the governed positional-pressure evidence is complete.</div></details></article>"
        }
    }
'@

$matchCount = [regex]::Matches($core, [regex]::Escape($oldPosition.Trim())).Count
if ($matchCount -ne 1) {
    throw "BF-828 BLOCKED: staged BF-803 Position Outlook rendering contract expected one match, found $matchCount."
}
$core = $core.Replace($oldPosition.Trim(), $newPosition.Trim())

foreach ($required in @(
    '$positionHtml',
    'Coverage needed',
    'card position-card position-partial',
    'Value coverage $(ConvertTo-HtmlText $position.Valued)/$(ConvertTo-HtmlText $position.Players)',
    'stale $(ConvertTo-HtmlText $position.Stale)',
    'missing $(ConvertTo-HtmlText $position.Missing)',
    '$(ConvertTo-HtmlText $position.DirectStarters) direct starter(s)',
    'No position tier is inferred until the governed positional-pressure evidence is complete.',
    '$(ConvertTo-HtmlText $position.Tier)',
    'Starter coverage $(ConvertTo-HtmlText $position.StarterCoverageValue)',
    'total value $(ConvertTo-HtmlText $position.TotalPositionValue)'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-828 BLOCKED: required Position Outlook marker is missing: $required"
    }
}

foreach ($forbidden in @('https://api.sleeper.app', 'Invoke-RestMethod', 'Invoke-WebRequest', 'Method = "POST"')) {
    if ($newPosition.Contains($forbidden)) {
        throw "BF-828 BLOCKED: partial-evidence presentation introduced provider, API, or write behavior marker $forbidden"
    }
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))
