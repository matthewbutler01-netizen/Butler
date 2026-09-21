param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-881 BLOCKED: staged Butler core not found at $CorePath"
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
    if ($start -lt 0) {
        throw "BF-881 BLOCKED: $Contract start marker is missing."
    }
    if ($Text.IndexOf($StartMarker, $start + $StartMarker.Length, [System.StringComparison]::Ordinal) -ge 0) {
        throw "BF-881 BLOCKED: $Contract start marker is ambiguous."
    }
    $next = $Text.IndexOf($NextMarker, $start + $StartMarker.Length, [System.StringComparison]::Ordinal)
    if ($next -lt 0) {
        throw "BF-881 BLOCKED: $Contract end marker is missing."
    }

    return $Text.Substring(0, $start) + $Replacement.TrimEnd() + "`r`n`r`n" + $Text.Substring($next)
}

$core = [System.IO.File]::ReadAllText($CorePath)

$matchupReplacement = @'
function Get-MatchupLineupDecisionView {
    param([Parameter(Mandatory = $true)]$AutoFill)

    if (-not $AutoFill.Requested) {
        return [pscustomobject]@{
            Title = "Review this week's lineup"
            Copy = 'Your opponent is confirmed. Run the existing read-only Lineup Advisor before deciding whether to change any starters.'
            Status = 'NOT REVIEWED'
            StatusClass = 'done'
            Detail = 'No weekly lineup recommendation has been requested from this page yet.'
            ActionLabel = 'Review Lineup'
            ActionHref = '/matchup/autofill'
        }
    }

    if (-not $AutoFill.Ready) {
        return [pscustomobject]@{
            Title = 'Lineup review needs evidence'
            Copy = 'Butler could not prove a complete weekly lineup recommendation from the current evidence, so it will not guess.'
            Status = 'EVIDENCE GAP'
            StatusClass = 'warn'
            Detail = [string]$AutoFill.Reason
            ActionLabel = 'Retry Lineup Review'
            ActionHref = '/matchup/autofill'
        }
    }

    $changedAssignments = @($AutoFill.Assignments | Where-Object { $_.Changed })
    $projectionHolds = @($AutoFill.ProjectionHolds)
    if ($projectionHolds.Count -gt 0 -or [string]$AutoFill.ProjectionCoverage -ceq 'PARTIAL') {
        $holdNames = @($projectionHolds | ForEach-Object { [string]$_.Name })
        $holdText = if ($holdNames.Count -eq 0) { 'one or more active roster players' } else { $holdNames -join ', ' }
        $changeText = if ($changedAssignments.Count -gt 0) {
            "$($changedAssignments.Count) scoreable-slot change(s) found"
        } else {
            'No proven changes in scoreable slots'
        }

        return [pscustomobject]@{
            Title = 'Partial lineup review'
            Copy = 'Butler completed the scoreable portion of the lineup review, but one or more active players lack usable current-week projection evidence.'
            Status = 'PARTIAL REVIEW'
            StatusClass = 'warn'
            Detail = "$changeText | Projection hold: $holdText | Held players remain unchanged and receive no synthetic projection."
            ActionLabel = ''
            ActionHref = ''
        }
    }

    if ($changedAssignments.Count -gt 0) {
        $changeWord = if ($changedAssignments.Count -eq 1) { 'change' } else { 'changes' }
        $startNames = @($AutoFill.Promotions | ForEach-Object { [string]$_.Name })
        $sitNames = @($AutoFill.BenchMoves | ForEach-Object { [string]$_.Name })
        $startText = if ($startNames.Count -eq 0) { 'See exact lineup rows below' } else { $startNames -join ', ' }
        $sitText = if ($sitNames.Count -eq 0) { 'See exact lineup rows below' } else { $sitNames -join ', ' }

        return [pscustomobject]@{
            Title = "Make $($changedAssignments.Count) lineup $changeWord"
            Copy = 'The existing governed Lineup Advisor found a stronger legal projected lineup for this weekly frame. Review the exact START/SIT moves before deciding.'
            Status = 'CHANGES FOUND'
            StatusClass = 'good'
            Detail = "Start: $startText | Sit: $sitText | Projected change: $($AutoFill.Gain)"
            ActionLabel = ''
            ActionHref = ''
        }
    }

    return [pscustomobject]@{
        Title = 'Keep the current lineup'
        Copy = 'The existing governed Lineup Advisor found no proven projected improvement over the current legal starters for this weekly frame.'
        Status = 'NO CHANGES'
        StatusClass = 'done'
        Detail = "Current projected starter total: $($AutoFill.CurrentTotal)"
        ActionLabel = ''
        ActionHref = ''
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

    $css = Get-AppCss
    $nav = Get-AppNav -Active 'matchup'
    $displayTeam = if (-not [string]::IsNullOrWhiteSpace([string]$Roster.TeamName) -and $Roster.TeamName -cne 'none') { $Roster.TeamName } else { $Roster.ButlerTeamName }
    $decision = Get-MatchupLineupDecisionView -AutoFill $AutoFill
    $autoFillHtml = ConvertTo-MatchupAutoFillHtml -AutoFill $AutoFill
    $opponentHtml = ConvertTo-MatchupOpponentContextHtml -Strength $OpponentStrength -Pressure $OpponentPressure
    $decisionAction = if (-not [string]::IsNullOrWhiteSpace([string]$decision.ActionHref)) {
        "<div class=`"button-row`"><a class=`"btn btn-primary`" href=`"$(ConvertTo-HtmlText $decision.ActionHref)`">$(ConvertTo-HtmlText $decision.ActionLabel)</a></div>"
    }
    else { '' }

    return @"
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="dark"><title>Butler - Weekly Matchup</title><style>$css</style></head><body><main class="shell">
<header class="top"><div class="brand"><h1>BUTLER</h1><p>We're here to serve you. Less Research. Better Decisions.</p></div><div class="target">$(ConvertTo-HtmlText $Roster.LeagueName) &middot; Week $(ConvertTo-HtmlText $Matchup.Week)</div></header>
$nav
<section class="panel hero-panel"><div class="manager-head"><div><div class="eyebrow">Weekly matchup &middot; lineup decision first</div><h1 class="headline">$(ConvertTo-HtmlText $decision.Title)</h1><p class="lede">$(ConvertTo-HtmlText $displayTeam) vs. $(ConvertTo-HtmlText $Matchup.OpponentTeamName). $(ConvertTo-HtmlText $decision.Copy)</p></div><span class="status $($decision.StatusClass)">$(ConvertTo-HtmlText $decision.Status)</span></div><div class="next"><strong>What to do now</strong><p>$(ConvertTo-HtmlText $decision.Detail)</p></div>$decisionAction<div class="stats"><div class="stat"><strong>Week</strong><span>$(ConvertTo-HtmlText $Matchup.Week)</span></div><div class="stat"><strong>Your team</strong><span>$(ConvertTo-HtmlText $displayTeam)</span></div><div class="stat"><strong>Opponent</strong><span>$(ConvertTo-HtmlText $Matchup.OpponentTeamName)</span></div></div><details><summary>Matchup evidence</summary><div class="technical">Sleeper matchup $(ConvertTo-HtmlText $Matchup.MatchupId) &middot; source $(ConvertTo-HtmlText $Matchup.Source) &middot; as-of $(ConvertTo-HtmlText $Matchup.AsOf)</div></details></section>
$autoFillHtml
$opponentHtml
<section class="panel boundary"><span class="lock">READ ONLY.</span> Weekly Matchup leads with the existing Lineup Advisor decision, then shows confirmed-opponent context. It does not create a new projection model, does not predict a winner, submit a lineup, refresh evidence automatically, or execute any Sleeper transaction.</section>
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
    $decision = Get-MatchupLineupDecisionView -AutoFill $AutoFill
    $autoFillHtml = ConvertTo-MatchupAutoFillHtml -AutoFill $AutoFill
    $decisionAction = if (-not [string]::IsNullOrWhiteSpace([string]$decision.ActionHref)) {
        "<div class=`"button-row`"><a class=`"btn btn-primary`" href=`"$(ConvertTo-HtmlText $decision.ActionHref)`">$(ConvertTo-HtmlText $decision.ActionLabel)</a></div>"
    }
    else { '' }

    return @"
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="dark"><title>Butler - Weekly Matchup</title><style>$css</style></head><body><main class="shell">
<header class="top"><div class="brand"><h1>BUTLER</h1><p>We're here to serve you. Less Research. Better Decisions.</p></div><div class="target">$(ConvertTo-HtmlText $Roster.LeagueName) &middot; $(ConvertTo-HtmlText $displayTeam)</div></header>
$nav
<section class="panel hero-panel"><div class="manager-head"><div><div class="eyebrow">Weekly matchup &middot; lineup decision first</div><h1 class="headline">$(ConvertTo-HtmlText $decision.Title)</h1><p class="lede">Opponent data is incomplete, but your existing Lineup Advisor decision remains the first manager task. $(ConvertTo-HtmlText $decision.Copy)</p></div><span class="status $($decision.StatusClass)">$(ConvertTo-HtmlText $decision.Status)</span></div><div class="next"><strong>What to do now</strong><p>$(ConvertTo-HtmlText $decision.Detail)</p></div>$decisionAction</section>
$autoFillHtml
<section class="panel"><div class="manager-head"><div><div class="eyebrow">Opponent context</div><h2>Opponent not confirmed</h2><p class="lede">Butler could not confirm the current Sleeper opponent, so it will not guess or imply a matchup result.</p></div><span class="status warn">MATCHUP DATA NEEDED</span></div><div class="callout">$(ConvertTo-HtmlText $Reason)</div></section>
<section class="panel boundary"><span class="lock">READ ONLY.</span> Butler keeps the lineup decision usable when possible while leaving missing opponent evidence explicit. It does not invent an opponent, create a new recommendation, submit a lineup, refresh evidence automatically, or execute a Sleeper transaction.</section>
</main></body></html>
"@
}
'@

$core = Replace-FunctionBlock -Text $core -StartMarker 'function ConvertTo-MatchupHtml {' -NextMarker 'function Get-PlayerDetailRequestId {' -Replacement $matchupReplacement -Contract 'Weekly Matchup decision-first presentation'

foreach ($required in @(
    'function Get-MatchupLineupDecisionView {',
    "Review this week's lineup",
    'Lineup review needs evidence',
    'Make $($changedAssignments.Count) lineup $changeWord',
    'Keep the current lineup',
    'Partial lineup review',
    'PARTIAL REVIEW',
    'Projection hold:',
    'Weekly matchup &middot; lineup decision first',
    'What to do now',
    '$autoFillHtml',
    '$opponentHtml',
    'Opponent data is incomplete',
    'does not create a new projection model'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-881 BLOCKED: required decision-first contract is missing: $required"
    }
}

$matchupStart = $core.IndexOf('function ConvertTo-MatchupHtml {', [System.StringComparison]::Ordinal)
$autoFillRender = $core.IndexOf('$autoFillHtml', $matchupStart, [System.StringComparison]::Ordinal)
$opponentRender = $core.IndexOf('$opponentHtml', $matchupStart, [System.StringComparison]::Ordinal)
if ($matchupStart -lt 0 -or $autoFillRender -lt 0 -or $opponentRender -lt 0 -or $autoFillRender -gt $opponentRender) {
    throw 'BF-881 BLOCKED: Lineup Advisor must render before opponent-context detail.'
}

$presentation = $matchupReplacement
if ($presentation -match 'Invoke-RestMethod|Invoke-WebRequest|Method = "POST"|submitTransaction|setFaab|win probability|new start/sit score') {
    throw 'BF-881 BLOCKED: decision-first presentation introduced provider, write, gambling, or new-score behavior.'
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))
