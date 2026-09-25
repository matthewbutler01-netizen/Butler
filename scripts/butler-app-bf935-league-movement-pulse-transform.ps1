param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-935 BLOCKED: staged Butler core not found at $CorePath"
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
        throw "BF-935 BLOCKED: $Contract expected one match, found $count."
    }
    return $Text.Replace($Old, $New)
}

$core = [System.IO.File]::ReadAllText($CorePath)
$leagueStart = $core.IndexOf('function ConvertTo-LeagueHtml {', [System.StringComparison]::Ordinal)
$leagueEnd = $core.IndexOf('function New-AutoFillIdleView {', $leagueStart, [System.StringComparison]::Ordinal)
if ($leagueStart -lt 0 -or $leagueEnd -le $leagueStart) {
    throw 'BF-935 BLOCKED: League Hub renderer boundary is missing.'
}
$leagueBlock = $core.Substring($leagueStart, $leagueEnd - $leagueStart)

$movementHtmlAnchor = @'
    $movementHtml = ""
'@

$movementPulsePrelude = @'
    $movementRiserCount = 0
    $movementFallerCount = 0
    if ($View.MovementAvailable) {
        foreach ($movementItem in @($View.Movers)) {
            $movementText = ([string]$movementItem).TrimStart()
            if ($movementText.StartsWith('+', [System.StringComparison]::Ordinal)) {
                $movementRiserCount++
            }
            elseif ($movementText.StartsWith('-', [System.StringComparison]::Ordinal)) {
                $movementFallerCount++
            }
        }
    }

    $movementTrackedText = if ($View.MovementAvailable) { [string](@($View.Movers).Count) } else { "Unavailable" }
    $movementRiserText = if ($View.MovementAvailable) { [string]$movementRiserCount } else { "Unavailable" }
    $movementFallerText = if ($View.MovementAvailable) { [string]$movementFallerCount } else { "Unavailable" }
    $movementPulseCoverageText = if ($View.MovementAvailable) { "$($View.MovementCoverage)%" } else { "Unavailable" }

'@

$leagueBlock = Replace-ExactlyOnce -Text $leagueBlock -Old $movementHtmlAnchor.TrimEnd() -New ($movementPulsePrelude + $movementHtmlAnchor).TrimEnd() -Contract 'League movement pulse derivation'

$movementSectionOld = @'
<section class="panel"><div class="section-head"><div><div class="eyebrow">Comparable movement</div><h2>What changed between value snapshots</h2><p class="lede">$movementSummary</p></div></div><div class="league-movement-list">$movementHtml</div><details><summary>Movement boundary</summary><div class="technical">Butler shows movement only when comparable provider snapshots exist. Missing comparison evidence stays unavailable rather than being inferred.</div></details></section>
'@

$movementSectionNew = @'
<section class="panel"><div class="section-head"><div><div class="eyebrow">League pulse</div><h2>Movement at a glance</h2><p class="lede">$movementSummary</p></div></div><div class="league-pulse-grid"><div class="league-pulse-card"><span>Risers</span><strong>$(ConvertTo-HtmlText $movementRiserText)</strong></div><div class="league-pulse-card"><span>Fallers</span><strong>$(ConvertTo-HtmlText $movementFallerText)</strong></div><div class="league-pulse-card"><span>Tracked movers</span><strong>$(ConvertTo-HtmlText $movementTrackedText)</strong></div><div class="league-pulse-card"><span>Coverage</span><strong>$(ConvertTo-HtmlText $movementPulseCoverageText)</strong></div></div><p class="meta league-pulse-note">Descriptive movement only. Butler is not ranking trade targets or recommending action from these counts.</p><details class="league-movement-details"><summary>View movement details</summary><div class="league-movement-list">$movementHtml</div></details><details><summary>Movement boundary</summary><div class="technical">Butler shows movement only when comparable provider snapshots exist. Missing comparison evidence stays unavailable rather than being inferred.</div></details></section>
'@

$leagueBlock = Replace-ExactlyOnce -Text $leagueBlock -Old $movementSectionOld.TrimEnd() -New $movementSectionNew.TrimEnd() -Contract 'League movement progressive disclosure'

$core = $core.Substring(0, $leagueStart) + $leagueBlock + $core.Substring($leagueEnd)

$cssStart = $core.IndexOf('function Get-AppCss {', [System.StringComparison]::Ordinal)
$cssEnd = $core.IndexOf('function Get-AppNav {', $cssStart, [System.StringComparison]::Ordinal)
if ($cssStart -lt 0 -or $cssEnd -le $cssStart) {
    throw 'BF-935 BLOCKED: manager CSS boundary is missing.'
}
$cssBlock = $core.Substring($cssStart, $cssEnd - $cssStart)
$cssTerminator = $cssBlock.LastIndexOf("'@", [System.StringComparison]::Ordinal)
if ($cssTerminator -lt 0) {
    throw 'BF-935 BLOCKED: manager CSS terminator is missing.'
}

$bf935Css = @'
/* BF-935 League Hub movement pulse. */
.league-pulse-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:8px;margin-top:16px}.league-pulse-card{padding:12px 13px;border:1px solid var(--line);border-radius:9px;background:var(--surface-2)}.league-pulse-card span{display:block;font-size:10px;font-weight:800;color:var(--muted)}.league-pulse-card strong{display:block;margin-top:4px;font-family:var(--font-display);font-size:20px;color:var(--ink)}.league-pulse-note{margin:9px 0 0}.league-movement-details{margin-top:14px}.league-movement-details .league-movement-list{margin-top:12px}@media(max-width:760px){.league-pulse-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}
'@

$cssBlock = $cssBlock.Substring(0, $cssTerminator) + [Environment]::NewLine + $bf935Css.TrimEnd() + [Environment]::NewLine + $cssBlock.Substring($cssTerminator)
$core = $core.Substring(0, $cssStart) + $cssBlock + $core.Substring($cssEnd)

foreach ($required in @(
    'BF-935 League Hub movement pulse',
    '$movementRiserCount = 0',
    '$movementFallerCount = 0',
    '[string]@($View.Movers).Count',
    'League pulse',
    'Movement at a glance',
    'Risers',
    'Fallers',
    'Tracked movers',
    'View movement details',
    'Descriptive movement only.',
    'href="/franchise?id=$leaderHrefId">Scout franchise</a>',
    'href="/trade?opponent=$leaderHrefId">Open Trade Analyzer</a>'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-935 BLOCKED: required League movement marker is missing: $required"
    }
}

$installedLeagueStart = $core.IndexOf('function ConvertTo-LeagueHtml {', [System.StringComparison]::Ordinal)
$installedLeagueEnd = $core.IndexOf('function New-AutoFillIdleView {', $installedLeagueStart, [System.StringComparison]::Ordinal)
$installedLeague = $core.Substring($installedLeagueStart, $installedLeagueEnd - $installedLeagueStart)
if ($installedLeague -match 'Invoke-RestMethod|Invoke-WebRequest|Invoke-ButlerReadOnly|Invoke-Bf742DashboardWorkerRead|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
    throw 'BF-935 BLOCKED: League movement pulse introduced provider, backend-read, optimizer, or write behavior.'
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($CorePath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $parseSummary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-935 BLOCKED: generated staged core failed PowerShell parse: $parseSummary"
}

Write-Host 'BF-935 League Hub movement pulse applied.'
