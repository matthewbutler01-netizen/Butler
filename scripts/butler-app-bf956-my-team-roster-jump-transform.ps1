param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-956 BLOCKED: staged Butler core not found at $CorePath"
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
        throw "BF-956 BLOCKED: $Contract expected one match, found $count."
    }
    return $Text.Replace($Old, $New)
}

$core = [System.IO.File]::ReadAllText($CorePath)
foreach ($required in @(
    'aria-label="Roster slot distribution"',
    '<div class="roster-state-item"><span>Starters</span>',
    '<div class="roster-state-item"><span>Bench</span>',
    '<div class="roster-state-item"><span>Reserve / taxi</span>',
    '<section class="roster-group"><div class="roster-group-head"><h3>Starting lineup</h3>',
    '<section class="roster-group"><div class="roster-group-head"><h3>Bench</h3>',
    '<section class="roster-group"><div class="roster-group-head"><h3>Reserve &amp; taxi</h3>'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-956 BLOCKED: required roster navigation capability is missing: $required"
    }
}

$teamStart = $core.IndexOf('function ConvertTo-TeamHtml {', [System.StringComparison]::Ordinal)
$teamEnd = $core.IndexOf('function Add-LeagueNavigation {', $teamStart, [System.StringComparison]::Ordinal)
if ($teamStart -lt 0 -or $teamEnd -le $teamStart) {
    throw 'BF-956 BLOCKED: My Team renderer boundary is missing.'
}
$team = $core.Substring($teamStart, $teamEnd - $teamStart)

$startersOld = '<div class="roster-state-item"><span>Starters</span><strong>$(ConvertTo-HtmlText $Roster.StarterCount)</strong></div>'
$startersNew = '<a class="roster-state-item" href="#roster-starters"><span>Starters</span><strong>$(ConvertTo-HtmlText $Roster.StarterCount)</strong></a>'
$benchOld = '<div class="roster-state-item"><span>Bench</span><strong>$(ConvertTo-HtmlText $Roster.BenchCount)</strong></div>'
$benchNew = '<a class="roster-state-item" href="#roster-bench"><span>Bench</span><strong>$(ConvertTo-HtmlText $Roster.BenchCount)</strong></a>'
$reserveOld = '<div class="roster-state-item"><span>Reserve / taxi</span><strong>$(ConvertTo-HtmlText $reserveTaxiCount)</strong><small>Reserve $(ConvertTo-HtmlText $Roster.ReserveCount) &middot; Taxi $(ConvertTo-HtmlText $Roster.TaxiCount)</small></div>'
$reserveNew = '<a class="roster-state-item" href="#roster-reserve"><span>Reserve / taxi</span><strong>$(ConvertTo-HtmlText $reserveTaxiCount)</strong><small>Reserve $(ConvertTo-HtmlText $Roster.ReserveCount) &middot; Taxi $(ConvertTo-HtmlText $Roster.TaxiCount)</small></a>'

$team = Replace-ExactlyOnce -Text $team -Old $startersOld -New $startersNew -Contract 'starter roster jump'
$team = Replace-ExactlyOnce -Text $team -Old $benchOld -New $benchNew -Contract 'bench roster jump'
$team = Replace-ExactlyOnce -Text $team -Old $reserveOld -New $reserveNew -Contract 'reserve roster jump'

$groupStartersOld = '<section class="roster-group"><div class="roster-group-head"><h3>Starting lineup</h3>'
$groupStartersNew = '<section id="roster-starters" class="roster-group" tabindex="-1"><div class="roster-group-head"><h3>Starting lineup</h3>'
$groupBenchOld = '<section class="roster-group"><div class="roster-group-head"><h3>Bench</h3>'
$groupBenchNew = '<section id="roster-bench" class="roster-group" tabindex="-1"><div class="roster-group-head"><h3>Bench</h3>'
$groupReserveOld = '<section class="roster-group"><div class="roster-group-head"><h3>Reserve &amp; taxi</h3>'
$groupReserveNew = '<section id="roster-reserve" class="roster-group" tabindex="-1"><div class="roster-group-head"><h3>Reserve &amp; taxi</h3>'

$team = Replace-ExactlyOnce -Text $team -Old $groupStartersOld -New $groupStartersNew -Contract 'starter roster target'
$team = Replace-ExactlyOnce -Text $team -Old $groupBenchOld -New $groupBenchNew -Contract 'bench roster target'
$team = Replace-ExactlyOnce -Text $team -Old $groupReserveOld -New $groupReserveNew -Contract 'reserve roster target'

$core = $core.Substring(0, $teamStart) + $team + $core.Substring($teamEnd)

$cssStart = $core.IndexOf('function Get-AppCss {', [System.StringComparison]::Ordinal)
$cssEnd = $core.IndexOf('function Get-AppNav {', $cssStart, [System.StringComparison]::Ordinal)
if ($cssStart -lt 0 -or $cssEnd -le $cssStart) {
    throw 'BF-956 BLOCKED: manager CSS boundary is missing.'
}
$cssBlock = $core.Substring($cssStart, $cssEnd - $cssStart)
$cssTerminator = $cssBlock.LastIndexOf("'@", [System.StringComparison]::Ordinal)
if ($cssTerminator -lt 0) {
    throw 'BF-956 BLOCKED: manager CSS terminator is missing.'
}

$bf956Css = @'
/* BF-956 My Team roster jump navigation. */
a.roster-state-item{text-decoration:none;color:inherit;transition:border-color .12s ease,background .12s ease,transform .12s ease}a.roster-state-item:hover,a.roster-state-item:focus-visible{border-color:var(--turf);background:color-mix(in srgb,var(--turf) 8%,var(--surface));outline:none}a.roster-state-item:active{transform:translateY(1px)}.roster-group{scroll-margin-top:20px}.roster-group:focus{outline:2px solid color-mix(in srgb,var(--turf) 50%,transparent);outline-offset:-2px}
'@

$cssBlock = $cssBlock.Substring(0, $cssTerminator) + [Environment]::NewLine + $bf956Css.TrimEnd() + [Environment]::NewLine + $cssBlock.Substring($cssTerminator)
$core = $core.Substring(0, $cssStart) + $cssBlock + $core.Substring($cssEnd)

foreach ($required in @(
    'BF-956 My Team roster jump navigation',
    'href="#roster-starters"',
    'href="#roster-bench"',
    'href="#roster-reserve"',
    'id="roster-starters"',
    'id="roster-bench"',
    'id="roster-reserve"',
    'tabindex="-1"',
    '.roster-group{scroll-margin-top:20px}'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-956 BLOCKED: required roster jump marker is missing: $required"
    }
}

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseInput($core, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $summary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-956 BLOCKED: generated staged core failed PowerShell parse: $summary"
}

$bf956Surface = $startersNew + $benchNew + $reserveNew + $groupStartersNew + $groupBenchNew + $groupReserveNew + $bf956Css
if ($bf956Surface -match 'Invoke-RestMethod|Invoke-WebRequest|Invoke-ButlerReadOnly|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
    throw 'BF-956 BLOCKED: roster jump navigation introduced provider, backend-read, optimizer, or write behavior.'
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))
Write-Host 'BF-956 My Team roster jump navigation applied.'
