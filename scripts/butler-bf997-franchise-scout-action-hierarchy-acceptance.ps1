Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$transformPath = Join-Path $PSScriptRoot 'butler-app-bf997-franchise-scout-action-hierarchy-transform.ps1'
$tokens = $null
$errors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($transformPath, [ref]$tokens, [ref]$errors)
if (@($errors).Count -ne 0) {
    $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-997 BLOCKED: transform failed PowerShell parse before staging: $summary"
}

$root = Join-Path ([IO.Path]::GetTempPath()) ('Butler-bf997-franchise-scout-' + [guid]::NewGuid().ToString('N'))
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
        throw "BF-997 BLOCKED: staged core failed PowerShell parse: $summary"
    }

    function Get-OneFunction {
        param([Parameter(Mandatory = $true)][string]$Name)
        $matches = @($ast.FindAll({
            param($node)
            $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
            $node.Name -eq $Name
        }, $true))
        if ($matches.Count -ne 1) {
            throw "BF-997 BLOCKED: expected one $Name function, found $($matches.Count)."
        }
        return $matches[0].Extent.Text
    }

    $scout = Get-OneFunction -Name 'Add-FranchiseScoutPresentation'
    foreach ($required in @(
        '$teamHrefId = [System.Uri]::EscapeDataString([string]$View.TeamId)',
        '$teamNameHtml = ConvertTo-HtmlText ([string]$View.TeamName)',
        '<div class="eyebrow">Manager actions</div><h2>Scout this franchise</h2>',
        'You are scouting <strong>$teamNameHtml</strong>.',
        '<span>Selected franchise</span><strong>$teamNameHtml</strong>',
        'Trade Analyzer keeps this exact team as the selected partner through review.',
        '<span class="eyebrow">Primary next step</span>',
        '<h3>Build the exact deal</h3>',
        'href="/trade?opponent=$teamHrefId">Open Trade Analyzer</a>',
        '<span class="eyebrow">Continue scouting</span>',
        'href="/league">Back to League</a>',
        'href="/players">Find a player</a>',
        'href="/compare">Compare players</a>'
    )) {
        if ($scout.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-997 BLOCKED: staged Franchise Scout marker is missing: $required"
        }
    }

    $coreText = [IO.File]::ReadAllText($corePath)
    foreach ($required in @(
        'View franchise evidence',
        'BF-997 Franchise Scout action hierarchy.',
        '.franchise-scout-lock{',
        '.franchise-scout-action-grid{',
        '.franchise-scout-primary{',
        '@media(max-width:760px)'
    )) {
        if ($coreText.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-997 BLOCKED: Franchise Scout final-surface marker is missing: $required"
        }
    }

    $exactTradeCount = [regex]::Matches($scout, [regex]::Escape('href="/trade?opponent=$teamHrefId">Open Trade Analyzer</a>')).Count
    if ($exactTradeCount -ne 1) {
        throw "BF-997 BLOCKED: expected one exact Franchise Scout Trade Analyzer action, found $exactTradeCount."
    }

    $surface = $scout
    if ($surface -match 'Invoke-RestMethod|Invoke-WebRequest|Invoke-ButlerReadOnly|Invoke-Bf742DashboardWorkerRead|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|returnUrl|redirectUrl|javascript:') {
        throw 'BF-997 BLOCKED: Franchise Scout action hierarchy introduced provider, backend-read, optimizer, write, or open-redirect behavior.'
    }

    Write-Host 'BF-997 FRANCHISE SCOUT ACTION-HIERARCHY ACCEPTANCE: PASS'
    Write-Host 'Coverage: selected-franchise identity, exact partner-locked Trade Analyzer primary action, preserved league/player tools, responsive hierarchy, compact evidence, and no new reads or writes'
}
finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
