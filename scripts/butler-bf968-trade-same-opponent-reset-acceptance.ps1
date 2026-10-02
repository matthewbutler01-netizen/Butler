Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$tradePath = Join-Path $PSScriptRoot 'butler-trade-lab.ps1'

$tokens = $null
$errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($tradePath, [ref]$tokens, [ref]$errors)
if (@($errors).Count -ne 0) {
    $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-968 BLOCKED: Trade Analyzer script failed PowerShell parse: $summary"
}

$functions = @($ast.FindAll({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
    $node.Name -eq 'ConvertTo-TradeLabHtml'
}, $true))
if ($functions.Count -ne 1) {
    throw "BF-968 BLOCKED: expected one Trade Analyzer renderer, found $($functions.Count)."
}

$trade = $functions[0].Extent.Text

foreach ($required in @(
    '$opponentTeamHrefId = [System.Uri]::EscapeDataString([string]$Opponent.TeamId)',
    'if ($null -ne $Evaluation) {',
    'href="/trade?opponent=$opponentTeamHrefId">Start new deal with this opponent</a>',
    '<summary>Edit this deal</summary>',
    'Scout franchise',
    'Back to League'
)) {
    if ($trade.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-968 BLOCKED: Trade Analyzer reset marker is missing: $required"
    }
}

$resetMatch = [regex]::Match(
    $trade,
    'href="/trade\?opponent=\$opponentTeamHrefId">Start new deal with this opponent</a>'
)
if (-not $resetMatch.Success) {
    throw 'BF-968 BLOCKED: exact same-opponent reset link is missing.'
}

$resetSurfaceStart = [Math]::Max(0, $resetMatch.Index - 240)
$resetSurfaceLength = [Math]::Min(560, $trade.Length - $resetSurfaceStart)
$resetSurface = $trade.Substring($resetSurfaceStart, $resetSurfaceLength)

foreach ($forbidden in @(
    'evaluate=',
    'counter=',
    'name="give"',
    'name="receive"',
    'Invoke-ButlerReadOnly',
    'Invoke-RestMethod',
    'Invoke-WebRequest',
    'Method = "POST"',
    'submitTransaction',
    'setFaab'
)) {
    if ($resetSurface.IndexOf($forbidden, [System.StringComparison]::Ordinal) -ge 0) {
        throw "BF-968 BLOCKED: reset surface must clear deal/evaluation state and remain read-only; found $forbidden"
    }
}

Write-Host 'BF-968 TRADE ANALYZER SAME-OPPONENT RESET ACCEPTANCE: PASS'
