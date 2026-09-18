param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-840 BLOCKED: staged Butler core not found at $CorePath"
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
    if ($Text.IndexOf($StartMarker, $start + $StartMarker.Length, [System.StringComparison]::Ordinal) -ge 0) {
        throw "BF-840 BLOCKED: $Contract start marker is ambiguous."
    }
    $next = $Text.IndexOf($NextMarker, $start + $StartMarker.Length, [System.StringComparison]::Ordinal)
    if ($next -lt 0) { throw "BF-840 BLOCKED: $Contract end marker is missing." }
    return $Text.Substring(0, $start) + $Replacement.TrimEnd() + "`r`n`r`n" + $Text.Substring($next)
}

$core = [System.IO.File]::ReadAllText($CorePath)

$providerStatus = '        ProviderStatus = $season.Groups[''status''].Value.Trim()'
$providerStatusWithLeg = @'
        ProviderStatus = $season.Groups['status'].Value.Trim()
        ProviderLeg = $season.Groups['leg'].Value.Trim()
'@
$statusCount = [regex]::Matches($core, [regex]::Escape($providerStatus)).Count
if ($statusCount -ne 1) {
    throw "BF-840 BLOCKED: roster provider-frame contract expected one match, found $statusCount."
}
$core = $core.Replace($providerStatus, $providerStatusWithLeg.TrimEnd())

$navReplacement = @'
function Get-AppNav {
    param([Parameter(Mandatory = $true)][string]$Active)
    $dashboardClass = if ($Active -ceq "dashboard") { ' class="active"' } else { "" }
    $teamClass = if ($Active -ceq "team") { ' class="active"' } else { "" }
    $matchupClass = if ($Active -ceq "matchup") { ' class="active"' } else { "" }
    $waiversClass = if ($Active -ceq "waivers") { ' class="active"' } else { "" }
    $leagueClass = if ($Active -ceq "league") { ' class="active"' } else { "" }
    return "<nav class=`"nav`" aria-label=`"Butler sections`"><a$dashboardClass href=`"/`">Dashboard</a><a$teamClass href=`"/team`">My Team</a><a$matchupClass href=`"/matchup`">Matchup</a><a$waiversClass href=`"/waivers`">Waiver Board</a><a$leagueClass href=`"/league`">League</a></nav>"
}
'@
$core = Replace-FunctionBlock -Text $core -StartMarker 'function Get-AppNav {' -NextMarker 'function ConvertTo-LeagueHtml {' -Replacement $navReplacement -Contract 'manager navigation'

$matchupFunctions = @'
function ConvertTo-WeeklyMatchupView {
    param([Parameter(Mandatory = $true)][string]$Text)

    $state = [regex]::Match($Text, '(?m)^State:\s+(?<value>READY)\s*$')
    $leagueId = [regex]::Match($Text, '(?m)^League ID:\s+(?<value>\S+)\s*$')
    $leagueName = [regex]::Match($Text, '(?m)^League name:\s+(?<value>.+?)\s*$')
    $season = [regex]::Match($Text, '(?m)^Season:\s+(?<value>\d+)\s*$')
    $week = [regex]::Match($Text, '(?m)^Week:\s+(?<value>\d+)\s*$')
    $matchupId = [regex]::Match($Text, '(?m)^Matchup ID:\s+(?<value>\d+)\s*$')
    $userTeamId = [regex]::Match($Text, '(?m)^User team ID:\s+(?<value>\S+)\s*$')
    $userTeamName = [regex]::Match($Text, '(?m)^User team name:\s+(?<value>.+?)\s*$')
    $opponentTeamId = [regex]::Match($Text, '(?m)^Opponent team ID:\s+(?<value>\S+)\s*$')
    $opponentTeamName = [regex]::Match($Text, '(?m)^Opponent team name:\s+(?<value>.+?)\s*$')
    $source = [regex]::Match($Text, '(?m)^Source:\s+(?<value>\S+)\s*$')
    $asOf = [regex]::Match($Text, '(?m)^As-of:\s+(?<value>\d{4}-\d{2}-\d{2})\s*$')

    foreach ($required in @($state,$leagueId,$leagueName,$season,$week,$matchupId,$userTeamId,$userTeamName,$opponentTeamId,$opponentTeamName,$source,$asOf)) {
        if (-not $required.Success) {
            throw 'BF-840 BLOCKED: exact weekly matchup output is missing a required field.'
        }
    }

    return [pscustomobject]@{
        LeagueId = $leagueId.Groups['value'].Value.Trim()
        LeagueName = $leagueName.Groups['value'].Value.Trim()
        Season = [int]$season.Groups['value'].Value
        Week = [int]$week.Groups['value'].Value
        MatchupId = [int]$matchupId.Groups['value'].Value
        UserTeamId = $userTeamId.Groups['value'].Value.Trim()
        UserTeamName = $userTeamName.Groups['value'].Value.Trim()
        OpponentTeamId = $opponentTeamId.Groups['value'].Value.Trim()
        OpponentTeamName = $opponentTeamName.Groups['value'].Value.Trim()
        Source = $source.Groups['value'].Value.Trim()
        AsOf = $asOf.Groups['value'].Value.Trim()
    }
}

function ConvertTo-MatchupOpponentContextHtml {
    param(
        [Parameter(Mandatory = $true)]$Strength,
        [Parameter(Mandatory = $true)]$Pressure
    )

    if ($Strength.Available) {
        $strengthText = [string]$Strength.Tier
        $strengthEvidence = "Starter value $($Strength.StarterValue) | roster value $($Strength.TotalPlayerValue) | coverage $($Strength.Coverage)%"
    }
    else {
        $strengthText = 'Coverage needed'
        $strengthEvidence = if ($null -ne $Strength.PSObject.Properties['Reason']) { [string]$Strength.Reason } else { 'Current governed roster-strength evidence is unavailable.' }
    }

    $positionCards = ''
    foreach ($position in $Pressure) {
        if ($position.Available) {
            $positionCards += "<article class=`"card position-card`"><div class=`"rank`">$(ConvertTo-HtmlText $position.Position)</div><div class=`"pressure-tier`">$(ConvertTo-HtmlText $position.Tier)</div><div class=`"meta`">Starter coverage $(ConvertTo-HtmlText $position.StarterCoverageValue) &middot; total position value $(ConvertTo-HtmlText $position.TotalPositionValue)</div></article>"
        }
        else {
            $positionCards += "<article class=`"card position-card`"><div class=`"rank`">$(ConvertTo-HtmlText $position.Position)</div><div class=`"pressure-tier`">Coverage needed</div><div class=`"meta`">$(ConvertTo-HtmlText $position.Reason)</div></article>"
        }
    }

    return "<section class=`"panel`"><div class=`"section-head`"><div><div class=`"eyebrow`">Opponent context</div><h2>Roster profile</h2><p class=`"lede`">Existing governed roster-strength and positional evidence only. This context does not predict a matchup winner.</p></div></div><div class=`"manager-metrics`"><div class=`"metric-card`"><span class=`"metric-label`">Roster strength</span><span class=`"metric-value`">$(ConvertTo-HtmlText $strengthText)</span><div class=`"meta`">$(ConvertTo-HtmlText $strengthEvidence)</div></div></div><div class=`"grid four`">$positionCards</div></section>"
}

function ConvertTo-MatchupHtml {
    param(
        [Parameter(Mandatory = $true)]$Roster,
        [Parameter(Mandatory = $true)]$Matchup,
        [Parameter(Mandatory = $true)]$AutoFill,
        [Parameter(Mandatory = $true)]$OpponentStrength,
        [Parameter(Mandatory = $true)]$OpponentPressure
    )

    $css = Get-AppCss
    $nav = Get-AppNav -Active 'matchup'
    $displayTeam = if (-not [string]::IsNullOrWhiteSpace([string]$Roster.TeamName) -and $Roster.TeamName -cne 'none') { $Roster.TeamName } else { $Roster.ButlerTeamName }
    $autoFillHtml = ConvertTo-AutoFillHtml -AutoFill $AutoFill
    $opponentHtml = ConvertTo-MatchupOpponentContextHtml -Strength $OpponentStrength -Pressure $OpponentPressure

    return @"
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="dark"><title>Butler - Weekly Matchup</title><style>$css</style></head><body><main class="shell">
<header class="top"><div class="brand"><h1>BUTLER</h1><p>We're here to serve you. Less Research. Better Decisions.</p></div><div class="target">$(ConvertTo-HtmlText $Roster.LeagueName) &middot; Week $(ConvertTo-HtmlText $Matchup.Week)</div></header>
$nav
<section class="panel hero-panel"><div class="manager-head"><div><div class="eyebrow">Weekly matchup</div><h1 class="headline">$(ConvertTo-HtmlText $displayTeam) vs. $(ConvertTo-HtmlText $Matchup.OpponentTeamName)</h1><p class="lede">Exact Sleeper matchup pairing for Week $(ConvertTo-HtmlText $Matchup.Week). Butler uses this page to organize your lineup decision and supported opponent context, not to predict a winner.</p></div><span class="status good">PAIRING VERIFIED</span></div><div class="stats"><div class="stat"><strong>Week</strong><span>$(ConvertTo-HtmlText $Matchup.Week)</span></div><div class="stat"><strong>Your team</strong><span>$(ConvertTo-HtmlText $displayTeam)</span></div><div class="stat"><strong>Opponent</strong><span>$(ConvertTo-HtmlText $Matchup.OpponentTeamName)</span></div></div><details><summary>Pairing evidence</summary><div class="technical">Sleeper matchup $(ConvertTo-HtmlText $Matchup.MatchupId) &middot; source $(ConvertTo-HtmlText $Matchup.Source) &middot; as-of $(ConvertTo-HtmlText $Matchup.AsOf)</div></details></section>
$autoFillHtml
$opponentHtml
<section class="panel boundary"><span class="lock">READ ONLY.</span> Weekly Matchup uses exact persisted Sleeper pairing plus Butler's existing governed Lineup Advisor, roster-strength, and positional context. It does not calculate win probability, predict a winner, submit a lineup, refresh evidence automatically, or execute any Sleeper transaction.</section>
</main></body></html>
"@
}

function ConvertTo-MatchupUnavailableHtml {
    param(
        [Parameter(Mandatory = $true)]$Roster,
        [Parameter(Mandatory = $true)]$AutoFill,
        [Parameter(Mandatory = $true)][string]$Reason
    )

    $css = Get-AppCss
    $nav = Get-AppNav -Active 'matchup'
    $displayTeam = if (-not [string]::IsNullOrWhiteSpace([string]$Roster.TeamName) -and $Roster.TeamName -cne 'none') { $Roster.TeamName } else { $Roster.ButlerTeamName }
    $autoFillHtml = ConvertTo-AutoFillHtml -AutoFill $AutoFill

    return @"
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="dark"><title>Butler - Weekly Matchup</title><style>$css</style></head><body><main class="shell">
<header class="top"><div class="brand"><h1>BUTLER</h1><p>We're here to serve you. Less Research. Better Decisions.</p></div><div class="target">$(ConvertTo-HtmlText $Roster.LeagueName) &middot; $(ConvertTo-HtmlText $displayTeam)</div></header>
$nav
<section class="panel hero-panel"><div class="manager-head"><div><div class="eyebrow">Weekly matchup</div><h1 class="headline">Opponent pairing unavailable</h1><p class="lede">Butler could not prove the exact current Sleeper opponent, so no opponent is inferred or displayed.</p></div><span class="status warn">EVIDENCE NEEDED</span></div><div class="callout">$(ConvertTo-HtmlText $Reason)</div></section>
$autoFillHtml
<section class="panel boundary"><span class="lock">READ ONLY.</span> Butler keeps the lineup review available when supported, but it will not guess the opponent or imply a matchup result when exact pairing evidence is missing.</section>
</main></body></html>
"@
}

'@

$leagueMarker = 'function ConvertTo-LeagueHtml {'
$leagueIndex = $core.IndexOf($leagueMarker, [System.StringComparison]::Ordinal)
if ($leagueIndex -lt 0) {
    throw 'BF-840 BLOCKED: League renderer insertion marker is missing.'
}
$core = $core.Insert($leagueIndex, $matchupFunctions.TrimEnd() + "`r`n")

$routeMarker = '            if ($path -eq "/league") {'
$routeIndex = $core.IndexOf($routeMarker, [System.StringComparison]::Ordinal)
if ($routeIndex -lt 0) {
    throw 'BF-840 BLOCKED: matchup route insertion marker is missing.'
}

$matchupRoute = @'
            if ($path -eq "/matchup") {
                try {
                    $bundleText = Invoke-ButlerReadOnlyTask -Task ":bet:bet-cli:sleeperLiveWaiverTargetRosterContextAudit" -Arguments "$LeagueId --team-bundle-autofill" -BoundaryName "BF-840"
                    $rosterView = ConvertTo-RosterContextView -Text (Get-TeamEvidenceBundleSection -Text $bundleText -Name "ROSTER_CONTEXT")
                    $autoFill = ConvertTo-AutoFillView -Text (Get-TeamEvidenceBundleSection -Text $bundleText -Name "AUTOFILL")

                    $week = 0
                    if (-not [int]::TryParse([string]$rosterView.ProviderLeg, [ref]$week) -or $week -le 0) {
                        $html = ConvertTo-MatchupUnavailableHtml -Roster $rosterView -AutoFill $autoFill -Reason "Current Sleeper week is unavailable, so exact opponent pairing cannot be resolved."
                        Send-HttpResponse -Stream $stream -StatusCode 200 -StatusText "OK" -ContentType "text/html; charset=utf-8" -Body $html
                        continue
                    }

                    try {
                        $rawMatchup = Invoke-ButlerReadOnlyTask -Task ":bet:bet-cli:weeklyMatchupWorkspace" -Arguments "$($rosterView.SleeperLeagueId) $($rosterView.ButlerTeamId) $($rosterView.Season) $week" -BoundaryName "BF-840"
                        $matchup = ConvertTo-WeeklyMatchupView -Text $rawMatchup
                        if ($matchup.UserTeamId -cne $rosterView.ButlerTeamId -or $matchup.Week -ne $week -or $matchup.Season -ne $rosterView.Season) {
                            throw "BF-840 BLOCKED: exact matchup frame does not match the bound roster frame."
                        }
                        $strengthText = Get-TeamEvidenceBundleSection -Text $bundleText -Name "ROSTER_STRENGTH"
                        $pressureText = Get-TeamEvidenceBundleSection -Text $bundleText -Name "POSITIONAL_PRESSURE"
                        $opponentStrength = ConvertTo-RosterStrengthView -Text $strengthText -TeamId $matchup.OpponentTeamId
                        $opponentPressure = ConvertTo-PositionalPressureView -Text $pressureText -TeamId $matchup.OpponentTeamId
                        $html = ConvertTo-MatchupHtml -Roster $rosterView -Matchup $matchup -AutoFill $autoFill -OpponentStrength $opponentStrength -OpponentPressure $opponentPressure
                    }
                    catch {
                        $html = ConvertTo-MatchupUnavailableHtml -Roster $rosterView -AutoFill $autoFill -Reason $_.Exception.Message
                    }

                    Send-HttpResponse -Stream $stream -StatusCode 200 -StatusText "OK" -ContentType "text/html; charset=utf-8" -Body $html
                }
                catch {
                    $errorHtml = "<!doctype html><html><body><h1>Butler Weekly Matchup view blocked</h1><pre>$(ConvertTo-HtmlText $_.Exception.Message)</pre><p>No Butler or Sleeper write was executed.</p></body></html>"
                    Send-HttpResponse -Stream $stream -StatusCode 500 -StatusText "Internal Server Error" -ContentType "text/html; charset=utf-8" -Body $errorHtml
                }
                continue
            }

'@
$core = $core.Insert($routeIndex, $matchupRoute)

foreach ($required in @(
    'ProviderLeg = $season.Groups[''leg''].Value.Trim()',
    '/matchup',
    'Weekly matchup',
    'PAIRING VERIFIED',
    'Opponent pairing unavailable',
    'does not predict a winner',
    ':bet:bet-cli:weeklyMatchupWorkspace',
    '--team-bundle-autofill',
    'ConvertTo-AutoFillHtml -AutoFill $AutoFill'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-840 BLOCKED: required weekly-matchup marker is missing: $required"
    }
}

$installedStart = $core.IndexOf('function ConvertTo-WeeklyMatchupView {', [System.StringComparison]::Ordinal)
$installedEnd = $core.IndexOf('function ConvertTo-LeagueHtml {', $installedStart, [System.StringComparison]::Ordinal)
$installedBlock = $core.Substring($installedStart, $installedEnd - $installedStart)
if ($installedBlock -match 'winnerProbability|predictedWinner|Method = "POST"|submitTransaction|setFaab|Invoke-RestMethod|https://api\.sleeper\.app') {
    throw 'BF-840 BLOCKED: Weekly Matchup presentation introduced prediction, provider, or write behavior.'
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($CorePath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $parseSummary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-840 BLOCKED: generated staged core failed PowerShell parse: $parseSummary"
}
