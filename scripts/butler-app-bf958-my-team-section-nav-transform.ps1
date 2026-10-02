param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-958 BLOCKED: staged Butler core not found at $CorePath"
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
        throw "BF-958 BLOCKED: $Contract expected one match, found $count."
    }
    return $Text.Replace($Old, $New)
}

$core = [System.IO.File]::ReadAllText($CorePath)
foreach ($required in @(
    'aria-label="Team workspace"',
    '<a class="rail-current" href="/team">My Team</a>',
    '<div class="eyebrow">Roster hub</div><h2>Lineup and depth at a glance</h2>',
    '<div class="eyebrow">Roster construction</div><h2>Position outlook</h2>',
    '<div class="eyebrow">Future flexibility</div><h2>Draft capital</h2>',
    '$autoFillHtml = ConvertTo-AutoFillHtml -AutoFill $AutoFill',
    'BF-957 My Team position inventory links'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-958 BLOCKED: required My Team workspace capability is missing: $required"
    }
}

$teamStart = $core.IndexOf('function ConvertTo-TeamHtml {', [System.StringComparison]::Ordinal)
$teamEnd = $core.IndexOf('function Add-LeagueNavigation {', $teamStart, [System.StringComparison]::Ordinal)
if ($teamStart -lt 0 -or $teamEnd -le $teamStart) {
    throw 'BF-958 BLOCKED: My Team renderer boundary is missing.'
}
$team = $core.Substring($teamStart, $teamEnd - $teamStart)

$railOld = '<a href="/">Dashboard</a><a class="rail-current" href="/team">My Team</a><div class="rail-label">THIS WEEK</div>'
$railNew = '<a href="/">Dashboard</a><a class="rail-current" href="/team">My Team</a><div class="rail-label">ON THIS PAGE</div><a class="rail-jump" href="#team-roster">Roster</a><a class="rail-jump" href="#team-lineup">Lineup advisor</a><a class="rail-jump" href="#team-positions">Position outlook</a><a class="rail-jump" href="#team-draft">Draft capital</a><div class="rail-label">THIS WEEK</div>'
$team = Replace-ExactlyOnce -Text $team -Old $railOld -New $railNew -Contract 'workspace section navigation'

$rosterOld = '<section class="panel"><div class="section-head"><div><div class="eyebrow">Roster hub</div><h2>Lineup and depth at a glance</h2>'
$rosterNew = '<section id="team-roster" class="panel team-section-target" tabindex="-1"><div class="section-head"><div><div class="eyebrow">Roster hub</div><h2>Lineup and depth at a glance</h2>'
$team = Replace-ExactlyOnce -Text $team -Old $rosterOld -New $rosterNew -Contract 'roster section anchor'

$positionOld = '<section class="panel"><div class="section-head"><div><div class="eyebrow">Roster construction</div><h2>Position outlook</h2>'
$positionNew = '<section id="team-positions" class="panel team-section-target" tabindex="-1"><div class="section-head"><div><div class="eyebrow">Roster construction</div><h2>Position outlook</h2>'
$team = Replace-ExactlyOnce -Text $team -Old $positionOld -New $positionNew -Contract 'position section anchor'

$draftOld = '<section class="panel"><div class="eyebrow">Future flexibility</div><h2>Draft capital</h2>'
$draftNew = '<section id="team-draft" class="panel team-section-target" tabindex="-1"><div class="eyebrow">Future flexibility</div><h2>Draft capital</h2>'
$team = Replace-ExactlyOnce -Text $team -Old $draftOld -New $draftNew -Contract 'draft section anchor'

$autoFillOld = '    $autoFillHtml = ConvertTo-AutoFillHtml -AutoFill $AutoFill'
$autoFillNew = @'
    $autoFillHtml = ConvertTo-AutoFillHtml -AutoFill $AutoFill
    $autoFillHtml = $autoFillHtml.Replace('<section class="panel recommendation-panel"', '<section id="team-lineup" class="panel recommendation-panel team-section-target" tabindex="-1"')
'@
$team = Replace-ExactlyOnce -Text $team -Old $autoFillOld -New $autoFillNew.TrimEnd() -Contract 'lineup advisor section anchor'

$core = $core.Substring(0, $teamStart) + $team + $core.Substring($teamEnd)

$cssStart = $core.IndexOf('function Get-AppCss {', [System.StringComparison]::Ordinal)
$cssEnd = $core.IndexOf('function Get-AppNav {', $cssStart, [System.StringComparison]::Ordinal)
if ($cssStart -lt 0 -or $cssEnd -le $cssStart) {
    throw 'BF-958 BLOCKED: manager CSS boundary is missing.'
}
$cssBlock = $core.Substring($cssStart, $cssEnd - $cssStart)
$cssTerminator = $cssBlock.LastIndexOf("'@", [System.StringComparison]::Ordinal)
if ($cssTerminator -lt 0) {
    throw 'BF-958 BLOCKED: manager CSS terminator is missing.'
}

$bf958Css = @'
/* BF-958 My Team workspace section navigation. */
.team-section-target{scroll-margin-top:20px}.team-section-target:focus{outline:2px solid color-mix(in srgb,var(--turf) 50%,transparent);outline-offset:2px}.team-rail .rail-jump{padding-top:7px;padding-bottom:7px;font-size:12px;color:var(--muted-2)}.team-rail .rail-jump::before{content:"↳";margin-right:7px;color:var(--turf)}
'@

$cssBlock = $cssBlock.Substring(0, $cssTerminator) + [Environment]::NewLine + $bf958Css.TrimEnd() + [Environment]::NewLine + $cssBlock.Substring($cssTerminator)
$core = $core.Substring(0, $cssStart) + $cssBlock + $core.Substring($cssEnd)

foreach ($required in @(
    'BF-958 My Team workspace section navigation',
    '<div class="rail-label">ON THIS PAGE</div>',
    'href="#team-roster">Roster</a>',
    'href="#team-lineup">Lineup advisor</a>',
    'href="#team-positions">Position outlook</a>',
    'href="#team-draft">Draft capital</a>',
    'id="team-roster"',
    'id="team-lineup"',
    'id="team-positions"',
    'id="team-draft"',
    'class="panel team-section-target"',
    'recommendation-panel team-section-target',
    '.team-section-target{scroll-margin-top:20px}'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-958 BLOCKED: required workspace section marker is missing: $required"
    }
}

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseInput($core, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $summary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-958 BLOCKED: generated staged core failed PowerShell parse: $summary"
}

$bf958Surface = $railNew + $rosterNew + $positionNew + $draftNew + $autoFillNew + $bf958Css
if ($bf958Surface -match 'Invoke-RestMethod|Invoke-WebRequest|Invoke-ButlerReadOnly|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
    throw 'BF-958 BLOCKED: workspace section navigation introduced provider, backend-read, optimizer, or write behavior.'
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))
Write-Host 'BF-958 My Team workspace section navigation applied.'
