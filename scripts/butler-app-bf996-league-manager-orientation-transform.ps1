param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-996 BLOCKED: staged Butler core not found at $CorePath"
}

function Get-Bf996ParsedAst {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Contract
    )

    $tokens = $null
    $errors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseInput($Text, [ref]$tokens, [ref]$errors)
    if (@($errors).Count -gt 0) {
        $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
        throw "BF-996 BLOCKED: $Contract failed PowerShell parse: $summary"
    }
    return $ast
}

function Get-Bf996OneFunction {
    param(
        [Parameter(Mandatory = $true)]$Ast,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Contract
    )

    $matches = @($Ast.FindAll({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq $Name
    }, $true))
    if ($matches.Count -ne 1) {
        throw "BF-996 BLOCKED: $Contract expected one $Name function, found $($matches.Count)."
    }
    return $matches[0]
}

function Replace-Bf996ExactlyOnce {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Old,
        [Parameter(Mandatory = $true)][string]$New,
        [Parameter(Mandatory = $true)][string]$Contract
    )

    $count = [regex]::Matches($Text, [regex]::Escape($Old)).Count
    if ($count -ne 1) {
        throw "BF-996 BLOCKED: $Contract expected one match, found $count."
    }
    return $Text.Replace($Old, $New)
}

$core = [IO.File]::ReadAllText($CorePath)
$ast = Get-Bf996ParsedAst -Text $core -Contract 'pre-transform staged core'
$leagueFn = Get-Bf996OneFunction -Ast $ast -Name 'ConvertTo-LeagueHtml' -Contract 'League Hub renderer'
$league = $leagueFn.Extent.Text

foreach ($required in @(
    '<div class="eyebrow">League hub</div>',
    '<div class="eyebrow">Franchise board</div>',
    '$leaderHrefId = [System.Uri]::EscapeDataString([string]$leader.TeamId)',
    'href="/franchise?id=$leaderHrefId">Scout franchise</a>',
    'href="/trade?opponent=$leaderHrefId">Open Trade Analyzer</a>',
    '<div class="eyebrow">League pulse</div>'
)) {
    if ($league.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-996 BLOCKED: finalized League Hub capability is missing: $required"
    }
}

$boardOld = @'
<section class="panel"><div class="section-head"><div><div class="eyebrow">Franchise board</div><h2>Top franchise snapshot</h2><p class="lede">The passive League overview exposes Butler's top three governed franchises. Rank, total value, player value, and pick value are descriptive context, not a manager grade or trade-target list.</p></div></div><div class="league-franchise-board">$franchiseHtml</div></section>
'@.TrimEnd()

$boardNew = @'
<section class="panel league-role-panel"><div class="section-head"><div><div class="eyebrow">Manager orientation</div><h2>Your team and the league have different jobs here</h2><p class="lede">Keep your own roster work in My Team. Use the league board for neutral franchise context, scouting, and exact-partner trade setup.</p></div></div><div class="league-role-grid"><article class="league-role-card league-role-owned"><span class="eyebrow">Your franchise</span><h3>My Team</h3><p>Roster, lineup review, Weekly Attention, and position inventory stay in your dedicated team workspace.</p><a class="btn btn-primary" href="/team">Open My Team</a></article><article class="league-role-card"><span class="eyebrow">League teams</span><h3>Scout or build a deal</h3><p>The franchise board is league-neutral. It can include your own franchise, so Butler does not guess that every displayed team is an opponent.</p><a class="btn btn-secondary" href="#league-franchise-board">View franchise board</a></article></div></section>
<section id="league-franchise-board" class="panel" tabindex="-1"><div class="section-head"><div><div class="eyebrow">Franchise board</div><h2>Top franchise snapshot</h2><p class="lede">This is a neutral league snapshot and can include your own franchise. Rank, total value, player value, and pick value are descriptive context only. Each displayed card keeps its exact team identity for Scout franchise and Trade Analyzer.</p></div></div><div class="league-franchise-board">$franchiseHtml</div></section>
'@.TrimEnd()

$league = Replace-Bf996ExactlyOnce -Text $league -Old $boardOld -New $boardNew -Contract 'League manager orientation and neutral franchise board'
$core = $core.Substring(0, $leagueFn.Extent.StartOffset) + $league + $core.Substring($leagueFn.Extent.EndOffset)

$cssStart = $core.IndexOf('function Get-AppCss {', [System.StringComparison]::Ordinal)
$cssEnd = $core.IndexOf('function Get-AppNav {', $cssStart, [System.StringComparison]::Ordinal)
if ($cssStart -lt 0 -or $cssEnd -le $cssStart) {
    throw 'BF-996 BLOCKED: manager CSS boundary is missing.'
}
$cssBlock = $core.Substring($cssStart, $cssEnd - $cssStart)
$cssTerminator = $cssBlock.LastIndexOf("'@", [System.StringComparison]::Ordinal)
if ($cssTerminator -lt 0) {
    throw 'BF-996 BLOCKED: manager CSS terminator is missing.'
}

$bf996Css = @'
/* BF-996 League manager orientation. */
.league-role-panel{scroll-margin-top:18px}.league-role-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;margin-top:16px}.league-role-card{padding:16px;border:1px solid var(--line);border-radius:11px;background:var(--surface-2)}.league-role-card h3{margin:5px 0 6px;font-family:var(--font-display);font-size:19px;color:var(--ink)}.league-role-card p{margin:0 0 13px;color:var(--muted);font-size:12px;line-height:1.55}.league-role-owned{border-left:3px solid var(--turf)}#league-franchise-board{scroll-margin-top:18px}@media(max-width:760px){.league-role-grid{grid-template-columns:1fr}.league-role-card .btn{width:100%;text-align:center}}
'@

$cssBlock = $cssBlock.Substring(0, $cssTerminator) + [Environment]::NewLine + $bf996Css.TrimEnd() + [Environment]::NewLine + $cssBlock.Substring($cssTerminator)
$core = $core.Substring(0, $cssStart) + $cssBlock + $core.Substring($cssEnd)

$finalAst = Get-Bf996ParsedAst -Text $core -Contract 'generated staged core'
$installed = Get-Bf996OneFunction -Ast $finalAst -Name 'ConvertTo-LeagueHtml' -Contract 'oriented League Hub renderer'
$installedText = $installed.Extent.Text

foreach ($required in @(
    '<div class="eyebrow">Manager orientation</div>',
    'Your team and the league have different jobs here',
    '<span class="eyebrow">Your franchise</span>',
    'href="/team">Open My Team</a>',
    '<span class="eyebrow">League teams</span>',
    'Butler does not guess that every displayed team is an opponent.',
    'href="#league-franchise-board">View franchise board</a>',
    'id="league-franchise-board"',
    'This is a neutral league snapshot and can include your own franchise.',
    'Each displayed card keeps its exact team identity for Scout franchise and Trade Analyzer.',
    'href="/franchise?id=$leaderHrefId">Scout franchise</a>',
    'href="/trade?opponent=$leaderHrefId">Open Trade Analyzer</a>'
)) {
    if ($installedText.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-996 BLOCKED: League manager-orientation marker is missing: $required"
    }
}

if ($core.IndexOf('BF-996 League manager orientation.', [System.StringComparison]::Ordinal) -lt 0) {
    throw 'BF-996 BLOCKED: League manager-orientation CSS was not installed.'
}

$surface = $installedText + [Environment]::NewLine + $bf996Css
if ($surface -match 'Invoke-RestMethod|Invoke-WebRequest|Invoke-ButlerReadOnly|Invoke-Bf742DashboardWorkerRead|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|returnUrl|redirectUrl|javascript:') {
    throw 'BF-996 BLOCKED: League manager orientation introduced provider, backend-read, optimizer, write, or open-redirect behavior.'
}

[IO.File]::WriteAllText($CorePath, $core, [Text.UTF8Encoding]::new($false))
Write-Host 'BF-996 League manager orientation applied.'
