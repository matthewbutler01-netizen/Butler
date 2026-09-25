param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-930 BLOCKED: staged Butler core not found at $CorePath"
}

$core = [System.IO.File]::ReadAllText($CorePath)

$searchStart = $core.IndexOf('function ConvertTo-PlayerSearchHtml {', [System.StringComparison]::Ordinal)
$searchEnd = $core.IndexOf('function Get-PlayerDetailRequestId {', $searchStart, [System.StringComparison]::Ordinal)
if ($searchStart -lt 0 -or $searchEnd -le $searchStart) {
    throw 'BF-930 BLOCKED: final Player Search renderer boundary is missing.'
}

$searchBlock = $core.Substring($searchStart, $searchEnd - $searchStart)

$old = '<div class="button-row"><a class="btn btn-secondary" href="/team">Back to My Team</a><a class="btn btn-secondary" href="/league">Back to League</a></div></section>'
$new = '<div class="button-row"><a class="btn btn-secondary" href="/team">Back to My Team</a><a class="btn btn-secondary" href="/league">Back to League</a><a class="btn btn-secondary" href="/waivers">Check Waiver Board</a></div></section>'

$count = [regex]::Matches($searchBlock, [regex]::Escape($old)).Count
if ($count -ne 1) {
    throw "BF-930 BLOCKED: Player Search manager action row expected one match, found $count."
}
$searchBlock = $searchBlock.Replace($old, $new)
$core = $core.Substring(0, $searchStart) + $searchBlock + $core.Substring($searchEnd)

foreach ($required in @(
    'Free agents remain on Waiver Board.',
    'href="/team">Back to My Team</a>',
    'href="/league">Back to League</a>',
    'href="/waivers">Check Waiver Board</a>'
)) {
    if ($searchBlock.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-930 BLOCKED: required Player Search waiver-bridge marker is missing: $required"
    }
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($CorePath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $parseSummary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-930 BLOCKED: generated staged core failed PowerShell parse: $parseSummary"
}

Write-Host 'BF-930 Player Search Waiver Board bridge applied.'
