param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-934 BLOCKED: staged Butler core not found at $CorePath"
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
        throw "BF-934 BLOCKED: $Contract expected one match, found $count."
    }
    return $Text.Replace($Old, $New)
}

$core = [System.IO.File]::ReadAllText($CorePath)
$teamStart = $core.IndexOf('function ConvertTo-TeamHtml {', [System.StringComparison]::Ordinal)
$teamEnd = $core.IndexOf('function Add-LeagueNavigation {', $teamStart, [System.StringComparison]::Ordinal)
if ($teamStart -lt 0 -or $teamEnd -le $teamStart) {
    throw 'BF-934 BLOCKED: My Team renderer boundary is missing.'
}
$teamBlock = $core.Substring($teamStart, $teamEnd - $teamStart)

$countAnchor = @'
    $autoFillHtml = ConvertTo-AutoFillHtml -AutoFill $AutoFill
'@

$countPrelude = @'
    $qbRosterCount = @($Roster.Players | Where-Object { [string]$_.Position -ceq "QB" }).Count
    $rbRosterCount = @($Roster.Players | Where-Object { [string]$_.Position -ceq "RB" }).Count
    $wrRosterCount = @($Roster.Players | Where-Object { [string]$_.Position -ceq "WR" }).Count
    $teRosterCount = @($Roster.Players | Where-Object { [string]$_.Position -ceq "TE" }).Count
    $corePositionInventoryHtml = @"
<div class="roster-inventory" aria-label="Core position inventory">
  <div class="roster-inventory-card"><span>QB</span><strong>$qbRosterCount</strong></div>
  <div class="roster-inventory-card"><span>RB</span><strong>$rbRosterCount</strong></div>
  <div class="roster-inventory-card"><span>WR</span><strong>$wrRosterCount</strong></div>
  <div class="roster-inventory-card"><span>TE</span><strong>$teRosterCount</strong></div>
</div>
<p class="meta roster-inventory-note">Roster inventory only. Butler's positional-pressure cards remain the interpretation layer.</p>
"@

'@

$teamBlock = Replace-ExactlyOnce -Text $teamBlock -Old $countAnchor.TrimEnd() -New ($countPrelude + $countAnchor).TrimEnd() -Contract 'core position count prelude'

$rosterHeading = '<section class="panel"><div class="section-head"><div><div class="eyebrow">Roster hub</div><h2>Lineup and depth at a glance</h2><p class="lede">Starters first, then bench and reserve. Current roster assignment is context only; Butler is not ranking players here. Click a mapped player name for Player Detail.</p></div><div class="button-row"><a class="btn btn-secondary" href="/players">Player Search</a><a class="btn btn-secondary" href="/compare">Player Compare</a></div></div>'
$rosterHeadingNew = $rosterHeading + '$corePositionInventoryHtml'
$teamBlock = Replace-ExactlyOnce -Text $teamBlock -Old $rosterHeading -New $rosterHeadingNew -Contract 'Roster Hub position inventory placement'

$core = $core.Substring(0, $teamStart) + $teamBlock + $core.Substring($teamEnd)

$cssStart = $core.IndexOf('function Get-AppCss {', [System.StringComparison]::Ordinal)
$cssEnd = $core.IndexOf('function Get-AppNav {', $cssStart, [System.StringComparison]::Ordinal)
if ($cssStart -lt 0 -or $cssEnd -le $cssStart) {
    throw 'BF-934 BLOCKED: manager CSS boundary is missing.'
}
$cssBlock = $core.Substring($cssStart, $cssEnd - $cssStart)
$cssTerminator = $cssBlock.LastIndexOf("'@", [System.StringComparison]::Ordinal)
if ($cssTerminator -lt 0) {
    throw 'BF-934 BLOCKED: manager CSS terminator is missing.'
}

$bf934Css = @'
/* BF-934 My Team core position inventory. */
.roster-inventory{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:8px;margin-top:16px}.roster-inventory-card{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:11px 13px;border:1px solid var(--line);border-radius:9px;background:var(--surface-2)}.roster-inventory-card span{font-size:10px;font-weight:900;letter-spacing:.06em;color:var(--muted)}.roster-inventory-card strong{font-family:var(--font-display);font-size:20px;color:var(--ink)}.roster-inventory-note{margin:8px 0 0}@media(max-width:760px){.roster-inventory{grid-template-columns:repeat(2,minmax(0,1fr))}}
'@

$cssBlock = $cssBlock.Substring(0, $cssTerminator) + [Environment]::NewLine + $bf934Css.TrimEnd() + [Environment]::NewLine + $cssBlock.Substring($cssTerminator)
$core = $core.Substring(0, $cssStart) + $cssBlock + $core.Substring($cssEnd)

foreach ($required in @(
    'BF-934 My Team core position inventory',
    '$qbRosterCount = @($Roster.Players | Where-Object',
    '$rbRosterCount = @($Roster.Players | Where-Object',
    '$wrRosterCount = @($Roster.Players | Where-Object',
    '$teRosterCount = @($Roster.Players | Where-Object',
    'aria-label="Core position inventory"',
    '<span>QB</span><strong>$qbRosterCount</strong>',
    '<span>RB</span><strong>$rbRosterCount</strong>',
    '<span>WR</span><strong>$wrRosterCount</strong>',
    '<span>TE</span><strong>$teRosterCount</strong>',
    'Roster inventory only.',
    'positional-pressure cards remain the interpretation layer.'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-934 BLOCKED: required position-inventory marker is missing: $required"
    }
}

$installedTeamStart = $core.IndexOf('function ConvertTo-TeamHtml {', [System.StringComparison]::Ordinal)
$installedTeamEnd = $core.IndexOf('function Add-LeagueNavigation {', $installedTeamStart, [System.StringComparison]::Ordinal)
$installedTeam = $core.Substring($installedTeamStart, $installedTeamEnd - $installedTeamStart)
if ($installedTeam -match 'Invoke-RestMethod|Invoke-WebRequest|Invoke-ButlerReadOnly|Invoke-Bf742DashboardWorkerRead|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
    throw 'BF-934 BLOCKED: roster inventory introduced provider, backend-read, optimizer, or write behavior.'
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($CorePath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $parseSummary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-934 BLOCKED: generated staged core failed PowerShell parse: $parseSummary"
}

Write-Host 'BF-934 My Team core position inventory applied.'
