param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-908 BLOCKED: staged Butler core not found at $CorePath"
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
        throw "BF-908 BLOCKED: $Contract expected one match, found $count."
    }
    return $Text.Replace($Old, $New)
}

$core = [System.IO.File]::ReadAllText($CorePath)
$teamStart = $core.IndexOf('function ConvertTo-TeamHtml {', [System.StringComparison]::Ordinal)
if ($teamStart -lt 0) {
    throw 'BF-908 BLOCKED: My Team renderer start is missing.'
}
$teamEnd = $core.IndexOf('function Add-LeagueNavigation {', $teamStart, [System.StringComparison]::Ordinal)
if ($teamEnd -le $teamStart) {
    throw 'BF-908 BLOCKED: My Team renderer end is missing.'
}

if ($core.IndexOf('function ConvertTo-MyTeamPlayerNameHtml {', [System.StringComparison]::Ordinal) -lt 0) {
    throw 'BF-908 BLOCKED: Player Detail roster-link helper must be installed before Roster Hub.'
}


$teamStart = $core.IndexOf('function ConvertTo-TeamHtml {', [System.StringComparison]::Ordinal)
$teamEnd = $core.IndexOf('function Add-LeagueNavigation {', $teamStart, [System.StringComparison]::Ordinal)
$teamBlock = $core.Substring($teamStart, $teamEnd - $teamStart)

$rosterHeadingOld = '<div class="section-head"><div><div class="eyebrow">Roster</div><h2>Players by lineup state</h2><p class="lede">Starters first, then bench and reserve. No player IDs or operator-only details are shown in the manager view.</p></div></div>'
$rosterHeadingNew = '<div class="section-head"><div><div class="eyebrow">Roster hub</div><h2>Lineup and depth at a glance</h2><p class="lede">Starters first, then bench and reserve. Current roster assignment is context only; Butler is not ranking players here. Click a mapped player name for Player Detail.</p></div><div class="button-row"><a class="btn btn-secondary" href="/players">Player Search</a><a class="btn btn-secondary" href="/compare">Player Compare</a></div></div>'
$teamBlock = Replace-ExactlyOnce -Text $teamBlock -Old $rosterHeadingOld -New $rosterHeadingNew -Contract 'Roster Hub heading'

$positionMarker = '<section class="panel"><div class="section-head"><div><div class="eyebrow">Roster construction</div>'
$rosterMarker = '<section class="panel"><div class="section-head"><div><div class="eyebrow">Roster hub</div>'
$futureMarker = '<section class="panel"><div class="eyebrow">Future flexibility</div>'

$positionStart = $teamBlock.IndexOf($positionMarker, [System.StringComparison]::Ordinal)
$rosterStart = $teamBlock.IndexOf($rosterMarker, [System.StringComparison]::Ordinal)
$futureStart = $teamBlock.IndexOf($futureMarker, [System.StringComparison]::Ordinal)
if ($positionStart -lt 0 -or $rosterStart -lt 0 -or $futureStart -lt 0) {
    throw 'BF-908 BLOCKED: final My Team section markers are incomplete.'
}
if (-not ($positionStart -lt $rosterStart -and $rosterStart -lt $futureStart)) {
    throw 'BF-908 BLOCKED: final My Team section order is not the expected position -> roster -> future sequence.'
}

$positionBlock = $teamBlock.Substring($positionStart, $rosterStart - $positionStart)
$rosterBlock = $teamBlock.Substring($rosterStart, $futureStart - $rosterStart)
$teamBlock = $teamBlock.Substring(0, $positionStart) + $rosterBlock + $positionBlock + $teamBlock.Substring($futureStart)

$core = $core.Substring(0, $teamStart) + $teamBlock + $core.Substring($teamEnd)

$cssStart = $core.IndexOf('function Get-AppCss {', [System.StringComparison]::Ordinal)
if ($cssStart -lt 0) {
    throw 'BF-908 BLOCKED: manager CSS start is missing.'
}
$cssEnd = $core.IndexOf('function Get-AppNav {', $cssStart, [System.StringComparison]::Ordinal)
if ($cssEnd -le $cssStart) {
    throw 'BF-908 BLOCKED: manager CSS end is missing.'
}
$cssBlock = $core.Substring($cssStart, $cssEnd - $cssStart)
$cssTerminator = $cssBlock.LastIndexOf("`n'@", [System.StringComparison]::Ordinal)
if ($cssTerminator -lt 0) {
    throw 'BF-908 BLOCKED: manager CSS terminator is missing.'
}

$bf908Css = @'
/* BF-908 My Team Roster Hub. */
.roster-board{margin-top:14px}.roster-group-head{padding-top:12px;padding-bottom:12px}.player-row{transition:background .12s ease}.player-primary strong a{color:var(--ink);text-decoration:none}.player-primary strong a:hover{color:var(--turf-deep);text-decoration:underline}@media(max-width:760px){.section-head .button-row{width:100%}.section-head .button-row .btn{flex:1}}
'@

$cssBlock = $cssBlock.Substring(0, $cssTerminator) + "`n" + $bf908Css.TrimEnd() + $cssBlock.Substring($cssTerminator)
$core = $core.Substring(0, $cssStart) + $cssBlock + $core.Substring($cssEnd)

foreach ($required in @(
    'BF-908 My Team Roster Hub',
    'Roster hub',
    'Lineup and depth at a glance',
    'ConvertTo-MyTeamPlayerNameHtml -Player $player',
    'ConvertTo-MyTeamPlayerNameHtml -Player $player',
    'href="/players">Player Search</a>',
    'href="/compare">Player Compare</a>',
    'Starting lineup',
    'Bench',
    'Reserve &amp; taxi',
    'Roster construction',
    'Future flexibility'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-908 BLOCKED: required Roster Hub marker is missing: $required"
    }
}

$installedStart = $core.IndexOf('function ConvertTo-TeamHtml {', [System.StringComparison]::Ordinal)
if ($installedStart -lt 0) {
    throw 'BF-908 BLOCKED: installed My Team renderer start is missing.'
}
$installedEnd = $core.IndexOf('function Add-LeagueNavigation {', $installedStart, [System.StringComparison]::Ordinal)
if ($installedEnd -le $installedStart) {
    throw 'BF-908 BLOCKED: installed My Team renderer end is missing.'
}
$installedTeam = $core.Substring($installedStart, $installedEnd - $installedStart)
if ($installedTeam -match 'Invoke-RestMethod|Invoke-WebRequest|Invoke-ButlerReadOnly|Invoke-Bf742DashboardWorkerRead|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
    throw 'BF-908 BLOCKED: Roster Hub introduced provider, optimizer, backend-read, or write behavior.'
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($CorePath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $parseSummary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-908 BLOCKED: generated staged core failed PowerShell parse: $parseSummary"
}

Write-Host 'BF-908 My Team Roster Hub applied.'
