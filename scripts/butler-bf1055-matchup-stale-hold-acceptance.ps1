Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$transform = Join-Path $PSScriptRoot 'butler-app-bf840-weekly-matchup-transform.ps1'
$worker = Join-Path $PSScriptRoot 'butler-app-request-worker.ps1'
$source = [IO.File]::ReadAllText($transform)
$workerSource = [IO.File]::ReadAllText($worker)

$start = $source.IndexOf('function ConvertTo-MatchupWeekHoldHtml {', [StringComparison]::Ordinal)
$end = $source.IndexOf('function Get-MatchupPublicWeekProof {', $start, [StringComparison]::Ordinal)
if ($start -lt 0 -or $end -le $start) {
    throw 'BF-1055 BLOCKED: stale-week full-page renderer is not installed.'
}
. ([scriptblock]::Create($source.Substring($start, $end - $start)))

function Get-AppCss { return '.panel{color:inherit}' }
function Get-AppNav { param([string]$Active); return '<nav class="nav">SAFE MANAGER NAV</nav>' }
function ConvertTo-HtmlText { param([string]$Text); return [Net.WebUtility]::HtmlEncode($Text) }

$reason = 'Saved pairing is out of date. This is a synthetic test only.'
$matchup = ConvertTo-MatchupWeekHoldHtml -Reason $reason -StartSit $false
if ($matchup -notmatch '<section class="panel hero-panel">' -or
    $matchup -notmatch 'Saved matchup not usable' -or
    $matchup -notmatch 'DO NOT ACT' -or
    $matchup -notmatch [regex]::Escape($reason) -or
    $matchup -match 'Review Lineup|Recommended starter|OPPONENT CONFIRMED|href="/matchup/autofill"') {
    throw 'BF-1055 BLOCKED: stale matchup view still offers saved pairing advice or actionable lineup navigation.'
}
$assistant = ConvertTo-MatchupWeekHoldHtml -Reason $reason -StartSit $true
if ($assistant -notmatch 'start-sit-assistant' -or
    $assistant -notmatch 'Start/Sit review held' -or
    $assistant -notmatch 'No player recommendation is available' -or
    $assistant -notmatch 'DO NOT ACT' -or
    $assistant -match 'Review Lineup|Recommended starter|Promote to lineup|href="/matchup/autofill"') {
    throw 'BF-1055 BLOCKED: stale Start/Sit view exposes a saved-lineup recommendation.'
}
$encoded = ConvertTo-MatchupWeekHoldHtml -Reason '<img src=x onerror=alert(1)>' -StartSit $true
if ($encoded -match '<img src=x' -or $encoded -notmatch '&lt;img src=x') {
    throw 'BF-1055 BLOCKED: reason text was not HTML encoded.'
}
foreach ($required in @(
    "if ($([char]36)weekProof.State -ceq 'MISMATCH' -or",
    "($([char]36)requestAutoFill -and $([char]36)weekProof.State -cne 'MATCH')",
    'ConvertTo-MatchupWeekHoldHtml -Reason $reason -StartSit $requestAutoFill',
    'Add-MatchupPublicWeekNotice -Html $html -Proof $weekProof'
)) {
    if ($source.IndexOf($required, [StringComparison]::Ordinal) -lt 0) {
        throw "BF-1055 BLOCKED: saved week guard absent in final route: $required"
    }
}
foreach ($required in @(
    'data-butler-week-state="MISMATCH"',
    'data-butler-week-state="UNVERIFIED"'
)) {
    if ($workerSource.IndexOf($required, [StringComparison]::Ordinal) -lt 0) {
        throw "BF-1055 BLOCKED: Start/Sit still advertises auto-recheck for held evidence: $required"
    }
}

Write-Host 'BF-1055 STALE MATCHUP/START-SIT HOLD: PASS'
Write-Host 'Coverage: dated paired evidence withheld, current-week unknown Start/Sit withheld, safe advice-free manager pages, encoded reasons, no fabricated auto-recheck.'
