# BF-671/BF-679/BF-680/BF-681 native read-only Decision History app module.
# Uses BF-628 as the sole authoritative immutable waiver-audit history source.

function Get-AppNav {
    param([Parameter(Mandatory = $true)][string]$Active)
    $dashboardClass = if ($Active -ceq 'dashboard') { ' class="active"' } else { '' }
    $teamClass = if ($Active -ceq 'team') { ' class="active"' } else { '' }
    $matchupClass = if ($Active -ceq 'matchup') { ' class="active"' } else { '' }
    $waiversClass = if ($Active -ceq 'waivers') { ' class="active"' } else { '' }
    $leagueClass = if ($Active -ceq 'league') { ' class="active"' } else { '' }
    $tradeClass = if ($Active -ceq 'trade') { ' class="active"' } else { '' }
    $historyClass = if ($Active -ceq 'history') { ' class="active"' } else { '' }
    return "<nav class=`"nav`" aria-label=`"Butler sections`"><a$dashboardClass href=`"/`">Dashboard</a><a$teamClass href=`"/team`">My Team</a><a$matchupClass href=`"/matchup`">Matchup</a><a$waiversClass href=`"/waivers`">Waiver Board</a><a$leagueClass href=`"/league`">League</a><a$tradeClass href=`"/trade`">Trade Analyzer</a><a$historyClass href=`"/history`">History</a></nav>"
}

function Add-AppNavigation {
    param([Parameter(Mandatory = $true)][string]$Html)
    if ($Html -notmatch '<nav class="nav" aria-label="Butler sections">') {
        throw 'BF-671 BLOCKED: proxied Butler HTML is missing the navigation contract.'
    }
    if ($Html -notmatch 'href="/matchup"') {
        $anchor = [regex]'(<a[^>]*href="/team"[^>]*>My Team</a>)'
        if (-not $anchor.IsMatch($Html)) { throw 'BF-870 BLOCKED: My Team navigation anchor is missing.' }
        $Html = $anchor.Replace($Html, '$1<a href="/matchup">Matchup</a>', 1)
    }
    if ($Html -notmatch 'href="/trade"') {
        $anchor = [regex]'(<a[^>]*href="/league"[^>]*>League</a>)'
        if (-not $anchor.IsMatch($Html)) { throw 'BF-870 BLOCKED: League navigation anchor is missing.' }
        $Html = $anchor.Replace($Html, '$1<a href="/trade">Trade Analyzer</a>', 1)
    }
    if ($Html -notmatch 'href="/history"') {
        $anchor = [regex]'(<a[^>]*href="/trade"[^>]*>Trade Analyzer</a>)'
        if (-not $anchor.IsMatch($Html)) { throw 'BF-870 BLOCKED: Trade Analyzer navigation anchor is missing.' }
        $Html = $anchor.Replace($Html, '$1<a href="/history">History</a>', 1)
    }
    return $Html
}

function Get-DecisionHistoryLoadingHtml {
    param([Parameter(Mandatory = $true)][string]$LeagueId)

    $css = Get-AppCss
    $nav = Get-AppNav -Active 'history'
    $safeLeague = ConvertTo-HtmlText $LeagueId
    return @"
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="refresh" content="1;url=/history?load=1">
<title>Butler Decision History</title>
<style>$css</style>
</head>
<body>
<main class="shell">
<div class="top"><div class="brand"><h1>BUTLER</h1><p>We're here to serve you. Less Research. Better Decisions.</p></div><div class="target">$safeLeague</div></div>
$nav
<section class="panel">
<div class="eyebrow">Governed decision history</div>
<div class="statusrow">
<div><h2 class="headline">Opening Decision History...</h2><p class="lede">Loading recorded waiver decisions for this league and roster.</p></div>
<span class="status done">READ ONLY</span>
</div>
<div class="empty">This view reads existing decision history only. It does not capture, refresh, rerank, or submit anything.</div>
</section>
</main>
</body>
</html>
"@
}

function ConvertTo-DecisionHistoryView {
    param([Parameter(Mandatory = $true)][string]$Text)

    $policy = [regex]::Match($Text, '(?m)^Policy:\s+(?<value>.+?)\s*$')
    $target = [regex]::Match($Text, '(?m)^Butler league / BF-623 verified owner:\s+(?<league>\S+)\s+/\s+(?<owner>\S+)\s*$')
    $roster = [regex]::Match($Text, '(?m)^Sleeper league / target roster:\s+(?<league>\S+)\s+/\s+(?<roster>\d+)\s*$')
    $state = [regex]::Match($Text, '(?m)^History state:\s+(?<value>\S+)\s*$')
    $count = [regex]::Match($Text, '(?m)^Immutable audit records:\s+(?<value>\d+)\s*$')
    if (-not $policy.Success -or -not $target.Success -or -not $roster.Success -or -not $state.Success -or -not $count.Success) {
        throw 'BF-671 BLOCKED: BF-628 history output is missing required header fields.'
    }

    $entries = @()
    $lines = @($Text -split "`r?`n")
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $audit = [regex]::Match($lines[$i], '^\s{2}Audit:\s+(?<id>\S+)\s+\|\s+captured=(?<captured>\S+)\s*$')
        if (-not $audit.Success) { continue }
        if ($i + 5 -ge $lines.Count) {
            throw 'BF-671 BLOCKED: BF-628 history entry ended before all required fields were present.'
        }

        $provider = [regex]::Match($lines[$i + 1], '^\s{4}provider=(?<season>[^/]+)/(?<status>[^/]+)/(?<leg>.+?)\s*$')
        $snapshots = [regex]::Match($lines[$i + 2], '^\s{4}BF-603 market / BF-602 waiver=(?<market>\S+)\s+/\s+(?<waiver>\S+)\s*$')
        $decision = [regex]::Match($lines[$i + 3], '^\s{4}selection / recommendation=(?<selection>\S+)\s+/\s+(?<recommendation>\S+)\s*$')
        $players = [regex]::Match($lines[$i + 4], '^\s{4}add / drop Sleeper ids=(?<add>\S+)\s+/\s+(?<drop>\S+)\s*$')
        $integrity = [regex]::Match($lines[$i + 5], '^\s{4}integrity=(?<value>\S+)\s*$')
        if (-not $provider.Success -or -not $snapshots.Success -or -not $decision.Success -or -not $players.Success -or -not $integrity.Success) {
            throw "BF-671 BLOCKED: BF-628 history entry $($audit.Groups['id'].Value) is malformed."
        }

        $entries += [pscustomobject]@{
            AuditId = $audit.Groups['id'].Value.Trim()
            Captured = $audit.Groups['captured'].Value.Trim()
            ProviderSeason = $provider.Groups['season'].Value.Trim()
            ProviderStatus = $provider.Groups['status'].Value.Trim()
            ProviderLeg = $provider.Groups['leg'].Value.Trim()
            MarketSnapshotId = $snapshots.Groups['market'].Value.Trim()
            WaiverSnapshotId = $snapshots.Groups['waiver'].Value.Trim()
            SelectionState = $decision.Groups['selection'].Value.Trim()
            RecommendationState = $decision.Groups['recommendation'].Value.Trim()
            AddSleeperId = $players.Groups['add'].Value.Trim()
            DropSleeperId = $players.Groups['drop'].Value.Trim()
            IntegrityState = $integrity.Groups['value'].Value.Trim()
        }
        $i += 5
    }

    $declaredCount = [int]$count.Groups['value'].Value
    if ($entries.Count -ne $declaredCount) {
        throw 'BF-671 BLOCKED: parsed history count does not match BF-628 immutable audit record count.'
    }

    return [pscustomobject]@{
        Policy = $policy.Groups['value'].Value.Trim()
        LeagueId = $target.Groups['league'].Value.Trim()
        SleeperOwnerId = $target.Groups['owner'].Value.Trim()
        SleeperLeagueId = $roster.Groups['league'].Value.Trim()
        RosterId = [int]$roster.Groups['roster'].Value
        State = $state.Groups['value'].Value.Trim()
        RecordCount = $declaredCount
        Entries = @($entries)
    }
}

function ConvertTo-HistoryCapturedLabel {
    param([Parameter(Mandatory = $true)][string]$Captured)

    try {
        $parsed = [System.DateTimeOffset]::Parse(
            $Captured,
            [System.Globalization.CultureInfo]::InvariantCulture,
            [System.Globalization.DateTimeStyles]::AllowWhiteSpaces
        )
        return $parsed.ToUniversalTime().ToString("MMM d, yyyy HH:mm 'UTC'", [System.Globalization.CultureInfo]::InvariantCulture)
    }
    catch {
        return $Captured
    }
}

function ConvertTo-DecisionHistoryHtml {
    param([Parameter(Mandatory = $true)]$History)

    $css = Get-AppCss
    $nav = Get-AppNav -Active 'history'
    $historyCss = @'
.history-list{display:grid;gap:12px;margin-top:18px}.history-card{padding:18px;border:1px solid var(--line);border-radius:12px;background:var(--surface-2)}.history-card-latest{border-left:4px solid var(--turf)}.history-card h3{margin:4px 0 7px}.history-card-compact{padding-top:14px;padding-bottom:14px}.history-card-compact .history-older-details{margin-top:8px}.history-meta{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px;margin-top:14px}.history-meta div{padding:11px;border:1px solid var(--line);border-radius:10px;background:var(--surface)}.history-meta strong{display:block;color:var(--muted);font-size:10px;text-transform:uppercase;letter-spacing:.06em}.history-meta span{display:block;margin-top:4px;font-weight:700;word-break:break-word}.history-lineage{font:12px Consolas,monospace;color:var(--muted);word-break:break-word}.history-decision{font-size:22px;font-weight:900;line-height:1.2}.history-card-compact .history-decision{font-size:16px}.history-summary{max-width:72ch;margin:7px 0 0;color:var(--muted);font-size:13px;line-height:1.45}.history-integrity{font-size:12px;font-weight:800;color:var(--turf-deep)}.history-timeline-label{margin-top:20px;color:var(--muted);font-size:10px;font-weight:900;letter-spacing:.08em;text-transform:uppercase}@media(max-width:760px){.history-meta{grid-template-columns:1fr}.history-card .statusrow{align-items:flex-start}.history-card .status{margin-top:8px}}
'@

    # BF-679 reverses the already-authoritative BF-628 sequence for presentation only.
    # BF-680 leaves the newest displayed record full-size and progressively discloses older technical fields.
    # BF-681 humanizes the displayed capture time in UTC while preserving the exact source value in details.
    # ConvertTo-DecisionHistoryView keeps the parsed source order and exact captured value unchanged.
    $presentationEntries = @($History.Entries)
    $cards = ''
    $latestOutcome = 'No recorded decisions'
    $latestCaptured = 'None yet'
    for ($entryIndex = $presentationEntries.Count - 1; $entryIndex -ge 0; $entryIndex--) {
        $entry = $presentationEntries[$entryIndex]
        $capturedLabel = ConvertTo-HistoryCapturedLabel -Captured $entry.Captured
        $decisionLabel = if ($entry.RecommendationState -ceq 'NO_GOVERNED_TRANSACTION') {
            'No move recorded'
        }
        elseif ($entry.RecommendationState -ceq 'RECOMMEND_ADD_DROP') {
            'Waiver move recorded'
        }
        else {
            'Waiver decision recorded'
        }
        $decisionSummary = if ($entry.RecommendationState -ceq 'NO_GOVERNED_TRANSACTION') {
            'Butler recorded a governed no-action waiver result for this evidence frame.'
        }
        elseif ($entry.RecommendationState -ceq 'RECOMMEND_ADD_DROP') {
            'Butler recorded one governed add/drop waiver decision. Exact player identifiers remain in Decision details.'
        }
        else {
            'Butler recorded a governed waiver decision. The exact source state remains in Decision details.'
        }
        $decisionStatus = if ($entry.RecommendationState -ceq 'NO_GOVERNED_TRANSACTION') {
            'NO MOVE'
        }
        elseif ($entry.RecommendationState -ceq 'RECOMMEND_ADD_DROP') {
            'MOVE RECORDED'
        }
        else {
            'RECORDED'
        }
        $decisionClass = if ($entry.RecommendationState -ceq 'RECOMMEND_ADD_DROP') { 'good' } else { 'done' }

        $isNewest = $entryIndex -eq ($presentationEntries.Count - 1)
        if ($isNewest) {
            $latestOutcome = $decisionLabel
            $latestCaptured = $capturedLabel
        }
        if ($isNewest) {
            $cardHtml = @"
<article class="history-card history-card-latest">
<div class="statusrow"><div><div class="eyebrow">Latest recorded waiver decision</div><h3>$(ConvertTo-HtmlText $capturedLabel)</h3><div class="history-decision">$decisionLabel</div><p class="history-summary">$(ConvertTo-HtmlText $decisionSummary)</p></div><span class="status $decisionClass">$(ConvertTo-HtmlText $decisionStatus)</span></div>
<details><summary>Decision details</summary><div class="history-meta"><div><strong>Integrity</strong><span>$(ConvertTo-HtmlText $entry.IntegrityState)</span></div><div><strong>Provider frame</strong><span>$(ConvertTo-HtmlText $entry.ProviderSeason) / $(ConvertTo-HtmlText $entry.ProviderStatus) / $(ConvertTo-HtmlText $entry.ProviderLeg)</span></div><div><strong>Selection state</strong><span>$(ConvertTo-HtmlText $entry.SelectionState)</span></div><div><strong>Recommendation state</strong><span>$(ConvertTo-HtmlText $entry.RecommendationState)</span></div></div><p class="history-lineage">Captured UTC: $(ConvertTo-HtmlText $entry.Captured)</p><p class="history-lineage">Audit: $(ConvertTo-HtmlText $entry.AuditId)</p><p class="history-lineage">BF-603 market: $(ConvertTo-HtmlText $entry.MarketSnapshotId)</p><p class="history-lineage">BF-602 waiver: $(ConvertTo-HtmlText $entry.WaiverSnapshotId)</p><p class="history-lineage">ADD / DROP Sleeper ids: $(ConvertTo-HtmlText $entry.AddSleeperId) / $(ConvertTo-HtmlText $entry.DropSleeperId)</p></details>
</article>
"@
        }
        else {
            $cardHtml = @"
<article class="history-card history-card-compact">
<div class="statusrow"><div><div class="eyebrow">Recorded waiver decision</div><h3>$(ConvertTo-HtmlText $capturedLabel)</h3><div class="history-decision">$decisionLabel</div></div><span class="status $decisionClass">$(ConvertTo-HtmlText $decisionStatus)</span></div>
<details class="history-older-details"><summary>Show decision details</summary>
<div class="history-meta"><div><strong>Integrity</strong><span>$(ConvertTo-HtmlText $entry.IntegrityState)</span></div><div><strong>Provider frame</strong><span>$(ConvertTo-HtmlText $entry.ProviderSeason) / $(ConvertTo-HtmlText $entry.ProviderStatus) / $(ConvertTo-HtmlText $entry.ProviderLeg)</span></div><div><strong>Selection state</strong><span>$(ConvertTo-HtmlText $entry.SelectionState)</span></div><div><strong>Recommendation state</strong><span>$(ConvertTo-HtmlText $entry.RecommendationState)</span></div></div>
<p class="history-lineage">Captured UTC: $(ConvertTo-HtmlText $entry.Captured)</p><p class="history-lineage">Audit: $(ConvertTo-HtmlText $entry.AuditId)</p><p class="history-lineage">BF-603 market: $(ConvertTo-HtmlText $entry.MarketSnapshotId)</p><p class="history-lineage">BF-602 waiver: $(ConvertTo-HtmlText $entry.WaiverSnapshotId)</p><p class="history-lineage">ADD / DROP Sleeper ids: $(ConvertTo-HtmlText $entry.AddSleeperId) / $(ConvertTo-HtmlText $entry.DropSleeperId)</p>
</details>
</article>
"@
        }
        $cards += $cardHtml
    }
    if ([string]::IsNullOrWhiteSpace($cards)) {
        $cards = '<div class="empty">No recorded governed waiver decisions are available for this league yet.</div>'
    }

    return @"
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="dark"><title>Butler - Decision History</title><style>$css$historyCss</style></head><body><main class="shell">
<header class="top"><div class="brand"><h1>BUTLER</h1><p>We're here to serve you. Less Research. Better Decisions.</p></div><div class="target">Decision History &middot; $(ConvertTo-HtmlText $History.RecordCount) recorded</div></header>
$nav
<section class="panel"><div class="eyebrow">Decision History</div><div class="statusrow"><div><h1 class="headline">Your waiver decision timeline</h1><p class="lede">Recorded waiver decisions. Butler shows the newest recorded decision first for this league and roster. This page does not rerun recommendations.</p></div><div class="status done">READ ONLY</div></div><div class="stats"><div class="stat"><strong>Latest outcome</strong><span>$(ConvertTo-HtmlText $latestOutcome)</span></div><div class="stat"><strong>Latest recorded</strong><span>$(ConvertTo-HtmlText $latestCaptured)</span></div><div class="stat"><strong>Recorded decisions</strong><span>$(ConvertTo-HtmlText $History.RecordCount)</span></div></div><div class="history-timeline-label">Newest first</div><div class="history-list">$cards</div></section>
<section class="panel boundary"><span class="lock">READ ONLY.</span> Decision History reads recorded governed waiver history only. It cannot capture or rewrite a decision record, refresh evidence, rerank a waiver decision, execute a transaction, set FAAB, alter a roster, or submit a Sleeper transaction.</section>
</main></body></html>
"@
}

function Invoke-DecisionHistoryHtml {
    param([Parameter(Mandatory = $true)][string]$LeagueId)

    $raw = Invoke-ButlerReadOnlyTask -Task ':bet:bet-cli:sleeperLiveWaiverRecommendationAuditHistory' -Arguments $LeagueId -BoundaryName 'BF-671'
    $history = ConvertTo-DecisionHistoryView -Text $raw
    if ($history.LeagueId -cne $LeagueId) {
        throw 'BF-671 BLOCKED: BF-628 history league does not match the configured Butler league.'
    }
    return ConvertTo-DecisionHistoryHtml -History $history
}
