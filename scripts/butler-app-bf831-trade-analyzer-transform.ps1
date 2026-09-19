param(
    [Parameter(Mandatory = $true)]
    [string]$CorePath,

    [string]$TradeLabPath = (Join-Path $PSScriptRoot 'butler-trade-lab.ps1'),
    [string]$TradeHostPath = (Join-Path $PSScriptRoot 'butler-trade-lab-host.ps1')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

foreach ($requiredPath in @($CorePath, $TradeLabPath, $TradeHostPath)) {
    if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
        throw "BF-831 required file is missing: $requiredPath"
    }
}

# BF-837 installs the BF-833 visual language into the staged manager-page core.
# This may rewrite only the staged CorePath; BF-836's tracked Trade Analyzer sources
# remain canonical and must never be rewritten by this validation step.
$bf837Transform = Join-Path $PSScriptRoot 'butler-app-bf837-manager-page-visual-transform.ps1'
if (-not (Test-Path -LiteralPath $bf837Transform -PathType Leaf)) {
    throw "BF-837 BLOCKED: manager-page visual transform not found at $bf837Transform"
}
& $bf837Transform -CorePath $CorePath

$core = [System.IO.File]::ReadAllText($CorePath)
foreach ($required in @(
    'BF-837 manager-page visual alignment',
    '--bg:#F3F2EE',
    '--surface-2:#F7F6F2',
    '--turf:#376E50',
    "--font-display:'Inter'",
    '--radius:10px',
    'background-image:none'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-831 BLOCKED: staged manager-page visual marker is missing: $required"
    }
}

$tradeHostText = [System.IO.File]::ReadAllText($TradeHostPath)
$lab = [System.IO.File]::ReadAllText($TradeLabPath)

foreach ($required in @(
    'read-only Trade Analyzer module',
    '--bg:#F3F2EE',
    '--surface:#FFFFFF',
    '--surface-2:#F7F6F2',
    '--line:#D9DCD7',
    '--turf:#376E50',
    '--turf-deep:#28543D',
    '--gold:#A77418',
    '--ink:#1E2521',
    '--muted:#68726B',
    '--brick:#A65245',
    "--font-display:'Inter'",
    '--radius:10px',
    'background-image:none',
    '--bg:#111315',
    '--surface:#191C1E',
    '--surface-2:#202426',
    '@media(prefers-color-scheme:dark)',
    'Trade Analyzer',
    'Opening Trade Analyzer...',
    'READ ONLY'
)) {
    if ($tradeHostText.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-831 BLOCKED: canonical Trade Analyzer host marker is missing: $required"
    }
}

foreach ($required in @(
    'read-only Trade Analyzer app module',
    'trade recommendation $LeagueId',
    'ConvertTo-TradeRecommendationView',
    'PerspectiveTeamId',
    'StrategicVeto',
    'EvidenceComplete',
    'TransitionCoverage',
    'ProtectedCoverage',
    'Why Butler says this',
    'Raw decision record',
    'READ ONLY',
    'Butler recommendation',
    'Analyze a trade',
    'Get Butler recommendation',
    '.trade-result{border-left:4px solid var(--turf)}',
    '.trade-proof',
    '.gate-grid',
    '.veto-item',
    '.raw-output'
)) {
    if ($lab.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-831 BLOCKED: canonical Trade Analyzer decision marker is missing: $required"
    }
}

foreach ($forbidden in @(
    'https://api.sleeper.app',
    'Start-Process',
    'Invoke-RestMethod',
    'Invoke-WebRequest',
    'Method = "POST"'
)) {
    if ($tradeHostText.Contains($forbidden) -or $lab.Contains($forbidden)) {
        throw "BF-831 BLOCKED: Trade Analyzer introduced provider, API, or write behavior marker $forbidden"
    }
}

foreach ($pathToParse in @($CorePath, $TradeHostPath, $TradeLabPath)) {
    $parseTokens = $null
    $parseErrors = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile($pathToParse, [ref]$parseTokens, [ref]$parseErrors)
    if (@($parseErrors).Count -gt 0) {
        $parseMessage = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
        throw "BF-831 BLOCKED: Trade Analyzer validation failed PowerShell parse for ${pathToParse}: $parseMessage"
    }
}
