# BF-671 native read-only Decision History app module.
# Uses BF-628 as the sole authoritative immutable waiver-audit history source.

function Get-AppNav {
    param([Parameter(Mandatory = $true)][string]$Active)
    $dashboardClass = if ($Active -ceq 'dashboard') { ' class="active"' } else { '' }
    $teamClass = if ($Active -ceq 'team') { ' class="active"' } else { '' }
    $waiversClass = if ($Active -ceq 'waivers') { ' class="active"' } else { '' }
    $leagueClass = if ($Active -ceq 'league') { ' class="active"' } else { '' }
    $tradeClass = if ($Active -ceq 'trade') { ' class="active"' } else { '' }
    $historyClass = if ($Active -ceq 'history') { ' class="active"' } else { '' }
    return "<nav class=`"nav`" aria-label=`"Butler sections`"><a$dashboardClass href=`"/`">Dashboard</a><a$teamClass href=`"/team`">My Team</a><a$waiversClass href=`"/waivers`">Waiver Board</a><a$leagueClass href=`"/league`">League</a><a$tradeClass href=`"/trade`">Trade Lab</a><a$historyClass href=`"/history`">History</a></nav>"
}

function Add-AppNavigation {
    param([Parameter(Mandatory = $true)][string]$Html)
    if ($Html -notmatch '<nav class="nav" aria-label="Butler sections">') {
        throw 'BF-671 BLOCKED: proxied Butler HTML is missing the navigation contract.'
    }
    $links = ''
    if ($Html -notmatch 'href="/trade"') { $links += '<a href="/trade">Trade Lab</a>' }
    if ($Html -notmatch 'href="/history"') { $links += '<a href="/history">History</a>' }
    if ([string]::IsNullOrWhiteSpace($links)) { return $Html }
    return $Html.Replace('</nav>', "$links</nav>")
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
<div><h2 class="headline">Opening Decision History...</h2><p class="lede">Loading Butler's BF-628 integrity-verified immutable waiver audit trail.</p></div>
<span class="status done">READ ONLY</span>
</div>
<div class="empty">This view reads existing audit history only. It does not capture, refresh, rerank, or submit anything.</div>
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

function ConvertTo-DecisionHistoryHtml {
    param([Parameter(Mandatory = $true)]$History)

    $css = Get-AppCss
    $nav = Get-AppNav -Active 'history'
    $historyCss = @'
.history-list{display:grid;gap:14px;margin-top:18px}.history-card{padding:18px;border:1px solid #2b3962;border-radius:16px;background:#0d1630}.history-card h3{margin:4px 0 8px}.history-meta{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px;margin-top:14px}.history-meta div{padding:11px;border:1px solid #26345c;border-radius:10px;background:#0a1329}.history-meta strong{display:block;color:#8797bd;font-size:10px;text-transform:uppercase;letter-spacing:.06em}.history-meta span{display:block;margin-top:4px;font-weight:700;word-break:break-word}.history-lineage{font:12px Consolas,monospace;color:#a9b5d2;word-break:break-word}.history-decision{font-size:17px;font-weight:800}.history-integrity{font-size:12px;font-weight:800;color:#8ff0b9}@media(max-width:760px){.history-meta{grid-template-columns:1fr}}
'@

    $cards = ''
    foreach ($entry in @($History.Entries)) {
        $decisionLabel = if ($entry.RecommendationState -ceq 'NO_GOVERNED_TRANSACTION') {
            'No governed transaction'
        }
        elseif ($entry.RecommendationState -ceq 'RECOMMEND_ADD_DROP') {
            "ADD $(ConvertTo-HtmlText $entry.AddSleeperId) / DROP $(ConvertTo-HtmlText $entry.DropSleeperId)"
        }
        else {
            ConvertTo-HtmlText $entry.RecommendationState
        }
        $cards += @"
<article class="history-card">
<div class="statusrow"><div><div class="eyebrow">Immutable audit</div><h3>$(ConvertTo-HtmlText $entry.Captured)</h3><div class="history-decision">$decisionLabel</div></div><div class="history-integrity">$(ConvertTo-HtmlText $entry.IntegrityState)</div></div>
<div class="history-meta"><div><strong>Provider frame</strong><span>$(ConvertTo-HtmlText $entry.ProviderSeason) / $(ConvertTo-HtmlText $entry.ProviderStatus) / $(ConvertTo-HtmlText $entry.ProviderLeg)</span></div><div><strong>Selection state</strong><span>$(ConvertTo-HtmlText $entry.SelectionState)</span></div><div><strong>Recommendation</strong><span>$(ConvertTo-HtmlText $entry.RecommendationState)</span></div></div>
<details><summary>Audit and evidence lineage</summary><p class="history-lineage">Audit: $(ConvertTo-HtmlText $entry.AuditId)</p><p class="history-lineage">BF-603 market: $(ConvertTo-HtmlText $entry.MarketSnapshotId)</p><p class="history-lineage">BF-602 waiver: $(ConvertTo-HtmlText $entry.WaiverSnapshotId)</p><p class="history-lineage">ADD / DROP Sleeper ids: $(ConvertTo-HtmlText $entry.AddSleeperId) / $(ConvertTo-HtmlText $entry.DropSleeperId)</p></details>
</article>
"@
    }
    if ([string]::IsNullOrWhiteSpace($cards)) {
        $cards = '<div class="empty">BF-628 reports no immutable governed waiver audits for this exact Butler league.</div>'
    }

    return @"
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="dark"><title>Butler - Decision History</title><style>$css$historyCss</style></head><body><main class="shell">
<header class="top"><div class="brand"><h1>BUTLER</h1><p>We're here to serve you. Less Research. Better Decisions.</p></div><div class="target">$(ConvertTo-HtmlText $History.LeagueId) &middot; roster $(ConvertTo-HtmlText $History.RosterId)</div></header>
$nav
<section class="panel"><div class="eyebrow">Decision History</div><div class="statusrow"><div><h1 class="headline">Immutable governed waiver audits</h1><p class="lede">BF-628 integrity-verified history for the exact BF-623-bound league and roster. This page does not rerun recommendations.</p></div><div class="status done">READ ONLY</div></div><div class="stats"><div class="stat"><strong>History state</strong><span>$(ConvertTo-HtmlText $History.State)</span></div><div class="stat"><strong>Audit records</strong><span>$(ConvertTo-HtmlText $History.RecordCount)</span></div><div class="stat"><strong>Target roster</strong><span>$(ConvertTo-HtmlText $History.RosterId)</span></div></div><div class="history-list">$cards</div></section>
<section class="panel boundary"><span class="lock">READ ONLY.</span> BF-671 displays BF-628 history only. It cannot capture or rewrite an audit, refresh evidence, rerank a waiver decision, execute BF-641, set FAAB, alter a roster, or submit a Sleeper transaction.</section>
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
