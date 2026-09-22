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
/* BF-907 Decision Center: compact week summary above the full governed decision queue. */
.week-glance{padding:20px}.week-glance-head{display:flex;align-items:flex-start;justify-content:space-between;gap:16px;margin-bottom:14px}.week-glance-head h2{margin:4px 0 4px}.week-glance-head p{margin:0;color:var(--muted);font-size:13px;max-width:72ch}.week-glance-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px}.week-glance-card{display:flex;flex-direction:column;min-height:154px;padding:15px;border:1px solid var(--line);border-radius:12px;background:var(--surface-2)}.week-glance-card .week-kind{font-size:10px;text-transform:uppercase;letter-spacing:.1em;color:var(--turf);font-weight:900}.week-glance-card .week-title{margin:8px 0 0;font-size:16px;line-height:1.25;color:var(--ink);font-weight:800}.week-glance-card .status{align-self:flex-start;margin-top:10px}.week-glance-card .week-action{margin-top:auto;padding-top:14px}.week-glance-card .week-action a{font-size:12px;font-weight:800;color:var(--turf-deep);text-decoration:none}.week-glance-card .week-action a:hover{text-decoration:underline}.week-tools{display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin-top:13px;padding-top:13px;border-top:1px solid var(--line)}.week-tools strong{font-size:10px;text-transform:uppercase;letter-spacing:.09em;color:var(--muted);margin-right:2px}.week-tool{display:inline-flex;align-items:center;text-decoration:none;padding:6px 9px;border:1px solid var(--line);border-radius:999px;background:var(--surface);color:var(--muted);font-size:11px;font-weight:800}.week-tool:hover{border-color:var(--turf);color:var(--turf-deep)}.manager-queue{margin-top:0}.manager-queue-head{display:flex;justify-content:space-between;align-items:flex-start;gap:16px}.manager-queue-head:after{content:'Full decision queue';display:inline-flex;padding:5px 9px;border:1px solid var(--line);border-radius:999px;background:var(--surface-2);color:var(--muted);font-size:10px;font-weight:800;white-space:nowrap}@media(max-width:820px){.week-glance-grid{grid-template-columns:1fr}.week-glance-card{min-height:0}.manager-queue-head{display:block}.manager-queue-head:after{margin-top:10px}}@media(max-width:760px){.week-glance{padding:15px 14px}.week-glance-head{display:block}.week-glance-head .status{margin-top:10px}.week-tools{gap:6px}.week-tool{flex:1 1 auto;justify-content:center}}
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
        },
        [pscustomobject]@{
            Kind = "Trade"
            Title = "Trade analysis is ready when you are"
            Status = "ON DEMAND"
            StatusClass = "done"
            ActionHref = "/trade"
            ActionLabel = "Open Trade Analyzer"
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
        "1 NEEDS ATTENTION"
    }
    else {
        "$managerAttentionCount NEED ATTENTION"
    }
    $bf907AttentionClass = if ($managerAttentionCount -gt 0) { "warn" } else { "good" }

    $bf907WeekGlanceHtml = @"
<section class="panel week-glance"><div class="week-glance-head"><div><div class="eyebrow">Week at a glance</div><h2>Your fantasy week in one view</h2><p>Lineup, waiver, and trade state from Butler's existing decision frame. Start with Priority 01 above, then use this summary to see what else deserves attention.</p></div><div class="status $bf907AttentionClass">$(ConvertTo-HtmlText $bf907AttentionText)</div></div><div class="week-glance-grid">$bf907GlanceCards</div><div class="week-tools"><strong>Quick tools</strong><a class="week-tool" href="/matchup">Weekly Matchup</a><a class="week-tool" href="/players">Player Search</a><a class="week-tool" href="/compare">Player Compare</a><a class="week-tool" href="/league">League</a></div></section>
"@

'@

$dashboardBlock = $dashboardBlock.Insert($finalReturn, $bf907Prelude.TrimEnd() + $newline + $newline)

$queueMarker = '<section class="panel manager-queue">'
$queueMatches = [regex]::Matches($dashboardBlock, [regex]::Escape($queueMarker)).Count
if ($queueMatches -ne 1) {
    throw "BF-907 BLOCKED: manager queue insertion point expected one match, found $queueMatches."
}
$dashboardBlock = $dashboardBlock.Replace($queueMarker, '$bf907WeekGlanceHtml' + $newline + $queueMarker)

$text = $text.Substring(0, $dashboardStart) + $dashboardBlock + $text.Substring($dashboardEnd)

foreach ($required in @(
    'BF-907 Decision Center',
    'Week at a glance',
    'Your fantasy week in one view',
    '$bf907GlanceItems',
    'Kind = "Lineup"',
    'Kind = "Waivers"',
    'Kind = "Trade"',
    'href="/matchup">Weekly Matchup</a>',
    'href="/players">Player Search</a>',
    'href="/compare">Player Compare</a>',
    'href="/league">League</a>',
    '$bf907WeekGlanceHtml',
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

$installed = [System.IO.File]::ReadAllText($DashboardPath)
if ($installed -match 'Invoke-RestMethod|Invoke-WebRequest|Invoke-ButlerReadOnly|Invoke-Bf742DashboardWorkerRead|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
    throw 'BF-907 BLOCKED: Decision Center presentation introduced an operational read/write marker.'
}

Write-Host 'BF-907 Dashboard Decision Center applied.'
