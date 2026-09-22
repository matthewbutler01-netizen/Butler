param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-908 BLOCKED: staged Butler core not found at $CorePath"
}

$core = [System.IO.File]::ReadAllText($CorePath)
$teamStart = $core.IndexOf('function ConvertTo-TeamHtml {', [System.StringComparison]::Ordinal)
$teamEnd = $core.IndexOf('function Add-LeagueNavigation {', $teamStart, [System.StringComparison]::Ordinal)
if ($teamStart -lt 0 -or $teamEnd -le $teamStart) {
    throw 'BF-908 BLOCKED: My Team renderer function boundary is missing.'
}

$teamBlock = $core.Substring($teamStart, $teamEnd - $teamStart)
if ($teamBlock.IndexOf('ConvertTo-MyTeamPlayerNameHtml', [System.StringComparison]::Ordinal) -lt 0) {
    throw 'BF-908 BLOCKED: Player Detail roster-link helper must be installed before Roster Hub.'
}

$cssStart = $core.IndexOf('function Get-AppCss {', [System.StringComparison]::Ordinal)
$cssEnd = $core.IndexOf('function Get-AppNav {', $cssStart, [System.StringComparison]::Ordinal)
if ($cssStart -lt 0 -or $cssEnd -le $cssStart) {
    throw 'BF-908 BLOCKED: manager CSS function boundary is missing.'
}
$cssBlock = $core.Substring($cssStart, $cssEnd - $cssStart)
$cssTerminator = $cssBlock.LastIndexOf("`n'@", [System.StringComparison]::Ordinal)
if ($cssTerminator -lt 0) {
    throw 'BF-908 BLOCKED: manager CSS terminator is missing.'
}

$bf908Css = @'
/* BF-908 My Team Roster Hub. */
.roster-hub{padding:20px}.roster-hub-head{display:flex;align-items:flex-start;justify-content:space-between;gap:16px}.roster-hub-head h2{margin:4px 0 5px}.roster-hub-head p{margin:0;color:var(--muted);font-size:13px;max-width:74ch}.roster-counts{display:flex;gap:7px;flex-wrap:wrap;justify-content:flex-end}.roster-count{display:inline-flex;padding:5px 8px;border:1px solid var(--line);border-radius:999px;background:var(--surface-2);color:var(--muted);font-size:10px;font-weight:800;white-space:nowrap}.roster-lane{margin-top:17px}.roster-lane-head{display:flex;align-items:baseline;justify-content:space-between;gap:12px;margin-bottom:9px}.roster-lane-head h3{margin:0;font-size:16px}.roster-lane-head span{color:var(--muted);font-size:11px}.roster-list{display:grid;gap:8px}.roster-row{display:grid;grid-template-columns:minmax(0,1.3fr) minmax(130px,.55fr) auto;align-items:center;gap:12px;padding:11px 12px;border:1px solid var(--line);border-radius:10px;background:var(--surface-2)}.roster-row.starter{border-left:3px solid var(--turf)}.roster-row.reserve{opacity:.92}.roster-player-name{font-size:14px;font-weight:850;color:var(--ink)}.roster-player-name a{color:var(--ink);text-decoration:none}.roster-player-name a:hover{color:var(--turf-deep);text-decoration:underline}.roster-player-meta{margin-top:3px;color:var(--muted);font-size:11px}.roster-slot-context{display:flex;flex-direction:column;gap:3px}.roster-slot-context strong{color:var(--ink);font-size:11px}.roster-slot-context span{color:var(--muted);font-size:10px}.roster-row-actions{display:flex;gap:7px;justify-content:flex-end;flex-wrap:wrap}.roster-mini-action{display:inline-flex;align-items:center;justify-content:center;padding:6px 8px;border:1px solid var(--line);border-radius:8px;background:var(--surface);color:var(--turf-deep);text-decoration:none;font-size:10px;font-weight:800}.roster-mini-action:hover{border-color:var(--turf)}.roster-tools-unavailable{color:var(--muted);font-size:10px}.roster-empty{padding:13px;border:1px dashed var(--line);border-radius:10px;color:var(--muted);font-size:12px}.roster-supporting{margin-top:16px;padding-top:12px;border-top:1px solid var(--line);display:flex;gap:8px;flex-wrap:wrap}.roster-supporting a{font-size:11px}@media(max-width:760px){.roster-hub{padding:16px 14px}.roster-hub-head{display:block}.roster-counts{justify-content:flex-start;margin-top:10px}.roster-row{grid-template-columns:1fr}.roster-row-actions{justify-content:flex-start}.roster-slot-context{flex-direction:row;gap:8px;align-items:center}}
'@

$cssBlock = $cssBlock.Substring(0, $cssTerminator) + "`n" + $bf908Css.TrimEnd() + $cssBlock.Substring($cssTerminator)
$core = $core.Substring(0, $cssStart) + $cssBlock + $core.Substring($cssEnd)

# Re-read the team block after CSS insertion so string offsets remain exact.
$teamStart = $core.IndexOf('function ConvertTo-TeamHtml {', [System.StringComparison]::Ordinal)
$teamEnd = $core.IndexOf('function Add-LeagueNavigation {', $teamStart, [System.StringComparison]::Ordinal)
$teamBlock = $core.Substring($teamStart, $teamEnd - $teamStart)

$preludeMarker = '    $css = Get-AppCss'
$preludeMatches = [regex]::Matches($teamBlock, [regex]::Escape($preludeMarker)).Count
if ($preludeMatches -ne 1) {
    throw "BF-908 BLOCKED: roster-hub prelude insertion expected one match, found $preludeMatches."
}

$bf908Prelude = @'
    # BF-908 is presentation-only. Reuse the exact live roster already loaded for My Team.
    $bf908Starters = @($Roster.Players | Where-Object { [string]$_.Slot -ceq "STARTER" })
    $bf908Bench = @($Roster.Players | Where-Object { [string]$_.Slot -ceq "BENCH" })
    $bf908Reserve = @($Roster.Players | Where-Object { [string]$_.Slot -cne "STARTER" -and [string]$_.Slot -cne "BENCH" })

    $bf908RenderLane = {
        param(
            [Parameter(Mandatory = $true)][object[]]$Players,
            [Parameter(Mandatory = $true)][string]$LaneClass
        )

        if ($Players.Count -eq 0) {
            return '<div class="roster-empty">No players are currently assigned to this roster lane.</div>'
        }

        $rows = New-Object System.Collections.Generic.List[string]
        foreach ($player in $Players) {
            $nameHtml = ConvertTo-MyTeamPlayerNameHtml -Player $player
            $slotLabel = if ([string]$player.Slot -ceq "STARTER" -and -not [string]::IsNullOrWhiteSpace([string]$player.Lineup)) {
                [string]$player.Lineup
            }
            else {
                [string]$player.Slot
            }

            $mapped = [string]$player.Mapping -ceq "EXACT_CANONICAL"
            $playerId = [string]$player.ButlerPlayerId
            $actions = '<span class="roster-tools-unavailable">Player tools unavailable</span>'
            if ($mapped -and -not [string]::IsNullOrWhiteSpace($playerId) -and $playerId -cne '-' -and $playerId -cne 'none') {
                $hrefId = [System.Uri]::EscapeDataString($playerId)
                $actions = '<a class="roster-mini-action" href="/player?id=' + $hrefId + '">Detail</a><a class="roster-mini-action" href="/compare?left=' + $hrefId + '">Compare</a>'
            }

            $rows.Add(@"
<article class="roster-row $LaneClass"><div><div class="roster-player-name">$nameHtml</div><div class="roster-player-meta">$(ConvertTo-HtmlText $player.Position) &middot; $(ConvertTo-HtmlText $player.NflTeam)</div></div><div class="roster-slot-context"><strong>$(ConvertTo-HtmlText $slotLabel)</strong><span>$(ConvertTo-HtmlText $player.Slot)</span></div><div class="roster-row-actions">$actions</div></article>
"@)
        }
        return ($rows -join "")
    }

    $bf908StarterRows = & $bf908RenderLane -Players $bf908Starters -LaneClass "starter"
    $bf908BenchRows = & $bf908RenderLane -Players $bf908Bench -LaneClass "bench"
    $bf908ReserveRows = & $bf908RenderLane -Players $bf908Reserve -LaneClass "reserve"

    $bf908RosterHubHtml = @"
<section class="panel roster-hub"><div class="roster-hub-head"><div><div class="eyebrow">Roster hub</div><h2>Lineup and depth at a glance</h2><p>Starters first, then bench and reserve context. Players are grouped by current roster assignment only; Butler is not ranking them here.</p></div><div class="roster-counts"><span class="roster-count">$($bf908Starters.Count) starters</span><span class="roster-count">$($bf908Bench.Count) bench</span><span class="roster-count">$($bf908Reserve.Count) reserve</span></div></div><div class="roster-lane"><div class="roster-lane-head"><h3>Starters</h3><span>Current lineup slots</span></div><div class="roster-list">$bf908StarterRows</div></div><div class="roster-lane"><div class="roster-lane-head"><h3>Bench</h3><span>Current bench depth</span></div><div class="roster-list">$bf908BenchRows</div></div>$(if ($bf908Reserve.Count -gt 0) { '<div class="roster-lane"><div class="roster-lane-head"><h3>Reserve</h3><span>IR, taxi, or other non-bench slots</span></div><div class="roster-list">' + $bf908ReserveRows + '</div></div>' } else { '' })<div class="roster-supporting"><a class="btn btn-primary" href="/matchup">Review Matchup</a><a class="btn btn-secondary" href="/matchup/autofill">Review Lineup</a><a class="btn btn-secondary" href="/players">Player Search</a></div></section>
"@

'@

$teamBlock = $teamBlock.Replace($preludeMarker, $bf908Prelude.TrimEnd() + [Environment]::NewLine + [Environment]::NewLine + $preludeMarker)

$rosterSectionStartMarker = '<section class="panel"><div class="eyebrow">Current roster</div><h2>Players</h2>'
$rosterSectionStart = $teamBlock.IndexOf($rosterSectionStartMarker, [System.StringComparison]::Ordinal)
$boundaryStart = $teamBlock.IndexOf('<section class="panel boundary">', $rosterSectionStart, [System.StringComparison]::Ordinal)
if ($rosterSectionStart -lt 0 -or $boundaryStart -le $rosterSectionStart) {
    throw 'BF-908 BLOCKED: legacy Current roster section boundary is missing.'
}
$teamBlock = $teamBlock.Remove($rosterSectionStart, $boundaryStart - $rosterSectionStart)

$positionMarker = '<section class="panel"><div class="eyebrow">Lineup-aware pressure</div><h2>Position context</h2>'
$positionIndex = $teamBlock.IndexOf($positionMarker, [System.StringComparison]::Ordinal)
if ($positionIndex -lt 0) {
    throw 'BF-908 BLOCKED: Position context insertion marker is missing.'
}
$teamBlock = $teamBlock.Insert($positionIndex, '$bf908RosterHubHtml' + [Environment]::NewLine)

$core = $core.Substring(0, $teamStart) + $teamBlock + $core.Substring($teamEnd)

foreach ($required in @(
    'BF-908 My Team Roster Hub',
    'Roster hub',
    'Lineup and depth at a glance',
    '$bf908Starters',
    '$bf908Bench',
    '$bf908Reserve',
    'ConvertTo-MyTeamPlayerNameHtml -Player $player',
    'href="/player?id=',
    'href="/compare?left=',
    'Review Matchup',
    'Review Lineup',
    'Player Search',
    '$bf908RosterHubHtml',
    'Lineup-aware pressure',
    'Future flexibility'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-908 BLOCKED: required Roster Hub marker is missing: $required"
    }
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($CorePath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $parseSummary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-908 BLOCKED: generated staged core failed PowerShell parse: $parseSummary"
}

$installedStart = $core.IndexOf('function ConvertTo-TeamHtml {', [System.StringComparison]::Ordinal)
$installedEnd = $core.IndexOf('function Add-LeagueNavigation {', $installedStart, [System.StringComparison]::Ordinal)
$installedTeam = $core.Substring($installedStart, $installedEnd - $installedStart)
if ($installedTeam -match 'Invoke-RestMethod|Invoke-WebRequest|Invoke-ButlerReadOnly|Invoke-Bf742DashboardWorkerRead|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
    throw 'BF-908 BLOCKED: Roster Hub introduced provider, optimizer, backend-read, or write behavior.'
}

Write-Host 'BF-908 My Team Roster Hub applied.'
