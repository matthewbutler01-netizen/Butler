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

# BF-836 promotes the proven BF-831 Trade Analyzer presentation into the canonical
# BF-670 source modules. This guard is intentionally validation-only: it may read
# source/runtime files, but it must never rewrite tracked Trade Analyzer sources.
$core = [System.IO.File]::ReadAllText($CorePath)
if (-not $core.Contains('--turf:#2E6B47')) {
    throw 'BF-831 requires the BF-830 command-center visual baseline.'
}

$tradeHostText = [System.IO.File]::ReadAllText($TradeHostPath)
$lab = [System.IO.File]::ReadAllText($TradeLabPath)

foreach ($required in @(
    'read-only Trade Analyzer module',
    '--bg:#F4F2EA',
    '--surface:#FFFFFF',
    '--surface-2:#ECE9DD',
    '--line:#D8D4C4',
    '--turf:#2E6B47',
    '--turf-deep:#1F4D33',
    '--gold:#C98A1F',
    '--ink:#16201A',
    '--muted:#5B6459',
    '--brick:#A8452F',
    "--font-display:'Teko'",
    '--radius:3px',
    'repeating-linear-gradient',
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
    'Technical governed output',
    'READ ONLY',
    'Butler recommendation',
    'Analyze a trade',
    'Get Butler recommendation',
    '.trade-result{border-left:4px solid var(--turf)}',
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
