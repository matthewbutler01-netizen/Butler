Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$acceptance = Join-Path $PSScriptRoot 'butler-bf943-lineup-render-acceptance.ps1'
if (-not (Test-Path -LiteralPath $acceptance -PathType Leaf)) {
    throw "BF-948 BLOCKED: shared lineup render acceptance not found at $acceptance"
}

$lines = @(& $acceptance 2>&1)
$text = ($lines | ForEach-Object { "$_" }) -join "`n"

if ($text -notmatch 'BF-947 Compact lineup comparison evidence applied\.' -or
    $text -notmatch 'BF-948 Lineup Review unresolved-item count applied\.' -or
    $text -notmatch 'BF-943 LINEUP RENDER ACCEPTANCE: PASS') {
    throw "BF-948 BLOCKED: staged lineup chain or shared render acceptance did not complete as expected.`n$text"
}

$transform = Join-Path $PSScriptRoot 'butler-app-bf948-lineup-review-count-transform.ps1'
$transformText = [System.IO.File]::ReadAllText($transform)
foreach ($required in @(
    '$reviewQueueCount = [regex]::Matches($queueItems, ''<li>'').Count',
    '$decisionTitle = "Review $reviewQueueCount unresolved $reviewQueueNoun"',
    'lineup-review-queue-head',
    '$reviewQueueBadge'
)) {
    if ($transformText.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-948 BLOCKED: unresolved review-count contract is missing: $required"
    }
}

$lines | ForEach-Object { Write-Host "$_" }
Write-Host 'BF-948 LINEUP REVIEW COUNT ACCEPTANCE: PASS'
