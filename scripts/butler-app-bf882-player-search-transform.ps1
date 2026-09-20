param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-882 BLOCKED: staged Butler core not found at $CorePath"
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
        throw "BF-882 BLOCKED: $Contract expected one match, found $matches."
    }
    return $Text.Replace($Old, $New)
}

$core = [System.IO.File]::ReadAllText($CorePath)

$searchFunctions = @'
function Get-PlayerSearchRequestQuery {
    param([Parameter(Mandatory = $true)][string]$RequestTarget)

    $question = $RequestTarget.IndexOf('?')
    if ($question -lt 0) { return '' }

    $queries = @()
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
        if ($name -cne 'q') {
            throw 'BF-882 BLOCKED: Player Search accepts only the q query parameter.'
        }
        $queries += [System.Uri]::UnescapeDataString($rawValue.Replace('+', ' '))
    }

    if ($queries.Count -eq 0) { return '' }
    if ($queries.Count -ne 1) {
        throw 'BF-882 BLOCKED: Player Search requires at most one q query parameter.'
    }

    $query = [regex]::Replace(([string]$queries[0]).Trim(), '\s+', ' ')
    if ([string]::IsNullOrWhiteSpace($query)) { return '' }
    if ($query.Length -gt 80) {
        throw 'BF-882 BLOCKED: Player Search query must be 80 characters or fewer.'
    }
    if ($query -notmatch '^[A-Za-z0-9 ._''-]+$') {
        throw 'BF-882 BLOCKED: Player Search query contains unsupported characters.'
    }
    return $query
}

function ConvertTo-PlayerSearchView {
    param([Parameter(Mandatory = $true)][string]$Text)

    $league = [regex]::Match($Text, '(?m)^League ID:\s+(?<value>\S+)\s*$')
    $source = [regex]::Match($Text, '(?m)^Source:\s+(?<value>.+?)\s*$')
    $query = [regex]::Match($Text, '(?m)^Query:\s+(?<value>.+?)\s*$')
    $playerCount = [regex]::Match($Text, '(?m)^Player matches:\s+(?<value>\d+)\s*$')
    $otherCount = [regex]::Match($Text, '(?m)^Other asset matches:\s+(?<value>\d+)\s*$')
    foreach ($required in @($league, $source, $query, $playerCount, $otherCount)) {
        if (-not $required.Success) {
            throw 'BF-882 BLOCKED: player-search output is missing a required summary field.'
        }
    }

    $players = @()
    $current = $null
    foreach ($line in @($Text -split '\r?\n')) {
        if ($line -ceq '===BUTLER_PLAYER_SEARCH:PLAYER:BEGIN===') {
            if ($null -ne $current) {
                throw 'BF-882 BLOCKED: nested player-search result block.'
            }
            $current = @{}
            continue
        }
        if ($line -ceq '===BUTLER_PLAYER_SEARCH:PLAYER:END===') {
            if ($null -eq $current) {
                throw 'BF-882 BLOCKED: player-search result ended without a begin marker.'
            }
            foreach ($field in @(
                'Player ID','Player name','Position','NFL team','Owner team ID',
                'Owner team name','Roster slot','Value','Value as-of'
            )) {
                if (-not $current.ContainsKey($field) -or [string]::IsNullOrWhiteSpace([string]$current[$field])) {
                    throw "BF-882 BLOCKED: player-search result is missing $field."
                }
            }
            $players += [pscustomobject]@{
                PlayerId = [string]$current['Player ID']
                Name = [string]$current['Player name']
                Position = [string]$current['Position']
                NflTeam = [string]$current['NFL team']
                OwnerTeamId = [string]$current['Owner team ID']
                OwnerTeamName = [string]$current['Owner team name']
                Slot = [string]$current['Roster slot']
                Value = [string]$current['Value']
                ValueAsOf = [string]$current['Value as-of']
            }
            $current = $null
            continue
        }

        if ($null -eq $current) { continue }
        $field = [regex]::Match($line, '^(?<name>Player ID|Player name|Position|NFL team|Owner team ID|Owner team name|Roster slot|Value|Value as-of):\s*(?<value>.*)$')
        if ($field.Success) {
            $name = $field.Groups['name'].Value
            if ($current.ContainsKey($name)) {
                throw "BF-882 BLOCKED: duplicate player-search field $name."
            }
            $current[$name] = $field.Groups['value'].Value.Trim()
        }
    }

    if ($null -ne $current) {
        throw 'BF-882 BLOCKED: player-search result block is incomplete.'
    }

    $expected = [int]$playerCount.Groups['value'].Value
    if ($players.Count -ne $expected) {
        throw "BF-882 BLOCKED: parsed player-search result count $($players.Count) does not match $expected."
    }

    return [pscustomobject]@{
        LeagueId = $league.Groups['value'].Value
        Source = $source.Groups['value'].Value.Trim()
        Query = $query.Groups['value'].Value.Trim()
        PlayerMatches = $expected
        OtherAssetMatches = [int]$otherCount.Groups['value'].Value
        Players = @($players)
    }
}

function ConvertTo-PlayerSearchHtml {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Query,
        [AllowNull()]$View
    )

    $css = Get-AppCss
    $nav = Get-AppNav -Active 'league'
    $inputValue = ConvertTo-HtmlText $Query
    $form = '<form method="get" action="/players" style="display:flex;gap:10px;flex-wrap:wrap;align-items:center;margin-top:18px"><input name="q" value="' + $inputValue + '" maxlength="80" placeholder="Player name, position, NFL team..." aria-label="Player search" style="flex:1;min-width:240px;background:#0d1630;color:#f7f8fb;border:1px solid #33436f;border-radius:11px;padding:12px 14px;font:inherit"><button class="btn btn-primary" type="submit">Search players</button></form>'

    $resultsHtml = ''
    $summary = 'Search the players currently rostered in this Butler league. Free agents remain on Waiver Board.'
    $status = 'READY'
    $statusClass = 'done'

    if ($null -ne $View) {
        $status = 'NOT A RANKING'
        $summary = "Found $($View.PlayerMatches) rostered player match(es) for '$($View.Query)'. Results keep Butler's existing deterministic asset-search order."
        if ($View.PlayerMatches -eq 0) {
            $resultsHtml = '<div class="empty">No currently rostered player matched this query. Butler did not broaden the search to fantasy-team owner names.</div>'
        }
        else {
            $cards = ''
            foreach ($player in $View.Players) {
                $hrefId = [System.Uri]::EscapeDataString([string]$player.PlayerId)
                $valueText = if ($player.Value -ceq 'UNAVAILABLE') { 'Value unavailable' } else { "Persisted value $($player.Value)" }
                $asOfText = if ($player.ValueAsOf -ceq 'UNAVAILABLE') { 'as-of unavailable' } else { "as-of $($player.ValueAsOf)" }
                $cards += "<article class=`"card`"><div class=`"eyebrow`">$(ConvertTo-HtmlText $player.Position) &middot; NFL $(ConvertTo-HtmlText $player.NflTeam)</div><div class=`"name`">$(ConvertTo-HtmlText $player.Name)</div><div class=`"meta`">Rostered by $(ConvertTo-HtmlText $player.OwnerTeamName) &middot; $(ConvertTo-HtmlText $player.Slot)</div><div class=`"meta`">$(ConvertTo-HtmlText $valueText) &middot; $(ConvertTo-HtmlText $asOfText)</div><div class=`"button-row`" style=`"margin-top:12px`"><a class=`"btn btn-secondary`" href=`"/player?id=$hrefId`">View Player Detail</a></div></article>"
            }
            $resultsHtml = "<div class=`"grid`">$cards</div>"
        }

        if ($View.OtherAssetMatches -gt 0) {
            $resultsHtml += "<div class=`"callout`" style=`"margin-top:14px`">This query also matched $(ConvertTo-HtmlText $View.OtherAssetMatches) draft-pick asset(s). Player Search intentionally shows rostered players only.</div>"
        }
    }

    return @"
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="dark"><title>Butler - Player Search</title><style>$css</style></head><body><main class="shell">
<header class="top"><div class="brand"><h1>BUTLER</h1><p>We're here to serve you. Less Research. Better Decisions.</p></div><div class="target">Player Search</div></header>
$nav
<section class="panel hero-panel"><div class="manager-head"><div><div class="eyebrow">League tool</div><h1 class="headline">Find a rostered player</h1><p class="lede">$(ConvertTo-HtmlText $summary)</p></div><span class="status $statusClass">$(ConvertTo-HtmlText $status)</span></div>$form</section>
$(if ($null -ne $View) { "<section class=`"panel`"><div class=`"section-head`"><div><div class=`"eyebrow`">Search results</div><h2>Rostered players</h2><p class=`"lede`">Names link only through exact Butler player IDs. Persisted value is descriptive evidence, not a ranking.</p></div></div>$resultsHtml<details><summary>Search evidence</summary><div class=`"technical`">source $(ConvertTo-HtmlText $View.Source) &middot; league $(ConvertTo-HtmlText $View.LeagueId) &middot; query $(ConvertTo-HtmlText $View.Query) &middot; player matches $(ConvertTo-HtmlText $View.PlayerMatches) &middot; other asset matches $(ConvertTo-HtmlText $View.OtherAssetMatches)</div></details></section>" } else { '' })
<section class="panel boundary"><span class="lock">READ ONLY &middot; NOT A RANKING.</span> Player Search uses Butler's existing persisted league asset-search evidence for currently rostered players. Fantasy-team names are context only and do not cause matches. It does not search free agents, refresh evidence, adjust values, create a player score or rank, or produce waiver, trade, or start/sit recommendations.</section>
</main></body></html>
"@
}
'@

$playerMarker = 'function Get-PlayerDetailRequestId {'
$playerIndex = $core.IndexOf($playerMarker, [System.StringComparison]::Ordinal)
if ($playerIndex -lt 0) {
    throw 'BF-882 BLOCKED: Player Search function insertion marker is missing.'
}
$core = $core.Insert($playerIndex, $searchFunctions.TrimEnd() + [Environment]::NewLine + [Environment]::NewLine)

$teamActionsOld = '<div class="button-row team-primary-actions"><a class="btn btn-primary" href="/matchup">Review Matchup</a><a class="btn btn-secondary" href="/matchup/autofill">Review Lineup</a></div>'
$teamActionsNew = '<div class="button-row team-primary-actions"><a class="btn btn-primary" href="/matchup">Review Matchup</a><a class="btn btn-secondary" href="/matchup/autofill">Review Lineup</a><a class="btn btn-secondary" href="/players">Find a player</a></div>'
$core = Replace-ExactlyOnce -Text $core -Old $teamActionsOld -New $teamActionsNew -Contract 'My Team secondary Player Search action'

$playerDetailStart = $core.IndexOf('function ConvertTo-PlayerDetailHtml {', [System.StringComparison]::Ordinal)
$playerDetailEnd = $core.IndexOf('function Get-FranchiseDetailRequestId {', $playerDetailStart, [System.StringComparison]::Ordinal)
if ($playerDetailStart -lt 0 -or $playerDetailEnd -le $playerDetailStart) {
    throw 'BF-882 BLOCKED: Player Detail function boundary is missing.'
}
$playerDetailBlock = $core.Substring($playerDetailStart, $playerDetailEnd - $playerDetailStart)
$playerActionsOld = '<div class="button-row"><a class="btn btn-secondary" href="/team">Back to My Team</a></div>'
$playerActionsNew = '<div class="button-row"><a class="btn btn-secondary" href="/team">Back to My Team</a><a class="btn btn-secondary" href="/players">Find another player</a></div>'
$playerDetailBlock = Replace-ExactlyOnce -Text $playerDetailBlock -Old $playerActionsOld -New $playerActionsNew -Contract 'Player Detail secondary Player Search action'
$core = $core.Substring(0, $playerDetailStart) + $playerDetailBlock + $core.Substring($playerDetailEnd)

$leagueStart = $core.IndexOf('function ConvertTo-LeagueHtml {', [System.StringComparison]::Ordinal)
$leagueEnd = $core.IndexOf('function ConvertTo-TeamHtml {', $leagueStart, [System.StringComparison]::Ordinal)
if ($leagueStart -lt 0 -or $leagueEnd -le $leagueStart) {
    throw 'BF-882 BLOCKED: League renderer boundary is missing.'
}
$leagueBlock = $core.Substring($leagueStart, $leagueEnd - $leagueStart)
$leagueSourceOld = '<details><summary>Source details</summary>'
$leagueSourceNew = '<div class="button-row"><a class="btn btn-secondary" href="/players">Find a player</a></div><details><summary>Source details</summary>'
$leagueBlock = Replace-ExactlyOnce -Text $leagueBlock -Old $leagueSourceOld -New $leagueSourceNew -Contract 'League secondary Player Search action'
$core = $core.Substring(0, $leagueStart) + $leagueBlock + $core.Substring($leagueEnd)

$routeMarker = '            if ($path -eq "/player") {'
$routeIndex = $core.IndexOf($routeMarker, [System.StringComparison]::Ordinal)
if ($routeIndex -lt 0) {
    throw 'BF-882 BLOCKED: Player Search route insertion marker is missing.'
}

$searchRoute = @'
            if ($path -eq "/players") {
                try {
                    $query = Get-PlayerSearchRequestQuery -RequestTarget $parts[1]
                    if ([string]::IsNullOrWhiteSpace($query)) {
                        $html = ConvertTo-PlayerSearchHtml -Query '' -View $null
                    }
                    else {
                        $rawPlayerSearch = Invoke-ButlerReadOnly -Arguments "league player-search $LeagueId $query" -BoundaryName "BF-882"
                        $playerSearch = ConvertTo-PlayerSearchView -Text $rawPlayerSearch
                        if ($playerSearch.LeagueId -cne $LeagueId) {
                            throw 'BF-882 BLOCKED: player-search response does not match the exact current league.'
                        }
                        if ($playerSearch.Query -cne $query) {
                            throw 'BF-882 BLOCKED: player-search response does not match the exact normalized query.'
                        }
                        $html = ConvertTo-PlayerSearchHtml -Query $query -View $playerSearch
                    }
                    Send-HttpResponse -Stream $stream -StatusCode 200 -StatusText "OK" -ContentType "text/html; charset=utf-8" -Body $html
                }
                catch {
                    $errorHtml = "<!doctype html><html><body><h1>Butler Player Search blocked</h1><pre>$(ConvertTo-HtmlText $_.Exception.Message)</pre><p>No provider refresh, Butler write, or Sleeper write was executed.</p><p><a href=`"/players`">Back to Player Search</a></p></body></html>"
                    Send-HttpResponse -Stream $stream -StatusCode 400 -StatusText "Bad Request" -ContentType "text/html; charset=utf-8" -Body $errorHtml
                }
                continue
            }

'@
$core = $core.Insert($routeIndex, $searchRoute)

foreach ($required in @(
    'function Get-PlayerSearchRequestQuery',
    'function ConvertTo-PlayerSearchView',
    'function ConvertTo-PlayerSearchHtml',
    'Find a rostered player',
    'NOT A RANKING',
    'Fantasy-team names are context only and do not cause matches',
    'league player-search $LeagueId $query',
    'href=`"/player?id=$hrefId`"',
    'href="/players">Find a player</a>',
    'href="/players">Find another player</a>'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-882 BLOCKED: required Player Search marker is missing: $required"
    }
}

$searchStart = $core.IndexOf('function Get-PlayerSearchRequestQuery', [System.StringComparison]::Ordinal)
$searchEnd = $core.IndexOf('function Get-PlayerDetailRequestId', $searchStart, [System.StringComparison]::Ordinal)
$installedSearch = $core.Substring($searchStart, $searchEnd - $searchStart)
foreach ($forbidden in @(
    'Invoke-RestMethod',
    'Invoke-WebRequest',
    'Method = "POST"',
    'https://api.sleeper.app',
    'submitTransaction',
    'setFaab',
    'player-score'
)) {
    if ($installedSearch.IndexOf($forbidden, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
        throw "BF-882 BLOCKED: Player Search introduced forbidden provider, write, or score behavior: $forbidden"
    }
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))
