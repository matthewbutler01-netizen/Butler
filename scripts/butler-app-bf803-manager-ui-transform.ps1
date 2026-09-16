param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-803 BLOCKED: staged Butler core not found at $CorePath"
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
        throw "BF-803 BLOCKED: $Contract start marker is missing."
    }
    $secondStart = $Text.IndexOf($StartMarker, $start + $StartMarker.Length, [System.StringComparison]::Ordinal)
    if ($secondStart -ge 0) {
        throw "BF-803 BLOCKED: $Contract start marker is ambiguous."
    }
    $next = $Text.IndexOf($NextMarker, $start + $StartMarker.Length, [System.StringComparison]::Ordinal)
    if ($next -lt 0) {
        throw "BF-803 BLOCKED: $Contract end marker is missing."
    }

    return $Text.Substring(0, $start) + $Replacement.TrimEnd() + "`r`n`r`n" + $Text.Substring($next)
}

$core = [System.IO.File]::ReadAllText($CorePath)

$cssReplacement = @"
function Get-AppCss {
    return @'
:root{font-family:Inter,Segoe UI,Arial,sans-serif;color:#f6f8fc;background:#07101f;line-height:1.45;--bg:#07101f;--surface:#0d1830;--surface-2:#111f3b;--surface-3:#172849;--line:#263a61;--line-soft:#1b2c4d;--text:#f6f8fc;--muted:#9fb0ce;--muted-2:#7f93b8;--blue:#4d7df3;--blue-2:#83a7ff;--green:#5dd39e;--amber:#f4c15d;--red:#ff7d8d;--shadow:0 18px 48px rgba(0,0,0,.28)}*{box-sizing:border-box}body{margin:0;background:linear-gradient(180deg,#0b1730 0,#07101f 32%,#050b15 100%);min-height:100vh;color:var(--text)}a{color:var(--blue-2)}.shell{max-width:1240px;margin:0 auto;padding:26px 24px 64px}.top{display:flex;justify-content:space-between;gap:24px;align-items:flex-end;margin-bottom:18px}.brand h1{font-size:34px;letter-spacing:.17em;margin:0;font-weight:900}.brand p{margin:4px 0 0;color:var(--muted);font-size:13px}.target{font-size:13px;color:#c8d4e9;text-align:right}.nav{display:flex;gap:8px;margin:0 0 20px;flex-wrap:wrap;padding:6px;border:1px solid var(--line-soft);border-radius:14px;background:rgba(8,17,34,.86)}.nav a{color:#b8c6df;text-decoration:none;padding:9px 13px;border-radius:9px;font-weight:750;font-size:13px}.nav a:hover{background:var(--surface-2);color:white}.nav a.active{background:var(--blue);color:white}.panel{background:rgba(13,24,48,.94);border:1px solid var(--line);border-radius:18px;padding:22px;box-shadow:var(--shadow);margin-bottom:16px}.hero-panel{background:linear-gradient(135deg,rgba(27,52,96,.96),rgba(13,24,48,.96) 62%)}.recommendation-panel{border-color:#365d9f;background:linear-gradient(140deg,rgba(18,39,76,.98),rgba(12,24,48,.98))}.section-head,.statusrow,.manager-head{display:flex;align-items:flex-start;justify-content:space-between;gap:18px}.eyebrow{font-size:11px;text-transform:uppercase;letter-spacing:.15em;color:#86a5dc;font-weight:800}.headline{font-size:30px;line-height:1.15;margin:6px 0}.lede{color:#c3cee2;margin:0;max-width:850px}.status{font-weight:850;padding:7px 11px;border-radius:999px;font-size:11px;white-space:nowrap;letter-spacing:.04em}.good{background:#143c31;color:#83e7b9}.warn{background:#493715;color:#f4cf7d}.danger{background:#4a202a;color:#ffadba}.done{background:#1c3768;color:#b7ccff}.stats,.manager-metrics{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px;margin-top:18px}.stat,.metric-card,.card,.action{padding:15px;border:1px solid var(--line-soft);border-radius:13px;background:rgba(9,19,39,.8)}.stat strong,.metric-label{display:block;color:var(--muted-2);font-size:10px;text-transform:uppercase;letter-spacing:.09em}.stat span,.metric-value{display:block;font-size:20px;font-weight:900;margin-top:5px}.metric-positive{color:#7ee6b7}.metric-negative{color:#ff9eab}.grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:11px;margin-top:15px}.grid.four{grid-template-columns:repeat(4,minmax(0,1fr))}.card .rank{color:#8ca5cf;font-size:11px;font-weight:800;text-transform:uppercase;letter-spacing:.05em}.card .name{font-size:17px;font-weight:850;margin-top:4px}.card .total{font-size:23px;font-weight:900;margin-top:9px}.card .meta,.meta{color:var(--muted);font-size:12px;margin-top:6px}.pressure-tier{font-size:20px;font-weight:900;margin-top:7px}.position-card{min-height:118px}.movers,.actions,.season-list{display:grid;gap:9px;margin-top:14px}.mover,.season-row{padding:12px 14px;border:1px solid var(--line-soft);border-radius:11px;background:#0a152a;font-size:12px;color:#c6d2e6}.action-head{display:flex;gap:8px;align-items:center;flex-wrap:wrap}.pill,.chip,.state-chip,.decision-chip,.position-chip{display:inline-flex;align-items:center;justify-content:center;border-radius:999px;font-size:10px;font-weight:900;letter-spacing:.04em}.pill,.chip{padding:5px 8px}.required{background:#493715;color:#f4cf7d}.optional{background:#1c3768;color:#b7ccff}.action .kind{font-weight:800;color:#d6e2f8}.action p{margin:8px 0 0;color:#c3cee2}.command{display:block;width:100%;margin-top:10px;padding:10px 12px;border-radius:10px;background:#060d1a;border:1px solid var(--line);color:#c7d9ff;font-family:Consolas,monospace;font-size:12px}.empty,.callout{margin-top:14px;padding:15px;border:1px solid var(--line);border-radius:13px;background:#0a152a;color:#b7c5dd}.callout-danger{border-color:#61303b;background:#2b1720;color:#ffc1ca}.boundary{font-size:12px;color:#93a5c3;background:#091426;box-shadow:none}.lock{font-weight:900;color:#a9c6ff}.btn{display:inline-flex;align-items:center;justify-content:center;padding:10px 14px;border-radius:10px;text-decoration:none;font-weight:850;font-size:13px;border:1px solid transparent}.btn-primary{background:var(--blue);color:white}.btn-primary:hover{background:#5b87f5}.btn-secondary{background:#101f3b;color:#c8d8f5;border-color:#2c4776}.button-row{display:flex;gap:9px;flex-wrap:wrap;margin-top:16px}.manager-summary{display:grid;grid-template-columns:minmax(0,1.6fr) minmax(280px,.7fr);gap:14px;margin-top:18px}.summary-card{padding:16px;border-radius:13px;background:#0a152a;border:1px solid var(--line-soft)}.summary-card h3{margin:0 0 5px;font-size:15px}.summary-card p{margin:0;color:var(--muted);font-size:12px}.roster-board{margin-top:16px;border:1px solid var(--line-soft);border-radius:14px;overflow:hidden;background:#091426}.roster-group+.roster-group{border-top:1px solid var(--line)}.roster-group-head{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:11px 14px;background:#101e38}.roster-group-head h3{margin:0;font-size:13px}.roster-group-head span{font-size:11px;color:var(--muted)}.player-row{display:grid;grid-template-columns:90px minmax(0,1fr) 96px;gap:12px;align-items:center;padding:11px 14px;border-top:1px solid #162746}.player-row:first-child{border-top:0}.player-row:hover{background:#0d1b34}.slot-marker{font-size:10px;font-weight:900;letter-spacing:.06em;color:#8facdd}.player-primary strong{display:block;font-size:14px}.player-primary span{display:block;color:var(--muted);font-size:11px;margin-top:2px}.state-chip{justify-self:end;padding:5px 8px}.state-start{background:#143c31;color:#83e7b9}.state-bench{background:#1c3768;color:#b7ccff}.state-reserve{background:#332b45;color:#cdbcf6}.autofill-summary{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px;margin-top:18px}.lineup-board{margin-top:16px;border:1px solid var(--line);border-radius:14px;overflow:hidden}.lineup-row{display:grid;grid-template-columns:90px minmax(0,1fr) 34px minmax(0,1fr) 84px 86px;gap:10px;align-items:center;padding:12px 14px;border-top:1px solid #1b2d4d;background:#091426}.lineup-row:first-child{border-top:0}.lineup-row.changed{background:linear-gradient(90deg,rgba(29,55,99,.48),rgba(9,20,38,.96))}.position-chip{padding:5px 8px;background:#142a50;color:#bcd0f7}.lineup-choice small{display:block;color:#7186aa;text-transform:uppercase;letter-spacing:.07em;font-size:9px;font-weight:800}.lineup-choice strong{display:block;margin-top:2px;font-size:13px}.lineup-arrow{text-align:center;color:#6685b8;font-size:18px}.projection{text-align:right;font-weight:900;font-size:14px}.projection span{display:block;color:var(--muted-2);font-weight:700;font-size:9px;text-transform:uppercase}.decision-chip{justify-self:end;padding:5px 8px}.decision-keep{background:#143c31;color:#83e7b9}.decision-change{background:#4b3713;color:#f6d17d}.movement-strip{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-top:14px}.movement-box{padding:13px;border:1px solid var(--line-soft);border-radius:12px;background:#0a152a}.movement-box strong{display:block;font-size:11px;text-transform:uppercase;letter-spacing:.06em;color:#8da6cf}.movement-box div{display:flex;gap:6px;flex-wrap:wrap;margin-top:8px}.chip{background:#162c51;color:#bed1f7}.source-note{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-top:14px;padding-top:12px;border-top:1px solid var(--line-soft);color:var(--muted);font-size:11px}details{margin-top:13px;border-top:1px solid var(--line-soft);padding-top:11px}summary{cursor:pointer;color:#a9c6ff;font-weight:700}.technical{margin-top:10px;color:#8296b8;font-size:11px}@media(max-width:960px){.grid.four{grid-template-columns:repeat(2,minmax(0,1fr))}.stats,.manager-metrics{grid-template-columns:repeat(2,minmax(0,1fr))}.lineup-row{grid-template-columns:72px minmax(0,1fr) 28px minmax(0,1fr) 70px}.decision-chip{grid-column:2/6;justify-self:start}}@media(max-width:760px){.shell{padding:18px 14px 44px}.top,.statusrow,.manager-head,.source-note{display:block}.target{text-align:left;margin-top:10px}.brand h1{font-size:28px}.headline{font-size:25px}.status{display:inline-flex;margin-top:11px}.stats,.manager-metrics,.grid,.grid.four,.manager-summary,.autofill-summary,.movement-strip{grid-template-columns:1fr}.player-row{grid-template-columns:70px minmax(0,1fr) 80px}.lineup-row{grid-template-columns:66px minmax(0,1fr) 70px;padding:11px}.lineup-arrow{display:none}.lineup-choice.current{grid-column:2}.lineup-choice.recommended{grid-column:2}.projection{grid-column:3;grid-row:1/3}.decision-chip{grid-column:2/4}.source-note .button-row{margin-top:10px}}
'@
}
"@
$core = Replace-FunctionBlock -Text $core -StartMarker 'function Get-AppCss {' -NextMarker 'function Get-AppNav {' -Replacement $cssReplacement -Contract 'shared manager design system'

$autoFillReplacement = @'
function ConvertTo-AutoFillHtml {
    param([Parameter(Mandatory = $true)]$AutoFill)

    if (-not $AutoFill.Requested) {
        return '<section class="panel recommendation-panel"><div class="manager-head"><div><div class="eyebrow">Lineup advisor</div><h2>AutoFill Roster</h2><p class="lede">Preview the highest-projected legal lineup Butler can build from your active roster using this week''s FantasyPros consensus projections.</p></div><span class="status done">PREVIEW ONLY</span></div><div class="button-row"><a class="btn btn-primary" href="/team/autofill">Run AutoFill</a></div><p class="meta"><strong>Read only:</strong> FantasyPros is contacted only after you run AutoFill. Nothing is submitted to Sleeper.</p></section>'
    }

    $frame = "Week $(ConvertTo-HtmlText $AutoFill.Week) &middot; $(ConvertTo-HtmlText $AutoFill.Scoring) scoring"
    if (-not $AutoFill.Ready) {
        return "<section class=`"panel recommendation-panel`"><div class=`"manager-head`"><div><div class=`"eyebrow`">Lineup advisor</div><h2>AutoFill unavailable</h2><p class=`"lede`">Butler could not prove a complete lineup recommendation, so it did not guess.</p></div><span class=`"status warn`">NO RECOMMENDATION</span></div><div class=`"callout callout-danger`">$(ConvertTo-HtmlText $AutoFill.Reason)</div><div class=`"source-note`"><span>$frame &middot; No lineup was submitted.</span><div class=`"button-row`"><a class=`"btn btn-primary`" href=`"/team/autofill`">Try again</a><a class=`"btn btn-secondary`" href=`"/team`">Back to My Team</a></div></div></section>"
    }

    $rows = ''
    foreach ($assignment in $AutoFill.Assignments) {
        $rowClass = if ($assignment.Changed) { 'lineup-row changed' } else { 'lineup-row' }
        $decisionClass = if ($assignment.Changed) { 'decision-chip decision-change' } else { 'decision-chip decision-keep' }
        $decision = if ($assignment.Changed) { 'CHANGE' } else { 'KEEP' }
        $rows += "<div class=`"$rowClass`"><div><span class=`"position-chip`">$(ConvertTo-HtmlText $assignment.Slot)</span></div><div class=`"lineup-choice current`"><small>Current</small><strong>$(ConvertTo-HtmlText $assignment.Current)</strong></div><div class=`"lineup-arrow`">&rarr;</div><div class=`"lineup-choice recommended`"><small>Recommended</small><strong>$(ConvertTo-HtmlText $assignment.Recommended)</strong></div><div class=`"projection`">$(ConvertTo-HtmlText $assignment.Points)<span>proj pts</span></div><span class=`"$decisionClass`">$decision</span></div>"
    }

    $benchChips = if ($AutoFill.BenchMoves.Count -eq 0) {
        '<span class="chip">No changes</span>'
    } else {
        (@($AutoFill.BenchMoves | ForEach-Object { "<span class=`"chip`">$(ConvertTo-HtmlText $_.Name)</span>" }) -join '')
    }
    $promotionChips = if ($AutoFill.Promotions.Count -eq 0) {
        '<span class="chip">No changes</span>'
    } else {
        (@($AutoFill.Promotions | ForEach-Object { "<span class=`"chip`">$(ConvertTo-HtmlText $_.Name)</span>" }) -join '')
    }
    $gainClass = if ([string]$AutoFill.Gain -match '^-') { 'metric-value metric-negative' } else { 'metric-value metric-positive' }

    return "<section class=`"panel recommendation-panel`"><div class=`"manager-head`"><div><div class=`"eyebrow`">Lineup advisor</div><h2>Butler's recommended lineup</h2><p class=`"lede`">Current lineup versus the strongest legal projected lineup Butler found for this week.</p></div><span class=`"status good`">READY</span></div><div class=`"autofill-summary`"><div class=`"metric-card`"><span class=`"metric-label`">Current projection</span><span class=`"metric-value`">$(ConvertTo-HtmlText $AutoFill.CurrentTotal)</span></div><div class=`"metric-card`"><span class=`"metric-label`">Recommended</span><span class=`"metric-value`">$(ConvertTo-HtmlText $AutoFill.RecommendedTotal)</span></div><div class=`"metric-card`"><span class=`"metric-label`">Projected change</span><span class=`"$gainClass`">$(ConvertTo-HtmlText $AutoFill.Gain)</span></div></div><div class=`"lineup-board`">$rows</div><div class=`"movement-strip`"><div class=`"movement-box`"><strong>Promote to lineup</strong><div>$promotionChips</div></div><div class=`"movement-box`"><strong>Move to bench</strong><div>$benchChips</div></div></div><div class=`"source-note`"><span>Projections: $(ConvertTo-HtmlText $AutoFill.Source) &middot; $frame &middot; Preview only</span><div class=`"button-row`"><a class=`"btn btn-secondary`" href=`"/team/autofill`">Refresh projection</a><a class=`"btn btn-secondary`" href=`"/team`">Back to My Team</a></div></div><p class=`"meta`"><strong>Read only:</strong> Butler did not submit this lineup to Sleeper.</p></section>"
}
'@
$core = Replace-FunctionBlock -Text $core -StartMarker 'function ConvertTo-AutoFillHtml {' -NextMarker 'function ConvertTo-TeamHtml {' -Replacement $autoFillReplacement -Contract 'AutoFill manager presentation'

$teamReplacement = @'
function ConvertTo-TeamHtml {
    param(
        [Parameter(Mandatory = $true)]$Roster,
        [Parameter(Mandatory = $true)]$Context,
        [Parameter(Mandatory = $true)]$Strength,
        [Parameter(Mandatory = $true)]$Pressure,
        [Parameter(Mandatory = $true)]$Posture,
        [Parameter(Mandatory = $true)]$Capital,
        [Parameter(Mandatory = $true)]$AutoFill
    )

    $displayTeam = if (-not [string]::IsNullOrWhiteSpace($Roster.TeamName) -and $Roster.TeamName -cne "none") { $Roster.TeamName } else { $Roster.ButlerTeamName }
    $rankText = if ($Context.Rank -eq "-") { "Unavailable" } else { "#$($Context.Rank)" }
    $strengthTier = if ($Strength.Available) { $Strength.Tier } else { "Unavailable" }
    $postureText = if ($Posture.Available) { $Posture.Posture } else { "Unavailable" }
    $capitalTier = if ($Capital.Available) { $Capital.Tier } else { "Unavailable" }

    $positionHtml = ''
    foreach ($position in $Pressure) {
        if ($position.Available) {
            $positionHtml += "<article class=`"card position-card`"><div class=`"rank`">$(ConvertTo-HtmlText $position.Position) &middot; $(ConvertTo-HtmlText $position.DirectStarters) starter slot(s)</div><div class=`"pressure-tier`">$(ConvertTo-HtmlText $position.Tier)</div><div class=`"meta`">Starter coverage $(ConvertTo-HtmlText $position.StarterCoverageValue) &middot; total value $(ConvertTo-HtmlText $position.TotalPositionValue)</div></article>"
        }
        else {
            $positionHtml += "<article class=`"card position-card`"><div class=`"rank`">$(ConvertTo-HtmlText $position.Position)</div><div class=`"pressure-tier`">Unavailable</div><div class=`"meta`">$(ConvertTo-HtmlText $position.Reason)</div></article>"
        }
    }

    $seasonHtml = ''
    if ($Capital.Available -and $Capital.Seasons.Count -gt 0) {
        foreach ($season in $Capital.Seasons) {
            $seasonHtml += "<div class=`"season-row`"><strong>$(ConvertTo-HtmlText $season.Season)</strong> &middot; value $(ConvertTo-HtmlText $season.Value) &middot; coverage $(ConvertTo-HtmlText $season.Coverage)% &middot; $(ConvertTo-HtmlText $season.Rounds)</div>"
        }
    }
    elseif (-not $Capital.Available) {
        $seasonHtml = "<div class=`"empty`">$(ConvertTo-HtmlText $Capital.Reason)</div>"
    }
    else {
        $seasonHtml = '<div class="empty">No future-pick rows are available.</div>'
    }

    $starterRows = ''
    $starters = @($Roster.Players | Where-Object { $_.Slot -ceq 'STARTER' } | Sort-Object -Property @{ Expression = { if ([string]::IsNullOrWhiteSpace([string]$_.Ordinal)) { 999 } else { [int]$_.Ordinal } } })
    foreach ($player in $starters) {
        $lineupSlot = if ([string]::IsNullOrWhiteSpace([string]$player.Lineup)) { 'START' } else { [string]$player.Lineup }
        $starterRows += "<div class=`"player-row`"><div class=`"slot-marker`">$(ConvertTo-HtmlText $lineupSlot)</div><div class=`"player-primary`"><strong>$(ConvertTo-HtmlText $player.Name)</strong><span>$(ConvertTo-HtmlText $player.Position) &middot; $(ConvertTo-HtmlText $player.NflTeam)</span></div><span class=`"state-chip state-start`">START</span></div>"
    }
    if ($starters.Count -eq 0) { $starterRows = '<div class="empty">No starter rows are available.</div>' }

    $benchRows = ''
    $bench = @($Roster.Players | Where-Object { $_.Slot -ceq 'BENCH' } | Sort-Object Position, Name)
    foreach ($player in $bench) {
        $benchRows += "<div class=`"player-row`"><div class=`"slot-marker`">$(ConvertTo-HtmlText $player.Position)</div><div class=`"player-primary`"><strong>$(ConvertTo-HtmlText $player.Name)</strong><span>$(ConvertTo-HtmlText $player.Position) &middot; $(ConvertTo-HtmlText $player.NflTeam)</span></div><span class=`"state-chip state-bench`">BENCH</span></div>"
    }
    if ($bench.Count -eq 0) { $benchRows = '<div class="empty">No bench players are available.</div>' }

    $reserveRows = ''
    $reserve = @($Roster.Players | Where-Object { $_.Slot -notin @('STARTER','BENCH') } | Sort-Object Slot, Position, Name)
    foreach ($player in $reserve) {
        $state = if ([string]::IsNullOrWhiteSpace([string]$player.Slot)) { 'RESERVE' } else { ([string]$player.Slot).ToUpperInvariant() }
        $reserveRows += "<div class=`"player-row`"><div class=`"slot-marker`">$(ConvertTo-HtmlText $player.Position)</div><div class=`"player-primary`"><strong>$(ConvertTo-HtmlText $player.Name)</strong><span>$(ConvertTo-HtmlText $player.Position) &middot; $(ConvertTo-HtmlText $player.NflTeam)</span></div><span class=`"state-chip state-reserve`">$(ConvertTo-HtmlText $state)</span></div>"
    }
    if ($reserve.Count -eq 0) { $reserveRows = '<div class="empty">No reserve or taxi players.</div>' }

    $autoFillHtml = ConvertTo-AutoFillHtml -AutoFill $AutoFill
    $css = Get-AppCss
    $nav = Get-AppNav -Active "team"

    return @"
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="dark"><title>Butler - My Team</title><style>$css</style></head><body><main class="shell">
<header class="top"><div class="brand"><h1>BUTLER</h1><p>We're here to serve you. Less Research. Better Decisions.</p></div><div class="target">$(ConvertTo-HtmlText $Roster.LeagueName) &middot; $(ConvertTo-HtmlText $displayTeam)</div></header>
$nav
<section class="panel hero-panel"><div class="manager-head"><div><div class="eyebrow">My Team</div><h1 class="headline">$(ConvertTo-HtmlText $displayTeam)</h1><p class="lede">Your roster, team outlook, and the lineup decision that matters this week.</p></div><span class="status good">UP TO DATE</span></div><div class="manager-metrics"><div class="metric-card"><span class="metric-label">Franchise rank</span><span class="metric-value">$(ConvertTo-HtmlText $rankText)</span></div><div class="metric-card"><span class="metric-label">Roster strength</span><span class="metric-value">$(ConvertTo-HtmlText $strengthTier)</span></div><div class="metric-card"><span class="metric-label">Team direction</span><span class="metric-value">$(ConvertTo-HtmlText $postureText)</span></div><div class="metric-card"><span class="metric-label">Draft capital</span><span class="metric-value">$(ConvertTo-HtmlText $capitalTier)</span></div></div></section>
$autoFillHtml
<section class="panel"><div class="section-head"><div><div class="eyebrow">Roster construction</div><h2>Position outlook</h2><p class="lede">Where Butler sees strength or pressure using the roster evidence already available.</p></div></div><div class="grid four">$positionHtml</div></section>
<section class="panel"><div class="section-head"><div><div class="eyebrow">Roster</div><h2>Players by lineup state</h2><p class="lede">Starters first, then bench and reserve. No player IDs or operator-only details are shown in the manager view.</p></div></div><div class="stats"><div class="stat"><strong>Starters</strong><span>$(ConvertTo-HtmlText $Roster.StarterCount)</span></div><div class="stat"><strong>Bench</strong><span>$(ConvertTo-HtmlText $Roster.BenchCount)</span></div><div class="stat"><strong>Reserve</strong><span>$(ConvertTo-HtmlText $Roster.ReserveCount)</span></div><div class="stat"><strong>Taxi</strong><span>$(ConvertTo-HtmlText $Roster.TaxiCount)</span></div></div><div class="roster-board"><section class="roster-group"><div class="roster-group-head"><h3>Starting lineup</h3><span>Current Sleeper starters</span></div>$starterRows</section><section class="roster-group"><div class="roster-group-head"><h3>Bench</h3><span>Available for lineup changes</span></div>$benchRows</section><section class="roster-group"><div class="roster-group-head"><h3>Reserve &amp; taxi</h3><span>Non-active roster slots</span></div>$reserveRows</section></div></section>
<section class="panel"><div class="eyebrow">Future flexibility</div><h2>Draft capital</h2><div class="season-list">$seasonHtml</div></section>
<section class="panel boundary"><span class="lock">READ ONLY.</span> Butler can preview lineup changes here, but this manager screen does not submit a lineup, change your Sleeper roster, set FAAB, execute a trade, or perform any Sleeper transaction.</section>
</main></body></html>
"@
}
'@
$core = Replace-FunctionBlock -Text $core -StartMarker 'function ConvertTo-TeamHtml {' -NextMarker 'function Add-LeagueNavigation {' -Replacement $teamReplacement -Contract 'My Team manager presentation'

if ($core -notmatch 'Butler''s recommended lineup' -or $core -notmatch 'Players by lineup state') {
    throw 'BF-803 BLOCKED: staged manager presentation markers were not installed.'
}
if ($core -match 'roster-card.*SleeperId' -or $core -match 'roster-card.*ButlerPlayerId') {
    throw 'BF-803 BLOCKED: raw player identifiers remain in the normal My Team roster presentation.'
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))
