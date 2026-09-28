param([Parameter(Mandatory = $true)][string]$CorePath)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) { throw 'My Team workspace: staged core is missing.' }

$core = [IO.File]::ReadAllText($CorePath)
$start = $core.IndexOf('function ConvertTo-TeamHtml {', [StringComparison]::Ordinal)
$end = $core.IndexOf('function Add-LeagueNavigation {', $start, [StringComparison]::Ordinal)
if ($start -lt 0 -or $end -le $start) { throw 'My Team workspace: renderer boundary is missing.' }
$team = $core.Substring($start, $end - $start)

function Replace-TeamOnce([string]$Old, [string]$New) {
    $count = [regex]::Matches($script:team, [regex]::Escape($Old)).Count
    if ($count -ne 1) { throw "My Team workspace: expected one '$Old', found $count." }
    $script:team = $script:team.Replace($Old, $New)
}

Replace-TeamOnce '<body><main class="shell">' '<body class="team-page"><main class="shell">'
$rail = @'
$nav
<div class="team-workspace">
<aside class="team-rail" aria-label="Team workspace"><div class="rail-title">MY PLAYBOOK</div><div class="rail-team">$(ConvertTo-HtmlText $displayTeam)</div><a href="/">Dashboard</a><a class="rail-current" href="/team">My Team</a><div class="rail-label">THIS WEEK</div><a href="/matchup">Matchup</a><a href="/matchup/autofill">Lineup review</a><a href="/waivers">Waiver Board</a><div class="rail-label">EXPLORE</div><a href="/players">Player Search</a><a href="/compare">Player Compare</a><a href="/league">League</a><a href="/trade">Trade Analyzer</a><a href="/history">History</a></aside>
<div class="team-content">
'@
Replace-TeamOnce "`$nav`n<section" ($rail.TrimEnd() + "`n<section")
Replace-TeamOnce '</main></body></html>' '</div></div></main></body></html>'

$core = $core.Substring(0, $start) + $team + $core.Substring($end)
$cssStart = $core.IndexOf('function Get-AppCss {', [StringComparison]::Ordinal)
$cssEnd = $core.IndexOf('function Get-AppNav {', $cssStart, [StringComparison]::Ordinal)
if ($cssStart -lt 0 -or $cssEnd -le $cssStart) { throw 'My Team workspace: CSS boundary is missing.' }
$cssBlock = $core.Substring($cssStart, $cssEnd - $cssStart)
$terminator = $cssBlock.LastIndexOf("`n'@", [StringComparison]::Ordinal)
if ($terminator -lt 0) { throw 'My Team workspace: CSS terminator is missing.' }
$css = @'
/* My Team workspace: an original Butler navigation and decision layout. */
.team-page .shell{max-width:1540px}.team-workspace{display:grid;grid-template-columns:minmax(0,1fr)}.team-content{min-width:0}.team-rail{display:none}.team-page .hero-panel{padding:20px 24px}.team-page .hero-panel .headline{font-size:clamp(25px,2.3vw,34px)}.team-page .manager-metrics{grid-template-columns:repeat(4,minmax(0,1fr));gap:9px;margin-top:14px}.team-page .metric-card{min-height:76px;padding:12px 14px}.team-page .metric-label{font-size:12px}.team-page .metric-value{font-size:clamp(17px,1.6vw,23px);line-height:1.2;overflow-wrap:anywhere}.team-page .team-primary-actions{margin-top:14px}.team-page .team-action-note{margin-top:7px}.team-page .panel h2{margin-top:5px}.team-page .roster-inventory-card span{font-size:12px}.team-page .roster-group-head h3{font-size:15px}.team-page .player-primary span,.team-page .roster-group-head span{font-size:12px}
@media(min-width:1200px){.team-page .shell{padding-top:18px}.team-page .top{margin-bottom:12px}.team-page .top .brand h1{font-size:27px}.team-page .nav{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap}.team-page .nav:focus-within{position:static;width:auto;height:auto;margin:0;overflow:visible;clip:auto;white-space:normal}.team-workspace{grid-template-columns:205px minmax(0,1fr);gap:20px;align-items:start}.team-rail{display:flex;flex-direction:column;position:sticky;top:16px;padding:17px 10px;border:1px solid var(--line);border-radius:15px;background:var(--surface);max-height:calc(100vh - 32px);overflow-y:auto}.team-rail a{display:block;color:var(--muted);padding:10px 12px;border-radius:8px;text-decoration:none;font-size:14px;font-weight:700}.team-rail a:hover,.team-rail a:focus-visible{background:var(--surface-2);color:var(--ink)}.team-rail .rail-current{background:rgba(93,211,158,.15);color:var(--green)}.rail-title{padding:3px 12px;color:var(--green);font-size:12px;font-weight:900;letter-spacing:.12em}.rail-team{padding:8px 12px 13px;color:var(--ink);font-size:15px;font-weight:800;overflow-wrap:anywhere}.rail-label{padding:18px 12px 5px;color:var(--muted-2);font-size:12px;font-weight:900;letter-spacing:.07em;border-top:1px solid var(--line-soft)}}
@media(min-width:1450px){.team-page .team-content{display:grid;grid-template-columns:minmax(0,1.7fr) minmax(300px,1fr);gap:14px;align-items:start}.team-page .team-content>.hero-panel{grid-column:1/-1}.team-page .team-content>.panel{margin-bottom:0}.team-page .team-content>.panel:has(.roster-inventory){grid-column:1;grid-row:2/4}.team-page .team-content>.recommendation-panel{grid-column:2}.team-page .team-content>.recommendation-panel .manager-summary,.team-page .team-content>.recommendation-panel .grid.four,.team-page .team-content>.recommendation-panel .autofill-summary{grid-template-columns:1fr}.team-page .team-content>.recommendation-panel .summary-card{min-width:0}.team-page .team-content>.recommendation-panel .lineup-board{overflow-x:auto}.team-page .team-content>.panel.boundary{grid-column:1/-1}}
@media(max-width:1199px){.team-page .manager-metrics{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:600px){.team-page .hero-panel{padding:18px}.team-page .manager-metrics{grid-template-columns:repeat(2,minmax(0,1fr))}.team-page .metric-card{min-height:83px}.team-page .roster-inventory{grid-template-columns:repeat(2,minmax(0,1fr))}}
'@
$cssBlock = $cssBlock.Substring(0, $terminator) + "`n" + $css.TrimEnd() + $cssBlock.Substring($terminator)
$core = $core.Substring(0, $cssStart) + $cssBlock + $core.Substring($cssEnd)

$tokens = $null; $errors = $null
[void][System.Management.Automation.Language.Parser]::ParseInput($core, [ref]$tokens, [ref]$errors)
if (@($errors).Count -gt 0) { throw "My Team workspace: generated core has $(@($errors).Count) parse error(s)." }
[IO.File]::WriteAllText($CorePath, $core, [Text.UTF8Encoding]::new($false))
Write-Host 'My Team workspace layout applied.'
