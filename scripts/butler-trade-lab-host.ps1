# BF-670 host helpers for the read-only Trade Analyzer module.
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
:root{font-family:Inter,Segoe UI,Arial,sans-serif;color:#1E2521;background:#F3F2EE;line-height:1.5;--bg:#F3F2EE;--surface:#FFFFFF;--surface-2:#F7F6F2;--line:#D9DCD7;--turf:#376E50;--turf-deep:#28543D;--gold:#A77418;--ink:#1E2521;--muted:#68726B;--brick:#A65245;--font-display:'Inter',Segoe UI,Arial,sans-serif;--font-body:'Inter',Segoe UI,Arial,sans-serif;--radius:10px;--shadow:0 8px 24px rgba(25,35,29,.04)}*{box-sizing:border-box}html{background:var(--bg)}body{font-family:var(--font-body);margin:0;min-height:100vh;color:var(--ink);background:#F3F2EE;background-image:none;-webkit-font-smoothing:antialiased}a{color:var(--turf-deep)}h1,h2,h3{color:var(--ink)}.shell{max-width:1160px;margin:0 auto;padding:28px 28px 72px}.top{display:flex;justify-content:space-between;gap:24px;align-items:flex-end;padding:24px 26px 18px;border:1px solid var(--line);border-bottom:1px solid var(--line);border-radius:14px 14px 0 0;background:var(--surface);box-shadow:0 8px 28px rgba(25,35,29,.05)}.brand h1{font-family:var(--font-display);font-weight:800;font-size:30px;line-height:1.1;letter-spacing:.16em;margin:0}.brand p{margin:6px 0 0;color:var(--muted);font-size:13px;line-height:1.5}.target{font-size:12px;color:var(--muted);text-align:right;max-width:440px}.nav{display:flex;gap:4px;margin:0 0 24px;flex-wrap:wrap;padding:8px 12px 9px;border:1px solid var(--line);border-top:0;border-radius:0 0 14px 14px;background:var(--surface);box-shadow:0 8px 28px rgba(25,35,29,.05)}.nav a{font-family:var(--font-body);color:var(--muted);text-decoration:none;padding:9px 12px;font-weight:700;font-size:13px;letter-spacing:0;border:1px solid transparent;border-radius:8px;background:transparent}.nav a:hover{color:var(--ink);background:var(--surface-2)}.nav a.active{color:var(--turf-deep);background:#EDF3EF;border-color:#D4E0D8}.panel{background:var(--surface);border:1px solid var(--line);border-radius:12px;padding:22px 24px;box-shadow:var(--shadow);margin-bottom:18px}.eyebrow{font-size:10px;text-transform:uppercase;letter-spacing:.1em;color:var(--turf);font-weight:800}.statusrow{display:flex;align-items:flex-start;justify-content:space-between;gap:20px}.headline{font-family:var(--font-display);font-size:30px;font-weight:800;line-height:1.15;letter-spacing:-.02em;margin:7px 0}.lede{color:var(--muted);margin:0;max-width:860px;font-size:14px}.status{display:inline-flex;align-items:center;font-weight:800;padding:5px 9px;border-radius:999px;font-size:10px;white-space:nowrap;letter-spacing:.04em;border:1px solid currentColor;background:transparent}.good{color:var(--turf)}.warn{color:var(--gold)}.danger{color:var(--brick)}.done{color:var(--muted)}.stats{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px;margin-top:18px;border:0}.stat{padding:14px 15px;border:1px solid var(--line);border-radius:10px;background:var(--surface-2)}.stat strong{display:block;color:var(--muted);font-size:10px;letter-spacing:.04em}.stat span{display:block;font-size:20px;font-weight:800;margin-top:5px;color:var(--ink)}.grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;margin-top:16px}.card,.action,.roster-card{padding:16px;border:1px solid var(--line);border-radius:10px;background:var(--surface-2)}.card .meta,.meta{color:var(--muted);font-size:12px;margin-top:6px}.empty{margin-top:14px;padding:14px;border:1px solid var(--line);border-radius:9px;background:var(--surface-2);color:var(--muted)}.boundary{font-size:12px;color:var(--muted);border-style:dashed;background:transparent;box-shadow:none}.lock{font-weight:800;color:var(--ink)}details{margin-top:14px;border-top:1px solid var(--line);padding-top:12px}summary{cursor:pointer;color:var(--ink);font-weight:700}.trade-loading-shell{min-height:55vh;display:grid;place-items:center}.trade-loading-card{max-width:520px;text-align:center}.trade-loading-spinner{width:42px;height:42px;margin:0 auto 16px;border:3px solid var(--line);border-top-color:var(--turf);border-radius:50%;animation:trade-spin .8s linear infinite}@keyframes trade-spin{to{transform:rotate(360deg)}}.history-card{border-color:var(--line)!important;background:var(--surface-2)!important;border-radius:10px!important}.history-card h3{color:var(--ink)!important}.history-meta div{border-color:var(--line)!important;background:var(--surface)!important;border-radius:8px!important}.history-meta strong,.history-lineage{color:var(--muted)!important}.history-integrity{color:var(--turf)!important}.detail-item{border-color:var(--line)!important;background:var(--surface-2)!important;border-radius:10px!important}.detail-item strong,.mono{color:var(--muted)!important}.explanation-copy{color:var(--ink)!important}.back-link{color:var(--turf-deep)!important}@media(prefers-color-scheme:dark){:root{--bg:#111315;--surface:#191C1E;--surface-2:#202426;--line:#303639;--turf:#69A27D;--turf-deep:#8CBC9A;--gold:#D4A64B;--ink:#F1F3F1;--muted:#A6AFA9;--brick:#D47A6B;--shadow:none}body{background:#111315;background-image:none}.top,.nav,.panel{box-shadow:none}.nav a.active{color:#A8D3B5;background:#26352C;border-color:#35483C}.history-card{background:#202426!important}.history-meta div,.detail-item{background:#191C1E!important}}@media(max-width:760px){.top,.statusrow{display:block}.target{text-align:left;margin-top:12px}.brand h1{font-size:26px}.status{margin-top:12px}.stats,.grid{grid-template-columns:1fr}.shell{padding:14px 14px 44px}.top{padding:20px 18px 15px}.nav{padding:7px 8px}.nav a{font-size:12px;padding:8px 9px}.panel{padding:18px 16px}.headline{font-size:27px}}
'@
}
function Get-AppNav {
    param([Parameter(Mandatory = $true)][string]$Active)
    $dashboardClass = if ($Active -ceq 'dashboard') { ' class="active"' } else { '' }
    $teamClass = if ($Active -ceq 'team') { ' class="active"' } else { '' }
    $matchupClass = if ($Active -ceq 'matchup') { ' class="active"' } else { '' }
    $waiversClass = if ($Active -ceq 'waivers') { ' class="active"' } else { '' }
    $leagueClass = if ($Active -ceq 'league') { ' class="active"' } else { '' }
    $tradeClass = if ($Active -ceq 'trade') { ' class="active"' } else { '' }
    return "<nav class=`"nav`" aria-label=`"Butler sections`"><a$dashboardClass href=`"/`">Dashboard</a><a$teamClass href=`"/team`">My Team</a><a$matchupClass href=`"/matchup`">Matchup</a><a$waiversClass href=`"/waivers`">Waiver Board</a><a$leagueClass href=`"/league`">League</a><a$tradeClass href=`"/trade`">Trade Analyzer</a></nav>"
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
<title>Butler Trade Analyzer</title>
<style>$css</style>
</head>
<body>
<main class="shell">
<div class="top"><div class="brand"><h1>BUTLER</h1><p>We're here to serve you. Less Research. Better Decisions.</p></div><div class="target">$safeLeague</div></div>
$nav
<section class="panel">
<div class="eyebrow">Governed trade intelligence</div>
<div class="statusrow">
<div><h2 class="headline">Opening Trade Analyzer...</h2><p class="lede">Loading your exact roster identity and persisted league assets. The workspace will appear automatically.</p></div>
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
    return $Html.Replace('</nav>', '<a href="/trade">Trade Analyzer</a></nav>')
}
