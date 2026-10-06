param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-1019 BLOCKED: staged Butler core not found at $CorePath"
}

function Get-Bf1019ParsedAst {
    param([Parameter(Mandatory = $true)][string]$Text)

    $tokens = $null
    $errors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseInput($Text, [ref]$tokens, [ref]$errors)
    if (@($errors).Count -gt 0) {
        $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
        throw "BF-1019 BLOCKED: staged core failed PowerShell parse: $summary"
    }
    return $ast
}

function Get-Bf1019TeamFunction {
    param([Parameter(Mandatory = $true)]$Ast)

    $matches = @($Ast.FindAll({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq 'ConvertTo-TeamHtml'
    }, $true))
    if ($matches.Count -ne 1) {
        throw "BF-1019 BLOCKED: expected one ConvertTo-TeamHtml function, found $($matches.Count)."
    }
    return $matches[0]
}

$core = [System.IO.File]::ReadAllText($CorePath)
$ast = Get-Bf1019ParsedAst -Text $core
$teamFn = Get-Bf1019TeamFunction -Ast $ast
$team = $teamFn.Extent.Text

foreach ($required in @(
    'aria-label="Team workspace"',
    '$weeklyAttentionRailHtml',
    'href="#team-roster">Roster</a>',
    'href="#team-lineup">Lineup advisor</a>',
    'href="#team-positions">Position outlook</a>',
    'href="#team-draft">Draft capital</a>'
)) {
    if ($team.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-1019 BLOCKED: finalized My Team rail capability is missing: $required"
    }
}

$railPattern = '(?s)<a href="/">Dashboard</a>.*?</aside>'
$railMatches = [regex]::Matches($team, $railPattern)
if ($railMatches.Count -ne 1) {
    throw "BF-1019 BLOCKED: expected one finalized My Team rail body, found $($railMatches.Count)."
}

$railReplacement = @'
<a href="/">Dashboard</a>
<div class="rail-label">LINEUP</div>
<a class="rail-current" href="/team">My Team</a>
<a href="/matchup/autofill">Start/Sit Assistant</a>
<a href="/matchup">Matchup</a>
<a href="/autopilot">Auto-Pilot</a>
<div class="rail-label">ON THIS PAGE</div>
$weeklyAttentionRailHtml
<a class="rail-jump" href="#team-roster">Roster</a>
<a class="rail-jump" href="#team-lineup">Lineup advisor</a>
<a class="rail-jump" href="#team-positions">Position outlook</a>
<a class="rail-jump" href="#team-draft">Draft capital</a>
<div class="rail-label">WAIVER</div>
<a href="/waivers">Waiver Board</a>
<a href="/players">Player Search</a>
<div class="rail-label">TRADE</div>
<a href="/trade">Trade Analyzer</a>
<div class="rail-label">LEAGUE</div>
<a href="/league">League</a>
<div class="rail-label">TOOLS</div>
<a href="/compare">Player Compare</a>
<a href="/history?load=1">History</a></aside>
'@.TrimEnd()

$team = [regex]::Replace($team, $railPattern, [System.Text.RegularExpressions.MatchEvaluator]{
    param($match)
    return $railReplacement
}, 1)

$core = $core.Substring(0, $teamFn.Extent.StartOffset) + $team + $core.Substring($teamFn.Extent.EndOffset)

$cssStart = $core.IndexOf('function Get-AppCss {', [System.StringComparison]::Ordinal)
$cssEnd = $core.IndexOf('function Get-AppNav {', $cssStart, [System.StringComparison]::Ordinal)
if ($cssStart -lt 0 -or $cssEnd -le $cssStart) {
    throw 'BF-1019 BLOCKED: manager CSS boundary is missing.'
}
$cssBlock = $core.Substring($cssStart, $cssEnd - $cssStart)
$cssTerminator = $cssBlock.LastIndexOf("'@", [System.StringComparison]::Ordinal)
if ($cssTerminator -lt 0) {
    throw 'BF-1019 BLOCKED: manager CSS terminator is missing.'
}

$bf1019Css = @'
/* BF-1019 v0.4 fantasy-manager shell foundation. */
@media(min-width:1200px){.team-workspace{grid-template-columns:235px minmax(0,1fr)}.team-rail .rail-label{padding-top:16px;font-size:11px;letter-spacing:.09em}.team-rail a[href="/autopilot"]::after{content:"BETA";margin-left:8px;padding:2px 5px;border:1px solid var(--line);border-radius:999px;font-size:8px;letter-spacing:.06em;color:var(--muted)}}
'@

$cssBlock = $cssBlock.Substring(0, $cssTerminator) + [Environment]::NewLine + $bf1019Css.TrimEnd() + [Environment]::NewLine + $cssBlock.Substring($cssTerminator)
$core = $core.Substring(0, $cssStart) + $cssBlock + $core.Substring($cssEnd)

$finalAst = Get-Bf1019ParsedAst -Text $core
$installedTeam = (Get-Bf1019TeamFunction -Ast $finalAst).Extent.Text
foreach ($required in @(
    '<div class="rail-label">LINEUP</div>',
    'href="/matchup/autofill">Start/Sit Assistant</a>',
    'href="/autopilot">Auto-Pilot</a>',
    '<div class="rail-label">WAIVER</div>',
    '<div class="rail-label">TRADE</div>',
    '<div class="rail-label">LEAGUE</div>',
    '<div class="rail-label">TOOLS</div>',
    '$weeklyAttentionRailHtml',
    'href="#team-roster">Roster</a>'
)) {
    if ($installedTeam.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-1019 BLOCKED: v0.4 My Team rail marker is missing: $required"
    }
}

$surface = $railReplacement + [Environment]::NewLine + $bf1019Css
if ($surface -match 'Invoke-RestMethod|Invoke-WebRequest|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
    throw 'BF-1019 BLOCKED: v0.4 manager shell introduced provider, optimizer, or write behavior.'
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))
Write-Host 'BF-1019 v0.4 fantasy-manager shell applied.'
