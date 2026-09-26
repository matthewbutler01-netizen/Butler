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

    $matches = [regex]::Matches($Text, [regex]::Escape($Old)).Count
    if ($matches -ne 1) {
        throw "BF-945 BLOCKED: $Contract expected one match, found $matches."
    }
    return $Text.Replace($Old, $New)
}

$core = [System.IO.File]::ReadAllText($CorePath)

$partialOld = @'
            Status = 'PARTIAL REVIEW'
            StatusClass = 'warn'
            Detail = "$changeText | Projection hold: $holdText | Held players remain unchanged and receive no synthetic projection."
            ActionLabel = ''
            ActionHref = ''
'@
$partialNew = @'
            Status = 'PARTIAL REVIEW'
            StatusClass = 'warn'
            Detail = "$changeText | Projection hold: $holdText | Held players remain unchanged and receive no synthetic projection."
            ActionLabel = 'Open Lineup Review'
            ActionHref = '/team/autofill'
'@
$core = Replace-ExactlyOnce -Text $core -Old $partialOld.TrimEnd() -New $partialNew.TrimEnd() -Contract 'partial-review bridge'

$changesOld = @'
            Status = 'CHANGES FOUND'
            StatusClass = 'good'
            Detail = "Start: $startText | Sit: $sitText | Projected change: $($AutoFill.Gain)"
            ActionLabel = ''
            ActionHref = ''
'@
$changesNew = @'
            Status = 'CHANGES FOUND'
            StatusClass = 'good'
            Detail = "Start: $startText | Sit: $sitText | Projected change: $($AutoFill.Gain)"
            ActionLabel = 'Open Lineup Review'
            ActionHref = '/team/autofill'
'@
$core = Replace-ExactlyOnce -Text $core -Old $changesOld.TrimEnd() -New $changesNew.TrimEnd() -Contract 'changes-found bridge'

foreach ($required in @(
    "Status = 'NOT REVIEWED'",
    "Status = 'EVIDENCE GAP'",
    "ActionHref = '/matchup/autofill'",
    "Status = 'PARTIAL REVIEW'",
    "Status = 'CHANGES FOUND'",
    "ActionLabel = 'Open Lineup Review'",
    "ActionHref = '/team/autofill'",
    "Status = 'NO CHANGES'",
    'href="/franchise?id=$opponentHrefId">Scout opponent</a>',
    'href="/trade?opponent=$opponentHrefId">Trade with opponent</a>',
    'Compare this swap',
    'CurrentPoints',
    'CHANGES FIRST',
    'Back to Lineup Review'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-945 BLOCKED: required prior navigation or lineup capability is missing: $required"
    }
}

$decisionStart = $core.IndexOf('function Get-MatchupLineupDecisionView {', [System.StringComparison]::Ordinal)
$decisionEnd = $core.IndexOf('function ConvertTo-MatchupHtml {', $decisionStart, [System.StringComparison]::Ordinal)
if ($decisionStart -lt 0 -or $decisionEnd -le $decisionStart) {
    throw 'BF-945 BLOCKED: Matchup lineup decision boundary is missing.'
}
$decision = $core.Substring($decisionStart, $decisionEnd - $decisionStart)
if ([regex]::Matches($decision, [regex]::Escape("ActionLabel = 'Open Lineup Review'")).Count -ne 2 -or
    [regex]::Matches($decision, [regex]::Escape("ActionHref = '/team/autofill'")).Count -ne 2) {
    throw 'BF-945 BLOCKED: only changes and partial review may bridge to the full Lineup Advisor.'
}
if ($decision -match 'Invoke-RestMethod|Invoke-WebRequest|Method = "POST"|submitTransaction|setFaab') {
    throw 'BF-945 BLOCKED: Matchup lineup bridge introduced provider or write behavior.'
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($CorePath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $parseSummary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-945 BLOCKED: generated staged core failed PowerShell parse: $parseSummary"
}

Write-Host 'BF-945 Matchup to Lineup Review bridge applied.'
