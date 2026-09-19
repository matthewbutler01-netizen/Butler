param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-872 BLOCKED: staged Butler core not found at $CorePath"
}

function Replace-ExactlyOnce {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Old,
        [Parameter(Mandatory = $true)][string]$New,
        [Parameter(Mandatory = $true)][string]$Contract
    )

    $count = [regex]::Matches($Text, [regex]::Escape($Old)).Count
    if ($count -ne 1) {
        throw "BF-872 BLOCKED: $Contract expected one match, found $count."
    }
    return $Text.Replace($Old, $New)
}

$core = [System.IO.File]::ReadAllText($CorePath)
$teamStart = $core.IndexOf('function ConvertTo-TeamHtml {', [System.StringComparison]::Ordinal)
$teamEnd = $core.IndexOf('function Add-LeagueNavigation {', $teamStart, [System.StringComparison]::Ordinal)
if ($teamStart -lt 0 -or $teamEnd -le $teamStart) {
    throw 'BF-872 BLOCKED: My Team renderer function boundary is missing.'
}
$teamBlock = $core.Substring($teamStart, $teamEnd - $teamStart)

$metricsOld = '<div class="manager-metrics"><div class="metric-card"><span class="metric-label">Franchise rank</span><span class="metric-value">$(ConvertTo-HtmlText $rankText)</span><div class="meta">$(ConvertTo-HtmlText $rankEvidence)</div></div><div class="metric-card"><span class="metric-label">Roster strength</span><span class="metric-value">$(ConvertTo-HtmlText $strengthTier)</span><div class="meta">$(ConvertTo-HtmlText $strengthEvidence)</div></div><div class="metric-card"><span class="metric-label">Team direction</span><span class="metric-value">$(ConvertTo-HtmlText $postureText)</span><div class="meta">$(ConvertTo-HtmlText $postureEvidence)</div></div><div class="metric-card"><span class="metric-label">Draft capital</span><span class="metric-value">$(ConvertTo-HtmlText $capitalTier)</span><div class="meta">$(ConvertTo-HtmlText $capitalEvidence)</div></div></div><details><summary>Roster intelligence evidence</summary><div class="technical">Franchise value $(ConvertTo-HtmlText $Context.Total) &middot; player value $(ConvertTo-HtmlText $Context.Players) &middot; pick value $(ConvertTo-HtmlText $Context.Picks) &middot; franchise coverage $(ConvertTo-HtmlText $Context.Coverage)% &middot; movement $(ConvertTo-HtmlText $Context.Movement) &middot; movement coverage $(ConvertTo-HtmlText $Context.MovementCoverage)%</div></details>'

$metricsNew = @'
<div class="manager-metrics"><div class="metric-card"><span class="metric-label">Franchise rank</span><span class="metric-value">$(ConvertTo-HtmlText $rankText)</span></div><div class="metric-card"><span class="metric-label">Roster strength</span><span class="metric-value">$(ConvertTo-HtmlText $strengthTier)</span></div><div class="metric-card"><span class="metric-label">Team direction</span><span class="metric-value">$(ConvertTo-HtmlText $postureText)</span></div><div class="metric-card"><span class="metric-label">Draft capital</span><span class="metric-value">$(ConvertTo-HtmlText $capitalTier)</span></div></div>
<details class="roster-intelligence-details"><summary>How Butler reads this roster</summary><div class="roster-intelligence-list"><div><strong>Franchise rank</strong><span>$(ConvertTo-HtmlText $rankEvidence)</span></div><div><strong>Roster strength</strong><span>$(ConvertTo-HtmlText $strengthEvidence)</span></div><div><strong>Team direction</strong><span>$(ConvertTo-HtmlText $postureEvidence)</span></div><div><strong>Draft capital</strong><span>$(ConvertTo-HtmlText $capitalEvidence)</span></div></div><div class="technical">Franchise value $(ConvertTo-HtmlText $Context.Total) &middot; player value $(ConvertTo-HtmlText $Context.Players) &middot; pick value $(ConvertTo-HtmlText $Context.Picks) &middot; franchise coverage $(ConvertTo-HtmlText $Context.Coverage)% &middot; movement $(ConvertTo-HtmlText $Context.Movement) &middot; movement coverage $(ConvertTo-HtmlText $Context.MovementCoverage)%</div></details>
'@
$teamBlock = Replace-ExactlyOnce -Text $teamBlock -Old $metricsOld -New $metricsNew.TrimEnd() -Contract 'concise roster-intelligence metrics'

$heroOld = '<div class="stats"><div class="stat"><strong>Franchise rank</strong><span>$(ConvertTo-HtmlText $rankText)</span></div><div class="stat"><strong>Total franchise value</strong><span>$(ConvertTo-HtmlText $Context.Total)</span></div><div class="stat"><strong>Roster players</strong><span>$(ConvertTo-HtmlText $Roster.TotalPlayers)</span></div></div></section>'
$heroNew = @'
<div class="stats"><div class="stat"><strong>Franchise rank</strong><span>$(ConvertTo-HtmlText $rankText)</span></div><div class="stat"><strong>Total franchise value</strong><span>$(ConvertTo-HtmlText $Context.Total)</span></div><div class="stat"><strong>Roster players</strong><span>$(ConvertTo-HtmlText $Roster.TotalPlayers)</span></div></div><div class="button-row team-primary-actions"><a class="btn btn-primary" href="/matchup">Review Matchup</a><a class="btn btn-secondary" href="/matchup/autofill">Review Lineup</a></div><p class="meta team-action-note">Lineup projections are requested only after you choose Review Lineup. My Team remains passive and read only.</p></section>
'@
$teamBlock = Replace-ExactlyOnce -Text $teamBlock -Old $heroOld -New $heroNew.TrimEnd() -Contract 'My Team manager actions'

$core = $core.Substring(0, $teamStart) + $teamBlock + $core.Substring($teamEnd)

$cssStart = $core.IndexOf('function Get-AppCss {', [System.StringComparison]::Ordinal)
$cssEnd = $core.IndexOf('function Get-AppNav {', $cssStart, [System.StringComparison]::Ordinal)
if ($cssStart -lt 0 -or $cssEnd -le $cssStart) {
    throw 'BF-872 BLOCKED: manager CSS function boundary is missing.'
}
$cssBlock = $core.Substring($cssStart, $cssEnd - $cssStart)
$cssTerminator = $cssBlock.LastIndexOf("`n'@", [System.StringComparison]::Ordinal)
if ($cssTerminator -lt 0) {
    throw 'BF-872 BLOCKED: manager CSS terminator is missing.'
}
$polishCss = @'
/* BF-872 My Team at-a-glance polish. */
.team-primary-actions{margin-top:16px}.team-action-note{margin:9px 0 0}.roster-intelligence-details{margin-top:14px}.roster-intelligence-list{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;margin:12px 0}.roster-intelligence-list>div{padding:12px 13px;border:1px solid var(--line);border-radius:9px;background:var(--surface-2)}.roster-intelligence-list strong{display:block;font-size:11px;color:var(--ink)}.roster-intelligence-list span{display:block;margin-top:4px;font-size:12px;line-height:1.5;color:var(--muted)}@media(max-width:760px){.team-primary-actions .btn{width:100%;text-align:center}.roster-intelligence-list{grid-template-columns:1fr}}
'@
$cssBlock = $cssBlock.Substring(0, $cssTerminator) + "`n" + $polishCss.TrimEnd() + $cssBlock.Substring($cssTerminator)
$core = $core.Substring(0, $cssStart) + $cssBlock + $core.Substring($cssEnd)

foreach ($required in @(
    'BF-872 My Team at-a-glance polish',
    'How Butler reads this roster',
    'href="/matchup">Review Matchup</a>',
    'href="/matchup/autofill">Review Lineup</a>',
    'Lineup projections are requested only after you choose Review Lineup.',
    '$(ConvertTo-HtmlText $rankEvidence)',
    '$(ConvertTo-HtmlText $strengthEvidence)',
    '$(ConvertTo-HtmlText $postureEvidence)',
    '$(ConvertTo-HtmlText $capitalEvidence)'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-872 BLOCKED: required My Team polish marker is missing: $required"
    }
}

$installedTeamEnd = $core.IndexOf('function Add-LeagueNavigation {', $teamStart, [System.StringComparison]::Ordinal)
$installedTeam = $core.Substring($teamStart, $installedTeamEnd - $teamStart)
if ($installedTeam -match 'Invoke-RestMethod|Invoke-WebRequest|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
    throw 'BF-872 BLOCKED: My Team polish introduced provider, optimizer, FAAB, or write behavior.'
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($CorePath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $parseSummary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-872 BLOCKED: generated staged core failed PowerShell parse: $parseSummary"
}

Write-Host 'BF-872 My Team at-a-glance polish applied.'
