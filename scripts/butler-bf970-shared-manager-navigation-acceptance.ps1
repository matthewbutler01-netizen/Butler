Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$transformPath = Join-Path $PSScriptRoot 'butler-app-bf970-shared-manager-navigation-transform.ps1'
$transformTokens = $null
$transformErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($transformPath, [ref]$transformTokens, [ref]$transformErrors)
if (@($transformErrors).Count -ne 0) {
    $summary = (@($transformErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-970 BLOCKED: transform script failed PowerShell parse before staging: $summary"
}

$root = Join-Path ([IO.Path]::GetTempPath()) ('Butler-bf970-shared-nav-' + [guid]::NewGuid().ToString('N'))
try {
    [IO.Directory]::CreateDirectory($root) | Out-Null
    foreach ($name in @('butler-dashboard.ps1', 'butler-app-shell-core-single.ps1')) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination $root
    }

    & (Join-Path $PSScriptRoot 'butler-dashboard-bf715-transform.ps1') -DashboardPath (Join-Path $root 'butler-dashboard.ps1')

    $corePath = Join-Path $root 'butler-app-shell-core-single.ps1'
    $dashboardPath = Join-Path $root 'butler-dashboard.ps1'

    $coreTokens = $null
    $coreErrors = $null
    $coreAst = [System.Management.Automation.Language.Parser]::ParseFile($corePath, [ref]$coreTokens, [ref]$coreErrors)
    if (@($coreErrors).Count -ne 0) {
        throw 'BF-970 BLOCKED: staged core has parse errors.'
    }

    $dashboardTokens = $null
    $dashboardErrors = $null
    $dashboardAst = [System.Management.Automation.Language.Parser]::ParseFile($dashboardPath, [ref]$dashboardTokens, [ref]$dashboardErrors)
    if (@($dashboardErrors).Count -ne 0) {
        throw 'BF-970 BLOCKED: staged Dashboard has parse errors.'
    }

    function Get-OneFunction {
        param($Ast, [string]$Name, [string]$Contract)
        $matches = @($Ast.FindAll({
            param($node)
            $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
            $node.Name -eq $Name
        }, $true))
        if ($matches.Count -ne 1) {
            throw "BF-970 BLOCKED: $Contract expected one $Name function, found $($matches.Count)."
        }
        return $matches[0].Extent.Text
    }

    $coreNav = Get-OneFunction -Ast $coreAst -Name 'Get-AppNav' -Contract 'shared app navigation'
    $dashboardHeader = Get-OneFunction -Ast $dashboardAst -Name 'Get-HeaderHtml' -Contract 'Dashboard navigation'

    foreach ($surface in @($coreNav, $dashboardHeader)) {
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
            if ($surface.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
                throw "BF-970 BLOCKED: normalized navigation marker is missing: $required"
            }
        }

        foreach ($required in @(
            '$playersClass',
            '$compareClass',
            '$historyClass'
        )) {
            if ($surface.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
                throw "BF-970 BLOCKED: normalized active-state marker is missing: $required"
            }
        }
    }

    $playerSearch = Get-OneFunction -Ast $coreAst -Name 'ConvertTo-PlayerSearchHtml' -Contract 'Player Search renderer'
    $playerCompare = Get-OneFunction -Ast $coreAst -Name 'ConvertTo-PlayerCompareHtml' -Contract 'Player Compare renderer'
    $playerDetail = Get-OneFunction -Ast $coreAst -Name 'ConvertTo-PlayerDetailHtml' -Contract 'Player Detail renderer'
    $team = Get-OneFunction -Ast $coreAst -Name 'ConvertTo-TeamHtml' -Contract 'My Team renderer'

    if ($playerSearch.IndexOf("Get-AppNav -Active 'players'", [System.StringComparison]::Ordinal) -lt 0 -or
        $playerSearch.IndexOf("Get-AppNav -Active 'league'", [System.StringComparison]::Ordinal) -ge 0) {
        throw 'BF-970 BLOCKED: Player Search active navigation is not normalized.'
    }
    if ($playerCompare.IndexOf("Get-AppNav -Active 'compare'", [System.StringComparison]::Ordinal) -lt 0 -or
        $playerCompare.IndexOf("Get-AppNav -Active 'league'", [System.StringComparison]::Ordinal) -ge 0) {
        throw 'BF-970 BLOCKED: Player Compare active navigation is not normalized.'
    }
    if ($playerDetail.IndexOf("Get-AppNav -Active 'players'", [System.StringComparison]::Ordinal) -lt 0) {
        throw 'BF-970 BLOCKED: Player Detail must remain inside the Player Search navigation section.'
    }
    if ($team.IndexOf('href="/history?load=1">History</a>', [System.StringComparison]::Ordinal) -lt 0) {
        throw 'BF-970 BLOCKED: My Team workspace History route is not direct-loaded.'
    }

    $core = [IO.File]::ReadAllText($corePath)
    $dashboard = [IO.File]::ReadAllText($dashboardPath)
    foreach ($text in @($core, $dashboard)) {
        if ($text.IndexOf('BF-898 mobile manager polish', [System.StringComparison]::Ordinal) -lt 0 -or
            $text.IndexOf('overflow-x:auto', [System.StringComparison]::Ordinal) -lt 0 -or
            $text.IndexOf('scroll-snap-type:x proximity', [System.StringComparison]::Ordinal) -lt 0) {
            throw 'BF-970 BLOCKED: swipeable mobile navigation regression detected.'
        }
    }

    if ($dashboard.IndexOf('href="/history?load=1">Decision History</a>', [System.StringComparison]::Ordinal) -lt 0) {
        throw 'BF-970 BLOCKED: BF-969 Dashboard Decision History quick tool was lost.'
    }
    if ($dashboard.IndexOf('href="/history?load=1">View Decision History</a>', [System.StringComparison]::Ordinal) -lt 0) {
        throw 'BF-970 BLOCKED: Waiver Board Decision History route contract was lost.'
    }

    $surface = $coreNav + $dashboardHeader
    if ($surface -match 'Invoke-RestMethod|Invoke-WebRequest|Invoke-ButlerReadOnly|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
        throw 'BF-970 BLOCKED: normalized navigation introduced provider, backend-read, optimizer, FAAB, or write behavior.'
    }

    Write-Host 'BF-970 SHARED MANAGER NAVIGATION ACCEPTANCE: PASS'
}
finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
