# BF-670 host helpers for the read-only Trade Lab module.
# This file intentionally contains presentation/orchestration helpers only.

function ConvertTo-HtmlText {
    param([AllowNull()][object]$Value)
    if ($null -eq $Value) { return 'none' }
    return [System.Net.WebUtility]::HtmlEncode([string]$Value)
}

function Invoke-ButlerReadOnly {
    param(
        [Parameter(Mandatory = $true)][string]$Arguments,
        [Parameter(Mandatory = $true)][string]$BoundaryName
    )
    $previousPreference = $ErrorActionPreference
    $lines = $null
    $exitCode = $null
    try {
        $ErrorActionPreference = 'Continue'
        $lines = & $gradle ':bet:bet-cli:run' "--args=$Arguments" 2>&1
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }
    $text = ($lines | ForEach-Object { "$_" }) -join "`n"
    if ($exitCode -ne 0) {
        throw "$BoundaryName BLOCKED: governed read-only command failed with Gradle exit code $exitCode.`n$text"
    }
    return $text
}

function Invoke-ButlerReadOnlyTask {
    param(
        [Parameter(Mandatory = $true)][string]$Task,
        [Parameter(Mandatory = $true)][string]$Arguments,
        [Parameter(Mandatory = $true)][string]$BoundaryName
    )
    $previousPreference = $ErrorActionPreference
    $lines = $null
    $exitCode = $null
    try {
        $ErrorActionPreference = 'Continue'
        $lines = & $gradle $Task "--args=$Arguments" 2>&1
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }
    $text = ($lines | ForEach-Object { "$_" }) -join "`n"
    if ($exitCode -ne 0) {
        throw "$BoundaryName BLOCKED: governed read-only task failed with Gradle exit code $exitCode.`n$text"
    }
    return $text
}

function ConvertTo-RosterContextView {
    param([Parameter(Mandatory = $true)][string]$Text)

    $gate = [regex]::Match($Text, '(?m)^Binding gate state:\s+(?<state>\S+)\s*$')
    $league = [regex]::Match($Text, '(?m)^Bound Sleeper league:\s+(?<id>\S+)\s+\|\s+(?<name>.+?)\s*$')
    $roster = [regex]::Match($Text, '(?m)^Bound roster / role:\s+(?<id>\d+)\s+/\s+(?<role>\S+)\s*$')
    $display = [regex]::Match($Text, '(?m)^Bound display/team:\s+(?<display>.*?)\s+/\s+(?<team>.*?)\s*$')
    $butlerTeam = [regex]::Match($Text, '(?m)^Butler team id/name:\s+(?<id>\S+)\s+/\s+(?<name>.+?)\s*$')
    $season = [regex]::Match($Text, '(?m)^Provider season/status/leg:\s+(?<season>\d+)/(?<status>[^/]+)/(?<leg>.+?)\s*$')
    $counts = [regex]::Match($Text, '(?m)^Target roster players starter/bench/reserve/taxi:\s+(?<total>\d+)\s+\|\s+(?<starter>\d+)/(?<bench>\d+)/(?<reserve>\d+)/(?<taxi>\d+)\s*$')
    if (-not $gate.Success -or $gate.Groups['state'].Value -cne 'BOUND_TARGET_LIVE_VERIFIED' -or
        -not $league.Success -or -not $roster.Success -or -not $display.Success -or
        -not $butlerTeam.Success -or -not $season.Success -or -not $counts.Success) {
        throw 'BF-670 BLOCKED: governed BF-623/BF-610 roster identity is missing or not live verified.'
    }

    return [pscustomobject]@{
        SleeperLeagueId = $league.Groups['id'].Value.Trim()
        LeagueName = $league.Groups['name'].Value.Trim()
        RosterId = [int]$roster.Groups['id'].Value
        Role = $roster.Groups['role'].Value
        DisplayName = $display.Groups['display'].Value.Trim()
        TeamName = $display.Groups['team'].Value.Trim()
        ButlerTeamId = $butlerTeam.Groups['id'].Value.Trim()
        ButlerTeamName = $butlerTeam.Groups['name'].Value.Trim()
        Season = [int]$season.Groups['season'].Value
        ProviderStatus = $season.Groups['status'].Value.Trim()
        TotalPlayers = [int]$counts.Groups['total'].Value
    }
}

function Get-AppCss {
    return @'
:root{font-family:Inter,Segoe UI,Arial,sans-serif;color:#f7f8fb;background:#0b1020;line-height:1.45}*{box-sizing:border-box}body{margin:0;background:radial-gradient(circle at top,#172447 0,#0b1020 38%,#070b15 100%);min-height:100vh}.shell{max-width:1120px;margin:0 auto;padding:28px 22px 56px}.top{display:flex;justify-content:space-between;gap:20px;align-items:flex-end;margin-bottom:16px}.brand h1{font-size:38px;letter-spacing:.16em;margin:0}.brand p{margin:5px 0 0;color:#9ca9c8}.target{font-size:14px;color:#cbd4eb;text-align:right}.nav{display:flex;gap:8px;margin:0 0 20px;flex-wrap:wrap}.nav a{color:#b9c6e5;text-decoration:none;padding:9px 13px;border:1px solid #28365f;border-radius:10px;background:#0d1630;font-weight:700}.nav a.active{background:#315dca;color:white;border-color:#315dca}.panel{background:rgba(16,24,48,.88);border:1px solid #28365f;border-radius:20px;padding:22px;box-shadow:0 20px 60px rgba(0,0,0,.28);margin-bottom:18px}.eyebrow{font-size:12px;text-transform:uppercase;letter-spacing:.14em;color:#8ea0c7}.statusrow{display:flex;align-items:flex-start;justify-content:space-between;gap:18px}.headline{font-size:28px;margin:6px 0 4px}.lede{color:#cbd4eb;margin:0;max-width:800px}.status{font-weight:800;padding:9px 13px;border-radius:999px;font-size:13px;white-space:nowrap}.good{background:#123d2c;color:#8ff0b9}.warn{background:#4b3713;color:#ffd98b}.danger{background:#4c2028;color:#ffb0bc}.done{background:#1c315c;color:#a9c6ff}.stats{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;margin-top:18px}.stat,.card,.action,.roster-card{padding:16px;border:1px solid #2b3962;border-radius:14px;background:#0d1630}.stat strong{display:block;color:#8797bd;font-size:11px;text-transform:uppercase;letter-spacing:.08em}.stat span{display:block;font-size:19px;font-weight:800;margin-top:5px}.grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;margin-top:16px}.card .rank{color:#8ea0c7;font-size:12px;font-weight:800}.card .name{font-size:18px;font-weight:800;margin-top:4px}.card .meta,.meta{color:#9eabd0;font-size:12px;margin-top:6px}.empty{margin-top:14px;padding:16px;border:1px solid #2b3962;border-radius:14px;background:#0d1630;color:#aebada}.boundary{font-size:13px;color:#a9b5d2}.lock{font-weight:800;color:#a9c6ff}details{margin-top:14px;border-top:1px solid #28365f;padding-top:12px}summary{cursor:pointer;color:#a9c6ff;font-weight:700}@media(max-width:760px){.top,.statusrow{display:block}.target{text-align:left;margin-top:12px}.brand h1{font-size:30px}.status{display:inline-block;margin-top:12px}.stats,.grid{grid-template-columns:1fr}}
'@
}

function Get-AppNav {
    param([Parameter(Mandatory = $true)][string]$Active)
    $dashboardClass = if ($Active -ceq 'dashboard') { ' class="active"' } else { '' }
    $teamClass = if ($Active -ceq 'team') { ' class="active"' } else { '' }
    $waiversClass = if ($Active -ceq 'waivers') { ' class="active"' } else { '' }
    $leagueClass = if ($Active -ceq 'league') { ' class="active"' } else { '' }
    $tradeClass = if ($Active -ceq 'trade') { ' class="active"' } else { '' }
    return "<nav class=`"nav`" aria-label=`"Butler sections`"><a$dashboardClass href=`"/`">Dashboard</a><a$teamClass href=`"/team`">My Team</a><a$waiversClass href=`"/waivers`">Waiver Board</a><a$leagueClass href=`"/league`">League</a><a$tradeClass href=`"/trade`">Trade Lab</a></nav>"
}

function Get-TradeLabLoadingHtml {
    param([Parameter(Mandatory = $true)][string]$LeagueId)

    $css = Get-AppCss
    $nav = Get-AppNav -Active 'trade'
    $safeLeague = ConvertTo-HtmlText $LeagueId
    return @"
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="refresh" content="1;url=/trade?load=1">
<title>Butler Trade Lab</title>
<style>$css</style>
</head>
<body>
<main class="shell">
<div class="top"><div class="brand"><h1>BUTLER</h1><p>We're here to serve you. Less Research. Better Decisions.</p></div><div class="target">$safeLeague</div></div>
$nav
<section class="panel">
<div class="eyebrow">Governed trade intelligence</div>
<div class="statusrow">
<div><h2 class="headline">Opening Trade Lab...</h2><p class="lede">Loading your exact roster identity and persisted league assets. The workspace will appear automatically.</p></div>
<span class="status done">READ ONLY</span>
</div>
<div class="empty">Butler is preparing the governed trade workspace. No proposal, transaction, or Sleeper write is being executed.</div>
</section>
</main>
</body>
</html>
"@
}

function Add-TradeNavigation {
    param([Parameter(Mandatory = $true)][string]$Html)
    if ($Html -match 'href="/trade"') { return $Html }
    if ($Html -notmatch '<nav class="nav" aria-label="Butler sections">') {
        throw 'BF-670 BLOCKED: proxied Butler HTML is missing the navigation contract.'
    }
    return $Html.Replace('</nav>', '<a href="/trade">Trade Lab</a></nav>')
}
