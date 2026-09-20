param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-880 BLOCKED: staged Butler core not found at $CorePath"
}

$core = [System.IO.File]::ReadAllText($CorePath)

$franchiseFunctions = @'
function Get-FranchiseDetailRequestId {
    param([Parameter(Mandatory = $true)][string]$RequestTarget)

    $question = $RequestTarget.IndexOf('?')
    if ($question -lt 0 -or $question -eq ($RequestTarget.Length - 1)) {
        throw 'BF-880 BLOCKED: Franchise Detail requires exactly one team id.'
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
        throw 'BF-880 BLOCKED: Franchise Detail requires exactly one non-empty team id.'
    }
    if ([string]$ids[0] -notmatch '^[A-Za-z0-9._:-]+$') {
        throw 'BF-880 BLOCKED: Franchise Detail team id contains unsupported characters.'
    }
    return [string]$ids[0]
}

function ConvertTo-FranchiseLeaderNameHtml {
    param([Parameter(Mandatory = $true)]$Leader)

    $name = ConvertTo-HtmlText $Leader.Name
    $teamId = [string]$Leader.TeamId
    if ([string]::IsNullOrWhiteSpace($teamId) -or $teamId -notmatch '^[A-Za-z0-9._:-]+$') {
        return $name
    }

    $hrefId = [System.Uri]::EscapeDataString($teamId)
    return '<a href="/franchise?id=' + $hrefId + '">' + $name + '</a>'
}

function ConvertTo-FranchiseDetailView {
    param([Parameter(Mandatory = $true)][string]$Text)

    $league = [regex]::Match($Text, '(?m)^League ID:\s+(?<value>\S+)\s*$')
    $source = [regex]::Match($Text, '(?m)^Source:\s+(?<value>\S+)\s*$')
    $teamId = [regex]::Match($Text, '(?m)^Team ID:\s+(?<value>\S+)\s*$')
    $teamName = [regex]::Match($Text, '(?m)^Team name:\s+(?<value>.+?)\s*$')
    $assetValue = [regex]::Match($Text, '(?m)^Usable asset value:\s+(?<value>-?\d+(?:\.\d+)?)\s*$')
    $playerValue = [regex]::Match($Text, '(?m)^Usable player value:\s+(?<value>-?\d+(?:\.\d+)?)\s*$')
    $pickValue = [regex]::Match($Text, '(?m)^Usable draft-pick value:\s+(?<value>-?\d+(?:\.\d+)?)\s*$')
    $starterShare = [regex]::Match($Text, '(?m)^Starter value share:\s+(?<value>-?\d+(?:\.\d+)?%)\s*$')
    $topAsset = [regex]::Match($Text, '(?m)^Top asset share:\s+(?<value>UNAVAILABLE|-?\d+(?:\.\d+)?%)\s*$')
    $topThree = [regex]::Match($Text, '(?m)^Top three asset share:\s+(?<value>UNAVAILABLE|-?\d+(?:\.\d+)?%)\s*$')
    $hhi = [regex]::Match($Text, '(?m)^Concentration index:\s+(?<value>-?\d+(?:\.\d+)?)\s*$')
    $assetCoverage = [regex]::Match($Text, '(?m)^Asset coverage:\s+valued=(?<valued>\d+)\s+total=(?<total>\d+)\s+stale=(?<stale>\d+)\s+missing=(?<missing>\d+)\s+percent=(?<percent>-?\d+(?:\.\d+)?%)\s*$')
    $rosterCoverage = [regex]::Match($Text, '(?m)^Roster coverage:\s+valued=(?<valued>\d+)\s+total=(?<total>\d+)\s+stale=(?<stale>\d+)\s+missing=(?<missing>\d+)\s+percent=(?<percent>-?\d+(?:\.\d+)?%)\s*$')
    $draftCoverage = [regex]::Match($Text, '(?m)^Draft coverage:\s+valued=(?<valued>\d+)\s+total=(?<total>\d+)\s+stale=(?<stale>\d+)\s+missing=(?<missing>\d+)\s+percent=(?<percent>-?\d+(?:\.\d+)?%)\s+seasons=(?<seasons>\d+)\s*$')
    $positionCount = [regex]::Match($Text, '(?m)^Positions:\s+(?<value>\d+)\s*$')

    foreach ($required in @(
        $league,$source,$teamId,$teamName,$assetValue,$playerValue,$pickValue,$starterShare,
        $topAsset,$topThree,$hhi,$assetCoverage,$rosterCoverage,$draftCoverage,$positionCount
    )) {
        if (-not $required.Success) {
            throw 'BF-880 BLOCKED: franchise detail output is missing a required evidence field.'
        }
    }

    $positions = @()
    $positionPattern = '^Position:\s+(?<position>[^|]+?)\s+\|\s+players=(?<players>\d+)\s+\|\s+valued=(?<valued>\d+)\s+\|\s+stale=(?<stale>\d+)\s+\|\s+missing=(?<missing>\d+)\s+\|\s+coverage=(?<coverage>-?\d+(?:\.\d+)?%)\s+\|\s+value=(?<value>-?\d+(?:\.\d+)?)\s+\|\s+top1=(?<top1>-?\d+(?:\.\d+)?%)\s+\|\s+top3=(?<top3>-?\d+(?:\.\d+)?%)\s*$'
    foreach ($line in @($Text -split '\r?\n')) {
        $position = [regex]::Match($line, $positionPattern)
        if (-not $position.Success) { continue }
        $positions += [pscustomobject]@{
            Position = $position.Groups['position'].Value.Trim()
            Players = [int]$position.Groups['players'].Value
            Valued = [int]$position.Groups['valued'].Value
            Stale = [int]$position.Groups['stale'].Value
            Missing = [int]$position.Groups['missing'].Value
            Coverage = $position.Groups['coverage'].Value.Trim()
            Value = $position.Groups['value'].Value.Trim()
            TopOne = $position.Groups['top1'].Value.Trim()
            TopThree = $position.Groups['top3'].Value.Trim()
        }
    }

    if ($positions.Count -ne [int]$positionCount.Groups['value'].Value) {
        throw 'BF-880 BLOCKED: franchise positional evidence count does not match the CLI output.'
    }

    return [pscustomobject]@{
        LeagueId = $league.Groups['value'].Value.Trim()
        Source = $source.Groups['value'].Value.Trim()
        TeamId = $teamId.Groups['value'].Value.Trim()
        TeamName = $teamName.Groups['value'].Value.Trim()
        AssetValue = $assetValue.Groups['value'].Value.Trim()
        PlayerValue = $playerValue.Groups['value'].Value.Trim()
        PickValue = $pickValue.Groups['value'].Value.Trim()
        StarterShare = $starterShare.Groups['value'].Value.Trim()
        TopAssetShare = $topAsset.Groups['value'].Value.Trim()
        TopThreeShare = $topThree.Groups['value'].Value.Trim()
        ConcentrationIndex = $hhi.Groups['value'].Value.Trim()
        AssetValued = [int]$assetCoverage.Groups['valued'].Value
        AssetTotal = [int]$assetCoverage.Groups['total'].Value
        AssetStale = [int]$assetCoverage.Groups['stale'].Value
        AssetMissing = [int]$assetCoverage.Groups['missing'].Value
        AssetCoverage = $assetCoverage.Groups['percent'].Value.Trim()
        RosterValued = [int]$rosterCoverage.Groups['valued'].Value
        RosterTotal = [int]$rosterCoverage.Groups['total'].Value
        RosterStale = [int]$rosterCoverage.Groups['stale'].Value
        RosterMissing = [int]$rosterCoverage.Groups['missing'].Value
        RosterCoverage = $rosterCoverage.Groups['percent'].Value.Trim()
        DraftValued = [int]$draftCoverage.Groups['valued'].Value
        DraftTotal = [int]$draftCoverage.Groups['total'].Value
        DraftStale = [int]$draftCoverage.Groups['stale'].Value
        DraftMissing = [int]$draftCoverage.Groups['missing'].Value
        DraftCoverage = $draftCoverage.Groups['percent'].Value.Trim()
        DraftSeasons = [int]$draftCoverage.Groups['seasons'].Value
        Positions = @($positions)
    }
}

function ConvertTo-FranchiseDetailHtml {
    param([Parameter(Mandatory = $true)]$View)

    $css = Get-AppCss
    $nav = Get-AppNav -Active 'league'

    $coverageCards = @"
<div class="metric-card"><span class="metric-label">Asset evidence</span><span class="metric-value">$(ConvertTo-HtmlText $View.AssetCoverage)</span><span class="meta">valued $(ConvertTo-HtmlText $View.AssetValued)/$(ConvertTo-HtmlText $View.AssetTotal) &middot; stale $(ConvertTo-HtmlText $View.AssetStale) &middot; missing $(ConvertTo-HtmlText $View.AssetMissing)</span></div>
<div class="metric-card"><span class="metric-label">Roster evidence</span><span class="metric-value">$(ConvertTo-HtmlText $View.RosterCoverage)</span><span class="meta">valued $(ConvertTo-HtmlText $View.RosterValued)/$(ConvertTo-HtmlText $View.RosterTotal) &middot; stale $(ConvertTo-HtmlText $View.RosterStale) &middot; missing $(ConvertTo-HtmlText $View.RosterMissing)</span></div>
<div class="metric-card"><span class="metric-label">Draft evidence</span><span class="metric-value">$(ConvertTo-HtmlText $View.DraftCoverage)</span><span class="meta">valued $(ConvertTo-HtmlText $View.DraftValued)/$(ConvertTo-HtmlText $View.DraftTotal) &middot; stale $(ConvertTo-HtmlText $View.DraftStale) &middot; missing $(ConvertTo-HtmlText $View.DraftMissing)</span></div>
"@

    $positionsHtml = ''
    foreach ($position in @($View.Positions)) {
        $positionsHtml += @"
<div class="card"><div class="action-head"><strong>$(ConvertTo-HtmlText $position.Position)</strong><span class="status done">$(ConvertTo-HtmlText $position.Coverage)</span></div><div class="stats"><div class="stat"><strong>Players</strong><span>$(ConvertTo-HtmlText $position.Players)</span></div><div class="stat"><strong>Usable value</strong><span>$(ConvertTo-HtmlText $position.Value)</span></div><div class="stat"><strong>Top 1 share</strong><span>$(ConvertTo-HtmlText $position.TopOne)</span></div><div class="stat"><strong>Top 3 share</strong><span>$(ConvertTo-HtmlText $position.TopThree)</span></div></div><p class="meta">valued $(ConvertTo-HtmlText $position.Valued)/$(ConvertTo-HtmlText $position.Players) &middot; stale $(ConvertTo-HtmlText $position.Stale) &middot; missing $(ConvertTo-HtmlText $position.Missing)</p></div>
"@
    }
    if ([string]::IsNullOrWhiteSpace($positionsHtml)) {
        $positionsHtml = '<div class="empty">No positional depth evidence is available for this franchise. Butler does not infer it.</div>'
    }

    return @"
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="dark"><title>Butler - $(ConvertTo-HtmlText $View.TeamName)</title><style>$css</style></head><body><main class="shell">
<header class="top"><div class="brand"><h1>BUTLER</h1><p>We're here to serve you. Less Research. Better Decisions.</p></div><div class="target">Franchise Detail</div></header>
$nav
<section class="panel hero-panel"><div class="manager-head"><div><div class="eyebrow">Franchise Detail</div><h1 class="headline">$(ConvertTo-HtmlText $View.TeamName)</h1><p class="lede">Existing neutral team-profile evidence, organized for a quick franchise read without adding a new grade or strategy label.</p></div><span class="status done">EVIDENCE</span></div><div class="button-row"><a class="btn btn-secondary" href="/league">Back to League</a></div></section>
<section class="panel"><div class="section-head"><div><div class="eyebrow">Current asset frame</div><h2>Franchise composition</h2><p class="lede">Usable values are existing Butler evidence. They are not a contender score, manager grade, or trade recommendation.</p></div></div><div class="manager-metrics"><div class="metric-card"><span class="metric-label">Total usable assets</span><span class="metric-value">$(ConvertTo-HtmlText $View.AssetValue)</span></div><div class="metric-card"><span class="metric-label">Players</span><span class="metric-value">$(ConvertTo-HtmlText $View.PlayerValue)</span></div><div class="metric-card"><span class="metric-label">Draft picks</span><span class="metric-value">$(ConvertTo-HtmlText $View.PickValue)</span></div><div class="metric-card"><span class="metric-label">Starter share</span><span class="metric-value">$(ConvertTo-HtmlText $View.StarterShare)</span></div></div></section>
<section class="panel"><div class="section-head"><div><div class="eyebrow">Evidence quality</div><h2>Coverage and missingness</h2><p class="lede">Stale and missing evidence stays visible. Butler does not fill evidence gaps with assumed values.</p></div></div><div class="manager-metrics">$coverageCards</div></section>
<section class="panel"><div class="section-head"><div><div class="eyebrow">Value concentration</div><h2>How usable value is distributed</h2><p class="lede">Concentration is descriptive context only; Butler defines no preferred concentration level.</p></div></div><div class="stats"><div class="stat"><strong>Top asset share</strong><span>$(ConvertTo-HtmlText $View.TopAssetShare)</span></div><div class="stat"><strong>Top three share</strong><span>$(ConvertTo-HtmlText $View.TopThreeShare)</span></div><div class="stat"><strong>Concentration index</strong><span>$(ConvertTo-HtmlText $View.ConcentrationIndex)</span></div></div></section>
<section class="panel"><div class="section-head"><div><div class="eyebrow">Positional evidence</div><h2>Depth by position</h2><p class="lede">Position cards are descriptive and alphabetic. They are not ranked, weighted, or converted into a roster recommendation.</p></div></div><div class="grid">$positionsHtml</div><details><summary>Evidence source</summary><div class="technical">source $(ConvertTo-HtmlText $View.Source) &middot; league $(ConvertTo-HtmlText $View.LeagueId) &middot; Butler team $(ConvertTo-HtmlText $View.TeamId) &middot; draft seasons represented $(ConvertTo-HtmlText $View.DraftSeasons)</div></details></section>
<section class="panel boundary"><span class="lock">READ ONLY.</span> Franchise Detail presents existing neutral team-profile evidence only. It does not create a new franchise ranking, contender/rebuilder label, trade-target label, manager grade, strategy recommendation, provider refresh, database write, or Sleeper transaction.</section>
</main></body></html>
"@
}
'@

$leagueMarker = 'function ConvertTo-LeagueHtml {'
$leagueIndex = $core.IndexOf($leagueMarker, [System.StringComparison]::Ordinal)
if ($leagueIndex -lt 0) {
    throw 'BF-880 BLOCKED: Franchise Detail renderer insertion marker is missing.'
}
$core = $core.Insert($leagueIndex, $franchiseFunctions.TrimEnd() + [Environment]::NewLine + [Environment]::NewLine)

$escape = [char]96
$oldLeaderName = '<div class=' + $escape + '"name' + $escape + '">$(ConvertTo-HtmlText $leader.Name)</div>'
$newLeaderName = '<div class=' + $escape + '"name' + $escape + '">$(ConvertTo-FranchiseLeaderNameHtml -Leader $leader)</div>'
$leaderMatches = [regex]::Matches($core, [regex]::Escape($oldLeaderName)).Count
if ($leaderMatches -ne 1) {
    throw "BF-880 BLOCKED: expected exactly one governed League leader-name render site, found $leaderMatches."
}
$core = $core.Replace($oldLeaderName, $newLeaderName)

$routeMarker = '            if ($path -eq "/league") {'
$routeIndex = $core.IndexOf($routeMarker, [System.StringComparison]::Ordinal)
if ($routeIndex -lt 0) {
    throw 'BF-880 BLOCKED: Franchise Detail route insertion marker is missing.'
}

$franchiseRoute = @'
            if ($path -eq "/franchise") {
                try {
                    $teamId = Get-FranchiseDetailRequestId -RequestTarget $parts[1]
                    $rawFranchiseDetail = Invoke-ButlerReadOnly -Arguments "league franchise-detail $LeagueId $teamId" -BoundaryName "BF-880"
                    $franchiseDetail = ConvertTo-FranchiseDetailView -Text $rawFranchiseDetail
                    if ($franchiseDetail.LeagueId -cne $LeagueId) {
                        throw 'BF-880 BLOCKED: franchise detail response does not match the exact current league.'
                    }
                    if ($franchiseDetail.TeamId -cne $teamId) {
                        throw 'BF-880 BLOCKED: franchise detail response does not match the exact requested team.'
                    }
                    $html = ConvertTo-FranchiseDetailHtml -View $franchiseDetail
                    Send-HttpResponse -Stream $stream -StatusCode 200 -StatusText "OK" -ContentType "text/html; charset=utf-8" -Body $html
                }
                catch {
                    $errorHtml = "<!doctype html><html><body><h1>Butler Franchise Detail blocked</h1><pre>$(ConvertTo-HtmlText $_.Exception.Message)</pre><p>No provider refresh, Butler write, or Sleeper write was executed.</p></body></html>"
                    Send-HttpResponse -Stream $stream -StatusCode 500 -StatusText "Internal Server Error" -ContentType "text/html; charset=utf-8" -Body $errorHtml
                }
                continue
            }

'@

$core = $core.Insert($routeIndex, $franchiseRoute)

foreach ($required in @(
    'function Get-FranchiseDetailRequestId',
    'function ConvertTo-FranchiseLeaderNameHtml',
    '/franchise?id=',
    'function ConvertTo-FranchiseDetailView',
    'function ConvertTo-FranchiseDetailHtml',
    'league franchise-detail $LeagueId $teamId',
    'Coverage and missingness',
    'Concentration is descriptive context only',
    'Position cards are descriptive and alphabetic',
    'Evidence source',
    'READ ONLY.'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-880 BLOCKED: required Franchise Detail marker is missing: $required"
    }
}

$installedStart = $core.IndexOf('function Get-FranchiseDetailRequestId', [System.StringComparison]::Ordinal)
$installedEnd = $core.IndexOf('function ConvertTo-LeagueHtml {', $installedStart, [System.StringComparison]::Ordinal)
$installedFranchise = $core.Substring($installedStart, $installedEnd - $installedStart)
foreach ($forbidden in @(
    'Invoke-RestMethod',
    'Invoke-WebRequest',
    'Method = "POST"',
    'https://api.sleeper.app',
    'submitTransaction',
    'setFaab',
    'franchise-rank'
)) {
    if ($installedFranchise.IndexOf($forbidden, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
        throw "BF-880 BLOCKED: Franchise Detail introduced forbidden provider, write, or ranking behavior: $forbidden"
    }
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($CorePath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $parseSummary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-880 BLOCKED: generated staged core failed PowerShell parse: $parseSummary"
}
