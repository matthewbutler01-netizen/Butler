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
:root{font-family:Inter,Segoe UI,Arial,sans-serif;color:#16201A;background:#F4F2EA;line-height:1.45;--bg:#F4F2EA;--surface:#FFFFFF;--surface-2:#ECE9DD;--line:#D8D4C4;--turf:#2E6B47;--turf-deep:#1F4D33;--gold:#C98A1F;--ink:#16201A;--muted:#5B6459;--brick:#A8452F;--font-display:'Teko',Impact,sans-serif;--font-body:'Inter',Segoe UI,Arial,sans-serif;--radius:3px}*{box-sizing:border-box}html{background:var(--bg)}body{font-family:var(--font-body);margin:0;min-height:100vh;color:var(--ink);background-color:var(--bg);background-image:repeating-linear-gradient(90deg,transparent 0,transparent 79px,color-mix(in srgb,var(--line) 28%,transparent) 80px)}a{color:var(--turf-deep)}h1,h2,h3{color:var(--ink)}.shell{max-width:1320px;margin:0 auto;padding:28px 30px 64px}.top{display:flex;justify-content:space-between;gap:28px;align-items:flex-end;padding:8px 0 20px;border-bottom:1px solid var(--ink);margin-bottom:0}.brand h1{font-family:var(--font-display);font-weight:600;font-size:42px;line-height:.9;letter-spacing:.03em;margin:0;text-transform:uppercase}.brand p{margin:8px 0 0;color:var(--muted);font-size:11px;letter-spacing:.08em;text-transform:uppercase}.target{font-size:11px;color:var(--muted);text-align:right;max-width:420px}.nav{display:flex;gap:22px;margin:0 0 30px;flex-wrap:wrap;padding:13px 0;border-bottom:1px solid var(--line)}.nav a{color:var(--muted);text-decoration:none;padding:4px 0 9px;font-weight:800;font-size:10px;letter-spacing:.09em;text-transform:uppercase;border:0;border-bottom:3px solid transparent;background:transparent;border-radius:0}.nav a:hover{color:var(--ink)}.nav a.active{color:var(--turf);border-bottom-color:var(--turf);background:transparent}.panel{background:var(--surface);border:1px solid var(--line);border-radius:var(--radius);padding:22px 24px;box-shadow:none;margin-bottom:18px}.eyebrow{font-size:10px;text-transform:uppercase;letter-spacing:.13em;color:var(--turf);font-weight:900}.statusrow{display:flex;align-items:flex-start;justify-content:space-between;gap:20px}.headline{font-family:var(--font-display);font-size:34px;line-height:1;margin:7px 0 7px;text-transform:uppercase}.lede{color:var(--muted);margin:0;max-width:860px}.status{display:inline-flex;align-items:center;font-weight:900;padding:6px 8px;border-radius:var(--radius);font-size:9px;white-space:nowrap;letter-spacing:.08em;text-transform:uppercase;border:1px solid currentColor;background:transparent}.good{color:var(--turf)}.warn{color:#8a6319}.danger{color:var(--brick)}.done{color:var(--muted)}.stats{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:0;margin-top:18px;border-top:1px solid var(--line);border-left:1px solid var(--line)}.stat{padding:14px;border-right:1px solid var(--line);border-bottom:1px solid var(--line);background:var(--surface)}.stat strong{display:block;color:var(--muted);font-size:9px;text-transform:uppercase;letter-spacing:.08em}.stat span{display:block;font-size:18px;font-weight:800;margin-top:5px}.grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;margin-top:16px}.card,.action,.roster-card{padding:16px;border:1px solid var(--line);border-radius:var(--radius);background:var(--surface)}.card .meta,.meta{color:var(--muted);font-size:12px;margin-top:6px}.empty{margin-top:14px;padding:14px;border:1px solid var(--line);border-radius:var(--radius);background:var(--surface-2);color:var(--muted)}.boundary{font-size:12px;color:var(--muted);border-style:dashed}.lock{font-weight:900;color:var(--ink)}details{margin-top:14px;border-top:1px solid var(--line);padding-top:12px}summary{cursor:pointer;color:var(--turf-deep);font-weight:800}.trade-loading-shell{min-height:55vh;display:grid;place-items:center}.trade-loading-card{max-width:520px;text-align:center}.trade-loading-spinner{width:42px;height:42px;margin:0 auto 16px;border:3px solid var(--line);border-top-color:var(--turf);border-radius:50%;animation:trade-spin .8s linear infinite}@keyframes trade-spin{to{transform:rotate(360deg)}}@media(prefers-color-scheme:dark){:root{--bg:#111713;--surface:#18201b;--surface-2:#202b24;--line:#344137;--turf:#77b58b;--turf-deep:#9ec7aa;--gold:#e1ad52;--ink:#edf4ef;--muted:#aab7ad;--brick:#d77a62}}@media(max-width:760px){.top,.statusrow{display:block}.target{text-align:left;margin-top:12px}.brand h1{font-size:36px}.status{margin-top:12px}.stats,.grid{grid-template-columns:1fr}.shell{padding:20px 16px 46px}.nav{gap:14px}}
'@
}
function Get-AppNav {
    param([Parameter(Mandatory = $true)][string]$Active)
    $dashboardClass = if ($Active -ceq 'dashboard') { ' class="active"' } else { '' }
    $teamClass = if ($Active -ceq 'team') { ' class="active"' } else { '' }
    $waiversClass = if ($Active -ceq 'waivers') { ' class="active"' } else { '' }
    $leagueClass = if ($Active -ceq 'league') { ' class="active"' } else { '' }
    $tradeClass = if ($Active -ceq 'trade') { ' class="active"' } else { '' }
    return "<nav class=`"nav`" aria-label=`"Butler sections`"><a$dashboardClass href=`"/`">Dashboard</a><a$teamClass href=`"/team`">My Team</a><a$waiversClass href=`"/waivers`">Waiver Board</a><a$leagueClass href=`"/league`">League</a><a$tradeClass href=`"/trade`">Trade Analyzer</a></nav>"
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
