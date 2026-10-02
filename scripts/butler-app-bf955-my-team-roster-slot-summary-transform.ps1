param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-955 BLOCKED: staged Butler core not found at $CorePath"
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
        throw "BF-955 BLOCKED: $Contract expected one match, found $count."
    }
    return $Text.Replace($Old, $New)
}

$core = [System.IO.File]::ReadAllText($CorePath)
foreach ($required in @(
    'Lineup and depth at a glance',
    '$corePositionInventoryHtml',
    '$Roster.StarterCount',
    '$Roster.BenchCount'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-955 BLOCKED: required My Team roster capability is missing: $required"
    }
}

$teamStart = $core.IndexOf('function ConvertTo-TeamHtml {', [System.StringComparison]::Ordinal)
$teamEnd = $core.IndexOf('function Add-LeagueNavigation {', $teamStart, [System.StringComparison]::Ordinal)
if ($teamStart -lt 0 -or $teamEnd -le $teamStart) {
    throw 'BF-955 BLOCKED: My Team renderer boundary is missing.'
}
$team = $core.Substring($teamStart, $teamEnd - $teamStart)

$autoFillAnchor = '    $autoFillHtml = ConvertTo-AutoFillHtml -AutoFill $AutoFill'
$slotPrelude = @'
    $reserveTaxiCount = [int]$Roster.ReserveCount + [int]$Roster.TaxiCount
    $rosterStateInventoryHtml = @"
<div class="roster-state-summary" aria-label="Roster slot distribution">
  <div class="roster-state-heading"><strong>Roster slots</strong><span>Current Sleeper assignment</span></div>
  <div class="roster-state-item"><span>Starters</span><strong>$(ConvertTo-HtmlText $Roster.StarterCount)</strong></div>
  <div class="roster-state-item"><span>Bench</span><strong>$(ConvertTo-HtmlText $Roster.BenchCount)</strong></div>
  <div class="roster-state-item"><span>Reserve / taxi</span><strong>$(ConvertTo-HtmlText $reserveTaxiCount)</strong><small>Reserve $(ConvertTo-HtmlText $Roster.ReserveCount) &middot; Taxi $(ConvertTo-HtmlText $Roster.TaxiCount)</small></div>
</div>
"@

'@
$team = Replace-ExactlyOnce -Text $team -Old $autoFillAnchor -New ($slotPrelude + $autoFillAnchor).TrimEnd() -Contract 'roster slot summary prelude'

$inventoryUsage = '</div></div>$corePositionInventoryHtml'
$inventoryUsageNew = '</div></div>$corePositionInventoryHtml$rosterStateInventoryHtml'
$team = Replace-ExactlyOnce -Text $team -Old $inventoryUsage -New $inventoryUsageNew -Contract 'roster slot summary placement'

$core = $core.Substring(0, $teamStart) + $team + $core.Substring($teamEnd)

$cssStart = $core.IndexOf('function Get-AppCss {', [System.StringComparison]::Ordinal)
$cssEnd = $core.IndexOf('function Get-AppNav {', $cssStart, [System.StringComparison]::Ordinal)
if ($cssStart -lt 0 -or $cssEnd -le $cssStart) {
    throw 'BF-955 BLOCKED: manager CSS boundary is missing.'
}
$cssBlock = $core.Substring($cssStart, $cssEnd - $cssStart)
$cssTerminator = $cssBlock.LastIndexOf("'@", [System.StringComparison]::Ordinal)
if ($cssTerminator -lt 0) {
    throw 'BF-955 BLOCKED: manager CSS terminator is missing.'
}

$bf955Css = @'
/* BF-955 My Team roster slot summary. */
.roster-state-summary{display:grid;grid-template-columns:minmax(150px,1.2fr) repeat(3,minmax(110px,1fr));gap:8px;align-items:stretch;margin-top:10px}.roster-state-heading,.roster-state-item{padding:11px 13px;border:1px solid var(--line);border-radius:9px;background:var(--surface)}.roster-state-heading{display:flex;flex-direction:column;justify-content:center}.roster-state-heading strong{font-size:12px;color:var(--ink)}.roster-state-heading span{margin-top:3px;font-size:11px;color:var(--muted)}.roster-state-item{display:grid;grid-template-columns:1fr auto;gap:2px 10px;align-items:center}.roster-state-item>span{font-size:10px;font-weight:900;letter-spacing:.05em;text-transform:uppercase;color:var(--muted)}.roster-state-item>strong{font-family:var(--font-display);font-size:20px;color:var(--ink)}.roster-state-item>small{grid-column:1/-1;font-size:10px;color:var(--muted)}@media(max-width:760px){.roster-state-summary{grid-template-columns:repeat(2,minmax(0,1fr))}.roster-state-heading{grid-column:1/-1}}@media(max-width:460px){.roster-state-summary{grid-template-columns:1fr}.roster-state-heading{grid-column:auto}}
'@

$cssBlock = $cssBlock.Substring(0, $cssTerminator) + [Environment]::NewLine + $bf955Css.TrimEnd() + [Environment]::NewLine + $cssBlock.Substring($cssTerminator)
$core = $core.Substring(0, $cssStart) + $cssBlock + $core.Substring($cssEnd)

foreach ($required in @(
    'BF-955 My Team roster slot summary',
    'aria-label="Roster slot distribution"',
    '<strong>Roster slots</strong>',
    '<span>Starters</span>',
    '<span>Bench</span>',
    '<span>Reserve / taxi</span>',
    '$reserveTaxiCount = [int]$Roster.ReserveCount + [int]$Roster.TaxiCount',
    'Reserve $(ConvertTo-HtmlText $Roster.ReserveCount)',
    'Taxi $(ConvertTo-HtmlText $Roster.TaxiCount)'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-955 BLOCKED: required roster slot summary marker is missing: $required"
    }
}

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseInput($core, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $summary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-955 BLOCKED: generated staged core failed PowerShell parse: $summary"
}

$bf955InstalledSurface = $slotPrelude + [Environment]::NewLine + $inventoryUsageNew + [Environment]::NewLine + $bf955Css
if ($bf955InstalledSurface -match 'Invoke-RestMethod|Invoke-WebRequest|Invoke-ButlerReadOnly|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
    throw 'BF-955 BLOCKED: roster slot summary itself introduced provider, backend-read, optimizer, or write behavior.'
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))
Write-Host 'BF-955 My Team roster slot summary applied.'

$bf956Transform = Join-Path $PSScriptRoot 'butler-app-bf956-my-team-roster-jump-transform.ps1'
if (-not (Test-Path -LiteralPath $bf956Transform -PathType Leaf)) {
    throw "BF-956 BLOCKED: My Team roster jump transform not found at $bf956Transform"
}
& $bf956Transform -CorePath $CorePath
