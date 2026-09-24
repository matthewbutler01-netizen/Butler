param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-915 BLOCKED: staged Butler core not found at $CorePath"
}

function Replace-ExactlyOnce {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Old,
        [Parameter(Mandatory = $true)][string]$New,
        [Parameter(Mandatory = $true)][string]$Contract
    )

    $matches = [regex]::Matches($Text, [regex]::Escape($Old)).Count
    if ($matches -ne 1) {
        throw "BF-915 BLOCKED: $Contract expected one match, found $matches."
    }
    return $Text.Replace($Old, $New)
}

$core = [System.IO.File]::ReadAllText($CorePath)

$playerHubFunctions = @'
function Add-PlayerHubPresentation {
    param(
        [Parameter(Mandatory = $true)][string]$Html,
        [Parameter(Mandatory = $true)]$View
    )

    $ageText = if ([string]$View.Age -ceq 'UNAVAILABLE') { 'Unavailable' } else { [string]$View.Age }
    $gamesText = if ([string]$View.GamesPlayed -ceq 'UNAVAILABLE') { 'Unavailable' } else { [string]$View.GamesPlayed }
    $hrefId = [System.Uri]::EscapeDataString([string]$View.PlayerId)

    $playerHub = @"
<section class="panel"><div class="section-head"><div><div class="eyebrow">Player snapshot</div><h2>Player hub</h2><p class="lede">Start with the roster context, then jump directly to the Butler workflow for the decision you are making.</p></div></div><div class="manager-metrics"><div class="metric-card"><span class="metric-label">Position</span><span class="metric-value">$(ConvertTo-HtmlText $View.Position)</span></div><div class="metric-card"><span class="metric-label">Roster slot</span><span class="metric-value">$(ConvertTo-HtmlText $View.RosterSlot)</span></div><div class="metric-card"><span class="metric-label">Age</span><span class="metric-value">$(ConvertTo-HtmlText $ageText)</span></div><div class="metric-card"><span class="metric-label">Games</span><span class="metric-value">$(ConvertTo-HtmlText $gamesText)</span></div></div></section>
<section class="panel"><div class="section-head"><div><div class="eyebrow">Decision shortcuts</div><h2>What do you want to decide?</h2><p class="lede">Open the existing Butler workflow that matches your question. This profile stays neutral and does not create a recommendation by itself.</p></div></div><div class="button-row"><a class="btn btn-primary" href="/matchup">Review Matchup</a><a class="btn btn-secondary" href="/compare?left=$hrefId">Compare this player</a><a class="btn btn-secondary" href="/trade">Open Trade Analyzer</a><a class="btn btn-secondary" href="/waivers">Check Waiver Board</a></div></section>
"@

    $anchor = '<section class="panel"><div class="section-head"><div><div class="eyebrow">Age context</div>'
    $matches = [regex]::Matches($Html, [regex]::Escape($anchor)).Count
    if ($matches -ne 1) {
        throw "BF-915 BLOCKED: Player Hub insertion expected one Age context anchor, found $matches."
    }

    return $Html.Replace($anchor, $playerHub.TrimEnd() + [Environment]::NewLine + $anchor)
}
'@

$insertMarker = 'function Get-PlayerSearchRequestQuery {'
$insertIndex = $core.IndexOf($insertMarker, [System.StringComparison]::Ordinal)
if ($insertIndex -lt 0) {
    throw 'BF-915 BLOCKED: Player Hub function insertion marker is missing.'
}
$core = $core.Insert($insertIndex, $playerHubFunctions.TrimEnd() + [Environment]::NewLine + [Environment]::NewLine)

$detailActionOld = '                    $html = Add-PlayerCompareDetailAction -Html $html -PlayerId $playerDetail.PlayerId'
$detailActionNew = @'
                    $html = Add-PlayerCompareDetailAction -Html $html -PlayerId $playerDetail.PlayerId
                    $html = Add-PlayerHubPresentation -Html $html -View $playerDetail
'@
$core = Replace-ExactlyOnce -Text $core -Old $detailActionOld -New $detailActionNew.TrimEnd() -Contract 'Player Detail Player Hub presentation'

foreach ($required in @(
    'function Add-PlayerHubPresentation',
    'Player snapshot',
    '<h2>Player hub</h2>',
    'Decision shortcuts',
    'What do you want to decide?',
    'href="/matchup">Review Matchup</a>',
    'href="/compare?left=$hrefId">Compare this player</a>',
    'href="/trade">Open Trade Analyzer</a>',
    'href="/waivers">Check Waiver Board</a>',
    'This profile stays neutral and does not create a recommendation by itself.',
    'Add-PlayerHubPresentation -Html $html -View $playerDetail'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-915 BLOCKED: required Player Hub marker is missing: $required"
    }
}

$installedStart = $core.IndexOf('function Add-PlayerHubPresentation', [System.StringComparison]::Ordinal)
$installedEnd = $core.IndexOf('function Get-PlayerSearchRequestQuery', $installedStart, [System.StringComparison]::Ordinal)
if ($installedStart -lt 0 -or $installedEnd -le $installedStart) {
    throw 'BF-915 BLOCKED: installed Player Hub function boundary is missing.'
}
$installed = $core.Substring($installedStart, $installedEnd - $installedStart)

foreach ($forbidden in @(
    'Invoke-RestMethod',
    'Invoke-WebRequest',
    'Invoke-ButlerReadOnly',
    'Invoke-Bf742DashboardWorkerRead',
    'https://api.sleeper.app',
    'Method = "POST"',
    'submitTransaction',
    'setFaab',
    'player-score',
    'winner-policy',
    'buy/sell'
)) {
    if ($installed.IndexOf($forbidden, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
        throw "BF-915 BLOCKED: Player Hub introduced provider, backend-read, write, score, or verdict behavior: $forbidden"
    }
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($CorePath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $parseSummary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-915 BLOCKED: generated staged core failed PowerShell parse: $parseSummary"
}

Write-Host 'BF-915 Player Hub applied.'
