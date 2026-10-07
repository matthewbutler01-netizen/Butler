param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $DashboardPath -PathType Leaf)) {
    throw "BF-907 BLOCKED: staged Butler dashboard not found at $DashboardPath"
}

$text = [System.IO.File]::ReadAllText($DashboardPath)
$dashboardStart = $text.IndexOf('function ConvertTo-DashboardHtml {', [System.StringComparison]::Ordinal)
$dashboardEnd = $text.IndexOf('function ConvertTo-TeamHtml {', $dashboardStart, [System.StringComparison]::Ordinal)
if ($dashboardStart -lt 0 -or $dashboardEnd -le $dashboardStart) {
    throw 'BF-907 BLOCKED: Dashboard renderer function boundary is missing.'
}

$dashboardBlock = $text.Substring($dashboardStart, $dashboardEnd - $dashboardStart)

$cssStart = $dashboardBlock.IndexOf('$bf819Css = @"', [System.StringComparison]::Ordinal)
if ($cssStart -lt 0) {
    throw 'BF-907 BLOCKED: BF-819 manager CSS block is missing.'
}
$cssEnd = $dashboardBlock.IndexOf('"@', $cssStart + '$bf819Css = @"'.Length, [System.StringComparison]::Ordinal)
if ($cssEnd -lt 0) {
    throw 'BF-907 BLOCKED: BF-819 manager CSS terminator is missing.'
}

$bf907Css = @'
/* BF-907 Decision Center + BF-1020 v0.4 Dashboard Command Center. */
.top{padding:15px 20px!important;min-height:0}.top .brand h1{font-size:25px!important;letter-spacing:.14em}.top .brand p{margin-top:4px!important;font-size:12px!important}.top .target{font-size:12px!important}
.manager-hero{padding:14px 18px!important;margin-bottom:14px!important}.manager-hero .command-head{align-items:center!important}.manager-hero .command-kicker{font-size:10px!important}.manager-hero .command-title{margin:3px 0 4px!important;font-size:clamp(18px,1.7vw,24px)!important;line-height:1.15!important}.manager-hero .command-copy{margin:0!important;font-size:13px!important;max-width:74ch!important}.manager-hero .manager-card-actions{margin-top:10px!important}.manager-hero .command-button{padding:8px 11px!important;font-size:11px!important}.manager-hero .command-meta{display:none!important}
.week-glance{padding:22px}.week-glance-head{display:flex;align-items:flex-start;justify-content:space-between;gap:16px;margin-bottom:15px}.week-glance-head h2{margin:4px 0 5px;font-size:clamp(23px,2vw,29px)}.week-glance-head p{margin:0;color:var(--muted);font-size:13px;max-width:72ch}.dashboard-summary-row{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:9px;margin:0 0 14px}.dashboard-summary-card{min-width:0;padding:11px 12px;border:1px solid var(--line);border-radius:10px;background:var(--surface)}.dashboard-summary-card span{display:block;color:var(--muted);font-size:9px;font-weight:900;letter-spacing:.09em;text-transform:uppercase}.dashboard-summary-card strong{display:block;margin-top:5px;color:var(--ink);font-size:12px;line-height:1.3;overflow-wrap:anywhere}.dashboard-summary-team strong{font-size:11px}
.week-glance-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px}.week-glance-card{display:flex;flex-direction:column;min-height:188px;padding:18px;border:1px solid var(--line);border-radius:12px;background:var(--surface-2)}.week-glance-grid>.week-glance-card:nth-child(2){border-color:color-mix(in srgb,var(--turf) 72%,var(--line));box-shadow:inset 0 3px 0 color-mix(in srgb,var(--turf) 78%,transparent)}.week-glance-grid>.week-glance-card:nth-child(2) .week-kind::after{content:" ASSISTANT";color:var(--muted)}.week-glance-card .week-kind{font-size:10px;text-transform:uppercase;letter-spacing:.1em;color:var(--turf);font-weight:900}.week-glance-card .week-title{margin:8px 0 0;font-size:18px;line-height:1.25;color:var(--ink);font-weight:800}.week-glance-card .status{align-self:flex-start;margin-top:10px}.week-glance-card .week-action{margin-top:auto;padding-top:12px}.week-glance-card .week-action a{font-size:12px;font-weight:800;color:var(--turf-deep);text-decoration:none}.week-glance-card .week-action a:hover{text-decoration:underline}
.manager-queue{margin-top:14px;padding:22px}.manager-queue-head{display:flex;justify-content:space-between;align-items:flex-start;gap:16px}.full-queue{margin-top:14px;border-top:1px solid var(--line);padding-top:12px}.full-queue>summary{cursor:pointer;color:var(--turf-deep);font-size:12px;font-weight:800}.full-queue .manager-decision-stack{margin-top:12px}
.dashboard-quick-tools{display:flex;align-items:center;justify-content:space-between;gap:18px;margin-top:14px;padding:16px 18px}.dashboard-quick-tools h2{margin:3px 0 3px;font-size:16px}.dashboard-quick-tools p{margin:0;color:var(--muted);font-size:12px}.dashboard-tool-links{display:flex;align-items:center;justify-content:flex-end;gap:7px;flex-wrap:wrap}.dashboard-tool-links a{display:inline-flex;align-items:center;min-height:34px;padding:7px 10px;border:1px solid var(--line);border-radius:999px;background:var(--surface-2);color:var(--muted);text-decoration:none;font-size:11px;font-weight:800}.dashboard-tool-links a:hover,.dashboard-tool-links a:focus-visible{border-color:var(--turf);color:var(--turf-deep)}
@media(max-width:1000px){.dashboard-summary-row{grid-template-columns:repeat(2,minmax(0,1fr))}.dashboard-quick-tools{align-items:flex-start;flex-direction:column}.dashboard-tool-links{justify-content:flex-start}}@media(max-width:820px){.week-glance-grid{grid-template-columns:1fr}.week-glance-card{min-height:0}.manager-queue-head{display:block}}@media(max-width:760px){.top{padding:13px 14px!important}.manager-hero{padding:13px 14px!important}.week-glance{padding:16px 14px}.week-glance-head{display:block}.week-glance-head .status{margin-top:10px}.dashboard-summary-row{grid-template-columns:1fr 1fr}.dashboard-tool-links a{flex:1 1 auto;justify-content:center}}
'@

$newline = [Environment]::NewLine
$dashboardBlock = $dashboardBlock.Insert($cssEnd, $newline + $bf907Css.TrimEnd() + $newline)

$finalReturn = $dashboardBlock.LastIndexOf('    return @"', [System.StringComparison]::Ordinal)
if ($finalReturn -lt 0) {
    throw 'BF-907 BLOCKED: final Dashboard HTML return is missing.'
}

$bf907Prelude = @'
    # BF-907 is presentation-only. It reuses the already-derived BF-819 manager
    # views and attention count; no provider, database, recommendation, or write path is invoked.
    # Normalize the Dashboard header back to Butler's seven manager destinations.
    $bf907RefreshNav = [regex]::new('(?is)<a\b[^>]*>\s*Refresh Butler data\s*</a>')
    $header = $bf907RefreshNav.Replace($header, '', 1)
    if ($header -notmatch 'href="/matchup"') {
        $bf907TeamNav = [regex]::new('(?is)(<a\b[^>]*href="/team"[^>]*>\s*My Team\s*</a>)')
        $header = $bf907TeamNav.Replace($header, '$1' + [Environment]::NewLine + '  <a href="/matchup">Matchup</a>', 1)
    }

    $bf907PrimaryCard = [regex]::new('(?is)^\s*<article class="manager-decision-card primary">.*?</article>\s*')
    $bf907OtherQueueHtml = $bf907PrimaryCard.Replace($managerQueueHtml, '', 1)
    if ([string]::IsNullOrWhiteSpace($bf907OtherQueueHtml)) {
        $bf907OtherQueueHtml = '<div class="empty">No additional priorities need review.</div>'
    }
    $bf907LineupView = $lineupViews[[string]$lineupSignalStatus]
    if ($null -eq $bf907LineupView) {
        $bf907LineupView = $lineupViews["NOT REVIEWED"]
    }

    $bf907WaiverView = $waiverViews[[string]$state]
    if ($null -eq $bf907WaiverView) {
        $bf907WaiverView = @("No waiver decision available", "Butler does not have a current waiver move to act on.", "UNAVAILABLE", "warn", "/waivers", "Waiver Board")
    }

    $bf907GlanceItems = @(
        [pscustomobject]@{
            Kind = "Matchup"
            Title = "Review this week's matchup"
            Status = "VIEW MATCHUP"
            StatusClass = "done"
            ActionHref = "/matchup"
            ActionLabel = "Open Weekly Matchup"
        },
        [pscustomobject]@{
            Kind = "Lineup"
            Title = [string]$bf907LineupView[0]
            Status = [string]$bf907LineupView[2]
            StatusClass = [string]$bf907LineupView[3]
            ActionHref = [string]$bf907LineupView[4]
            ActionLabel = [string]$bf907LineupView[5]
        },
        [pscustomobject]@{
            Kind = "Waivers"
            Title = [string]$bf907WaiverView[0]
            Status = [string]$bf907WaiverView[2]
            StatusClass = [string]$bf907WaiverView[3]
            ActionHref = [string]$bf907WaiverView[4]
            ActionLabel = [string]$bf907WaiverView[5]
        }
    )

    $bf907GlanceCardList = New-Object System.Collections.Generic.List[string]
    foreach ($bf907Item in $bf907GlanceItems) {
        $bf907GlanceCardList.Add(@"
<article class="week-glance-card"><div class="week-kind">$(ConvertTo-HtmlText $bf907Item.Kind)</div><div class="week-title">$(ConvertTo-HtmlText $bf907Item.Title)</div><div class="status $($bf907Item.StatusClass)">$(ConvertTo-HtmlText $bf907Item.Status)</div><div class="week-action"><a href="$(ConvertTo-HtmlText $bf907Item.ActionHref)">$(ConvertTo-HtmlText $bf907Item.ActionLabel) &rarr;</a></div></article>
"@)
    }
    $bf907GlanceCards = $bf907GlanceCardList -join ""

    $bf907AttentionText = if ($managerAttentionCount -eq 0) {
        "ON TRACK"
    }
    elseif ($managerAttentionCount -eq 1) {
        $bf907AttentionKind = if (@($managerAttentionItems).Count -eq 1) { [string]$managerAttentionItems[0].Kind } else { "" }
        if ([string]::IsNullOrWhiteSpace($bf907AttentionKind)) { "1 NEEDS ATTENTION" } else { "$($bf907AttentionKind.ToUpperInvariant()) NEEDS ATTENTION" }
    }
    else {
        "$managerAttentionCount NEED ATTENTION"
    }
    $bf907AttentionClass = if ($managerAttentionCount -gt 0) { "warn" } else { "good" }

    $bf1020SummaryHtml = @"
<div class="dashboard-summary-row"><div class="dashboard-summary-card"><span>Attention</span><strong>$(ConvertTo-HtmlText $bf907AttentionText)</strong></div><div class="dashboard-summary-card"><span>Start/Sit</span><strong>$(ConvertTo-HtmlText ([string]$bf907LineupView[2]))</strong></div><div class="dashboard-summary-card"><span>Waivers</span><strong>$(ConvertTo-HtmlText ([string]$bf907WaiverView[2]))</strong></div><div class="dashboard-summary-card dashboard-summary-team"><span>Roster</span><strong>$(ConvertTo-HtmlText $target)</strong></div></div>
"@

    $bf1020QuickToolsHtml = @"
<section class="panel dashboard-quick-tools"><div><div class="eyebrow">Quick tools</div><h2>More manager tools</h2><p>Use these after your weekly lineup, matchup, and waiver decisions are covered.</p></div><div class="dashboard-tool-links"><a href="/trade">Trade Analyzer</a><a href="/players">Player Search</a><a href="/compare">Player Compare</a><a href="/league">League</a></div></section>
"@

    $bf907WeekGlanceHtml = @"
<section class="panel week-glance"><div class="week-glance-head"><div><div class="eyebrow">Week at a glance</div><h2>Your fantasy week in one view</h2><p>Start with lineup, matchup, and waivers. Butler keeps secondary tools out of the way until you need them.</p></div><div class="status $bf907AttentionClass">$(ConvertTo-HtmlText $bf907AttentionText)</div></div>$bf1020SummaryHtml<div class="week-glance-grid">$bf907GlanceCards</div></section>
"@

'@

$dashboardBlock = $dashboardBlock.Insert($finalReturn, $bf907Prelude.TrimEnd() + $newline + $newline)

$queueMarker = '<section class="panel manager-queue">'
$queueMatches = [regex]::Matches($dashboardBlock, [regex]::Escape($queueMarker)).Count
if ($queueMatches -ne 1) {
    throw "BF-907 BLOCKED: manager queue insertion point expected one match, found $queueMatches."
}
$dashboardBlock = $dashboardBlock.Replace($queueMarker, '$bf907WeekGlanceHtml' + $newline + $queueMarker)

$queueBodyOld = '<section class="panel manager-queue"><div class="manager-queue-head"><div><div class="eyebrow">Butler''s priorities</div><h2>Your decision queue</h2><p>Act on what needs attention; leave completed and on-demand states alone.</p></div></div><div class="manager-decision-stack">$managerQueueHtml</div>'
$queueBodyNew = '<section class="panel manager-queue"><div class="manager-queue-head"><div><div class="eyebrow">After Priority 01</div><h2>Other priorities</h2><p>Your top decision is handled above. These are the remaining lineup, waiver, or trade states worth keeping in view.</p></div></div><div class="manager-decision-stack">$bf907OtherQueueHtml</div><details class="full-queue"><summary>View full decision queue</summary><div class="manager-decision-stack">$managerQueueHtml</div></details>'
$queueBodyMatches = [regex]::Matches($dashboardBlock, [regex]::Escape($queueBodyOld)).Count
if ($queueBodyMatches -ne 1) {
    throw "BF-907 BLOCKED: decision queue refinement expected one match, found $queueBodyMatches."
}
$dashboardBlock = $dashboardBlock.Replace($queueBodyOld, $queueBodyNew)

$footerMarker = '<div class="manager-readonly"><strong>Butler is read only.</strong> Recommendations stay reviewable and traceable; roster actions remain yours.</div>'
$footerMatches = [regex]::Matches($dashboardBlock, [regex]::Escape($footerMarker)).Count
if ($footerMatches -ne 1) {
    throw "BF-1020 BLOCKED: lower Quick Tools insertion point expected one match, found $footerMatches."
}
$dashboardBlock = $dashboardBlock.Replace($footerMarker, '$bf1020QuickToolsHtml' + $newline + $footerMarker)

$text = $text.Substring(0, $dashboardStart) + $dashboardBlock + $text.Substring($dashboardEnd)

foreach ($required in @(
    'BF-907 Decision Center',
    'Week at a glance',
    'Your fantasy week in one view',
    '$bf907GlanceItems',
    'Kind = "Lineup"',
    'Kind = "Waivers"',
    'Kind = "Matchup"',
    'href="/trade">Trade Analyzer</a>',
    'href="/players">Player Search</a>',
    'href="/compare">Player Compare</a>',
    'href="/league">League</a>',
    '$bf907WeekGlanceHtml',
    '$bf1020SummaryHtml',
    '$bf1020QuickToolsHtml',
    'href="/matchup">Matchup</a>',
    'After Priority 01',
    'Other priorities',
    'View full decision queue',
    '$bf907OtherQueueHtml',
    '<section class="panel manager-queue">'
)) {
    if ($text.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-907 BLOCKED: required Decision Center marker is missing: $required"
    }
}

[System.IO.File]::WriteAllText($DashboardPath, $text, [System.Text.UTF8Encoding]::new($false))

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($DashboardPath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $parseSummary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-907 BLOCKED: generated Dashboard failed PowerShell parse: $parseSummary"
}

$bf907InstalledSurface = $bf907Css + [Environment]::NewLine + $bf907Prelude
if ($bf907InstalledSurface -match 'Invoke-RestMethod|Invoke-WebRequest|Invoke-ButlerReadOnly|Invoke-Bf742DashboardWorkerRead|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
    throw 'BF-907 BLOCKED: Decision Center presentation introduced an operational read/write marker.'
}

Write-Host 'BF-907 Dashboard Decision Center applied.'
