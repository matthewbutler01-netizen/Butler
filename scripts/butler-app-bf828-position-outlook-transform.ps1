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

$oldPositionBlock = @'
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

$newPositionBlock = @'
    $positionHtml = ''
    foreach ($position in $Pressure) {
        $starterWord = if ([string]$position.DirectStarters -ceq '1') { 'starter slot' } else { 'starter slots' }
        if ($position.Available) {
            $coverageText = "$(ConvertTo-HtmlText $position.Valued)/$(ConvertTo-HtmlText $position.Players) valued"
            $positionHtml += "<article class=`"card position-card`"><div class=`"rank`">$(ConvertTo-HtmlText $position.Position) &middot; $(ConvertTo-HtmlText $position.DirectStarters) $starterWord</div><div class=`"pressure-tier`">$(ConvertTo-HtmlText $position.Tier)</div><div class=`"meta`">Starter coverage value $(ConvertTo-HtmlText $position.StarterCoverageValue) &middot; total position value $(ConvertTo-HtmlText $position.TotalPositionValue) &middot; $coverageText</div><div class=`"meta`">Stale $(ConvertTo-HtmlText $position.Stale) &middot; missing $(ConvertTo-HtmlText $position.Missing)</div></article>"
        }
        else {
            $reason = if (-not [string]::IsNullOrWhiteSpace([string]$position.Reason)) { [string]$position.Reason } else { "Complete current value coverage is required for this position." }
            $positionHtml += "<article class=`"card position-card`"><div class=`"rank`">$(ConvertTo-HtmlText $position.Position) &middot; $(ConvertTo-HtmlText $position.DirectStarters) $starterWord</div><div class=`"pressure-tier`">Coverage needed</div><div class=`"meta`">$(ConvertTo-HtmlText $reason)</div></article>"
        }
    }
'@

$positionCount = [regex]::Matches($core, [regex]::Escape($oldPositionBlock.Trim())).Count
if ($positionCount -ne 1) {
    throw "BF-828 BLOCKED: Position outlook contract expected one match, found $positionCount."
}
$core = $core.Replace($oldPositionBlock.Trim(), $newPositionBlock.Trim())

foreach ($required in @(
    'Coverage needed',
    'Starter coverage value $(ConvertTo-HtmlText $position.StarterCoverageValue)',
    'total position value $(ConvertTo-HtmlText $position.TotalPositionValue)',
    '$(ConvertTo-HtmlText $position.Valued)/$(ConvertTo-HtmlText $position.Players) valued',
    'Stale $(ConvertTo-HtmlText $position.Stale)',
    'missing $(ConvertTo-HtmlText $position.Missing)',
    'Complete current value coverage is required for this position.'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-828 BLOCKED: required position-intelligence marker is missing: $required"
    }
}

if ($core -notmatch 'Position outlook') {
    throw 'BF-828 BLOCKED: Position outlook section is missing after transform.'
}

# BF-828 is presentation-only. It may describe existing governed evidence, but it must not
# introduce credentials, provider calls, or fantasy-platform mutation behavior.
if ($newPositionBlock -match 'Method = "POST"|Invoke-RestMethod|Invoke-WebRequest|SleeperClient|\$env:') {
    throw 'BF-828 BLOCKED: Position outlook presentation introduced a network, credential, or write path.'
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))
