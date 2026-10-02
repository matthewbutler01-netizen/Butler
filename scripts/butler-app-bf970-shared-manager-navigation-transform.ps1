param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

foreach ($path in @($CorePath, $DashboardPath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "BF-970 BLOCKED: staged Butler file not found at $path"
    }
}

function Get-ParsedAst {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Contract
    )

    $tokens = $null
    $errors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseInput($Text, [ref]$tokens, [ref]$errors)
    if (@($errors).Count -gt 0) {
        $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
        throw "BF-970 BLOCKED: $Contract failed PowerShell parse: $summary"
    }
    return $ast
}

function Get-ExactFunctionAst {
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
        throw "BF-970 BLOCKED: $Contract expected exactly one $Name function, found $($matches.Count)."
    }
    return $matches[0]
}

function Replace-ExactFunction {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$FunctionName,
        [Parameter(Mandatory = $true)][string]$Replacement,
        [Parameter(Mandatory = $true)][string]$Contract
    )

    $ast = Get-ParsedAst -Text $Text -Contract "$Contract pre-transform"
    $function = Get-ExactFunctionAst -Ast $ast -Name $FunctionName -Contract $Contract
    return $Text.Substring(0, $function.Extent.StartOffset) + $Replacement.TrimEnd() + $Text.Substring($function.Extent.EndOffset)
}

function Replace-InExactFunction {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$FunctionName,
        [Parameter(Mandatory = $true)][string]$Old,
        [Parameter(Mandatory = $true)][string]$New,
        [Parameter(Mandatory = $true)][string]$Contract
    )

    $ast = Get-ParsedAst -Text $Text -Contract "$Contract pre-transform"
    $function = Get-ExactFunctionAst -Ast $ast -Name $FunctionName -Contract $Contract
    $block = $function.Extent.Text
    $count = [regex]::Matches($block, [regex]::Escape($Old)).Count
    if ($count -ne 1) {
        throw "BF-970 BLOCKED: $Contract expected one scoped match, found $count."
    }
    $updated = $block.Replace($Old, $New)
    return $Text.Substring(0, $function.Extent.StartOffset) + $updated + $Text.Substring($function.Extent.EndOffset)
}

$core = [System.IO.File]::ReadAllText($CorePath)

$coreNav = @'
function Get-AppNav {
    param([Parameter(Mandatory = $true)][string]$Active)
    $dashboardClass = if ($Active -ceq "dashboard") { ' class="active"' } else { "" }
    $teamClass = if ($Active -ceq "team") { ' class="active"' } else { "" }
    $matchupClass = if ($Active -ceq "matchup") { ' class="active"' } else { "" }
    $waiversClass = if ($Active -ceq "waivers") { ' class="active"' } else { "" }
    $playersClass = if ($Active -ceq "players") { ' class="active"' } else { "" }
    $compareClass = if ($Active -ceq "compare") { ' class="active"' } else { "" }
    $leagueClass = if ($Active -ceq "league") { ' class="active"' } else { "" }
    $tradeClass = if ($Active -ceq "trade") { ' class="active"' } else { "" }
    $historyClass = if ($Active -ceq "history") { ' class="active"' } else { "" }
    return '<nav class="nav" aria-label="Butler sections"><a' + $dashboardClass + ' href="/">Dashboard</a><a' + $teamClass + ' href="/team">My Team</a><a' + $matchupClass + ' href="/matchup">Matchup</a><a' + $waiversClass + ' href="/waivers">Waiver Board</a><a' + $playersClass + ' href="/players">Player Search</a><a' + $compareClass + ' href="/compare">Player Compare</a><a' + $leagueClass + ' href="/league">League</a><a' + $tradeClass + ' href="/trade">Trade Analyzer</a><a' + $historyClass + ' href="/history?load=1">History</a></nav>'
}
'@
$core = Replace-ExactFunction -Text $core -FunctionName 'Get-AppNav' -Replacement $coreNav -Contract 'shared app navigation'

$core = Replace-InExactFunction -Text $core -FunctionName 'ConvertTo-PlayerSearchHtml' -Old '$nav = Get-AppNav -Active ''league''' -New '$nav = Get-AppNav -Active ''players''' -Contract 'Player Search active navigation'
$core = Replace-InExactFunction -Text $core -FunctionName 'ConvertTo-PlayerCompareHtml' -Old '$nav = Get-AppNav -Active ''league''' -New '$nav = Get-AppNav -Active ''compare''' -Contract 'Player Compare active navigation'
$core = Replace-InExactFunction -Text $core -FunctionName 'ConvertTo-PlayerDetailHtml' -Old '$nav = Get-AppNav -Active ''team''' -New '$nav = Get-AppNav -Active ''players''' -Contract 'Player Detail active navigation'
$core = Replace-InExactFunction -Text $core -FunctionName 'ConvertTo-TeamHtml' -Old '<a href="/history">History</a>' -New '<a href="/history?load=1">History</a>' -Contract 'My Team direct-loaded History route'

$dashboard = [System.IO.File]::ReadAllText($DashboardPath)

$dashboardHeader = @'
function Get-HeaderHtml {
    param([AllowNull()][string]$Target, [Parameter(Mandatory=$true)][string]$Active)
    $dashboardClass = if ($Active -eq "dashboard") { "active" } else { "" }
    $teamClass = if ($Active -eq "team") { "active" } else { "" }
    $matchupClass = if ($Active -eq "matchup") { "active" } else { "" }
    $waiversClass = if ($Active -eq "waivers") { "active" } else { "" }
    $playersClass = if ($Active -eq "players") { "active" } else { "" }
    $compareClass = if ($Active -eq "compare") { "active" } else { "" }
    $leagueClass = if ($Active -eq "league") { "active" } else { "" }
    $tradeClass = if ($Active -eq "trade") { "active" } else { "" }
    $historyClass = if ($Active -eq "history") { "active" } else { "" }
    return @"
<header class="top">
  <div class="brand"><h1>BUTLER</h1><p>We're here to serve you. Less Research. Better Decisions.</p></div>
  <div class="target">$(ConvertTo-HtmlText $Target)</div>
</header>
<nav class="nav" aria-label="Butler sections">
  <a class="$dashboardClass" href="/">Dashboard</a>
  <a class="$teamClass" href="/team">My Team</a>
  <a class="$matchupClass" href="/matchup">Matchup</a>
  <a class="$waiversClass" href="/waivers">Waiver Board</a>
  <a class="$playersClass" href="/players">Player Search</a>
  <a class="$compareClass" href="/compare">Player Compare</a>
  <a class="$leagueClass" href="/league">League</a>
  <a class="$tradeClass" href="/trade">Trade Analyzer</a>
  <a class="$historyClass" href="/history?load=1">History</a>
</nav>
"@
}
'@
$dashboard = Replace-ExactFunction -Text $dashboard -FunctionName 'Get-HeaderHtml' -Replacement $dashboardHeader -Contract 'Dashboard shared navigation'

$coreAst = Get-ParsedAst -Text $core -Contract 'generated staged core'
$dashboardAst = Get-ParsedAst -Text $dashboard -Contract 'generated staged Dashboard'

$coreNavInstalled = (Get-ExactFunctionAst -Ast $coreAst -Name 'Get-AppNav' -Contract 'installed shared app navigation').Extent.Text
$dashboardHeaderInstalled = (Get-ExactFunctionAst -Ast $dashboardAst -Name 'Get-HeaderHtml' -Contract 'installed Dashboard shared navigation').Extent.Text

foreach ($required in @(
    'Dashboard</a>',
    'href="/team">My Team</a>',
    'href="/matchup">Matchup</a>',
    'href="/waivers">Waiver Board</a>',
    'href="/players">Player Search</a>',
    'href="/compare">Player Compare</a>',
    'href="/league">League</a>',
    'href="/trade">Trade Analyzer</a>',
    'href="/history?load=1">History</a>'
)) {
    if ($coreNavInstalled.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-970 BLOCKED: installed shared app navigation marker is missing: $required"
    }
    if ($dashboardHeaderInstalled.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-970 BLOCKED: installed Dashboard navigation marker is missing: $required"
    }
}

$playerSearch = (Get-ExactFunctionAst -Ast $coreAst -Name 'ConvertTo-PlayerSearchHtml' -Contract 'installed Player Search renderer').Extent.Text
$playerCompare = (Get-ExactFunctionAst -Ast $coreAst -Name 'ConvertTo-PlayerCompareHtml' -Contract 'installed Player Compare renderer').Extent.Text
$playerDetail = (Get-ExactFunctionAst -Ast $coreAst -Name 'ConvertTo-PlayerDetailHtml' -Contract 'installed Player Detail renderer').Extent.Text
$team = (Get-ExactFunctionAst -Ast $coreAst -Name 'ConvertTo-TeamHtml' -Contract 'installed My Team renderer').Extent.Text

if ($playerSearch.IndexOf("Get-AppNav -Active 'players'", [System.StringComparison]::Ordinal) -lt 0) {
    throw 'BF-970 BLOCKED: Player Search is not marked active in shared navigation.'
}
if ($playerCompare.IndexOf("Get-AppNav -Active 'compare'", [System.StringComparison]::Ordinal) -lt 0) {
    throw 'BF-970 BLOCKED: Player Compare is not marked active in shared navigation.'
}
if ($playerDetail.IndexOf("Get-AppNav -Active 'players'", [System.StringComparison]::Ordinal) -lt 0) {
    throw 'BF-970 BLOCKED: Player Detail is not marked inside the Player Search section.'
}
if ($team.IndexOf('href="/history?load=1">History</a>', [System.StringComparison]::Ordinal) -lt 0) {
    throw 'BF-970 BLOCKED: My Team workspace rail does not use direct-loaded Decision History.'
}

foreach ($text in @($core, $dashboard)) {
    if ($text.IndexOf('BF-898 mobile manager polish', [System.StringComparison]::Ordinal) -lt 0 -or $text.IndexOf('overflow-x:auto', [System.StringComparison]::Ordinal) -lt 0) {
        throw 'BF-970 BLOCKED: existing swipeable mobile navigation contract is missing.'
    }
}

$bf970Surface = $coreNav + $dashboardHeader
if ($bf970Surface -match 'Invoke-RestMethod|Invoke-WebRequest|Invoke-ButlerReadOnly|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
    throw 'BF-970 BLOCKED: shared manager navigation introduced provider, backend-read, optimizer, FAAB, or write behavior.'
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))
[System.IO.File]::WriteAllText($DashboardPath, $dashboard, [System.Text.UTF8Encoding]::new($false))
Write-Host 'BF-970 shared manager navigation applied.'
