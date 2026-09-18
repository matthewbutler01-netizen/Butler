param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

foreach ($required in @($CorePath, $DashboardPath)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "BF-840 BLOCKED: staged Butler source not found at $required"
    }
}

function Replace-ExactlyOnce {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Old,
        [Parameter(Mandatory = $true)][string]$New,
        [Parameter(Mandatory = $true)][string]$Contract
    )
    $first = $Text.IndexOf($Old, [System.StringComparison]::Ordinal)
    if ($first -lt 0) { throw "BF-840 BLOCKED: $Contract anchor is missing." }
    $second = $Text.IndexOf($Old, $first + $Old.Length, [System.StringComparison]::Ordinal)
    if ($second -ge 0) { throw "BF-840 BLOCKED: $Contract anchor is ambiguous." }
    return $Text.Substring(0, $first) + $New + $Text.Substring($first + $Old.Length)
}

function Replace-FunctionBlock {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$StartMarker,
        [Parameter(Mandatory = $true)][string]$NextMarker,
        [Parameter(Mandatory = $true)][string]$Replacement,
        [Parameter(Mandatory = $true)][string]$Contract
    )
    $start = $Text.IndexOf($StartMarker, [System.StringComparison]::Ordinal)
    if ($start -lt 0) { throw "BF-840 BLOCKED: $Contract start marker is missing." }
    $next = $Text.IndexOf($NextMarker, $start + $StartMarker.Length, [System.StringComparison]::Ordinal)
    if ($next -lt 0) { throw "BF-840 BLOCKED: $Contract next marker is missing." }
    return $Text.Substring(0, $start) + $Replacement.TrimEnd() + [Environment]::NewLine + [Environment]::NewLine + $Text.Substring($next)
}

$core = [System.IO.File]::ReadAllText($CorePath)
$dashboard = [System.IO.File]::ReadAllText($DashboardPath)

$rosterFrameOld = @'
        Season = [int]$season.Groups['season'].Value
        ProviderStatus = $season.Groups['status'].Value.Trim()
'@
$rosterFrameNew = @'
        Season = [int]$season.Groups['season'].Value
        Week = if ($season.Groups['leg'].Value.Trim() -match '^\d+$') { [int]$season.Groups['leg'].Value.Trim() } else { $null }
        ProviderStatus = $season.Groups['status'].Value.Trim()
'@
$core = Replace-ExactlyOnce -Text $core -Old $rosterFrameOld -New $rosterFrameNew -Contract 'current-week roster frame'

$functionsAnchor = 'function Get-AppCss {'
$matchupFunctions = @'
function ConvertTo-MatchupEvidenceView {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$ExpectedTeamId,
        [Parameter(Mandatory = $true)][int]$ExpectedSeason,
        [Parameter(Mandatory = $true)][int]$ExpectedWeek
    )

    $league = [regex]::Match($Text, '(?m)^League ID:\s+(?<value>\S+)\s*$')
    $frame = [regex]::Match($Text, '(?m)^Season/week:\s+(?<season>\d+)/(?<week>\d+)\s*$')
    $pair = [regex]::Match($Text, '(?m)^Provider matchup id:\s+(?<value>\d+)\s*$')
    $team = [regex]::Match($Text, '(?m)^Your team:\s+(?<name>.*?)\s+\[(?<id>[^\]]+)\]\s+roster=(?<roster>\S+)\s*$')
    $opponent = [regex]::Match($Text, '(?m)^Opponent:\s+(?<name>.*?)\s+\[(?<id>[^\]]+)\]\s+roster=(?<roster>\S+)\s*$')
    $asOf = [regex]::Match($Text, '(?m)^Pairing as-of:\s+(?<value>\d{4}-\d{2}-\d{2})\s*$')
    $state = [regex]::Match($Text, '(?m)^State:\s+(?<value>\S+)\s*$')

    if (-not $league.Success -or -not $frame.Success -or -not $pair.Success -or
        -not $team.Success -or -not $opponent.Success -or -not $asOf.Success -or
        -not $state.Success -or $state.Groups['value'].Value -cne 'EXACT_PAIR_VERIFIED') {
        throw 'BF-840 BLOCKED: exact weekly matchup evidence output is incomplete or unverified.'
    }

    if ($team.Groups['id'].Value.Trim() -cne $ExpectedTeamId -or
        [int]$frame.Groups['season'].Value -ne $ExpectedSeason -or
        [int]$frame.Groups['week'].Value -ne $ExpectedWeek) {
        throw 'BF-840 BLOCKED: matchup evidence does not reconcile with the verified current roster frame.'
    }

    return [pscustomobject]@{
        LeagueId = $league.Groups['value'].Value.Trim()
        Season = [int]$frame.Groups['season'].Value
        Week = [int]$frame.Groups['week'].Value
        ProviderMatchupId = [int]$pair.Groups['value'].Value
        TeamId = $team.Groups['id'].Value.Trim()
        TeamName = $team.Groups['name'].Value.Trim()
        TeamRosterId = $team.Groups['roster'].Value.Trim()
        OpponentTeamId = $opponent.Groups['id'].Value.Trim()
        OpponentTeamName = $opponent.Groups['name'].Value.Trim()
        OpponentRosterId = $opponent.Groups['roster'].Value.Trim()
        AsOf = $asOf.Groups['value'].Value
    }
}

function ConvertTo-MatchupHtml {
    param(
        [Parameter(Mandatory = $true)]$Roster,
        [Parameter(Mandatory = $true)]$Matchup,
        [Parameter(Mandatory = $true)]$AutoFill,
        [Parameter(Mandatory = $true)]$OpponentStrength,
        [Parameter(Mandatory = $true)]$OpponentPressure
    )

    $lineupHtml = (ConvertTo-AutoFillHtml -AutoFill $AutoFill).Replace('href="/team/autofill"', 'href="/matchup"')
    $strengthText = if ($OpponentStrength.Available) { $OpponentStrength.Tier } else { 'Unavailable' }
    $strengthMeta = if ($OpponentStrength.Available) {
        "Current roster-value coverage $($OpponentStrength.Coverage)%"
    }
    else {
        [string]$OpponentStrength.Reason
    }

    $positionCards = ''
    foreach ($position in $OpponentPressure) {
        $tier = if ($position.Available) { $position.Tier } else { 'Unavailable' }
        $detail = if ($position.Available) {
            "Direct starters $($position.DirectStarters) - valued $($position.Valued)/$($position.Players)"
        }
        else {
            [string]$position.Reason
        }
        $positionCards += "<article class='card'><div class='rank'>$(ConvertTo-HtmlText $position.Position) roster tier</div><div class='pressure-tier'>$(ConvertTo-HtmlText $tier)</div><div class='meta'>$(ConvertTo-HtmlText $detail)</div></article>"
    }

    $css = Get-AppCss
    $nav = Get-AppNav -Active "matchup"
    $safeLeague = ConvertTo-HtmlText $Roster.LeagueName
    $safeTeam = ConvertTo-HtmlText $Matchup.TeamName
    $safeOpponent = ConvertTo-HtmlText $Matchup.OpponentTeamName
    $safeAsOf = ConvertTo-HtmlText $Matchup.AsOf
    $safeStrength = ConvertTo-HtmlText $strengthText
    $safeStrengthMeta = ConvertTo-HtmlText $strengthMeta

    return @"
<!doctype html>
<html lang="en">
<head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Butler - Weekly Matchup</title><style>$css</style></head>
<body><main class="shell"><div class="top"><div class="brand"><h1>BUTLER</h1><p>We're here to serve you. Less Research. Better Decisions.</p></div><div class="target">$safeLeague - Week $($Matchup.Week)</div></div>$nav
<section class="panel"><div class="statusrow"><div><div class="eyebrow">Weekly matchup - Week $($Matchup.Week)</div><h1 class="headline">$safeTeam vs $safeOpponent</h1><p class="lede">Exact Sleeper matchup pairing for your bound roster. Butler uses this identity only to organize this week's decision support.</p></div><span class="status good">PAIR VERIFIED</span></div><div class="grid"><article class="card"><div class="rank">Your team</div><div class="name">$safeTeam</div></article><article class="card"><div class="rank">Opponent</div><div class="name">$safeOpponent</div></article><article class="card"><div class="rank">Pairing evidence</div><div class="name">$safeAsOf</div></article></div></section>
$lineupHtml
<section class="panel"><div class="eyebrow">Opponent context</div><h2>What Butler knows about $safeOpponent</h2><p class="lede">Current governed roster-value context only. These tiers describe the opponent's roster; they do not predict this matchup or imply a winner.</p><div class="stats"><div class="stat"><strong>Overall roster strength</strong><span>$safeStrength</span></div><div class="stat"><strong>Coverage</strong><span>$safeStrengthMeta</span></div><div class="stat"><strong>Frame</strong><span>Current roster</span></div></div><div class="grid four">$positionCards</div></section>
<section class="panel boundary"><span class="lock">READ ONLY.</span> BF-840 combines exact weekly opponent identity with Butler's existing lineup recommendation and descriptive opponent roster context. It does not predict a winner, calculate a win probability, provide betting guidance, or submit any Sleeper transaction.</section>
</main></body></html>
"@
}

function ConvertTo-MatchupUnavailableHtml {
    param([Parameter(Mandatory = $true)][string]$Message)

    $css = Get-AppCss
    $nav = Get-AppNav -Active "matchup"
    $safeMessage = ConvertTo-HtmlText $Message
    return @"
<!doctype html>
<html lang="en">
<head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Butler - Weekly Matchup</title><style>$css</style></head>
<body><main class="shell"><div class="top"><div class="brand"><h1>BUTLER</h1><p>We're here to serve you. Less Research. Better Decisions.</p></div></div>$nav
<section class="panel recommendation-panel"><div class="manager-head"><div><div class="eyebrow">Weekly matchup</div><h2>Weekly matchup needs current evidence</h2><p class="lede">Butler could not prove the exact current opponent and weekly decision frame, so it stopped instead of guessing.</p></div><span class="status warn">EVIDENCE NEEDED</span></div><div class="manager-summary"><div class="summary-card"><h3>Decision</h3><p>Refresh Butler's local evidence, then reopen Weekly Matchup.</p></div><div class="summary-card"><h3>Why</h3><p>The matchup page requires exact current roster identity, week, matchup pairing, and lineup evidence before it can present a weekly plan.</p></div></div><details><summary>View evidence details</summary><div class="callout callout-danger">$safeMessage</div></details><div class="button-row"><a class="btn btn-primary" href="/refresh">Refresh Butler Data</a><a class="btn btn-secondary" href="/team">Review My Team</a></div><p class="meta"><strong>Read only:</strong> opening Weekly Matchup did not write Butler evidence or submit anything to Sleeper.</p></section>
</main></body></html>
"@
}

'@
$core = Replace-ExactlyOnce -Text $core -Old $functionsAnchor -New ($matchupFunctions + $functionsAnchor) -Contract 'weekly matchup renderer insertion'

$navReplacement = @'
function Get-AppNav {
    param([Parameter(Mandatory = $true)][string]$Active)
    $dashboardClass = if ($Active -ceq "dashboard") { ' class="active"' } else { "" }
    $teamClass = if ($Active -ceq "team") { ' class="active"' } else { "" }
    $matchupClass = if ($Active -ceq "matchup") { ' class="active"' } else { "" }
    $waiversClass = if ($Active -ceq "waivers") { ' class="active"' } else { "" }
    $leagueClass = if ($Active -ceq "league") { ' class="active"' } else { "" }
    return '<nav class="nav" aria-label="Butler sections"><a' + $dashboardClass + ' href="/">Dashboard</a><a' + $teamClass + ' href="/team">My Team</a><a' + $matchupClass + ' href="/matchup">Matchup</a><a' + $waiversClass + ' href="/waivers">Waiver Board</a><a' + $leagueClass + ' href="/league">League</a></nav>'
}
'@
$core = Replace-FunctionBlock -Text $core -StartMarker 'function Get-AppNav {' -NextMarker 'function ConvertTo-LeagueHtml {' -Replacement $navReplacement -Contract 'shared Matchup navigation'

$core = Replace-ExactlyOnce -Text $core -Old '    Write-Host "My Team: http://127.0.0.1:$Port/team"' -New ('    Write-Host "My Team: http://127.0.0.1:$Port/team"' + [Environment]::NewLine + '    Write-Host "Matchup: http://127.0.0.1:$Port/matchup"') -Contract 'startup Matchup URL'

$routeAnchor = '            $candidate = $path -match ''^/waivers/candidate/[0-9]+$'''
$matchupRoute = @'
            if ($path -eq "/matchup") {
                try {
                    $bundleText = Invoke-ButlerReadOnlyTask -Task ":bet:bet-cli:sleeperLiveWaiverTargetRosterContextAudit" -Arguments "$LeagueId --team-bundle-autofill" -BoundaryName "BF-840"
                    $rosterText = Get-TeamEvidenceBundleSection -Text $bundleText -Name "ROSTER_CONTEXT"
                    $rosterView = ConvertTo-RosterContextView -Text $rosterText
                    if ($null -eq $rosterView.Week -or $rosterView.Week -le 0) {
                        throw 'BF-840 BLOCKED: current verified Sleeper week is unavailable.'
                    }
                    $teamId = $rosterView.ButlerTeamId
                    $matchupText = Invoke-ButlerReadOnly -Arguments "league team-week-matchup-evidence $LeagueId $teamId $($rosterView.Season) $($rosterView.Week)" -BoundaryName "BF-840"
                    $matchup = ConvertTo-MatchupEvidenceView -Text $matchupText -ExpectedTeamId $teamId -ExpectedSeason $rosterView.Season -ExpectedWeek $rosterView.Week
                    $strengthText = Get-TeamEvidenceBundleSection -Text $bundleText -Name "ROSTER_STRENGTH"
                    $pressureText = Get-TeamEvidenceBundleSection -Text $bundleText -Name "POSITIONAL_PRESSURE"
                    $opponentStrength = ConvertTo-RosterStrengthView -Text $strengthText -TeamId $matchup.OpponentTeamId
                    $opponentPressure = ConvertTo-PositionalPressureView -Text $pressureText -TeamId $matchup.OpponentTeamId
                    $autoFill = ConvertTo-AutoFillView -Text (Get-TeamEvidenceBundleSection -Text $bundleText -Name "AUTOFILL")
                    $html = ConvertTo-MatchupHtml -Roster $rosterView -Matchup $matchup -AutoFill $autoFill -OpponentStrength $opponentStrength -OpponentPressure $opponentPressure
                    Send-HttpResponse -Stream $stream -StatusCode 200 -StatusText "OK" -ContentType "text/html; charset=utf-8" -Body $html
                }
                catch {
                    $html = ConvertTo-MatchupUnavailableHtml -Message ([string]$_.Exception.Message)
                    Send-HttpResponse -Stream $stream -StatusCode 409 -StatusText "Conflict" -ContentType "text/html; charset=utf-8" -Body $html
                }
                continue
            }

'@
$core = Replace-ExactlyOnce -Text $core -Old $routeAnchor -New ($matchupRoute + $routeAnchor) -Contract 'weekly Matchup GET route'

$dashboard = Replace-ExactlyOnce -Text $dashboard -Old '  <a class="$teamClass" href="/team">My Team</a>' -New ('  <a class="$teamClass" href="/team">My Team</a>' + [Environment]::NewLine + '  <a href="/matchup">Matchup</a>') -Contract 'dashboard Matchup navigation link'

if ($core -notmatch 'href="/matchup">Matchup</a>') {
    throw 'BF-840 BLOCKED: staged core Matchup navigation is missing.'
}
if ($dashboard -notmatch 'href="/matchup">Matchup</a>') {
    throw 'BF-840 BLOCKED: staged dashboard Matchup navigation is missing.'
}
if ($core -notmatch 'PAIR VERIFIED' -or $core -notmatch 'EVIDENCE NEEDED') {
    throw 'BF-840 BLOCKED: manager-facing weekly matchup states are incomplete.'
}
if ($core -match 'favored|underdog|betting odds') {
    throw 'BF-840 BLOCKED: weekly matchup presentation introduced predictive or betting-style copy.'
}
if ($core -match 'Method = "POST"|create_transaction|submitTransaction') {
    throw 'BF-840 BLOCKED: weekly matchup presentation introduced a write path.'
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))
[System.IO.File]::WriteAllText($DashboardPath, $dashboard, [System.Text.UTF8Encoding]::new($false))
