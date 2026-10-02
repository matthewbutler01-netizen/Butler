Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$transformPath = Join-Path $PSScriptRoot 'butler-dashboard-bf969-history-shortcut-transform.ps1'
$transformTokens = $null
$transformErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($transformPath, [ref]$transformTokens, [ref]$transformErrors)
if (@($transformErrors).Count -ne 0) {
    $summary = (@($transformErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-969 BLOCKED: transform script failed PowerShell parse before staging: $summary"
}

$root = Join-Path ([IO.Path]::GetTempPath()) ('Butler-bf969-dashboard-history-' + [guid]::NewGuid().ToString('N'))
try {
    [IO.Directory]::CreateDirectory($root) | Out-Null
    foreach ($name in @('butler-dashboard.ps1', 'butler-app-shell-core-single.ps1')) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination $root
    }

    & (Join-Path $PSScriptRoot 'butler-dashboard-bf715-transform.ps1') -DashboardPath (Join-Path $root 'butler-dashboard.ps1')

    $dashboardPath = Join-Path $root 'butler-dashboard.ps1'
    $tokens = $null
    $errors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($dashboardPath, [ref]$tokens, [ref]$errors)
    if (@($errors).Count -ne 0) {
        throw 'BF-969 BLOCKED: staged Dashboard has parse errors.'
    }

    $functions = @($ast.FindAll({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq 'ConvertTo-DashboardHtml'
    }, $true))
    if ($functions.Count -ne 1) {
        throw "BF-969 BLOCKED: expected one staged Dashboard renderer, found $($functions.Count)."
    }

    $dashboard = $functions[0].Extent.Text
    foreach ($required in @(
        'Week at a glance',
        'Quick tools',
        'href="/trade">Trade Analyzer</a>',
        'href="/players">Player Search</a>',
        'href="/compare">Player Compare</a>',
        'href="/league">League</a>',
        'href="/history?load=1">Decision History</a>',
        'Kind = "Lineup"',
        'Kind = "Waivers"',
        'Kind = "Matchup"'
    )) {
        if ($dashboard.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-969 BLOCKED: staged Dashboard shortcut marker is missing: $required"
        }
    }

    $historyShortcutCount = [regex]::Matches(
        $dashboard,
        [regex]::Escape('href="/history?load=1">Decision History</a>')
    ).Count
    if ($historyShortcutCount -ne 1) {
        throw "BF-969 BLOCKED: Dashboard must expose exactly one Decision History quick tool; found $historyShortcutCount."
    }

    $allDashboardText = [IO.File]::ReadAllText($dashboardPath)
    if ($allDashboardText.IndexOf('href="/history?load=1">View Decision History</a>', [System.StringComparison]::Ordinal) -lt 0) {
        throw 'BF-969 BLOCKED: existing Waiver Board Decision History route contract is missing.'
    }

    if ($dashboard -match 'Invoke-RestMethod|Invoke-WebRequest|Invoke-ButlerReadOnly|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
        throw 'BF-969 BLOCKED: Dashboard quick-tool surface introduced provider, backend-read, optimizer, FAAB, or write behavior.'
    }

    Write-Host 'BF-969 DASHBOARD DECISION HISTORY SHORTCUT ACCEPTANCE: PASS'
}
finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
