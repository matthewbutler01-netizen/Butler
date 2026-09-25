param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-945 BLOCKED: staged Butler core not found at $CorePath"
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
        throw "BF-945 BLOCKED: $Contract expected one match, found $count."
    }
    return $Text.Replace($Old, $New)
}

$core = [System.IO.File]::ReadAllText($CorePath)

$decisionStart = $core.IndexOf('function Get-MatchupLineupDecisionView {', [System.StringComparison]::Ordinal)
$decisionEnd = $core.IndexOf('function ConvertTo-MatchupHtml {', $decisionStart, [System.StringComparison]::Ordinal)
if ($decisionStart -lt 0 -or $decisionEnd -le $decisionStart) {
    throw 'BF-945 BLOCKED: final Matchup lineup decision boundary is missing.'
}
$decision = $core.Substring($decisionStart, $decisionEnd - $decisionStart)

$partialOld = @'
            Detail = "$changeText | Projection hold: $holdText | Held players remain unchanged and receive no synthetic projection."
            ActionLabel = ''
            ActionHref = ''
'@
$partialNew = @'
            Detail = "$changeText | Projection hold: $holdText | Held players remain unchanged and receive no synthetic projection."
            ActionLabel = 'Open Lineup Review'
            ActionHref = '/team/autofill'
'@
$decision = Replace-ExactlyOnce -Text $decision -Old $partialOld.TrimEnd() -New $partialNew.TrimEnd() -Contract 'partial Matchup lineup-review bridge'

$changesOld = @'
            Detail = "Start: $startText | Sit: $sitText | Projected change: $($AutoFill.Gain)"
            ActionLabel = ''
            ActionHref = ''
'@
$changesNew = @'
            Detail = "Start: $startText | Sit: $sitText | Projected change: $($AutoFill.Gain)"
            ActionLabel = 'Open Lineup Review'
            ActionHref = '/team/autofill'
'@
$decision = Replace-ExactlyOnce -Text $decision -Old $changesOld.TrimEnd() -New $changesNew.TrimEnd() -Contract 'changed Matchup lineup-review bridge'

foreach ($required in @(
    "ActionLabel = 'Review Lineup'",
    "ActionHref = '/matchup/autofill'",
    "ActionLabel = 'Retry Lineup Review'",
    "Status = 'PARTIAL REVIEW'",
    "Status = 'CHANGES FOUND'",
    "ActionLabel = 'Open Lineup Review'",
    "ActionHref = '/team/autofill'",
    "Status = 'NO CHANGES'"
)) {
    if ($decision.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-945 BLOCKED: required Matchup decision marker is missing: $required"
    }
}

$noChangeStart = $decision.IndexOf("Title = 'Keep the current lineup'", [System.StringComparison]::Ordinal)
if ($noChangeStart -lt 0) {
    throw 'BF-945 BLOCKED: no-change Matchup state is missing.'
}
$noChangeTail = $decision.Substring($noChangeStart)
if ($noChangeTail.IndexOf("ActionLabel = ''", [System.StringComparison]::Ordinal) -lt 0 -or
    $noChangeTail.IndexOf("ActionHref = ''", [System.StringComparison]::Ordinal) -lt 0) {
    throw 'BF-945 BLOCKED: no-change Matchup state must remain action-free.'
}

$core = $core.Substring(0, $decisionStart) + $decision + $core.Substring($decisionEnd)

$matchupStart = $core.IndexOf('function ConvertTo-MatchupHtml {', [System.StringComparison]::Ordinal)
$matchupEnd = $core.IndexOf('function ConvertTo-MatchupUnavailableHtml {', $matchupStart, [System.StringComparison]::Ordinal)
if ($matchupStart -lt 0 -or $matchupEnd -le $matchupStart) {
    throw 'BF-945 BLOCKED: final Matchup renderer boundary is missing.'
}
$matchup = $core.Substring($matchupStart, $matchupEnd - $matchupStart)
foreach ($required in @(
    'href="/team">Open My Team</a>',
    'href="/franchise?id=$opponentHrefId">Scout opponent</a>',
    'href="/trade?opponent=$opponentHrefId">Trade with opponent</a>',
    '$decisionAction'
)) {
    if ($matchup.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-945 BLOCKED: required existing Matchup action is missing: $required"
    }
}

foreach ($forbidden in @(
    'Invoke-RestMethod',
    'Invoke-WebRequest',
    'Method = "POST"',
    'https://api.sleeper.app',
    'submitTransaction',
    'setFaab',
    'AutoFillLineupOptimizer'
)) {
    if ($decision.IndexOf($forbidden, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
        throw "BF-945 BLOCKED: Matchup lineup-review bridge introduced forbidden behavior: $forbidden"
    }
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($CorePath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $summary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-945 BLOCKED: generated staged core failed PowerShell parse: $summary"
}

Write-Host 'BF-945 Matchup Lineup Review Bridge applied.'
