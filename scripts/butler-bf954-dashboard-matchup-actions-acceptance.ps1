Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = Join-Path ([IO.Path]::GetTempPath()) ('Butler-bf954-dashboard-' + [guid]::NewGuid().ToString('N'))
try {
    [IO.Directory]::CreateDirectory($root) | Out-Null
    foreach ($name in @('butler-dashboard.ps1', 'butler-app-shell-core-single.ps1')) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination $root
    }

    & (Join-Path $PSScriptRoot 'butler-dashboard-bf715-transform.ps1') -DashboardPath (Join-Path $root 'butler-dashboard.ps1')

    $corePath = Join-Path $root 'butler-app-shell-core-single.ps1'
    $core = [IO.File]::ReadAllText($corePath)
    $tokens = $null
    $errors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($corePath, [ref]$tokens, [ref]$errors)
    if (@($errors).Count -ne 0) {
        throw 'BF-954 BLOCKED: staged core has parse errors.'
    }

    $function = $ast.Find({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq 'Add-DashboardMatchupSummary'
    }, $true)
    if ($null -eq $function) {
        throw 'BF-954 BLOCKED: staged Dashboard matchup summary helper is missing.'
    }

    $text = $function.Extent.Text
    foreach ($required in @(
        '$opponentHrefId = [System.Uri]::EscapeDataString([string]$matchup.OpponentTeamId)',
        'href="/franchise?id=',
        '>Scout</a>',
        'href="/trade?opponent=',
        '>Trade</a>',
        'Open Weekly Matchup &rarr;',
        '$opponentTools'
    )) {
        if ($text.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-954 BLOCKED: staged Dashboard matchup action marker is missing: $required"
        }
    }

    if ($text -match 'Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
        throw 'BF-954 BLOCKED: Dashboard matchup actions introduced write or optimizer behavior.'
    }

    Write-Host 'BF-954 DASHBOARD MATCHUP ACTIONS ACCEPTANCE: PASS'
}
finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
