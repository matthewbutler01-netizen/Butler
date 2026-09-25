param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-927 BLOCKED: staged Butler core not found at $CorePath"
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
        throw "BF-927 BLOCKED: $Contract expected one match, found $matches."
    }
    return $Text.Replace($Old, $New)
}

$core = [System.IO.File]::ReadAllText($CorePath)

if ($core.IndexOf('$leaderHrefId = [System.Uri]::EscapeDataString([string]$leader.TeamId)', [System.StringComparison]::Ordinal) -lt 0) {
    throw 'BF-927 BLOCKED: League Hub exact franchise href contract is missing.'
}

$tradeOld = 'href="/trade">Open Trade Analyzer</a>'
$tradeNew = 'href="/trade?opponent=$leaderHrefId">Open Trade Analyzer</a>'
$core = Replace-ExactlyOnce -Text $core -Old $tradeOld -New $tradeNew -Contract 'League Hub exact Trade Analyzer partner'

foreach ($required in @(
    '$leaderHrefId = [System.Uri]::EscapeDataString([string]$leader.TeamId)',
    'href="/franchise?id=$leaderHrefId">Scout franchise</a>',
    'href="/trade?opponent=$leaderHrefId">Open Trade Analyzer</a>'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-927 BLOCKED: required exact League trade marker is missing: $required"
    }
}

if ($core.IndexOf('href="/trade">Open Trade Analyzer</a>', [System.StringComparison]::Ordinal) -ge 0) {
    throw 'BF-927 BLOCKED: League Hub still contains a generic Trade Analyzer franchise action.'
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($CorePath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $parseSummary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-927 BLOCKED: generated staged core failed PowerShell parse: $parseSummary"
}

Write-Host 'BF-927 League exact Trade Analyzer partner applied.'
