param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-957 BLOCKED: staged Butler core not found at $CorePath"
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
        throw "BF-957 BLOCKED: $Contract expected one match, found $count."
    }
    return $Text.Replace($Old, $New)
}

$core = [System.IO.File]::ReadAllText($CorePath)
foreach ($required in @(
    'aria-label="Core position inventory"',
    '<div class="roster-inventory-card"><span>QB</span><strong>$qbRosterCount</strong></div>',
    '<div class="roster-inventory-card"><span>RB</span><strong>$rbRosterCount</strong></div>',
    '<div class="roster-inventory-card"><span>WR</span><strong>$wrRosterCount</strong></div>',
    '<div class="roster-inventory-card"><span>TE</span><strong>$teRosterCount</strong></div>',
    'href="#roster-starters"',
    'href="#roster-bench"',
    'href="#roster-reserve"'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-957 BLOCKED: required My Team inventory capability is missing: $required"
    }
}

$teamStart = $core.IndexOf('function ConvertTo-TeamHtml {', [System.StringComparison]::Ordinal)
$teamEnd = $core.IndexOf('function Add-LeagueNavigation {', $teamStart, [System.StringComparison]::Ordinal)
if ($teamStart -lt 0 -or $teamEnd -le $teamStart) {
    throw 'BF-957 BLOCKED: My Team renderer boundary is missing.'
}
$team = $core.Substring($teamStart, $teamEnd - $teamStart)

$qbOld = '<div class="roster-inventory-card"><span>QB</span><strong>$qbRosterCount</strong></div>'
$qbNew = '<a class="roster-inventory-card roster-position-link" href="/players?q=QB" aria-label="Browse QB players"><span>QB</span><strong>$qbRosterCount</strong><small>Browse</small></a>'
$rbOld = '<div class="roster-inventory-card"><span>RB</span><strong>$rbRosterCount</strong></div>'
$rbNew = '<a class="roster-inventory-card roster-position-link" href="/players?q=RB" aria-label="Browse RB players"><span>RB</span><strong>$rbRosterCount</strong><small>Browse</small></a>'
$wrOld = '<div class="roster-inventory-card"><span>WR</span><strong>$wrRosterCount</strong></div>'
$wrNew = '<a class="roster-inventory-card roster-position-link" href="/players?q=WR" aria-label="Browse WR players"><span>WR</span><strong>$wrRosterCount</strong><small>Browse</small></a>'
$teOld = '<div class="roster-inventory-card"><span>TE</span><strong>$teRosterCount</strong></div>'
$teNew = '<a class="roster-inventory-card roster-position-link" href="/players?q=TE" aria-label="Browse TE players"><span>TE</span><strong>$teRosterCount</strong><small>Browse</small></a>'

$team = Replace-ExactlyOnce -Text $team -Old $qbOld -New $qbNew -Contract 'QB position inventory link'
$team = Replace-ExactlyOnce -Text $team -Old $rbOld -New $rbNew -Contract 'RB position inventory link'
$team = Replace-ExactlyOnce -Text $team -Old $wrOld -New $wrNew -Contract 'WR position inventory link'
$team = Replace-ExactlyOnce -Text $team -Old $teOld -New $teNew -Contract 'TE position inventory link'

$core = $core.Substring(0, $teamStart) + $team + $core.Substring($teamEnd)

$cssStart = $core.IndexOf('function Get-AppCss {', [System.StringComparison]::Ordinal)
$cssEnd = $core.IndexOf('function Get-AppNav {', $cssStart, [System.StringComparison]::Ordinal)
if ($cssStart -lt 0 -or $cssEnd -le $cssStart) {
    throw 'BF-957 BLOCKED: manager CSS boundary is missing.'
}
$cssBlock = $core.Substring($cssStart, $cssEnd - $cssStart)
$cssTerminator = $cssBlock.LastIndexOf("'@", [System.StringComparison]::Ordinal)
if ($cssTerminator -lt 0) {
    throw 'BF-957 BLOCKED: manager CSS terminator is missing.'
}

$bf957Css = @'
/* BF-957 My Team position inventory links. */
a.roster-inventory-card{text-decoration:none;color:inherit;transition:border-color .12s ease,background .12s ease,transform .12s ease}a.roster-inventory-card:hover,a.roster-inventory-card:focus-visible{border-color:var(--turf);background:color-mix(in srgb,var(--turf) 8%,var(--surface-2));outline:none}a.roster-inventory-card:active{transform:translateY(1px)}.roster-position-link{display:grid;grid-template-columns:1fr auto;gap:2px 10px}.roster-position-link small{grid-column:1/-1;font-size:10px;font-weight:800;color:var(--turf-deep)}
'@

$cssBlock = $cssBlock.Substring(0, $cssTerminator) + [Environment]::NewLine + $bf957Css.TrimEnd() + [Environment]::NewLine + $cssBlock.Substring($cssTerminator)
$core = $core.Substring(0, $cssStart) + $cssBlock + $core.Substring($cssEnd)

foreach ($required in @(
    'BF-957 My Team position inventory links',
    'href="/players?q=QB"',
    'href="/players?q=RB"',
    'href="/players?q=WR"',
    'href="/players?q=TE"',
    'aria-label="Browse QB players"',
    'aria-label="Browse RB players"',
    'aria-label="Browse WR players"',
    'aria-label="Browse TE players"',
    '.roster-position-link{'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-957 BLOCKED: required position inventory link marker is missing: $required"
    }
}

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseInput($core, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $summary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-957 BLOCKED: generated staged core failed PowerShell parse: $summary"
}

$bf957Surface = $qbNew + $rbNew + $wrNew + $teNew + $bf957Css
if ($bf957Surface -match 'Invoke-RestMethod|Invoke-WebRequest|Invoke-ButlerReadOnly|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
    throw 'BF-957 BLOCKED: position inventory links introduced provider, backend-read, optimizer, or write behavior.'
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))
Write-Host 'BF-957 My Team position inventory links applied.'

$bf958Transform = Join-Path $PSScriptRoot 'butler-app-bf958-my-team-section-nav-transform.ps1'
if (-not (Test-Path -LiteralPath $bf958Transform -PathType Leaf)) {
    throw "BF-958 BLOCKED: My Team section navigation transform not found at $bf958Transform"
}
& $bf958Transform -CorePath $CorePath
