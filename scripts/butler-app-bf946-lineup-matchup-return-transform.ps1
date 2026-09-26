param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-946 BLOCKED: staged Butler core not found at $CorePath"
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
        throw "BF-946 BLOCKED: $Contract expected one match, found $matches."
    }
    return $Text.Replace($Old, $New)
}

$core = [System.IO.File]::ReadAllText($CorePath)

$readyActionsOld = '<div class="button-row"><a class="btn btn-secondary" href="/team/autofill">Refresh projection</a><a class="btn btn-secondary" href="/team">Back to My Team</a></div>'
$readyActionsNew = '<div class="button-row"><a class="btn btn-secondary" href="/team/autofill">Refresh projection</a><a class="btn btn-secondary" href="/matchup">Back to Matchup</a><a class="btn btn-secondary" href="/team">Back to My Team</a></div>'
$core = Replace-ExactlyOnce -Text $core -Old $readyActionsOld -New $readyActionsNew -Contract 'completed Lineup Review return actions'

foreach ($required in @(
    'href="/matchup">Back to Matchup</a>',
    'href="/team">Back to My Team</a>',
    'href="/team/autofill">Refresh projection</a>',
    "ActionLabel = 'Open Lineup Review'",
    "ActionHref = '/team/autofill'",
    'Back to Lineup Review',
    'Compare this swap',
    'CHANGES FIRST'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-946 BLOCKED: required weekly decision-loop marker is missing: $required"
    }
}

$autoFillStart = $core.IndexOf('function ConvertTo-AutoFillHtml {', [System.StringComparison]::Ordinal)
$teamStart = $core.IndexOf('function ConvertTo-TeamHtml {', $autoFillStart, [System.StringComparison]::Ordinal)
if ($autoFillStart -lt 0 -or $teamStart -le $autoFillStart) {
    throw 'BF-946 BLOCKED: final Lineup Advisor renderer boundary is missing.'
}
$installed = $core.Substring($autoFillStart, $teamStart - $autoFillStart)
if ([regex]::Matches($installed, [regex]::Escape('href="/matchup">Back to Matchup</a>')).Count -ne 1) {
    throw 'BF-946 BLOCKED: completed Lineup Review must expose exactly one Back to Matchup action.'
}
foreach ($forbidden in @(
    'Invoke-RestMethod',
    'Invoke-WebRequest',
    'Method = "POST"',
    'submitTransaction',
    'setFaab',
    'window.history',
    'javascript:'
)) {
    if ($installed.IndexOf($forbidden, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
        throw "BF-946 BLOCKED: lineup return action introduced forbidden behavior: $forbidden"
    }
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($CorePath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $summary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-946 BLOCKED: generated staged core failed PowerShell parse: $summary"
}

Write-Host 'BF-946 Lineup Review to Matchup return applied.'
