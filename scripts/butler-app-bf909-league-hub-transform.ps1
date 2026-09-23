param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-909 BLOCKED: staged Butler core not found at $CorePath"
}

$core = [System.IO.File]::ReadAllText($CorePath)
$leagueStart = $core.IndexOf('function ConvertTo-LeagueHtml {', [System.StringComparison]::Ordinal)
if ($leagueStart -lt 0) {
    throw 'BF-909 BLOCKED: League renderer start is missing.'
}
$leagueEnd = $core.IndexOf('function ConvertTo-TeamHtml {', $leagueStart, [System.StringComparison]::Ordinal)
if ($leagueEnd -le $leagueStart) {
    throw 'BF-909 BLOCKED: League renderer end is missing.'
}
if ($core.IndexOf('function ConvertTo-FranchiseLeaderNameHtml {', [System.StringComparison]::Ordinal) -lt 0) {
    throw 'BF-909 BLOCKED: Franchise Detail leader-link helper must be installed before League Hub.'
}

$leagueReplacement = @'
function ConvertTo-LeagueHtml {
    param([Parameter(Mandatory = $true)]$View)

    $statusClass = switch ($View.Status) {
        "READY" { "good" }
        "PARTIAL" { "warn" }
        "STALE" { "warn" }
        default { "danger" }
    }

    $coreText = if ($View.CoreReady) { "Ready" } else { "Not ready" }
    $attentionText = if ($View.RequiresAttention) { "Needs attention" } else { "No required blocker" }
    $franchiseCountText = if ($View.RankingsAvailable) { [string]$View.Leaders.Count } else { "Unavailable" }
    $movementCoverageText = if ($View.MovementAvailable) { "$(ConvertTo-HtmlText $View.MovementCoverage)%" } else { "Unavailable" }

    $franchiseHtml = ""
    if ($View.RankingsAvailable) {
        foreach ($leader in $View.Leaders) {
            $franchiseHtml += @"
<article class="league-franchise-card">
  <div class="league-franchise-head">
    <span class="league-rank">#$(ConvertTo-HtmlText $leader.Rank)</span>
    <div class="league-franchise-name">$(ConvertTo-FranchiseLeaderNameHtml -Leader $leader)</div>
  </div>
  <div class="league-value-grid">
    <div><span>Total value</span><strong>$(ConvertTo-HtmlText $leader.Total)</strong></div>
    <div><span>Player value</span><strong>$(ConvertTo-HtmlText $leader.Players)</strong></div>
    <div><span>Pick value</span><strong>$(ConvertTo-HtmlText $leader.Picks)</strong></div>
  </div>
  <p class="meta">Governed franchise rank and values from Butler's current league evidence. Open the franchise name for neutral team detail.</p>
</article>
"@
        }
    }
    else {
        $franchiseHtml = '<div class="empty">Franchise rankings are unavailable until Butler reports current asset coverage as READY. No franchise order is inferred.</div>'
    }

    $movementSummary = if ($View.MovementAvailable) {
        "$(ConvertTo-HtmlText $View.MovementPrevious) to $(ConvertTo-HtmlText $View.MovementLatest) &middot; comparable $(ConvertTo-HtmlText $View.MovementComparable)/$(ConvertTo-HtmlText $View.MovementTotal) &middot; coverage $(ConvertTo-HtmlText $View.MovementCoverage)%"
    } else {
        "Comparable movement unavailable"
    }

    $movementHtml = ""
    if ($View.MovementAvailable) {
        if ($View.Movers.Count -eq 0) {
            $movementHtml = '<div class="empty">The governed movement window is available, but no mover rows were returned.</div>'
        }
        else {
            foreach ($mover in $View.Movers) {
                $movementHtml += "<div class=""league-mover-row"">$(ConvertTo-HtmlText $mover)</div>"
            }
        }
    }
    else {
        $movementHtml = '<div class="empty">Value movement is unavailable until comparable provider snapshots exist. Butler does not manufacture a trend.</div>'
    }

    $actionsHtml = ""
    if ($View.Actions.Count -eq 0) {
        $actionsHtml = '<div class="empty">No governed next action is currently required.</div>'
    }
    else {
        foreach ($action in $View.Actions) {
            $pillClass = if ($action.Requirement -ceq "REQUIRED") { "required" } else { "optional" }
            $commandHtml = if ([string]::IsNullOrWhiteSpace($action.Command)) {
                ""
            } else {
                "<input class=""command"" readonly value=""$(ConvertTo-HtmlText $action.Command)"">"
            }
            $actionsHtml += @"
<article class="action">
  <div class="action-head"><span class="pill $pillClass">$(ConvertTo-HtmlText $action.Requirement)</span><span class="kind">$(ConvertTo-HtmlText $action.Kind)</span></div>
  <p>$(ConvertTo-HtmlText $action.Description)</p>
  $commandHtml
</article>
"@
        }
    }

    $css = Get-AppCss
    $nav = Get-AppNav -Active "league"

    return @"
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="dark"><title>Butler - League</title><style>$css</style></head><body><main class="shell">
<header class="top"><div class="brand"><h1>BUTLER</h1><p>We're here to serve you. Less Research. Better Decisions.</p></div><div class="target">League Hub &middot; $(ConvertTo-HtmlText $View.LeagueName)</div></header>
$nav
<section class="panel hero-panel"><div class="manager-head"><div><div class="eyebrow">League hub</div><h1 class="headline">$(ConvertTo-HtmlText $View.LeagueName)</h1><p class="lede">Scan the current franchise landscape, open exact team detail, and use comparable movement without adding a new Butler grade or strategy label.</p></div><span class="status $statusClass">$(ConvertTo-HtmlText $View.Status)</span></div><div class="manager-metrics league-summary"><div class="metric-card"><span class="metric-label">Franchises</span><span class="metric-value">$(ConvertTo-HtmlText $franchiseCountText)</span></div><div class="metric-card"><span class="metric-label">Movement coverage</span><span class="metric-value">$(ConvertTo-HtmlText $movementCoverageText)</span></div><div class="metric-card"><span class="metric-label">Core analysis</span><span class="metric-value">$(ConvertTo-HtmlText $coreText)</span></div><div class="metric-card"><span class="metric-label">League health</span><span class="metric-value">$(ConvertTo-HtmlText $attentionText)</span></div></div><div class="league-tool-row"><strong>Quick tools</strong><a class="btn btn-secondary" href="/team">My Team</a><a class="btn btn-secondary" href="/players">Player Search</a><a class="btn btn-secondary" href="/compare">Player Compare</a><a class="btn btn-secondary" href="/trade">Trade Analyzer</a></div></section>
<section class="panel"><div class="section-head"><div><div class="eyebrow">Franchise board</div><h2>League landscape</h2><p class="lede">Rank, total value, player value, and pick value are Butler's existing governed league evidence. They are descriptive context, not a manager grade or trade-target list.</p></div></div><div class="league-franchise-board">$franchiseHtml</div></section>
<section class="panel"><div class="section-head"><div><div class="eyebrow">Comparable movement</div><h2>What changed between value snapshots</h2><p class="lede">$movementSummary</p></div></div><div class="league-movement-list">$movementHtml</div><details><summary>Movement boundary</summary><div class="technical">Butler shows movement only when comparable provider snapshots exist. Missing comparison evidence stays unavailable rather than being inferred.</div></details></section>
<section class="panel"><div class="section-head"><div><div class="eyebrow">League evidence</div><h2>Source and governed actions</h2><p class="lede">League-health actions remain manual and read only. Butler never executes the displayed commands from this page.</p></div></div><details class="league-governance"><summary>View governed league actions</summary><div class="actions">$actionsHtml</div></details><details><summary>Evidence source</summary><div class="technical">source $(ConvertTo-HtmlText $View.Source) &middot; league $(ConvertTo-HtmlText $View.LeagueId) &middot; status $(ConvertTo-HtmlText $View.Status)</div></details></section>
<section class="panel boundary"><span class="lock">READ ONLY.</span> BF-909 reorganizes the existing governed League overview into a manager-first hub. It does not rerank franchises, create grades or contender/rebuilder labels, identify trade targets, refresh values, add a passive backend read, mutate evidence, execute trades, or submit Sleeper transactions.</section>
</main></body></html>
"@
}
'@

$core = $core.Substring(0, $leagueStart) + $leagueReplacement.TrimEnd() + [Environment]::NewLine + [Environment]::NewLine + $core.Substring($leagueEnd)

$cssStart = $core.IndexOf('function Get-AppCss {', [System.StringComparison]::Ordinal)
if ($cssStart -lt 0) {
    throw 'BF-909 BLOCKED: shared manager CSS start is missing.'
}
$cssEnd = $core.IndexOf('function Get-AppNav {', $cssStart, [System.StringComparison]::Ordinal)
if ($cssEnd -le $cssStart) {
    throw 'BF-909 BLOCKED: shared manager CSS end is missing.'
}
$cssBlock = $core.Substring($cssStart, $cssEnd - $cssStart)
$cssTerminator = $cssBlock.LastIndexOf("'@", [System.StringComparison]::Ordinal)
if ($cssTerminator -lt 0) {
    throw 'BF-909 BLOCKED: shared manager CSS terminator is missing.'
}

$bf909Css = @'
/* BF-909 manager-first League Hub. */
.league-summary{margin-top:16px}.league-tool-row{display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin-top:14px;padding-top:14px;border-top:1px solid var(--line)}.league-tool-row>strong{font-size:10px;text-transform:uppercase;letter-spacing:.08em;color:var(--muted);margin-right:2px}.league-franchise-board{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;margin-top:16px}.league-franchise-card{padding:16px;border:1px solid var(--line);border-radius:11px;background:var(--surface-2)}.league-franchise-head{display:flex;align-items:flex-start;gap:10px}.league-rank{display:inline-flex;align-items:center;justify-content:center;min-width:34px;height:28px;padding:0 8px;border:1px solid var(--line);border-radius:999px;color:var(--turf-deep);font-size:11px;font-weight:900}.league-franchise-name{font-family:var(--font-display);font-size:18px;font-weight:800;line-height:1.25}.league-franchise-name a{color:var(--ink);text-decoration:none}.league-franchise-name a:hover{color:var(--turf-deep);text-decoration:underline}.league-value-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px;margin-top:14px}.league-value-grid>div{padding:10px;border:1px solid var(--line);border-radius:9px;background:var(--surface)}.league-value-grid span{display:block;font-size:10px;color:var(--muted)}.league-value-grid strong{display:block;margin-top:3px;font-size:15px;color:var(--ink)}.league-franchise-card>.meta{margin:12px 0 0}.league-movement-list{display:grid;gap:8px;margin-top:16px}.league-mover-row{padding:12px 14px;border:1px solid var(--line);border-radius:10px;background:var(--surface-2);font-size:12px;color:var(--ink)}.league-governance{margin-top:14px}@media(max-width:760px){.league-franchise-board{grid-template-columns:1fr}.league-value-grid{grid-template-columns:1fr}.league-tool-row .btn{flex:1 1 45%;text-align:center}}
'@

$cssBlock = $cssBlock.Substring(0, $cssTerminator) + [Environment]::NewLine + $bf909Css.TrimEnd() + [Environment]::NewLine + $cssBlock.Substring($cssTerminator)
$core = $core.Substring(0, $cssStart) + $cssBlock + $core.Substring($cssEnd)

foreach ($required in @(
    'BF-909 manager-first League Hub',
    'League hub',
    'Franchise board',
    'League landscape',
    'ConvertTo-FranchiseLeaderNameHtml -Leader $leader',
    'href="/team">My Team</a>',
    'href="/players">Player Search</a>',
    'href="/compare">Player Compare</a>',
    'href="/trade">Trade Analyzer</a>',
    'Comparable movement',
    'View governed league actions',
    'does not rerank franchises'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-909 BLOCKED: required League Hub marker is missing: $required"
    }
}

$installedStart = $core.IndexOf('function ConvertTo-LeagueHtml {', [System.StringComparison]::Ordinal)
$installedEnd = $core.IndexOf('function ConvertTo-TeamHtml {', $installedStart, [System.StringComparison]::Ordinal)
$installedLeague = $core.Substring($installedStart, $installedEnd - $installedStart)

$boardIndex = $installedLeague.IndexOf('Franchise board', [System.StringComparison]::Ordinal)
$movementIndex = $installedLeague.IndexOf('Comparable movement', [System.StringComparison]::Ordinal)
$governanceIndex = $installedLeague.IndexOf('View governed league actions', [System.StringComparison]::Ordinal)
if (-not ($boardIndex -ge 0 -and $movementIndex -gt $boardIndex -and $governanceIndex -gt $movementIndex)) {
    throw 'BF-909 BLOCKED: League Hub section order must remain franchise board -> movement -> governed actions.'
}

if ($installedLeague -match 'Invoke-RestMethod|Invoke-WebRequest|Invoke-ButlerReadOnly|Invoke-Bf742DashboardWorkerRead|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
    throw 'BF-909 BLOCKED: League Hub introduced provider, backend-read, optimizer, or write behavior.'
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($CorePath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $parseSummary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-909 BLOCKED: generated staged core failed PowerShell parse: $parseSummary"
}

Write-Host 'BF-909 manager-first League Hub applied.'
