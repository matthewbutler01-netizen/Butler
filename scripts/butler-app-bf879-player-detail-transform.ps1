param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-879 BLOCKED: staged Butler core not found at $CorePath"
}

$core = [System.IO.File]::ReadAllText($CorePath)

$playerFunctions = @'
function Get-PlayerDetailRequestId {
    param([Parameter(Mandatory = $true)][string]$RequestTarget)

    $question = $RequestTarget.IndexOf('?')
    if ($question -lt 0 -or $question -eq ($RequestTarget.Length - 1)) {
        throw 'BF-879 BLOCKED: Player Detail requires exactly one player id.'
    }

    $ids = @()
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
        if ($name -ceq 'id') {
            $ids += [System.Uri]::UnescapeDataString($rawValue.Replace('+', ' ')).Trim()
        }
    }

    if ($ids.Count -ne 1 -or [string]::IsNullOrWhiteSpace([string]$ids[0])) {
        throw 'BF-879 BLOCKED: Player Detail requires exactly one non-empty player id.'
    }
    if ([string]$ids[0] -notmatch '^[A-Za-z0-9._:-]+$') {
        throw 'BF-879 BLOCKED: Player Detail player id contains unsupported characters.'
    }
    return [string]$ids[0]
}

function ConvertTo-MyTeamPlayerNameHtml {
    param([Parameter(Mandatory = $true)]$Player)

    $mapped = [string]$Player.Mapping -ceq 'EXACT_CANONICAL'
    $playerId = [string]$Player.ButlerPlayerId
    if ($mapped -and -not [string]::IsNullOrWhiteSpace($playerId) -and
        $playerId -cne '-' -and $playerId -cne 'none') {
        $hrefId = [System.Uri]::EscapeDataString($playerId)
        $link = @"
<a href="/player?id=$hrefId">$(ConvertTo-HtmlText $Player.Name)</a>
"@
        return $link.Trim()
    }

    return ConvertTo-HtmlText $Player.Name
}

function ConvertTo-PlayerDetailView {
    param([Parameter(Mandatory = $true)][string]$Text)

    $league = [regex]::Match($Text, '(?m)^League ID:\s+(?<value>\S+)\s*$')
    $season = [regex]::Match($Text, '(?m)^Season:\s+(?<value>\d{4})\s*$')
    $playerId = [regex]::Match($Text, '(?m)^Player ID:\s+(?<value>\S+)\s*$')
    $playerName = [regex]::Match($Text, '(?m)^Player name:\s+(?<value>.+?)\s*$')
    $position = [regex]::Match($Text, '(?m)^Position:\s+(?<value>\S+)\s*$')
    $team = [regex]::Match($Text, '(?m)^Butler team:\s+(?<name>.+?)\s+\[(?<id>[^\]]+)\]\s*$')
    $slot = [regex]::Match($Text, '(?m)^Roster slot:\s+(?<value>.+?)\s*$')
    $age = [regex]::Match($Text, '(?m)^Age:\s+(?<value>UNAVAILABLE|\d+)\s*$')
    $ageProvenance = [regex]::Match($Text, '(?m)^Age provenance:\s+(?<value>EXACT_BIRTH_DATE|PROVIDER_REPORTED|UNAVAILABLE)\s*$')
    $ageAsOf = [regex]::Match($Text, '(?m)^Age as-of:\s+(?<value>\d{4}-\d{2}-\d{2})\s*$')
    $profileSource = [regex]::Match($Text, '(?m)^Profile source:\s+(?<value>\S+)\s*$')
    $productionSource = [regex]::Match($Text, '(?m)^Production source:\s+(?<value>\S+)\s*$')
    $snapshot = [regex]::Match($Text, '(?m)^Production snapshot:\s+(?<value>AVAILABLE|MISSING)\s*$')
    $games = [regex]::Match($Text, '(?m)^Games played:\s+(?<value>UNAVAILABLE|\d+)\s*$')
    $passYards = [regex]::Match($Text, '(?m)^Passing yards/game:\s+(?<value>UNAVAILABLE|-?\d+(?:\.\d+)?)\s*$')
    $passTd = [regex]::Match($Text, '(?m)^Passing TD/game:\s+(?<value>UNAVAILABLE|-?\d+(?:\.\d+)?)\s*$')
    $interceptions = [regex]::Match($Text, '(?m)^Interceptions/game:\s+(?<value>UNAVAILABLE|-?\d+(?:\.\d+)?)\s*$')
    $rushYards = [regex]::Match($Text, '(?m)^Rushing yards/game:\s+(?<value>UNAVAILABLE|-?\d+(?:\.\d+)?)\s*$')
    $rushTd = [regex]::Match($Text, '(?m)^Rushing TD/game:\s+(?<value>UNAVAILABLE|-?\d+(?:\.\d+)?)\s*$')
    $receptions = [regex]::Match($Text, '(?m)^Receptions/game:\s+(?<value>UNAVAILABLE|-?\d+(?:\.\d+)?)\s*$')
    $receivingYards = [regex]::Match($Text, '(?m)^Receiving yards/game:\s+(?<value>UNAVAILABLE|-?\d+(?:\.\d+)?)\s*$')
    $receivingTd = [regex]::Match($Text, '(?m)^Receiving TD/game:\s+(?<value>UNAVAILABLE|-?\d+(?:\.\d+)?)\s*$')
    $fumbles = [regex]::Match($Text, '(?m)^Fumbles lost/game:\s+(?<value>UNAVAILABLE|-?\d+(?:\.\d+)?)\s*
    $modelAgeAsOf = [regex]::Match($Text, '(?m)^Supporting model age as-of:\s+(?<value>\d{4}-\d{2}-\d{2})\s*$')
    $supportPolicy = [regex]::Match($Text, '(?m)^Supporting policy:\s+(?<value>\S+)\s*$')
    $outlookPolicy = [regex]::Match($Text, '(?m)^Outlook policy:\s+(?<value>\S+)\s*$')
    $supportSources = [regex]::Match($Text, '(?m)^Supporting sources:\s+(?<value>.+?)\s*$')

    foreach ($required in @(
        $league,$season,$playerId,$playerName,$position,$team,$slot,$age,$ageProvenance,$ageAsOf,
        $profileSource,$productionSource,$snapshot,$games,$passYards,$passTd,$interceptions,
        $rushYards,$rushTd,$receptions,$receivingYards,$receivingTd,$fumbles,$supportingState
    )) {
        if (-not $required.Success) {
            throw 'BF-879 BLOCKED: player detail output is missing a required evidence field.'
        }
    }

    $supportingEvidenceState = $supportingState.Groups['value'].Value.Trim()
    if ($supportingEvidenceState -ceq 'READY') {
        foreach ($required in @($supportingCount,$modelAgeAsOf,$supportPolicy,$outlookPolicy,$supportSources)) {
            if (-not $required.Success) {
                throw 'BF-879 BLOCKED: ready player detail output is missing supporting evidence metadata.'
            }
        }
    }

    $flags = @()
    $flagPattern = '^\s{2}(?<signal>FAVORABLE|UNFAVORABLE|INCONCLUSIVE)\s+\|\s+(?<category>[^|]+?)\s+\|\s+(?<dimension>[^|]+?)\s+\|\s+(?<summary>.+?)\s*$'
    foreach ($line in @($Text -split '\r?\n')) {
        $flag = [regex]::Match($line, $flagPattern)
        if (-not $flag.Success) { continue }
        $flags += [pscustomobject]@{
            Signal = $flag.Groups['signal'].Value.Trim()
            Category = $flag.Groups['category'].Value.Trim()
            Dimension = $flag.Groups['dimension'].Value.Trim()
            Summary = $flag.Groups['summary'].Value.Trim()
        }
    }
    if ($supportingEvidenceState -ceq 'READY') {
        if ($flags.Count -ne [int]$supportingCount.Groups['value'].Value) {
            throw 'BF-879 BLOCKED: player detail supporting-flag count does not match the rendered evidence.'
        }
    }
    elseif ($flags.Count -ne 0) {
        throw 'BF-916 BLOCKED: deferred player detail unexpectedly included supporting flags.'
    }

    return [pscustomobject]@{
        LeagueId = $league.Groups['value'].Value.Trim()
        Season = [int]$season.Groups['value'].Value
        PlayerId = $playerId.Groups['value'].Value.Trim()
        PlayerName = $playerName.Groups['value'].Value.Trim()
        Position = $position.Groups['value'].Value.Trim()
        TeamId = $team.Groups['id'].Value.Trim()
        TeamName = $team.Groups['name'].Value.Trim()
        RosterSlot = $slot.Groups['value'].Value.Trim()
        Age = $age.Groups['value'].Value.Trim()
        AgeProvenance = $ageProvenance.Groups['value'].Value.Trim()
        AgeAsOf = $ageAsOf.Groups['value'].Value.Trim()
        ProfileSource = $profileSource.Groups['value'].Value.Trim()
        ProductionSource = $productionSource.Groups['value'].Value.Trim()
        ProductionSnapshot = $snapshot.Groups['value'].Value.Trim()
        GamesPlayed = $games.Groups['value'].Value.Trim()
        PassingYardsPerGame = $passYards.Groups['value'].Value.Trim()
        PassingTouchdownsPerGame = $passTd.Groups['value'].Value.Trim()
        InterceptionsPerGame = $interceptions.Groups['value'].Value.Trim()
        RushingYardsPerGame = $rushYards.Groups['value'].Value.Trim()
        RushingTouchdownsPerGame = $rushTd.Groups['value'].Value.Trim()
        ReceptionsPerGame = $receptions.Groups['value'].Value.Trim()
        ReceivingYardsPerGame = $receivingYards.Groups['value'].Value.Trim()
        ReceivingTouchdownsPerGame = $receivingTd.Groups['value'].Value.Trim()
        FumblesLostPerGame = $fumbles.Groups['value'].Value.Trim()
        SupportingEvidenceState = $supportingEvidenceState
        ModelAgeAsOf = if ($modelAgeAsOf.Success) { $modelAgeAsOf.Groups['value'].Value.Trim() } else { 'DEFERRED' }
        SupportPolicy = if ($supportPolicy.Success) { $supportPolicy.Groups['value'].Value.Trim() } else { 'DEFERRED' }
        OutlookPolicy = if ($outlookPolicy.Success) { $outlookPolicy.Groups['value'].Value.Trim() } else { 'DEFERRED' }
        SupportSources = if ($supportSources.Success) { $supportSources.Groups['value'].Value.Trim() } else { 'DEFERRED' }
        SupportingFlags = @($flags)
    }
}

function ConvertTo-PlayerDetailHtml {
    param([Parameter(Mandatory = $true)]$View)

    $css = Get-AppCss
    $nav = Get-AppNav -Active 'team'

    $ageText = if ($View.Age -ceq 'UNAVAILABLE') { 'Age unavailable' } else { "$($View.Age) years old" }
    $ageEvidence = switch ($View.AgeProvenance) {
        'EXACT_BIRTH_DATE' { 'Derived from exact birth-date evidence.' }
        'PROVIDER_REPORTED' { 'Provider-reported age; Butler does not extrapolate it.' }
        default { 'No age evidence is available; Butler does not infer one.' }
    }

    if ($View.ProductionSnapshot -ceq 'MISSING') {
        $productionHtml = '<div class="empty">No persisted production snapshot is available for this player and season. Butler does not substitute zeroes.</div>'
    }
    elseif ($View.GamesPlayed -ceq '0') {
        $productionHtml = '<div class="empty">A production snapshot exists with 0 games played. Per-game rates remain unavailable rather than being converted to zero.</div>'
    }
    else {
        $rateCards = ''
        foreach ($rate in @(
            [pscustomobject]@{ Label = 'Pass yards / game'; Value = $View.PassingYardsPerGame },
            [pscustomobject]@{ Label = 'Pass TD / game'; Value = $View.PassingTouchdownsPerGame },
            [pscustomobject]@{ Label = 'INT / game'; Value = $View.InterceptionsPerGame },
            [pscustomobject]@{ Label = 'Rush yards / game'; Value = $View.RushingYardsPerGame },
            [pscustomobject]@{ Label = 'Rush TD / game'; Value = $View.RushingTouchdownsPerGame },
            [pscustomobject]@{ Label = 'Receptions / game'; Value = $View.ReceptionsPerGame },
            [pscustomobject]@{ Label = 'Rec yards / game'; Value = $View.ReceivingYardsPerGame },
            [pscustomobject]@{ Label = 'Rec TD / game'; Value = $View.ReceivingTouchdownsPerGame },
            [pscustomobject]@{ Label = 'Fumbles lost / game'; Value = $View.FumblesLostPerGame }
        )) {
            if ([string]$rate.Value -ceq 'UNAVAILABLE') { continue }
            $rateCards += @"
<div class="metric-card"><span class="metric-label">$(ConvertTo-HtmlText $rate.Label)</span><span class="metric-value">$(ConvertTo-HtmlText $rate.Value)</span></div>
"@
        }
        if ([string]::IsNullOrWhiteSpace($rateCards)) {
            $rateCards = '<div class="empty">Per-game rates are unavailable for this production snapshot.</div>'
        }
        $productionHtml = @"
<div class="stats"><div class="stat"><strong>Season</strong><span>$(ConvertTo-HtmlText $View.Season)</span></div><div class="stat"><strong>Games</strong><span>$(ConvertTo-HtmlText $View.GamesPlayed)</span></div></div><div class="manager-metrics">$rateCards</div><p class="meta">Unavailable rates stay unavailable; Butler does not manufacture a zero for missing evidence.</p>
"@
    }

    $flagsHtml = ''
    foreach ($flag in @($View.SupportingFlags)) {
        $signalClass = switch ($flag.Signal) {
            'FAVORABLE' { 'good' }
            'UNFAVORABLE' { 'warn' }
            default { 'done' }
        }
        $flagsHtml += @"
<div class="card"><div class="action-head"><span class="status $signalClass">$(ConvertTo-HtmlText $flag.Signal)</span><span class="kind">$(ConvertTo-HtmlText $flag.Dimension)</span></div><p>$(ConvertTo-HtmlText $flag.Summary)</p></div>
"@
    }
    if ([string]::IsNullOrWhiteSpace($flagsHtml)) {
        $flagsHtml = '<div class="empty">No governed age-outlook supporting flags are available for this player. Butler does not infer one.</div>'
    }

    $supportingDetailsHtml = '<details><summary>Evidence sources</summary><div class="technical">Profile source ' + (ConvertTo-HtmlText $View.ProfileSource) + ' &middot; production source ' + (ConvertTo-HtmlText $View.ProductionSource) + ' &middot; supporting model age as-of ' + (ConvertTo-HtmlText $View.ModelAgeAsOf) + ' &middot; support policy ' + (ConvertTo-HtmlText $View.SupportPolicy) + ' &middot; outlook policy ' + (ConvertTo-HtmlText $View.OutlookPolicy) + ' &middot; supporting sources ' + (ConvertTo-HtmlText $View.SupportSources) + ' &middot; Butler player ' + (ConvertTo-HtmlText $View.PlayerId) + ' &middot; Butler team ' + (ConvertTo-HtmlText $View.TeamId) + '</div></details>'
    if ($View.SupportingEvidenceState -ceq 'DEFERRED') {
        $hrefId = [System.Uri]::EscapeDataString([string]$View.PlayerId)
        $supportHref = "/player?id=$hrefId&support=1#supporting-evidence"
        $flagsHtml = '<div class="empty">Supporting evidence is deferred so Player Hub can open quickly. Load it only when you want the deeper age-outlook context.</div><div class="button-row"><a class="btn btn-secondary" href="' + (ConvertTo-HtmlText $supportHref) + '">Load supporting evidence</a></div>'
        $supportingDetailsHtml = '<details><summary>Evidence sources</summary><div class="technical">Profile source ' + (ConvertTo-HtmlText $View.ProfileSource) + ' &middot; production source ' + (ConvertTo-HtmlText $View.ProductionSource) + ' &middot; supporting evidence deferred &middot; Butler player ' + (ConvertTo-HtmlText $View.PlayerId) + ' &middot; Butler team ' + (ConvertTo-HtmlText $View.TeamId) + '</div></details>'
    }

    return @"
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="dark"><title>Butler - $(ConvertTo-HtmlText $View.PlayerName)</title><style>$css</style></head><body><main class="shell">
<header class="top"><div class="brand"><h1>BUTLER</h1><p>We're here to serve you. Less Research. Better Decisions.</p></div><div class="target">$(ConvertTo-HtmlText $View.TeamName) &middot; $(ConvertTo-HtmlText $View.Season)</div></header>
$nav
<section class="panel hero-panel"><div class="manager-head"><div><div class="eyebrow">Player Detail</div><h1 class="headline">$(ConvertTo-HtmlText $View.PlayerName)</h1><p class="lede">$(ConvertTo-HtmlText $View.Position) &middot; $(ConvertTo-HtmlText $View.TeamName) &middot; $(ConvertTo-HtmlText $View.RosterSlot)</p></div><span class="status done">EVIDENCE</span></div><div class="button-row"><a class="btn btn-secondary" href="/team">Back to My Team</a></div></section>
<section class="panel"><div class="section-head"><div><div class="eyebrow">Age context</div><h2>$ageText</h2><p class="lede">$ageEvidence</p></div></div><div class="stats"><div class="stat"><strong>Age as of</strong><span>$(ConvertTo-HtmlText $View.AgeAsOf)</span></div><div class="stat"><strong>Provenance</strong><span>$(ConvertTo-HtmlText $View.AgeProvenance)</span></div></div></section>
<section class="panel"><div class="section-head"><div><div class="eyebrow">Season production</div><h2>Per-game evidence</h2><p class="lede">Persisted raw production context only. This is not a fantasy score or player grade.</p></div></div>$productionHtml</section>
<section class="panel" id="supporting-evidence"><div class="section-head"><div><div class="eyebrow">Age outlook context</div><h2>Supporting evidence</h2><p class="lede">Optional governed flags are shown as context only. They are not weighted into a player score or recommendation.</p></div></div><div class="actions">$flagsHtml</div>$supportingDetailsHtml</section>
<section class="panel boundary"><span class="lock">READ ONLY.</span> Player Detail shows existing neutral evidence only. It does not create a universal player score, grade, rank, buy/sell label, career-arc classification, dynasty adjustment, start/sit recommendation, trade recommendation, waiver recommendation, refresh, or Sleeper write.</section>
</main></body></html>
"@
}
'@

$leagueMarker = 'function ConvertTo-LeagueHtml {'
$leagueIndex = $core.IndexOf($leagueMarker, [System.StringComparison]::Ordinal)
if ($leagueIndex -lt 0) {
    throw 'BF-879 BLOCKED: Player Detail renderer insertion marker is missing.'
}
$core = $core.Insert($leagueIndex, $playerFunctions.TrimEnd() + [Environment]::NewLine + [Environment]::NewLine)

$nameMarkupOld = '<strong>$(ConvertTo-HtmlText $player.Name)</strong>'
$nameMarkupNew = '<strong>$(ConvertTo-MyTeamPlayerNameHtml -Player $player)</strong>'
$nameMatches = [regex]::Matches($core, [regex]::Escape($nameMarkupOld)).Count
if ($nameMatches -ne 3) {
    throw "BF-879 BLOCKED: expected exactly three My Team player-name render sites, found $nameMatches."
}
$core = $core.Replace($nameMarkupOld, $nameMarkupNew)

$routeMarker = '            if ($path -eq "/league") {'
$routeIndex = $core.IndexOf($routeMarker, [System.StringComparison]::Ordinal)
if ($routeIndex -lt 0) {
    throw 'BF-879 BLOCKED: Player Detail route insertion marker is missing.'
}

$playerRoute = @'
            if ($path -eq "/player") {
                try {
                    $playerId = Get-PlayerDetailRequestId -RequestTarget $parts[1]
                    $loadSupportingEvidence = [regex]::IsMatch($parts[1], '(?:\?|&)support=1(?:&|$)')
                    $playerDetailBase = if ($loadSupportingEvidence) {
                        "/__butler/internal/player-detail"
                    } else {
                        "/__butler/internal/player-detail-summary"
                    }
                    $playerDetailPath = $playerDetailBase + "?player=" + [System.Uri]::EscapeDataString($playerId)
                    $rawPlayerDetail = Invoke-Bf742DashboardWorkerRead -Path $playerDetailPath -BoundaryName "BF-916"
                    $playerDetail = ConvertTo-PlayerDetailView -Text $rawPlayerDetail
                    if ($playerDetail.LeagueId -cne $LeagueId) {
                        throw 'BF-879 BLOCKED: player detail response does not match the exact current league.'
                    }
                    if ($playerDetail.PlayerId -cne $playerId) {
                        throw 'BF-879 BLOCKED: player detail response does not match the exact requested player.'
                    }
                    $html = ConvertTo-PlayerDetailHtml -View $playerDetail
                    Send-HttpResponse -Stream $stream -StatusCode 200 -StatusText "OK" -ContentType "text/html; charset=utf-8" -Body $html
                }
                catch {
                    $errorHtml = "<!doctype html><html><body><h1>Butler Player Detail blocked</h1><pre>$(ConvertTo-HtmlText $_.Exception.Message)</pre><p>No provider refresh, Butler write, or Sleeper write was executed.</p></body></html>"
                    Send-HttpResponse -Stream $stream -StatusCode 500 -StatusText "Internal Server Error" -ContentType "text/html; charset=utf-8" -Body $errorHtml
                }
                continue
            }

'@

$core = $core.Insert($routeIndex, $playerRoute)

foreach ($required in @(
    'function Get-PlayerDetailRequestId',
    'function ConvertTo-MyTeamPlayerNameHtml',
    'EXACT_CANONICAL',
    '/player?id=',
    'function ConvertTo-PlayerDetailView',
    'function ConvertTo-PlayerDetailHtml',
    'Player Detail',
    '/__butler/internal/player-detail-summary',
    '/__butler/internal/player-detail',
    'support=1',
    'Load supporting evidence',
    'Invoke-Bf742DashboardWorkerRead -Path $playerDetailPath -BoundaryName "BF-916"',
    'No persisted production snapshot is available',
    '0 games played',
    'Unavailable rates stay unavailable',
    'Supporting evidence',
    'Evidence sources',
    'READ ONLY.'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-879 BLOCKED: required Player Detail marker is missing: $required"
    }
}

$installedStart = $core.IndexOf('function Get-PlayerDetailRequestId', [System.StringComparison]::Ordinal)
$installedEnd = $core.IndexOf('function ConvertTo-LeagueHtml {', $installedStart, [System.StringComparison]::Ordinal)
$installedPlayer = $core.Substring($installedStart, $installedEnd - $installedStart)
foreach ($forbidden in @(
    'Invoke-RestMethod',
    'Invoke-WebRequest',
    'Method = "POST"',
    'https://api.sleeper.app',
    'submitTransaction',
    'setFaab',
    'player-score'
)) {
    if ($installedPlayer.IndexOf($forbidden, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
        throw "BF-879 BLOCKED: Player Detail introduced forbidden provider, write, or score behavior: $forbidden"
    }
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($CorePath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $parseSummary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-879 BLOCKED: generated staged core failed PowerShell parse: $parseSummary"
}
)
    $supportingState = [regex]::Match($Text, '(?m)^Supporting evidence:\s+(?<value>READY|DEFERRED)\s*
    $modelAgeAsOf = [regex]::Match($Text, '(?m)^Supporting model age as-of:\s+(?<value>\d{4}-\d{2}-\d{2})\s*$')
    $supportPolicy = [regex]::Match($Text, '(?m)^Supporting policy:\s+(?<value>\S+)\s*$')
    $outlookPolicy = [regex]::Match($Text, '(?m)^Outlook policy:\s+(?<value>\S+)\s*$')
    $supportSources = [regex]::Match($Text, '(?m)^Supporting sources:\s+(?<value>.+?)\s*$')

    foreach ($required in @(
        $league,$season,$playerId,$playerName,$position,$team,$slot,$age,$ageProvenance,$ageAsOf,
        $profileSource,$productionSource,$snapshot,$games,$passYards,$passTd,$interceptions,
        $rushYards,$rushTd,$receptions,$receivingYards,$receivingTd,$fumbles,$supportingCount,
        $modelAgeAsOf,$supportPolicy,$outlookPolicy,$supportSources
    )) {
        if (-not $required.Success) {
            throw 'BF-879 BLOCKED: player detail output is missing a required evidence field.'
        }
    }

    $flags = @()
    $flagPattern = '^\s{2}(?<signal>FAVORABLE|UNFAVORABLE|INCONCLUSIVE)\s+\|\s+(?<category>[^|]+?)\s+\|\s+(?<dimension>[^|]+?)\s+\|\s+(?<summary>.+?)\s*$'
    foreach ($line in @($Text -split '\r?\n')) {
        $flag = [regex]::Match($line, $flagPattern)
        if (-not $flag.Success) { continue }
        $flags += [pscustomobject]@{
            Signal = $flag.Groups['signal'].Value.Trim()
            Category = $flag.Groups['category'].Value.Trim()
            Dimension = $flag.Groups['dimension'].Value.Trim()
            Summary = $flag.Groups['summary'].Value.Trim()
        }
    }
    if ($flags.Count -ne [int]$supportingCount.Groups['value'].Value) {
        throw 'BF-879 BLOCKED: player detail supporting-flag count does not match the rendered evidence.'
    }

    return [pscustomobject]@{
        LeagueId = $league.Groups['value'].Value.Trim()
        Season = [int]$season.Groups['value'].Value
        PlayerId = $playerId.Groups['value'].Value.Trim()
        PlayerName = $playerName.Groups['value'].Value.Trim()
        Position = $position.Groups['value'].Value.Trim()
        TeamId = $team.Groups['id'].Value.Trim()
        TeamName = $team.Groups['name'].Value.Trim()
        RosterSlot = $slot.Groups['value'].Value.Trim()
        Age = $age.Groups['value'].Value.Trim()
        AgeProvenance = $ageProvenance.Groups['value'].Value.Trim()
        AgeAsOf = $ageAsOf.Groups['value'].Value.Trim()
        ProfileSource = $profileSource.Groups['value'].Value.Trim()
        ProductionSource = $productionSource.Groups['value'].Value.Trim()
        ProductionSnapshot = $snapshot.Groups['value'].Value.Trim()
        GamesPlayed = $games.Groups['value'].Value.Trim()
        PassingYardsPerGame = $passYards.Groups['value'].Value.Trim()
        PassingTouchdownsPerGame = $passTd.Groups['value'].Value.Trim()
        InterceptionsPerGame = $interceptions.Groups['value'].Value.Trim()
        RushingYardsPerGame = $rushYards.Groups['value'].Value.Trim()
        RushingTouchdownsPerGame = $rushTd.Groups['value'].Value.Trim()
        ReceptionsPerGame = $receptions.Groups['value'].Value.Trim()
        ReceivingYardsPerGame = $receivingYards.Groups['value'].Value.Trim()
        ReceivingTouchdownsPerGame = $receivingTd.Groups['value'].Value.Trim()
        FumblesLostPerGame = $fumbles.Groups['value'].Value.Trim()
        ModelAgeAsOf = $modelAgeAsOf.Groups['value'].Value.Trim()
        SupportPolicy = $supportPolicy.Groups['value'].Value.Trim()
        OutlookPolicy = $outlookPolicy.Groups['value'].Value.Trim()
        SupportSources = $supportSources.Groups['value'].Value.Trim()
        SupportingFlags = @($flags)
    }
}

function ConvertTo-PlayerDetailHtml {
    param([Parameter(Mandatory = $true)]$View)

    $css = Get-AppCss
    $nav = Get-AppNav -Active 'team'

    $ageText = if ($View.Age -ceq 'UNAVAILABLE') { 'Age unavailable' } else { "$($View.Age) years old" }
    $ageEvidence = switch ($View.AgeProvenance) {
        'EXACT_BIRTH_DATE' { 'Derived from exact birth-date evidence.' }
        'PROVIDER_REPORTED' { 'Provider-reported age; Butler does not extrapolate it.' }
        default { 'No age evidence is available; Butler does not infer one.' }
    }

    if ($View.ProductionSnapshot -ceq 'MISSING') {
        $productionHtml = '<div class="empty">No persisted production snapshot is available for this player and season. Butler does not substitute zeroes.</div>'
    }
    elseif ($View.GamesPlayed -ceq '0') {
        $productionHtml = '<div class="empty">A production snapshot exists with 0 games played. Per-game rates remain unavailable rather than being converted to zero.</div>'
    }
    else {
        $rateCards = ''
        foreach ($rate in @(
            [pscustomobject]@{ Label = 'Pass yards / game'; Value = $View.PassingYardsPerGame },
            [pscustomobject]@{ Label = 'Pass TD / game'; Value = $View.PassingTouchdownsPerGame },
            [pscustomobject]@{ Label = 'INT / game'; Value = $View.InterceptionsPerGame },
            [pscustomobject]@{ Label = 'Rush yards / game'; Value = $View.RushingYardsPerGame },
            [pscustomobject]@{ Label = 'Rush TD / game'; Value = $View.RushingTouchdownsPerGame },
            [pscustomobject]@{ Label = 'Receptions / game'; Value = $View.ReceptionsPerGame },
            [pscustomobject]@{ Label = 'Rec yards / game'; Value = $View.ReceivingYardsPerGame },
            [pscustomobject]@{ Label = 'Rec TD / game'; Value = $View.ReceivingTouchdownsPerGame },
            [pscustomobject]@{ Label = 'Fumbles lost / game'; Value = $View.FumblesLostPerGame }
        )) {
            if ([string]$rate.Value -ceq 'UNAVAILABLE') { continue }
            $rateCards += @"
<div class="metric-card"><span class="metric-label">$(ConvertTo-HtmlText $rate.Label)</span><span class="metric-value">$(ConvertTo-HtmlText $rate.Value)</span></div>
"@
        }
        if ([string]::IsNullOrWhiteSpace($rateCards)) {
            $rateCards = '<div class="empty">Per-game rates are unavailable for this production snapshot.</div>'
        }
        $productionHtml = @"
<div class="stats"><div class="stat"><strong>Season</strong><span>$(ConvertTo-HtmlText $View.Season)</span></div><div class="stat"><strong>Games</strong><span>$(ConvertTo-HtmlText $View.GamesPlayed)</span></div></div><div class="manager-metrics">$rateCards</div><p class="meta">Unavailable rates stay unavailable; Butler does not manufacture a zero for missing evidence.</p>
"@
    }

    $flagsHtml = ''
    foreach ($flag in @($View.SupportingFlags)) {
        $signalClass = switch ($flag.Signal) {
            'FAVORABLE' { 'good' }
            'UNFAVORABLE' { 'warn' }
            default { 'done' }
        }
        $flagsHtml += @"
<div class="card"><div class="action-head"><span class="status $signalClass">$(ConvertTo-HtmlText $flag.Signal)</span><span class="kind">$(ConvertTo-HtmlText $flag.Dimension)</span></div><p>$(ConvertTo-HtmlText $flag.Summary)</p></div>
"@
    }
    if ([string]::IsNullOrWhiteSpace($flagsHtml)) {
        $flagsHtml = '<div class="empty">No governed age-outlook supporting flags are available for this player. Butler does not infer one.</div>'
    }

    return @"
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="dark"><title>Butler - $(ConvertTo-HtmlText $View.PlayerName)</title><style>$css</style></head><body><main class="shell">
<header class="top"><div class="brand"><h1>BUTLER</h1><p>We're here to serve you. Less Research. Better Decisions.</p></div><div class="target">$(ConvertTo-HtmlText $View.TeamName) &middot; $(ConvertTo-HtmlText $View.Season)</div></header>
$nav
<section class="panel hero-panel"><div class="manager-head"><div><div class="eyebrow">Player Detail</div><h1 class="headline">$(ConvertTo-HtmlText $View.PlayerName)</h1><p class="lede">$(ConvertTo-HtmlText $View.Position) &middot; $(ConvertTo-HtmlText $View.TeamName) &middot; $(ConvertTo-HtmlText $View.RosterSlot)</p></div><span class="status done">EVIDENCE</span></div><div class="button-row"><a class="btn btn-secondary" href="/team">Back to My Team</a></div></section>
<section class="panel"><div class="section-head"><div><div class="eyebrow">Age context</div><h2>$ageText</h2><p class="lede">$ageEvidence</p></div></div><div class="stats"><div class="stat"><strong>Age as of</strong><span>$(ConvertTo-HtmlText $View.AgeAsOf)</span></div><div class="stat"><strong>Provenance</strong><span>$(ConvertTo-HtmlText $View.AgeProvenance)</span></div></div></section>
<section class="panel"><div class="section-head"><div><div class="eyebrow">Season production</div><h2>Per-game evidence</h2><p class="lede">Persisted raw production context only. This is not a fantasy score or player grade.</p></div></div>$productionHtml</section>
<section class="panel"><div class="section-head"><div><div class="eyebrow">Age outlook context</div><h2>Supporting evidence</h2><p class="lede">Optional governed flags are shown as context only. They are not weighted into a player score or recommendation.</p></div></div><div class="actions">$flagsHtml</div><details><summary>Evidence sources</summary><div class="technical">Profile source $(ConvertTo-HtmlText $View.ProfileSource) &middot; production source $(ConvertTo-HtmlText $View.ProductionSource) &middot; supporting model age as-of $(ConvertTo-HtmlText $View.ModelAgeAsOf) &middot; support policy $(ConvertTo-HtmlText $View.SupportPolicy) &middot; outlook policy $(ConvertTo-HtmlText $View.OutlookPolicy) &middot; supporting sources $(ConvertTo-HtmlText $View.SupportSources) &middot; Butler player $(ConvertTo-HtmlText $View.PlayerId) &middot; Butler team $(ConvertTo-HtmlText $View.TeamId)</div></details></section>
<section class="panel boundary"><span class="lock">READ ONLY.</span> Player Detail shows existing neutral evidence only. It does not create a universal player score, grade, rank, buy/sell label, career-arc classification, dynasty adjustment, start/sit recommendation, trade recommendation, waiver recommendation, refresh, or Sleeper write.</section>
</main></body></html>
"@
}
'@

$leagueMarker = 'function ConvertTo-LeagueHtml {'
$leagueIndex = $core.IndexOf($leagueMarker, [System.StringComparison]::Ordinal)
if ($leagueIndex -lt 0) {
    throw 'BF-879 BLOCKED: Player Detail renderer insertion marker is missing.'
}
$core = $core.Insert($leagueIndex, $playerFunctions.TrimEnd() + [Environment]::NewLine + [Environment]::NewLine)

$nameMarkupOld = '<strong>$(ConvertTo-HtmlText $player.Name)</strong>'
$nameMarkupNew = '<strong>$(ConvertTo-MyTeamPlayerNameHtml -Player $player)</strong>'
$nameMatches = [regex]::Matches($core, [regex]::Escape($nameMarkupOld)).Count
if ($nameMatches -ne 3) {
    throw "BF-879 BLOCKED: expected exactly three My Team player-name render sites, found $nameMatches."
}
$core = $core.Replace($nameMarkupOld, $nameMarkupNew)

$routeMarker = '            if ($path -eq "/league") {'
$routeIndex = $core.IndexOf($routeMarker, [System.StringComparison]::Ordinal)
if ($routeIndex -lt 0) {
    throw 'BF-879 BLOCKED: Player Detail route insertion marker is missing.'
}

$playerRoute = @'
            if ($path -eq "/player") {
                try {
                    $playerId = Get-PlayerDetailRequestId -RequestTarget $parts[1]
                    $playerDetailPath = "/__butler/internal/player-detail?player=" + [System.Uri]::EscapeDataString($playerId)
                    $rawPlayerDetail = Invoke-Bf742DashboardWorkerRead -Path $playerDetailPath -BoundaryName "BF-916"
                    $playerDetail = ConvertTo-PlayerDetailView -Text $rawPlayerDetail
                    if ($playerDetail.LeagueId -cne $LeagueId) {
                        throw 'BF-879 BLOCKED: player detail response does not match the exact current league.'
                    }
                    if ($playerDetail.PlayerId -cne $playerId) {
                        throw 'BF-879 BLOCKED: player detail response does not match the exact requested player.'
                    }
                    $html = ConvertTo-PlayerDetailHtml -View $playerDetail
                    Send-HttpResponse -Stream $stream -StatusCode 200 -StatusText "OK" -ContentType "text/html; charset=utf-8" -Body $html
                }
                catch {
                    $errorHtml = "<!doctype html><html><body><h1>Butler Player Detail blocked</h1><pre>$(ConvertTo-HtmlText $_.Exception.Message)</pre><p>No provider refresh, Butler write, or Sleeper write was executed.</p></body></html>"
                    Send-HttpResponse -Stream $stream -StatusCode 500 -StatusText "Internal Server Error" -ContentType "text/html; charset=utf-8" -Body $errorHtml
                }
                continue
            }

'@

$core = $core.Insert($routeIndex, $playerRoute)

foreach ($required in @(
    'function Get-PlayerDetailRequestId',
    'function ConvertTo-MyTeamPlayerNameHtml',
    'EXACT_CANONICAL',
    '/player?id=',
    'function ConvertTo-PlayerDetailView',
    'function ConvertTo-PlayerDetailHtml',
    'Player Detail',
    '/__butler/internal/player-detail?player=',
    'Invoke-Bf742DashboardWorkerRead -Path $playerDetailPath -BoundaryName "BF-916"',
    'No persisted production snapshot is available',
    '0 games played',
    'Unavailable rates stay unavailable',
    'Supporting evidence',
    'Evidence sources',
    'READ ONLY.'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-879 BLOCKED: required Player Detail marker is missing: $required"
    }
}

$installedStart = $core.IndexOf('function Get-PlayerDetailRequestId', [System.StringComparison]::Ordinal)
$installedEnd = $core.IndexOf('function ConvertTo-LeagueHtml {', $installedStart, [System.StringComparison]::Ordinal)
$installedPlayer = $core.Substring($installedStart, $installedEnd - $installedStart)
foreach ($forbidden in @(
    'Invoke-RestMethod',
    'Invoke-WebRequest',
    'Method = "POST"',
    'https://api.sleeper.app',
    'submitTransaction',
    'setFaab',
    'player-score'
)) {
    if ($installedPlayer.IndexOf($forbidden, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
        throw "BF-879 BLOCKED: Player Detail introduced forbidden provider, write, or score behavior: $forbidden"
    }
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($CorePath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $parseSummary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-879 BLOCKED: generated staged core failed PowerShell parse: $parseSummary"
}
)
    $supportingCount = [regex]::Match($Text, '(?m)^Supporting flags:\s+(?<value>\d+)\s*
    $modelAgeAsOf = [regex]::Match($Text, '(?m)^Supporting model age as-of:\s+(?<value>\d{4}-\d{2}-\d{2})\s*$')
    $supportPolicy = [regex]::Match($Text, '(?m)^Supporting policy:\s+(?<value>\S+)\s*$')
    $outlookPolicy = [regex]::Match($Text, '(?m)^Outlook policy:\s+(?<value>\S+)\s*$')
    $supportSources = [regex]::Match($Text, '(?m)^Supporting sources:\s+(?<value>.+?)\s*$')

    foreach ($required in @(
        $league,$season,$playerId,$playerName,$position,$team,$slot,$age,$ageProvenance,$ageAsOf,
        $profileSource,$productionSource,$snapshot,$games,$passYards,$passTd,$interceptions,
        $rushYards,$rushTd,$receptions,$receivingYards,$receivingTd,$fumbles,$supportingCount,
        $modelAgeAsOf,$supportPolicy,$outlookPolicy,$supportSources
    )) {
        if (-not $required.Success) {
            throw 'BF-879 BLOCKED: player detail output is missing a required evidence field.'
        }
    }

    $flags = @()
    $flagPattern = '^\s{2}(?<signal>FAVORABLE|UNFAVORABLE|INCONCLUSIVE)\s+\|\s+(?<category>[^|]+?)\s+\|\s+(?<dimension>[^|]+?)\s+\|\s+(?<summary>.+?)\s*$'
    foreach ($line in @($Text -split '\r?\n')) {
        $flag = [regex]::Match($line, $flagPattern)
        if (-not $flag.Success) { continue }
        $flags += [pscustomobject]@{
            Signal = $flag.Groups['signal'].Value.Trim()
            Category = $flag.Groups['category'].Value.Trim()
            Dimension = $flag.Groups['dimension'].Value.Trim()
            Summary = $flag.Groups['summary'].Value.Trim()
        }
    }
    if ($flags.Count -ne [int]$supportingCount.Groups['value'].Value) {
        throw 'BF-879 BLOCKED: player detail supporting-flag count does not match the rendered evidence.'
    }

    return [pscustomobject]@{
        LeagueId = $league.Groups['value'].Value.Trim()
        Season = [int]$season.Groups['value'].Value
        PlayerId = $playerId.Groups['value'].Value.Trim()
        PlayerName = $playerName.Groups['value'].Value.Trim()
        Position = $position.Groups['value'].Value.Trim()
        TeamId = $team.Groups['id'].Value.Trim()
        TeamName = $team.Groups['name'].Value.Trim()
        RosterSlot = $slot.Groups['value'].Value.Trim()
        Age = $age.Groups['value'].Value.Trim()
        AgeProvenance = $ageProvenance.Groups['value'].Value.Trim()
        AgeAsOf = $ageAsOf.Groups['value'].Value.Trim()
        ProfileSource = $profileSource.Groups['value'].Value.Trim()
        ProductionSource = $productionSource.Groups['value'].Value.Trim()
        ProductionSnapshot = $snapshot.Groups['value'].Value.Trim()
        GamesPlayed = $games.Groups['value'].Value.Trim()
        PassingYardsPerGame = $passYards.Groups['value'].Value.Trim()
        PassingTouchdownsPerGame = $passTd.Groups['value'].Value.Trim()
        InterceptionsPerGame = $interceptions.Groups['value'].Value.Trim()
        RushingYardsPerGame = $rushYards.Groups['value'].Value.Trim()
        RushingTouchdownsPerGame = $rushTd.Groups['value'].Value.Trim()
        ReceptionsPerGame = $receptions.Groups['value'].Value.Trim()
        ReceivingYardsPerGame = $receivingYards.Groups['value'].Value.Trim()
        ReceivingTouchdownsPerGame = $receivingTd.Groups['value'].Value.Trim()
        FumblesLostPerGame = $fumbles.Groups['value'].Value.Trim()
        ModelAgeAsOf = $modelAgeAsOf.Groups['value'].Value.Trim()
        SupportPolicy = $supportPolicy.Groups['value'].Value.Trim()
        OutlookPolicy = $outlookPolicy.Groups['value'].Value.Trim()
        SupportSources = $supportSources.Groups['value'].Value.Trim()
        SupportingFlags = @($flags)
    }
}

function ConvertTo-PlayerDetailHtml {
    param([Parameter(Mandatory = $true)]$View)

    $css = Get-AppCss
    $nav = Get-AppNav -Active 'team'

    $ageText = if ($View.Age -ceq 'UNAVAILABLE') { 'Age unavailable' } else { "$($View.Age) years old" }
    $ageEvidence = switch ($View.AgeProvenance) {
        'EXACT_BIRTH_DATE' { 'Derived from exact birth-date evidence.' }
        'PROVIDER_REPORTED' { 'Provider-reported age; Butler does not extrapolate it.' }
        default { 'No age evidence is available; Butler does not infer one.' }
    }

    if ($View.ProductionSnapshot -ceq 'MISSING') {
        $productionHtml = '<div class="empty">No persisted production snapshot is available for this player and season. Butler does not substitute zeroes.</div>'
    }
    elseif ($View.GamesPlayed -ceq '0') {
        $productionHtml = '<div class="empty">A production snapshot exists with 0 games played. Per-game rates remain unavailable rather than being converted to zero.</div>'
    }
    else {
        $rateCards = ''
        foreach ($rate in @(
            [pscustomobject]@{ Label = 'Pass yards / game'; Value = $View.PassingYardsPerGame },
            [pscustomobject]@{ Label = 'Pass TD / game'; Value = $View.PassingTouchdownsPerGame },
            [pscustomobject]@{ Label = 'INT / game'; Value = $View.InterceptionsPerGame },
            [pscustomobject]@{ Label = 'Rush yards / game'; Value = $View.RushingYardsPerGame },
            [pscustomobject]@{ Label = 'Rush TD / game'; Value = $View.RushingTouchdownsPerGame },
            [pscustomobject]@{ Label = 'Receptions / game'; Value = $View.ReceptionsPerGame },
            [pscustomobject]@{ Label = 'Rec yards / game'; Value = $View.ReceivingYardsPerGame },
            [pscustomobject]@{ Label = 'Rec TD / game'; Value = $View.ReceivingTouchdownsPerGame },
            [pscustomobject]@{ Label = 'Fumbles lost / game'; Value = $View.FumblesLostPerGame }
        )) {
            if ([string]$rate.Value -ceq 'UNAVAILABLE') { continue }
            $rateCards += @"
<div class="metric-card"><span class="metric-label">$(ConvertTo-HtmlText $rate.Label)</span><span class="metric-value">$(ConvertTo-HtmlText $rate.Value)</span></div>
"@
        }
        if ([string]::IsNullOrWhiteSpace($rateCards)) {
            $rateCards = '<div class="empty">Per-game rates are unavailable for this production snapshot.</div>'
        }
        $productionHtml = @"
<div class="stats"><div class="stat"><strong>Season</strong><span>$(ConvertTo-HtmlText $View.Season)</span></div><div class="stat"><strong>Games</strong><span>$(ConvertTo-HtmlText $View.GamesPlayed)</span></div></div><div class="manager-metrics">$rateCards</div><p class="meta">Unavailable rates stay unavailable; Butler does not manufacture a zero for missing evidence.</p>
"@
    }

    $flagsHtml = ''
    foreach ($flag in @($View.SupportingFlags)) {
        $signalClass = switch ($flag.Signal) {
            'FAVORABLE' { 'good' }
            'UNFAVORABLE' { 'warn' }
            default { 'done' }
        }
        $flagsHtml += @"
<div class="card"><div class="action-head"><span class="status $signalClass">$(ConvertTo-HtmlText $flag.Signal)</span><span class="kind">$(ConvertTo-HtmlText $flag.Dimension)</span></div><p>$(ConvertTo-HtmlText $flag.Summary)</p></div>
"@
    }
    if ([string]::IsNullOrWhiteSpace($flagsHtml)) {
        $flagsHtml = '<div class="empty">No governed age-outlook supporting flags are available for this player. Butler does not infer one.</div>'
    }

    return @"
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="dark"><title>Butler - $(ConvertTo-HtmlText $View.PlayerName)</title><style>$css</style></head><body><main class="shell">
<header class="top"><div class="brand"><h1>BUTLER</h1><p>We're here to serve you. Less Research. Better Decisions.</p></div><div class="target">$(ConvertTo-HtmlText $View.TeamName) &middot; $(ConvertTo-HtmlText $View.Season)</div></header>
$nav
<section class="panel hero-panel"><div class="manager-head"><div><div class="eyebrow">Player Detail</div><h1 class="headline">$(ConvertTo-HtmlText $View.PlayerName)</h1><p class="lede">$(ConvertTo-HtmlText $View.Position) &middot; $(ConvertTo-HtmlText $View.TeamName) &middot; $(ConvertTo-HtmlText $View.RosterSlot)</p></div><span class="status done">EVIDENCE</span></div><div class="button-row"><a class="btn btn-secondary" href="/team">Back to My Team</a></div></section>
<section class="panel"><div class="section-head"><div><div class="eyebrow">Age context</div><h2>$ageText</h2><p class="lede">$ageEvidence</p></div></div><div class="stats"><div class="stat"><strong>Age as of</strong><span>$(ConvertTo-HtmlText $View.AgeAsOf)</span></div><div class="stat"><strong>Provenance</strong><span>$(ConvertTo-HtmlText $View.AgeProvenance)</span></div></div></section>
<section class="panel"><div class="section-head"><div><div class="eyebrow">Season production</div><h2>Per-game evidence</h2><p class="lede">Persisted raw production context only. This is not a fantasy score or player grade.</p></div></div>$productionHtml</section>
<section class="panel"><div class="section-head"><div><div class="eyebrow">Age outlook context</div><h2>Supporting evidence</h2><p class="lede">Optional governed flags are shown as context only. They are not weighted into a player score or recommendation.</p></div></div><div class="actions">$flagsHtml</div><details><summary>Evidence sources</summary><div class="technical">Profile source $(ConvertTo-HtmlText $View.ProfileSource) &middot; production source $(ConvertTo-HtmlText $View.ProductionSource) &middot; supporting model age as-of $(ConvertTo-HtmlText $View.ModelAgeAsOf) &middot; support policy $(ConvertTo-HtmlText $View.SupportPolicy) &middot; outlook policy $(ConvertTo-HtmlText $View.OutlookPolicy) &middot; supporting sources $(ConvertTo-HtmlText $View.SupportSources) &middot; Butler player $(ConvertTo-HtmlText $View.PlayerId) &middot; Butler team $(ConvertTo-HtmlText $View.TeamId)</div></details></section>
<section class="panel boundary"><span class="lock">READ ONLY.</span> Player Detail shows existing neutral evidence only. It does not create a universal player score, grade, rank, buy/sell label, career-arc classification, dynasty adjustment, start/sit recommendation, trade recommendation, waiver recommendation, refresh, or Sleeper write.</section>
</main></body></html>
"@
}
'@

$leagueMarker = 'function ConvertTo-LeagueHtml {'
$leagueIndex = $core.IndexOf($leagueMarker, [System.StringComparison]::Ordinal)
if ($leagueIndex -lt 0) {
    throw 'BF-879 BLOCKED: Player Detail renderer insertion marker is missing.'
}
$core = $core.Insert($leagueIndex, $playerFunctions.TrimEnd() + [Environment]::NewLine + [Environment]::NewLine)

$nameMarkupOld = '<strong>$(ConvertTo-HtmlText $player.Name)</strong>'
$nameMarkupNew = '<strong>$(ConvertTo-MyTeamPlayerNameHtml -Player $player)</strong>'
$nameMatches = [regex]::Matches($core, [regex]::Escape($nameMarkupOld)).Count
if ($nameMatches -ne 3) {
    throw "BF-879 BLOCKED: expected exactly three My Team player-name render sites, found $nameMatches."
}
$core = $core.Replace($nameMarkupOld, $nameMarkupNew)

$routeMarker = '            if ($path -eq "/league") {'
$routeIndex = $core.IndexOf($routeMarker, [System.StringComparison]::Ordinal)
if ($routeIndex -lt 0) {
    throw 'BF-879 BLOCKED: Player Detail route insertion marker is missing.'
}

$playerRoute = @'
            if ($path -eq "/player") {
                try {
                    $playerId = Get-PlayerDetailRequestId -RequestTarget $parts[1]
                    $playerDetailPath = "/__butler/internal/player-detail?player=" + [System.Uri]::EscapeDataString($playerId)
                    $rawPlayerDetail = Invoke-Bf742DashboardWorkerRead -Path $playerDetailPath -BoundaryName "BF-916"
                    $playerDetail = ConvertTo-PlayerDetailView -Text $rawPlayerDetail
                    if ($playerDetail.LeagueId -cne $LeagueId) {
                        throw 'BF-879 BLOCKED: player detail response does not match the exact current league.'
                    }
                    if ($playerDetail.PlayerId -cne $playerId) {
                        throw 'BF-879 BLOCKED: player detail response does not match the exact requested player.'
                    }
                    $html = ConvertTo-PlayerDetailHtml -View $playerDetail
                    Send-HttpResponse -Stream $stream -StatusCode 200 -StatusText "OK" -ContentType "text/html; charset=utf-8" -Body $html
                }
                catch {
                    $errorHtml = "<!doctype html><html><body><h1>Butler Player Detail blocked</h1><pre>$(ConvertTo-HtmlText $_.Exception.Message)</pre><p>No provider refresh, Butler write, or Sleeper write was executed.</p></body></html>"
                    Send-HttpResponse -Stream $stream -StatusCode 500 -StatusText "Internal Server Error" -ContentType "text/html; charset=utf-8" -Body $errorHtml
                }
                continue
            }

'@

$core = $core.Insert($routeIndex, $playerRoute)

foreach ($required in @(
    'function Get-PlayerDetailRequestId',
    'function ConvertTo-MyTeamPlayerNameHtml',
    'EXACT_CANONICAL',
    '/player?id=',
    'function ConvertTo-PlayerDetailView',
    'function ConvertTo-PlayerDetailHtml',
    'Player Detail',
    '/__butler/internal/player-detail?player=',
    'Invoke-Bf742DashboardWorkerRead -Path $playerDetailPath -BoundaryName "BF-916"',
    'No persisted production snapshot is available',
    '0 games played',
    'Unavailable rates stay unavailable',
    'Supporting evidence',
    'Evidence sources',
    'READ ONLY.'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-879 BLOCKED: required Player Detail marker is missing: $required"
    }
}

$installedStart = $core.IndexOf('function Get-PlayerDetailRequestId', [System.StringComparison]::Ordinal)
$installedEnd = $core.IndexOf('function ConvertTo-LeagueHtml {', $installedStart, [System.StringComparison]::Ordinal)
$installedPlayer = $core.Substring($installedStart, $installedEnd - $installedStart)
foreach ($forbidden in @(
    'Invoke-RestMethod',
    'Invoke-WebRequest',
    'Method = "POST"',
    'https://api.sleeper.app',
    'submitTransaction',
    'setFaab',
    'player-score'
)) {
    if ($installedPlayer.IndexOf($forbidden, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
        throw "BF-879 BLOCKED: Player Detail introduced forbidden provider, write, or score behavior: $forbidden"
    }
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($CorePath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $parseSummary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-879 BLOCKED: generated staged core failed PowerShell parse: $parseSummary"
}
)
    $modelAgeAsOf = [regex]::Match($Text, '(?m)^Supporting model age as-of:\s+(?<value>\d{4}-\d{2}-\d{2})\s*$')
    $supportPolicy = [regex]::Match($Text, '(?m)^Supporting policy:\s+(?<value>\S+)\s*$')
    $outlookPolicy = [regex]::Match($Text, '(?m)^Outlook policy:\s+(?<value>\S+)\s*$')
    $supportSources = [regex]::Match($Text, '(?m)^Supporting sources:\s+(?<value>.+?)\s*$')

    foreach ($required in @(
        $league,$season,$playerId,$playerName,$position,$team,$slot,$age,$ageProvenance,$ageAsOf,
        $profileSource,$productionSource,$snapshot,$games,$passYards,$passTd,$interceptions,
        $rushYards,$rushTd,$receptions,$receivingYards,$receivingTd,$fumbles,$supportingCount,
        $modelAgeAsOf,$supportPolicy,$outlookPolicy,$supportSources
    )) {
        if (-not $required.Success) {
            throw 'BF-879 BLOCKED: player detail output is missing a required evidence field.'
        }
    }

    $flags = @()
    $flagPattern = '^\s{2}(?<signal>FAVORABLE|UNFAVORABLE|INCONCLUSIVE)\s+\|\s+(?<category>[^|]+?)\s+\|\s+(?<dimension>[^|]+?)\s+\|\s+(?<summary>.+?)\s*$'
    foreach ($line in @($Text -split '\r?\n')) {
        $flag = [regex]::Match($line, $flagPattern)
        if (-not $flag.Success) { continue }
        $flags += [pscustomobject]@{
            Signal = $flag.Groups['signal'].Value.Trim()
            Category = $flag.Groups['category'].Value.Trim()
            Dimension = $flag.Groups['dimension'].Value.Trim()
            Summary = $flag.Groups['summary'].Value.Trim()
        }
    }
    if ($flags.Count -ne [int]$supportingCount.Groups['value'].Value) {
        throw 'BF-879 BLOCKED: player detail supporting-flag count does not match the rendered evidence.'
    }

    return [pscustomobject]@{
        LeagueId = $league.Groups['value'].Value.Trim()
        Season = [int]$season.Groups['value'].Value
        PlayerId = $playerId.Groups['value'].Value.Trim()
        PlayerName = $playerName.Groups['value'].Value.Trim()
        Position = $position.Groups['value'].Value.Trim()
        TeamId = $team.Groups['id'].Value.Trim()
        TeamName = $team.Groups['name'].Value.Trim()
        RosterSlot = $slot.Groups['value'].Value.Trim()
        Age = $age.Groups['value'].Value.Trim()
        AgeProvenance = $ageProvenance.Groups['value'].Value.Trim()
        AgeAsOf = $ageAsOf.Groups['value'].Value.Trim()
        ProfileSource = $profileSource.Groups['value'].Value.Trim()
        ProductionSource = $productionSource.Groups['value'].Value.Trim()
        ProductionSnapshot = $snapshot.Groups['value'].Value.Trim()
        GamesPlayed = $games.Groups['value'].Value.Trim()
        PassingYardsPerGame = $passYards.Groups['value'].Value.Trim()
        PassingTouchdownsPerGame = $passTd.Groups['value'].Value.Trim()
        InterceptionsPerGame = $interceptions.Groups['value'].Value.Trim()
        RushingYardsPerGame = $rushYards.Groups['value'].Value.Trim()
        RushingTouchdownsPerGame = $rushTd.Groups['value'].Value.Trim()
        ReceptionsPerGame = $receptions.Groups['value'].Value.Trim()
        ReceivingYardsPerGame = $receivingYards.Groups['value'].Value.Trim()
        ReceivingTouchdownsPerGame = $receivingTd.Groups['value'].Value.Trim()
        FumblesLostPerGame = $fumbles.Groups['value'].Value.Trim()
        ModelAgeAsOf = $modelAgeAsOf.Groups['value'].Value.Trim()
        SupportPolicy = $supportPolicy.Groups['value'].Value.Trim()
        OutlookPolicy = $outlookPolicy.Groups['value'].Value.Trim()
        SupportSources = $supportSources.Groups['value'].Value.Trim()
        SupportingFlags = @($flags)
    }
}

function ConvertTo-PlayerDetailHtml {
    param([Parameter(Mandatory = $true)]$View)

    $css = Get-AppCss
    $nav = Get-AppNav -Active 'team'

    $ageText = if ($View.Age -ceq 'UNAVAILABLE') { 'Age unavailable' } else { "$($View.Age) years old" }
    $ageEvidence = switch ($View.AgeProvenance) {
        'EXACT_BIRTH_DATE' { 'Derived from exact birth-date evidence.' }
        'PROVIDER_REPORTED' { 'Provider-reported age; Butler does not extrapolate it.' }
        default { 'No age evidence is available; Butler does not infer one.' }
    }

    if ($View.ProductionSnapshot -ceq 'MISSING') {
        $productionHtml = '<div class="empty">No persisted production snapshot is available for this player and season. Butler does not substitute zeroes.</div>'
    }
    elseif ($View.GamesPlayed -ceq '0') {
        $productionHtml = '<div class="empty">A production snapshot exists with 0 games played. Per-game rates remain unavailable rather than being converted to zero.</div>'
    }
    else {
        $rateCards = ''
        foreach ($rate in @(
            [pscustomobject]@{ Label = 'Pass yards / game'; Value = $View.PassingYardsPerGame },
            [pscustomobject]@{ Label = 'Pass TD / game'; Value = $View.PassingTouchdownsPerGame },
            [pscustomobject]@{ Label = 'INT / game'; Value = $View.InterceptionsPerGame },
            [pscustomobject]@{ Label = 'Rush yards / game'; Value = $View.RushingYardsPerGame },
            [pscustomobject]@{ Label = 'Rush TD / game'; Value = $View.RushingTouchdownsPerGame },
            [pscustomobject]@{ Label = 'Receptions / game'; Value = $View.ReceptionsPerGame },
            [pscustomobject]@{ Label = 'Rec yards / game'; Value = $View.ReceivingYardsPerGame },
            [pscustomobject]@{ Label = 'Rec TD / game'; Value = $View.ReceivingTouchdownsPerGame },
            [pscustomobject]@{ Label = 'Fumbles lost / game'; Value = $View.FumblesLostPerGame }
        )) {
            if ([string]$rate.Value -ceq 'UNAVAILABLE') { continue }
            $rateCards += @"
<div class="metric-card"><span class="metric-label">$(ConvertTo-HtmlText $rate.Label)</span><span class="metric-value">$(ConvertTo-HtmlText $rate.Value)</span></div>
"@
        }
        if ([string]::IsNullOrWhiteSpace($rateCards)) {
            $rateCards = '<div class="empty">Per-game rates are unavailable for this production snapshot.</div>'
        }
        $productionHtml = @"
<div class="stats"><div class="stat"><strong>Season</strong><span>$(ConvertTo-HtmlText $View.Season)</span></div><div class="stat"><strong>Games</strong><span>$(ConvertTo-HtmlText $View.GamesPlayed)</span></div></div><div class="manager-metrics">$rateCards</div><p class="meta">Unavailable rates stay unavailable; Butler does not manufacture a zero for missing evidence.</p>
"@
    }

    $flagsHtml = ''
    foreach ($flag in @($View.SupportingFlags)) {
        $signalClass = switch ($flag.Signal) {
            'FAVORABLE' { 'good' }
            'UNFAVORABLE' { 'warn' }
            default { 'done' }
        }
        $flagsHtml += @"
<div class="card"><div class="action-head"><span class="status $signalClass">$(ConvertTo-HtmlText $flag.Signal)</span><span class="kind">$(ConvertTo-HtmlText $flag.Dimension)</span></div><p>$(ConvertTo-HtmlText $flag.Summary)</p></div>
"@
    }
    if ([string]::IsNullOrWhiteSpace($flagsHtml)) {
        $flagsHtml = '<div class="empty">No governed age-outlook supporting flags are available for this player. Butler does not infer one.</div>'
    }

    return @"
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="dark"><title>Butler - $(ConvertTo-HtmlText $View.PlayerName)</title><style>$css</style></head><body><main class="shell">
<header class="top"><div class="brand"><h1>BUTLER</h1><p>We're here to serve you. Less Research. Better Decisions.</p></div><div class="target">$(ConvertTo-HtmlText $View.TeamName) &middot; $(ConvertTo-HtmlText $View.Season)</div></header>
$nav
<section class="panel hero-panel"><div class="manager-head"><div><div class="eyebrow">Player Detail</div><h1 class="headline">$(ConvertTo-HtmlText $View.PlayerName)</h1><p class="lede">$(ConvertTo-HtmlText $View.Position) &middot; $(ConvertTo-HtmlText $View.TeamName) &middot; $(ConvertTo-HtmlText $View.RosterSlot)</p></div><span class="status done">EVIDENCE</span></div><div class="button-row"><a class="btn btn-secondary" href="/team">Back to My Team</a></div></section>
<section class="panel"><div class="section-head"><div><div class="eyebrow">Age context</div><h2>$ageText</h2><p class="lede">$ageEvidence</p></div></div><div class="stats"><div class="stat"><strong>Age as of</strong><span>$(ConvertTo-HtmlText $View.AgeAsOf)</span></div><div class="stat"><strong>Provenance</strong><span>$(ConvertTo-HtmlText $View.AgeProvenance)</span></div></div></section>
<section class="panel"><div class="section-head"><div><div class="eyebrow">Season production</div><h2>Per-game evidence</h2><p class="lede">Persisted raw production context only. This is not a fantasy score or player grade.</p></div></div>$productionHtml</section>
<section class="panel"><div class="section-head"><div><div class="eyebrow">Age outlook context</div><h2>Supporting evidence</h2><p class="lede">Optional governed flags are shown as context only. They are not weighted into a player score or recommendation.</p></div></div><div class="actions">$flagsHtml</div><details><summary>Evidence sources</summary><div class="technical">Profile source $(ConvertTo-HtmlText $View.ProfileSource) &middot; production source $(ConvertTo-HtmlText $View.ProductionSource) &middot; supporting model age as-of $(ConvertTo-HtmlText $View.ModelAgeAsOf) &middot; support policy $(ConvertTo-HtmlText $View.SupportPolicy) &middot; outlook policy $(ConvertTo-HtmlText $View.OutlookPolicy) &middot; supporting sources $(ConvertTo-HtmlText $View.SupportSources) &middot; Butler player $(ConvertTo-HtmlText $View.PlayerId) &middot; Butler team $(ConvertTo-HtmlText $View.TeamId)</div></details></section>
<section class="panel boundary"><span class="lock">READ ONLY.</span> Player Detail shows existing neutral evidence only. It does not create a universal player score, grade, rank, buy/sell label, career-arc classification, dynasty adjustment, start/sit recommendation, trade recommendation, waiver recommendation, refresh, or Sleeper write.</section>
</main></body></html>
"@
}
'@

$leagueMarker = 'function ConvertTo-LeagueHtml {'
$leagueIndex = $core.IndexOf($leagueMarker, [System.StringComparison]::Ordinal)
if ($leagueIndex -lt 0) {
    throw 'BF-879 BLOCKED: Player Detail renderer insertion marker is missing.'
}
$core = $core.Insert($leagueIndex, $playerFunctions.TrimEnd() + [Environment]::NewLine + [Environment]::NewLine)

$nameMarkupOld = '<strong>$(ConvertTo-HtmlText $player.Name)</strong>'
$nameMarkupNew = '<strong>$(ConvertTo-MyTeamPlayerNameHtml -Player $player)</strong>'
$nameMatches = [regex]::Matches($core, [regex]::Escape($nameMarkupOld)).Count
if ($nameMatches -ne 3) {
    throw "BF-879 BLOCKED: expected exactly three My Team player-name render sites, found $nameMatches."
}
$core = $core.Replace($nameMarkupOld, $nameMarkupNew)

$routeMarker = '            if ($path -eq "/league") {'
$routeIndex = $core.IndexOf($routeMarker, [System.StringComparison]::Ordinal)
if ($routeIndex -lt 0) {
    throw 'BF-879 BLOCKED: Player Detail route insertion marker is missing.'
}

$playerRoute = @'
            if ($path -eq "/player") {
                try {
                    $playerId = Get-PlayerDetailRequestId -RequestTarget $parts[1]
                    $playerDetailPath = "/__butler/internal/player-detail?player=" + [System.Uri]::EscapeDataString($playerId)
                    $rawPlayerDetail = Invoke-Bf742DashboardWorkerRead -Path $playerDetailPath -BoundaryName "BF-916"
                    $playerDetail = ConvertTo-PlayerDetailView -Text $rawPlayerDetail
                    if ($playerDetail.LeagueId -cne $LeagueId) {
                        throw 'BF-879 BLOCKED: player detail response does not match the exact current league.'
                    }
                    if ($playerDetail.PlayerId -cne $playerId) {
                        throw 'BF-879 BLOCKED: player detail response does not match the exact requested player.'
                    }
                    $html = ConvertTo-PlayerDetailHtml -View $playerDetail
                    Send-HttpResponse -Stream $stream -StatusCode 200 -StatusText "OK" -ContentType "text/html; charset=utf-8" -Body $html
                }
                catch {
                    $errorHtml = "<!doctype html><html><body><h1>Butler Player Detail blocked</h1><pre>$(ConvertTo-HtmlText $_.Exception.Message)</pre><p>No provider refresh, Butler write, or Sleeper write was executed.</p></body></html>"
                    Send-HttpResponse -Stream $stream -StatusCode 500 -StatusText "Internal Server Error" -ContentType "text/html; charset=utf-8" -Body $errorHtml
                }
                continue
            }

'@

$core = $core.Insert($routeIndex, $playerRoute)

foreach ($required in @(
    'function Get-PlayerDetailRequestId',
    'function ConvertTo-MyTeamPlayerNameHtml',
    'EXACT_CANONICAL',
    '/player?id=',
    'function ConvertTo-PlayerDetailView',
    'function ConvertTo-PlayerDetailHtml',
    'Player Detail',
    '/__butler/internal/player-detail?player=',
    'Invoke-Bf742DashboardWorkerRead -Path $playerDetailPath -BoundaryName "BF-916"',
    'No persisted production snapshot is available',
    '0 games played',
    'Unavailable rates stay unavailable',
    'Supporting evidence',
    'Evidence sources',
    'READ ONLY.'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-879 BLOCKED: required Player Detail marker is missing: $required"
    }
}

$installedStart = $core.IndexOf('function Get-PlayerDetailRequestId', [System.StringComparison]::Ordinal)
$installedEnd = $core.IndexOf('function ConvertTo-LeagueHtml {', $installedStart, [System.StringComparison]::Ordinal)
$installedPlayer = $core.Substring($installedStart, $installedEnd - $installedStart)
foreach ($forbidden in @(
    'Invoke-RestMethod',
    'Invoke-WebRequest',
    'Method = "POST"',
    'https://api.sleeper.app',
    'submitTransaction',
    'setFaab',
    'player-score'
)) {
    if ($installedPlayer.IndexOf($forbidden, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
        throw "BF-879 BLOCKED: Player Detail introduced forbidden provider, write, or score behavior: $forbidden"
    }
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($CorePath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $parseSummary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-879 BLOCKED: generated staged core failed PowerShell parse: $parseSummary"
}
