Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = Join-Path ([IO.Path]::GetTempPath()) ('Butler-bf1021-start-sit-' + [guid]::NewGuid().ToString('N'))
try {
    [IO.Directory]::CreateDirectory($root) | Out-Null
    foreach ($name in @('butler-dashboard.ps1', 'butler-app-shell-core-single.ps1')) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination $root
    }

    & (Join-Path $PSScriptRoot 'butler-dashboard-bf715-transform.ps1') -DashboardPath (Join-Path $root 'butler-dashboard.ps1')

    $corePath = Join-Path $root 'butler-app-shell-core-single.ps1'
    $tokens = $null
    $errors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($corePath, [ref]$tokens, [ref]$errors)
    if (@($errors).Count -ne 0) {
        $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
        throw "BF-1021 BLOCKED: staged core has parse errors: $summary"
    }

    $functions = @($ast.FindAll({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq 'ConvertTo-AutoFillHtml'
    }, $true))
    if ($functions.Count -ne 1) {
        throw "BF-1021 BLOCKED: expected one staged Start/Sit renderer, found $($functions.Count)."
    }

    $surface = $functions[0].Extent.Text
    foreach ($required in @(
        'Start/Sit Assistant',
        'What should I change?',
        '<h3>START</h3>',
        '<h3>SIT</h3>',
        'Current starter',
        'Recommended starter',
        'Projected difference',
        'Compare this swap',
        'Review queue',
        'Projection hold'
    )) {
        if ($surface.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-1021 BLOCKED: staged Start/Sit marker is missing: $required"
        }
    }

    if ($surface -match 'Invoke-RestMethod|Invoke-WebRequest|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
        throw 'BF-1021 BLOCKED: Start/Sit Assistant introduced provider, optimizer, or write behavior.'
    }

    $all = [IO.File]::ReadAllText($corePath)
    foreach ($required in @(
        'BF-1021 v0.4 Start/Sit Assistant',
        '.start-sit-assistant{padding:22px}',
        '.lineup-row.changed{border-color:',
        '.grid.four>.summary-card:nth-child(3)',
        '.grid.four>.summary-card:nth-child(4)'
    )) {
        if ($all.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-1021 BLOCKED: staged Start/Sit visual marker is missing: $required"
        }
    }

    Write-Host 'BF-1021 V0.4 START/SIT ASSISTANT ACCEPTANCE: PASS'
    Write-Host 'Coverage: dedicated Start/Sit naming, What-should-I-change summary, START/SIT cards, current/recommended starter labels, projection difference, compare actions, holds, and read-only safety.'
}
finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
