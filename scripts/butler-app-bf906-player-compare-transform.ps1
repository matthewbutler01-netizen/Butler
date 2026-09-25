param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-906 BLOCKED: staged Butler core not found at $CorePath"
}

function Replace-ExactlyOnce {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Old,
        [Parameter(Mandatory = $true)][string]$New,
        [Parameter(Mandatory = $true)][string]$Contract
    )

    $matches = [regex]::Matches($Text, [regex]::Escape($Old)).Count
    if ($matches -ne 1) {
        throw "BF-906 BLOCKED: $Contract expected one match, found $matches."
    }
    return $Text.Replace($Old, $New)
}

$core = [System.IO.File]::ReadAllText($CorePath)

$compareFunctions = @'
function Get-PlayerCompareRequest {
    param([Parameter(Mandatory = $true)][string]$RequestTarget)

    $values = @{
        left = @()
        right = @()
        q = @()
        support = @()
    }

    $question = $RequestTarget.IndexOf('?')
    if ($question -ge 0 -and $question -lt ($RequestTarget.Length - 1)) {
        foreach ($part in @($RequestTarget.Substring($question + 1) -split '&')) {
            if ([string]::IsNullOrWhiteSpace($part)) { continue }

            $separator = $part.IndexOf('=')
            if ($separator -lt 0) {
                $rawName = $part
                $rawValue = ''
            }
            else {
                $rawName = $part.Substring(0, $separator)
                $rawValue = $part.Substring($separator + 1)
            }

            $name = [System.Uri]::UnescapeDataString($rawName.Replace('+', ' '))
            if (-not $values.ContainsKey($name)) {
                throw 'BF-906 BLOCKED: Player Compare accepts only left, right, q, and support query parameters.'
            }
            $values[$name] += [System.Uri]::UnescapeDataString($rawValue.Replace('+', ' ')).Trim()
        }
    }

    foreach ($name in @('left','right','q','support')) {
        if (@($values[$name]).Count -gt 1) {
            throw "BF-906 BLOCKED: Player Compare accepts at most one $name query parameter."
        }
    }

    $left = if (@($values.left).Count -eq 1) { [string]$values.left[0] } else { '' }
    $right = if (@($values.right).Count -eq 1) { [string]$values.right[0] } else { '' }
    $query = if (@($values.q).Count -eq 1) {
        [regex]::Replace(([string]$values.q[0]).Trim(), '\s+', ' ')
    } else { '' }
    $support = if (@($values.support).Count -eq 1) { [string]$values.support[0] } else { '' }
    if (@($values.support).Count -eq 1 -and $support -cne '1') {
        throw 'BF-906 BLOCKED: Player Compare support may only be requested as support=1.'
    }

    foreach ($playerId in @($left,$right)) {
        if ([string]::IsNullOrWhiteSpace($playerId)) { continue }
        if ($playerId.Length -gt 128 -or $playerId -notmatch '^[A-Za-z0-9._:-]+$') {
            throw 'BF-906 BLOCKED: Player Compare requires exact Butler player IDs.'
        }
    }
    if (-not [string]::IsNullOrWhiteSpace($right) -and [string]::IsNullOrWhiteSpace($left)) {
        throw 'BF-906 BLOCKED: Player Compare requires the left player before the right player.'
    }
    if (-not [string]::IsNullOrWhiteSpace($left) -and $left -ceq $right) {
        throw 'BF-906 BLOCKED: Player Compare requires two different exact players.'
    }
    if (-not [string]::IsNullOrWhiteSpace($query)) {
        if ([string]::IsNullOrWhiteSpace($left)) {
            throw 'BF-906 BLOCKED: second-player search requires an exact left player.'
        }
        if (-not [string]::IsNullOrWhiteSpace($right)) {
            throw 'BF-906 BLOCKED: Player Compare cannot search and compare in the same request.'
        }
        if ($query.Length -gt 80) {
            throw 'BF-906 BLOCKED: Player Compare search query must be 80 characters or fewer.'
        }
        if ($query -notmatch '^[A-Za-z0-9 ._''-]+$') {
            throw 'BF-906 BLOCKED: Player Compare search query contains unsupported characters.'
        }
    }

    if (@($values.support).Count -eq 1) {
        if ([string]::IsNullOrWhiteSpace($left) -or [string]::IsNullOrWhiteSpace($right)) {
            throw 'BF-906 BLOCKED: supporting evidence requires two exact players.'
        }
        if (-not [string]::IsNullOrWhiteSpace($query)) {
            throw 'BF-906 BLOCKED: supporting evidence cannot be loaded during second-player search.'
        }
    }

    return [pscustomobject]@{
        LeftPlayerId = $left
        RightPlayerId = $right
        Query = $query
        LoadSupportingEvidence = (@($values.support).Count -eq 1)
    }
}

function ConvertTo-PlayerCompareSideView {
    param(
        [Parameter(Mandatory = $true)][string]$Side,
        [Parameter(Mandatory = $true)][string]$Body
    )

    $fields = @{}
    $flags = @()
    foreach ($line in @($Body -split '\r?\n')) {
        $flag = [regex]::Match(
            $line,
            '^Supporting flag:\s+(?<signal>[^|]+?)\s+\|\s+(?<category>[^|]+?)\s+\|\s+(?<dimension>[^|]+?)\s+\|\s+(?<summary>.+?)\s*$')
        if ($flag.Success) {
            $flags += [pscustomobject]@{
                Signal = $flag.Groups['signal'].Value.Trim()
                Category = $flag.Groups['category'].Value.Trim()
                Dimension = $flag.Groups['dimension'].Value.Trim()
                Summary = $flag.Groups['summary'].Value.Trim()
            }
            continue
        }

        $field = [regex]::Match(
            $line,
            '^(?<name>Player ID|Player name|Position|NFL team|Butler team ID|Butler team name|Roster slot|Value|Value as-of|Age|Age provenance|Production snapshot|Games played|Passing yards/game|Passing TD/game|Interceptions/game|Rushing yards/game|Rushing TD/game|Receptions/game|Receiving yards/game|Receiving TD/game|Fumbles lost/game|Supporting flags):\s*(?<value>.*)$')
        if (-not $field.Success) { continue }
        $name = $field.Groups['name'].Value
        if ($fields.ContainsKey($name)) {
            throw "BF-906 BLOCKED: duplicate Player Compare $Side field $name."
        }
        $fields[$name] = $field.Groups['value'].Value.Trim()
    }

    foreach ($required in @(
        'Player ID','Player name','Position','NFL team','Butler team ID','Butler team name',
        'Roster slot','Value','Value as-of','Age','Age provenance','Production snapshot',
        'Games played','Passing yards/game','Passing TD/game','Interceptions/game',
        'Rushing yards/game','Rushing TD/game','Receptions/game','Receiving yards/game',
        'Receiving TD/game','Fumbles lost/game','Supporting flags'
    )) {
        if (-not $fields.ContainsKey($required) -or
            [string]::IsNullOrWhiteSpace([string]$fields[$required])) {
            throw "BF-906 BLOCKED: Player Compare $Side is missing $required."
        }
    }

    $expectedFlags = [int]$fields['Supporting flags']
    if ($flags.Count -ne $expectedFlags) {
        throw "BF-906 BLOCKED: Player Compare $Side supporting-flag count does not match."
    }

    return [pscustomobject]@{
        PlayerId = [string]$fields['Player ID']
        PlayerName = [string]$fields['Player name']
        Position = [string]$fields['Position']
        NflTeam = [string]$fields['NFL team']
        TeamId = [string]$fields['Butler team ID']
        TeamName = [string]$fields['Butler team name']
        RosterSlot = [string]$fields['Roster slot']
        Value = [string]$fields['Value']
        ValueAsOf = [string]$fields['Value as-of']
        Age = [string]$fields['Age']
        AgeProvenance = [string]$fields['Age provenance']
        ProductionSnapshot = [string]$fields['Production snapshot']
        GamesPlayed = [string]$fields['Games played']
        PassingYardsPerGame = [string]$fields['Passing yards/game']
        PassingTouchdownsPerGame = [string]$fields['Passing TD/game']
        InterceptionsPerGame = [string]$fields['Interceptions/game']
        RushingYardsPerGame = [string]$fields['Rushing yards/game']
        RushingTouchdownsPerGame = [string]$fields['Rushing TD/game']
        ReceptionsPerGame = [string]$fields['Receptions/game']
        ReceivingYardsPerGame = [string]$fields['Receiving yards/game']
        ReceivingTouchdownsPerGame = [string]$fields['Receiving TD/game']
        FumblesLostPerGame = [string]$fields['Fumbles lost/game']
        SupportingFlags = @($flags)
    }
}

function ConvertTo-PlayerCompareView {
    param([Parameter(Mandatory = $true)][string]$Text)

    $league = [regex]::Match($Text, '(?m)^League ID:\s+(?<value>\S+)\s*$')
    $season = [regex]::Match($Text, '(?m)^Season:\s+(?<value>\d{4})\s*$')
    $ageAsOf = [regex]::Match($Text, '(?m)^Age as-of:\s+(?<value>\d{4}-\d{2}-\d{2})\s*$')
    $valueSource = [regex]::Match($Text, '(?m)^Value source:\s+(?<value>\S+)\s*$')
    $supportingEvidence = [regex]::Match($Text, '(?m)^Supporting evidence:\s+(?<value>READY|DEFERRED)\s*$')
    foreach ($required in @($league,$season,$ageAsOf,$valueSource,$supportingEvidence)) {
        if (-not $required.Success) {
            throw 'BF-906 BLOCKED: Player Compare output is missing a required summary field.'
        }
    }

    $leftMatch = [regex]::Match(
        $Text,
        '(?ms)^===BUTLER_PLAYER_COMPARE:LEFT:BEGIN===\r?\n(?<body>.*?)^===BUTLER_PLAYER_COMPARE:LEFT:END===')
    $rightMatch = [regex]::Match(
        $Text,
        '(?ms)^===BUTLER_PLAYER_COMPARE:RIGHT:BEGIN===\r?\n(?<body>.*?)^===BUTLER_PLAYER_COMPARE:RIGHT:END===')
    if (-not $leftMatch.Success -or -not $rightMatch.Success) {
        throw 'BF-906 BLOCKED: Player Compare output is missing an exact player evidence block.'
    }

    $left = ConvertTo-PlayerCompareSideView -Side 'LEFT' -Body $leftMatch.Groups['body'].Value
    $right = ConvertTo-PlayerCompareSideView -Side 'RIGHT' -Body $rightMatch.Groups['body'].Value
    if ($left.PlayerId -ceq $right.PlayerId) {
        throw 'BF-906 BLOCKED: Player Compare output contains the same player twice.'
    }

    return [pscustomobject]@{
        LeagueId = $league.Groups['value'].Value.Trim()
        Season = [int]$season.Groups['value'].Value
        AgeAsOf = $ageAsOf.Groups['value'].Value.Trim()
        ValueSource = $valueSource.Groups['value'].Value.Trim()
        SupportingEvidenceState = $supportingEvidence.Groups['value'].Value.Trim()
        Left = $left
        Right = $right
        Raw = $Text
    }
}

function ConvertTo-PlayerCompareProductionHtml {
    param([Parameter(Mandatory = $true)]$Player)

    $metrics = @(
        [pscustomobject]@{ Label = 'Pass yards/game'; Value = $Player.PassingYardsPerGame },
        [pscustomobject]@{ Label = 'Pass TD/game'; Value = $Player.PassingTouchdownsPerGame },
        [pscustomobject]@{ Label = 'INT/game'; Value = $Player.InterceptionsPerGame },
        [pscustomobject]@{ Label = 'Rush yards/game'; Value = $Player.RushingYardsPerGame },
        [pscustomobject]@{ Label = 'Rush TD/game'; Value = $Player.RushingTouchdownsPerGame },
        [pscustomobject]@{ Label = 'Receptions/game'; Value = $Player.ReceptionsPerGame },
        [pscustomobject]@{ Label = 'Rec yards/game'; Value = $Player.ReceivingYardsPerGame },
        [pscustomobject]@{ Label = 'Rec TD/game'; Value = $Player.ReceivingTouchdownsPerGame },
        [pscustomobject]@{ Label = 'Fumbles lost/game'; Value = $Player.FumblesLostPerGame }
    )

    $html = ''
    foreach ($metric in $metrics) {
        if ([string]$metric.Value -ceq 'UNAVAILABLE') { continue }
        $html += "<div class=`"compare-metric`"><strong>$(ConvertTo-HtmlText $metric.Label)</strong><span>$(ConvertTo-HtmlText $metric.Value)</span></div>"
    }
    if ([string]::IsNullOrWhiteSpace($html)) {
        return '<div class="empty">Per-game production is unavailable for this player.</div>'
    }
    return "<div class=`"compare-metrics`">$html</div>"
}

function ConvertTo-PlayerCompareFlagsHtml {
    param([Parameter(Mandatory = $true)]$Player)

    if (@($Player.SupportingFlags).Count -eq 0) {
        return '<div class="empty">No supporting evidence flags are available.</div>'
    }

    $html = ''
    foreach ($flag in @($Player.SupportingFlags)) {
        $html += "<div class=`"compare-flag`"><strong>$(ConvertTo-HtmlText $flag.Signal)</strong><span>$(ConvertTo-HtmlText $flag.Category) &middot; $(ConvertTo-HtmlText $flag.Dimension)</span><p>$(ConvertTo-HtmlText $flag.Summary)</p></div>"
    }
    return $html
}

function ConvertTo-PlayerCompareCardHtml {
    param(
        [Parameter(Mandatory = $true)]$Player,
        [Parameter(Mandatory = $true)][string]$SupportingEvidenceState,
        [Parameter(Mandatory = $true)][string]$SupportingEvidenceHref
    )

    $value = if ($Player.Value -ceq 'UNAVAILABLE') { 'Unavailable' } else { $Player.Value }
    $age = if ($Player.Age -ceq 'UNAVAILABLE') { 'Unavailable' } else { $Player.Age }
    $games = if ($Player.GamesPlayed -ceq 'UNAVAILABLE') { 'Unavailable' } else { $Player.GamesPlayed }
    $valueAsOf = if ($Player.ValueAsOf -ceq 'UNAVAILABLE') { 'as-of unavailable' } else { "as-of $($Player.ValueAsOf)" }
    $productionHtml = ConvertTo-PlayerCompareProductionHtml -Player $Player
    $hrefId = [System.Uri]::EscapeDataString([string]$Player.PlayerId)
    $teamHrefId = [System.Uri]::EscapeDataString([string]$Player.TeamId)
    $supportingHtml = if ($SupportingEvidenceState -ceq 'READY') {
        ConvertTo-PlayerCompareFlagsHtml -Player $Player
    } else {
        '<div class="empty">Historical supporting evidence is loaded only when requested so the comparison stays fast.</div><div class="button-row"><a class="btn btn-secondary" href="' + (ConvertTo-HtmlText $SupportingEvidenceHref) + '">Load supporting evidence</a></div>'
    }

    return @"
<article class="compare-player">
<div class="eyebrow">$(ConvertTo-HtmlText $Player.Position) &middot; NFL $(ConvertTo-HtmlText $Player.NflTeam)</div>
<h2>$(ConvertTo-HtmlText $Player.PlayerName)</h2>
<p class="lede">Rostered by $(ConvertTo-HtmlText $Player.TeamName) &middot; $(ConvertTo-HtmlText $Player.RosterSlot)</p>
<div class="compare-summary">
<div class="compare-summary-card"><strong>Market value</strong><span>$(ConvertTo-HtmlText $value)</span><small>$(ConvertTo-HtmlText $valueAsOf)</small></div>
<div class="compare-summary-card"><strong>Age</strong><span>$(ConvertTo-HtmlText $age)</span><small>$(ConvertTo-HtmlText $Player.AgeProvenance)</small></div>
<div class="compare-summary-card"><strong>Games</strong><span>$(ConvertTo-HtmlText $games)</span><small>neutral production frame</small></div>
</div>
<h3>Per-game production</h3>
$productionHtml
<div class="button-row"><a class="btn btn-secondary" href="/player?id=$hrefId">View Player Detail</a><a class="btn btn-secondary" href="/franchise?id=$teamHrefId">Scout franchise</a></div>
<details><summary>Supporting evidence</summary><div class="compare-flags">$supportingHtml</div></details>
</article>
"@
}

function ConvertTo-PlayerCompareHtml {
    param(
        [Parameter(Mandatory = $true)]$Request,
        [AllowNull()]$View,
        [AllowNull()]$SelectedLeft,
        [AllowNull()]$SearchView
    )

    $css = Get-AppCss
    $nav = Get-AppNav -Active 'league'
    $compareCss = @"
.compare-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px;margin-top:16px}.compare-player{padding:20px;border:1px solid var(--line);border-radius:12px;background:var(--surface)}.compare-player h2{margin:6px 0}.compare-summary{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px;margin-top:16px}.compare-summary-card,.compare-metric,.compare-flag{padding:12px;border:1px solid var(--line);border-radius:9px;background:var(--surface-2)}.compare-summary-card strong,.compare-metric strong{display:block;color:var(--muted);font-size:10px;text-transform:uppercase;letter-spacing:.04em}.compare-summary-card span,.compare-metric span{display:block;margin-top:4px;font-weight:800}.compare-summary-card small{display:block;margin-top:3px;color:var(--muted)}.compare-metrics{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px}.compare-flags{display:grid;gap:8px;margin-top:10px}.compare-flag span{display:block;color:var(--muted);font-size:11px;margin-top:3px}.compare-flag p{margin:6px 0 0}.compare-search{display:flex;gap:10px;flex-wrap:wrap;align-items:center;margin-top:16px}.compare-search input[type=text]{flex:1;min-width:240px;background:var(--surface-2);color:var(--ink);border:1px solid var(--line);border-radius:9px;padding:11px 12px;font:inherit}.compare-choice-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;margin-top:14px}@media(max-width:800px){.compare-grid,.compare-choice-grid,.compare-summary,.compare-metrics{grid-template-columns:1fr}}
"@

    if ($null -ne $View) {
        $leftHref = [System.Uri]::EscapeDataString([string]$Request.LeftPlayerId)
        $rightHref = [System.Uri]::EscapeDataString([string]$Request.RightPlayerId)
        $supportHref = "/compare?left=$leftHref&right=$rightHref&support=1#supporting-evidence"
        $leftHtml = ConvertTo-PlayerCompareCardHtml -Player $View.Left -SupportingEvidenceState $View.SupportingEvidenceState -SupportingEvidenceHref $supportHref
        $rightHtml = ConvertTo-PlayerCompareCardHtml -Player $View.Right -SupportingEvidenceState $View.SupportingEvidenceState -SupportingEvidenceHref $supportHref
        return @"
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="dark"><title>Butler - Player Compare</title><style>$css$compareCss</style></head><body><main class="shell">
<header class="top"><div class="brand"><h1>BUTLER</h1><p>We're here to serve you. Less Research. Better Decisions.</p></div><div class="target">Player Compare</div></header>
$nav
<section class="panel hero-panel"><div class="manager-head"><div><div class="eyebrow">League tool</div><h1 class="headline">Player Compare</h1><p class="lede">Side-by-side neutral evidence for two exact rostered players. Butler does not choose a winner.</p></div><span class="status done">NOT A RANKING</span></div><div class="button-row"><a class="btn btn-secondary" href="/players">Compare different players</a></div></section>
<section class="panel" id="supporting-evidence"><div class="compare-grid">$leftHtml$rightHtml</div><details><summary>Comparison evidence</summary><div class="technical">season $(ConvertTo-HtmlText $View.Season) &middot; age as-of $(ConvertTo-HtmlText $View.AgeAsOf) &middot; value source $(ConvertTo-HtmlText $View.ValueSource)</div></details></section>
<section class="panel boundary"><span class="lock">READ ONLY &middot; NOT A RANKING.</span> Player Compare shows existing Butler evidence only. It does not select a better player, adjust dynasty value, create a score or grade, or produce start/sit, trade, waiver, buy/sell, or roster-move advice.</section>
</main></body></html>
"@
    }

    if ($null -eq $SelectedLeft) {
        return @"
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="dark"><title>Butler - Player Compare</title><style>$css$compareCss</style></head><body><main class="shell">
<header class="top"><div class="brand"><h1>BUTLER</h1><p>We're here to serve you. Less Research. Better Decisions.</p></div><div class="target">Player Compare</div></header>
$nav
<section class="panel hero-panel"><div class="manager-head"><div><div class="eyebrow">League tool</div><h1 class="headline">Player Compare</h1><p class="lede">Start with any currently rostered player, then choose the second exact player to compare.</p></div><span class="status done">NOT A RANKING</span></div><div class="button-row"><a class="btn btn-primary" href="/players">Find a player</a></div></section>
<section class="panel boundary"><span class="lock">READ ONLY.</span> Player Compare never changes rosters, refreshes providers, or submits a Sleeper transaction.</section>
</main></body></html>
"@
    }

    $leftHref = [System.Uri]::EscapeDataString([string]$SelectedLeft.PlayerId)
    $queryValue = ConvertTo-HtmlText ([string]$Request.Query)
    $resultsHtml = ''
    if ($null -ne $SearchView) {
        if ($SearchView.PlayerMatches -eq 0) {
            $resultsHtml = '<div class="empty">No currently rostered player matched that search.</div>'
        }
        else {
            $cards = ''
            foreach ($player in @($SearchView.Players)) {
                $rightHref = [System.Uri]::EscapeDataString([string]$player.PlayerId)
                $valueText = if ($player.Value -ceq 'UNAVAILABLE') { 'Value unavailable' } else { "Persisted value $($player.Value)" }
                $action = if ([string]$player.PlayerId -ceq [string]$SelectedLeft.PlayerId) {
                    '<span class="status done">SELECTED</span>'
                }
                else {
                    "<a class=`"btn btn-primary`" href=`"/compare?left=$leftHref&right=$rightHref`">Compare with this player</a>"
                }
                $cards += "<article class=`"card`"><div class=`"eyebrow`">$(ConvertTo-HtmlText $player.Position) &middot; NFL $(ConvertTo-HtmlText $player.NflTeam)</div><div class=`"name`">$(ConvertTo-HtmlText $player.Name)</div><div class=`"meta`">Rostered by $(ConvertTo-HtmlText $player.OwnerTeamName) &middot; $(ConvertTo-HtmlText $player.Slot)</div><div class=`"meta`">$(ConvertTo-HtmlText $valueText)</div><div class=`"button-row`" style=`"margin-top:12px`">$action</div></article>"
            }
            $resultsHtml = "<div class=`"compare-choice-grid`">$cards</div>"
        }
    }

    return @"
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="dark"><title>Butler - Player Compare</title><style>$css$compareCss</style></head><body><main class="shell">
<header class="top"><div class="brand"><h1>BUTLER</h1><p>We're here to serve you. Less Research. Better Decisions.</p></div><div class="target">Player Compare</div></header>
$nav
<section class="panel hero-panel"><div class="manager-head"><div><div class="eyebrow">First player selected</div><h1 class="headline">$(ConvertTo-HtmlText $SelectedLeft.PlayerName)</h1><p class="lede">$(ConvertTo-HtmlText $SelectedLeft.Position) &middot; $(ConvertTo-HtmlText $SelectedLeft.TeamName) &middot; $(ConvertTo-HtmlText $SelectedLeft.RosterSlot). Search the league for the second exact player.</p></div><span class="status done">NOT A RANKING</span></div><form class="compare-search" method="get" action="/compare"><input type="hidden" name="left" value="$(ConvertTo-HtmlText $SelectedLeft.PlayerId)"><input type="text" name="q" value="$queryValue" maxlength="80" placeholder="Search rostered players..." aria-label="Second player search"><button class="btn btn-primary" type="submit">Find second player</button></form><div class="button-row"><a class="btn btn-secondary" href="/players">Choose a different first player</a></div></section>
$(if ($null -ne $SearchView) { "<section class=`"panel`"><div class=`"eyebrow`">Second player</div><h2>Choose a player to compare</h2><p class=`"lede`">Exact Butler player IDs stay behind the interface; your choice is bound to the rostered player record.</p>$resultsHtml</section>" } else { '' })
<section class="panel boundary"><span class="lock">READ ONLY &middot; NOT A RANKING.</span> Player Compare uses existing rostered-player evidence and does not create a winner, score, grade, buy/sell label, or recommendation.</section>
</main></body></html>
"@
}

function Add-PlayerCompareDetailAction {
    param(
        [Parameter(Mandatory = $true)][string]$Html,
        [Parameter(Mandatory = $true)][string]$PlayerId
    )

    $hrefId = [System.Uri]::EscapeDataString($PlayerId)
    $compare = "<a class=`"btn btn-secondary`" href=`"/compare?left=$hrefId`">Compare this player</a>"
    $candidates = @(
        '<div class="button-row"><a class="btn btn-secondary" href="/players">Back to Player Search</a><a class="btn btn-secondary" href="/team">My Team</a></div>',
        '<div class="button-row"><a class="btn btn-secondary" href="/team">Back to My Team</a><a class="btn btn-secondary" href="/players">Player Search</a></div>'
    )

    $matches = 0
    foreach ($current in $candidates) {
        if ($Html.Contains($current)) {
            $replacement = $current.Replace('</div>', $compare + '</div>')
            $Html = $Html.Replace($current, $replacement)
            $matches++
        }
    }
    if ($matches -ne 1) {
        throw "BF-906 BLOCKED: Player Detail compare action expected one contextual action row, found $matches."
    }
    return $Html
}
'@

$insertMarker = 'function Get-PlayerSearchRequestQuery {'
$insertIndex = $core.IndexOf($insertMarker, [System.StringComparison]::Ordinal)
if ($insertIndex -lt 0) {
    throw 'BF-906 BLOCKED: Player Compare function insertion marker is missing.'
}
$core = $core.Insert($insertIndex, $compareFunctions.TrimEnd() + [Environment]::NewLine + [Environment]::NewLine)

$searchActionOld = '<div class=`"button-row`" style=`"margin-top:12px`"><a class=`"btn btn-secondary`" href=`"/player?id=$hrefId&from=players`">View Player Detail</a></div></article>'
$searchActionNew = '<div class=`"button-row`" style=`"margin-top:12px`"><a class=`"btn btn-secondary`" href=`"/player?id=$hrefId&from=players`">View Player Detail</a><a class=`"btn btn-secondary`" href=`"/compare?left=$hrefId`">Compare this player</a><a class=`"btn btn-secondary`" href=`"/franchise?id=$([System.Uri]::EscapeDataString([string]$player.OwnerTeamId))`">Scout franchise</a></div></article>'
$core = Replace-ExactlyOnce -Text $core -Old $searchActionOld -New $searchActionNew -Contract 'Player Search Compare action'

$detailRouteOld = @'
                    $html = ConvertTo-PlayerDetailHtml -View $playerDetail
                    $fromPlayers = [regex]::IsMatch($parts[1], '(?:\?|&)from=players(?:&|$)')
                    $html = Add-PlayerDetailContextNavigation -Html $html -FromPlayers $fromPlayers
'@
$detailRouteNew = @'
                    $html = ConvertTo-PlayerDetailHtml -View $playerDetail
                    $fromPlayers = [regex]::IsMatch($parts[1], '(?:\?|&)from=players(?:&|$)')
                    $html = Add-PlayerDetailContextNavigation -Html $html -FromPlayers $fromPlayers
                    $html = Add-PlayerCompareDetailAction -Html $html -PlayerId $playerDetail.PlayerId
'@
$core = Replace-ExactlyOnce -Text $core -Old $detailRouteOld.TrimEnd() -New $detailRouteNew.TrimEnd() -Contract 'Player Detail Compare action'

$routeMarker = '            if ($path -eq "/players") {'
$routeIndex = $core.IndexOf($routeMarker, [System.StringComparison]::Ordinal)
if ($routeIndex -lt 0) {
    throw 'BF-906 BLOCKED: Player Compare route insertion marker is missing.'
}

$compareRoute = @'
            if ($path -eq "/compare") {
                try {
                    $compareRequest = Get-PlayerCompareRequest -RequestTarget $parts[1]
                    $compareView = $null
                    $selectedLeft = $null
                    $searchView = $null

                    if (-not [string]::IsNullOrWhiteSpace($compareRequest.LeftPlayerId) -and
                        -not [string]::IsNullOrWhiteSpace($compareRequest.RightPlayerId)) {
                        $compareBase = if ($compareRequest.LoadSupportingEvidence) {
                            "/__butler/internal/player-compare"
                        } else {
                            "/__butler/internal/player-compare-summary"
                        }
                        $comparePath = $compareBase + "?left=" + [System.Uri]::EscapeDataString($compareRequest.LeftPlayerId) + "&right=" + [System.Uri]::EscapeDataString($compareRequest.RightPlayerId)
                        $rawCompare = Invoke-Bf742DashboardWorkerRead -Path $comparePath -BoundaryName "BF-906"
                        $compareView = ConvertTo-PlayerCompareView -Text $rawCompare
                        $expectedSupportingState = if ($compareRequest.LoadSupportingEvidence) { 'READY' } else { 'DEFERRED' }
                        if ($compareView.SupportingEvidenceState -cne $expectedSupportingState) {
                            throw 'BF-906 BLOCKED: Player Compare supporting-evidence state does not match the exact request.'
                        }
                        if ($compareView.LeagueId -cne $LeagueId) {
                            throw 'BF-906 BLOCKED: Player Compare response does not match the exact current league.'
                        }
                        if ($compareView.Left.PlayerId -cne $compareRequest.LeftPlayerId -or
                            $compareView.Right.PlayerId -cne $compareRequest.RightPlayerId) {
                            throw 'BF-906 BLOCKED: Player Compare response does not match the exact requested players.'
                        }
                    }
                    elseif (-not [string]::IsNullOrWhiteSpace($compareRequest.LeftPlayerId)) {
                        $leftLookupPath = "/__butler/internal/player-search?q=" + [System.Uri]::EscapeDataString($compareRequest.LeftPlayerId)
                        $rawLeftLookup = Invoke-Bf742DashboardWorkerRead -Path $leftLookupPath -BoundaryName "BF-906"
                        $leftLookup = ConvertTo-PlayerSearchView -Text $rawLeftLookup
                        if ($leftLookup.LeagueId -cne $LeagueId -or
                            $leftLookup.Query -cne $compareRequest.LeftPlayerId) {
                            throw 'BF-906 BLOCKED: selected Player Compare lookup does not match the exact requested player.'
                        }
                        $leftMatches = @($leftLookup.Players | Where-Object { [string]$_.PlayerId -ceq $compareRequest.LeftPlayerId })
                        if ($leftMatches.Count -ne 1) {
                            throw "BF-906 BLOCKED: selected Player Compare player must resolve exactly once in league inventory."
                        }
                        $leftMatch = $leftMatches[0]
                        $selectedLeft = [pscustomobject]@{
                            LeagueId = $leftLookup.LeagueId
                            PlayerId = [string]$leftMatch.PlayerId
                            PlayerName = [string]$leftMatch.Name
                            Position = [string]$leftMatch.Position
                            TeamName = [string]$leftMatch.OwnerTeamName
                            RosterSlot = [string]$leftMatch.Slot
                        }

                        if (-not [string]::IsNullOrWhiteSpace($compareRequest.Query)) {
                            $searchPath = "/__butler/internal/player-search?q=" + [System.Uri]::EscapeDataString($compareRequest.Query)
                            $rawSearch = Invoke-Bf742DashboardWorkerRead -Path $searchPath -BoundaryName "BF-906"
                            $searchView = ConvertTo-PlayerSearchView -Text $rawSearch
                            if ($searchView.LeagueId -cne $LeagueId -or
                                $searchView.Query -cne $compareRequest.Query) {
                                throw 'BF-906 BLOCKED: Player Compare search response does not match the exact request.'
                            }
                        }
                    }

                    $html = ConvertTo-PlayerCompareHtml -Request $compareRequest -View $compareView -SelectedLeft $selectedLeft -SearchView $searchView
                    Send-HttpResponse -Stream $stream -StatusCode 200 -StatusText "OK" -ContentType "text/html; charset=utf-8" -Body $html
                }
                catch {
                    $errorHtml = "<!doctype html><html><body><h1>Butler Player Compare blocked</h1><pre>$(ConvertTo-HtmlText $_.Exception.Message)</pre><p>No provider refresh, Butler write, or Sleeper write was executed.</p><p><a href=`"/players`">Back to Player Search</a></p></body></html>"
                    Send-HttpResponse -Stream $stream -StatusCode 400 -StatusText "Bad Request" -ContentType "text/html; charset=utf-8" -Body $errorHtml
                }
                continue
            }

'@
$core = $core.Insert($routeIndex, $compareRoute)

foreach ($required in @(
    'function Get-PlayerCompareRequest',
    'function ConvertTo-PlayerCompareView',
    'function ConvertTo-PlayerCompareHtml',
    'function Add-PlayerCompareDetailAction',
    'Invoke-Bf742DashboardWorkerRead',
    '/__butler/internal/player-compare',
    '/__butler/internal/player-compare-summary',
    '/__butler/internal/player-search?q=',
    'Load supporting evidence',
    'Compare this player',
    'href="/franchise?id=$teamHrefId">Scout franchise</a>',
    'OwnerTeamId',
    'href=`"/compare?left=$hrefId`">Compare this player</a>',
    'href=`"/compare?left=$leftHref&right=$rightHref`"',
    'NOT A RANKING',
    'Butler does not choose a winner',
    'Player Compare shows existing Butler evidence only'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-906 BLOCKED: required Player Compare marker is missing: $required"
    }
}

$installedStart = $core.IndexOf('function Get-PlayerCompareRequest', [System.StringComparison]::Ordinal)
$installedEnd = $core.IndexOf('function Get-PlayerSearchRequestQuery', $installedStart, [System.StringComparison]::Ordinal)
if ($installedStart -lt 0 -or $installedEnd -le $installedStart) {
    throw 'BF-906 BLOCKED: Player Compare installed function boundary is missing.'
}
$installed = $core.Substring($installedStart, $installedEnd - $installedStart)
foreach ($forbidden in @(
    'Invoke-RestMethod',
    'Invoke-WebRequest',
    'Method = "POST"',
    'https://api.sleeper.app',
    'submitTransaction',
    'setFaab',
    'player-score',
    'winner-policy',
    'better-player-score'
)) {
    if ($installed.IndexOf($forbidden, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
        throw "BF-906 BLOCKED: Player Compare introduced forbidden provider, write, score, or winner behavior: $forbidden"
    }
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($CorePath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $parseSummary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-906 BLOCKED: generated staged core failed PowerShell parse: $parseSummary"
}
