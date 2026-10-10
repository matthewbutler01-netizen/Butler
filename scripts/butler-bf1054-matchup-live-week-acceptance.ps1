Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$transform = Join-Path $PSScriptRoot 'butler-app-bf840-weekly-matchup-transform.ps1'
$source = [IO.File]::ReadAllText($transform)
$start = $source.IndexOf('function Get-MatchupPublicWeekProof {', [StringComparison]::Ordinal)
$end = $source.IndexOf('function ConvertTo-MatchupOpponentContextHtml {', $start, [StringComparison]::Ordinal)
if ($start -lt 0 -or $end -le $start) {
    throw 'BF-1054 BLOCKED: live public week UI helpers are absent.'
}
. ([scriptblock]::Create($source.Substring($start, $end - $start)))

function ConvertTo-HtmlText {
    param([string]$Text)
    return [System.Net.WebUtility]::HtmlEncode($Text)
}

$nl = [Environment]::NewLine
$baseline = @('Live public NFL week proof', 'State: MATCH', 'Saved season/week: 2026/5', 'Provider season/week: 2026/5') -join $nl
$healthy = Get-MatchupPublicWeekProof -Text $baseline
if ($healthy.State -cne 'MATCH' -or $healthy.Saved -cne '2026/5') {
    throw 'BF-1054 BLOCKED: a proven current NFL matchup was not recognized.'
}
$stale = Get-MatchupPublicWeekProof -Text ($baseline.Replace('State: MATCH', 'State: MISMATCH').
    Replace('Saved season/week: 2026/5', 'Saved season/week: 2026/4'))
if ($stale.State -cne 'MISMATCH' -or $stale.Provider -cne '2026/5') {
    throw 'BF-1054 BLOCKED: stale saved matchup did not fail closed.'
}

foreach ($bad in @(
    $baseline.Replace('State: MATCH', 'State: MISMATCH'),
    $baseline.Replace('Provider season/week: 2026/5', 'Provider season/week: -/-'),
    $baseline.Replace('Saved season/week: 2026/5', 'Saved season/week: 2025/5'),
    ($baseline + $nl + 'State: MATCH' + $nl),
    ($baseline + $nl + 'Saved season/week: 2026/5' + $nl),
    'State: MATCH'
)) {
    $proof = Get-MatchupPublicWeekProof -Text $bad
    if ($proof.State -cne 'UNVERIFIED') {
        throw 'BF-1054 BLOCKED: incomplete, duplicate or contradictory public source week was trusted.'
    }
}

$matchup = '<html><body><main><section class="panel hero-panel"><h1>Weekly matchup</h1></section></main></body></html>'
$shown = Add-MatchupPublicWeekNotice -Html $matchup -Proof $healthy
if ($shown -notmatch 'WEEK MATCHES SLEEPER' -or
    $shown -notmatch 'have not been independently refreshed' -or
    $shown -notmatch 'data-butler-week-state="MATCH"') {
    throw 'BF-1054 BLOCKED: matching public NFL week implied full roster freshness.'
}
$blocked = Add-MatchupPublicWeekNotice -Html $matchup -Proof $stale
if ($blocked -notmatch 'SAVED MATCHUP OUTDATED' -or
    $blocked -notmatch 'withholding saved matchup advice' -or
    $blocked -notmatch 'data-butler-week-state="MISMATCH"') {
    throw 'BF-1054 BLOCKED: stale saved week banner did not warn to withhold actions.'
}
$unknown = Get-MatchupPublicWeekProof -Text (@('State: UNVERIFIED', 'Saved season/week: 2026/5', 'Provider season/week: -/-') -join $nl)
$unverifiedHtml = Add-MatchupPublicWeekNotice -Html $matchup -Proof $unknown
if ($unverifiedHtml -notmatch 'WEEK NOT VERIFIED' -or
    $unverifiedHtml -match 'WEEK MATCHES SLEEPER') {
    throw 'BF-1054 BLOCKED: offline current-week check was misrepresented as confirmed.'
}
try {
    Add-MatchupPublicWeekNotice -Html '<html><body>No weekly matchup hero</body></html>' -Proof $healthy | Out-Null
    throw 'BF-1054 BLOCKED: missing weekly hero incorrectly permitted a proof banner.'
}
catch {
    if ($_.Exception.Message -notlike '*BF-1054 BLOCKED: current weekly matchup hero*') { throw }
}

foreach ($required in @(
    'Get-TeamEvidenceBundleSection -Text $bundleText -Name "WEEK_FRESHNESS"',
    'if ($weekProof.State -ceq ''MISMATCH'' -or',
    'New-AutoFillIdleView',
    'Add-MatchupPublicWeekNotice -Html $html -Proof $weekProof'
)) {
    if ($source.IndexOf($required, [StringComparison]::Ordinal) -lt 0) {
        throw "BF-1054 BLOCKED: actual app route lost on-open source verification: $required"
    }
}

Write-Host 'BF-1054 PASS: ordinary Matchup GET displays a bounded public Sleeper week check, blocks stale pairing advice, and never certifies injury/projection freshness.'
