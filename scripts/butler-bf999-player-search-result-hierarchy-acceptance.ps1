Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$transformPath = Join-Path $PSScriptRoot 'butler-app-bf999-player-search-result-hierarchy-transform.ps1'
$tokens = $null
$errors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($transformPath, [ref]$tokens, [ref]$errors)
if (@($errors).Count -ne 0) {
    $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-999 BLOCKED: transform failed PowerShell parse before staging: $summary"
}

$root = Join-Path ([IO.Path]::GetTempPath()) ('Butler-bf999-player-search-' + [guid]::NewGuid().ToString('N'))
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
        throw "BF-999 BLOCKED: staged core failed PowerShell parse: $summary"
    }

    $matches = @($ast.FindAll({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq 'ConvertTo-PlayerSearchHtml'
    }, $true))
    if ($matches.Count -ne 1) {
        throw "BF-999 BLOCKED: expected one ConvertTo-PlayerSearchHtml function, found $($matches.Count)."
    }
    $search = $matches[0].Extent.Text

    foreach ($required in @(
        '<div class=`"player-search-primary-action`">',
        '<span class=`"eyebrow`">Primary drill-down</span>',
        '<strong>Open this exact player</strong>',
        'Player Detail keeps the exact player ID and returns to this search query.',
        'class=`"btn btn-primary`" href=`"/player?id=$hrefId&from=players&q=$searchReturnHref`">View Player Detail</a>',
        '<div class=`"button-row player-search-secondary-actions`">',
        'href=`"/compare?left=$hrefId`">Compare this player</a>',
        'Scout franchise',
        '$resultWaiverAction',
        'Quick position searches',
        'Free agents remain on Waiver Board.'
    )) {
        if ($search.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-999 BLOCKED: staged Player Search marker is missing: $required"
        }
    }

    $coreText = [IO.File]::ReadAllText($corePath)
    foreach ($required in @(
        'BF-999 Player Search result hierarchy.',
        '.player-search-primary-action{',
        '.player-search-secondary-actions{',
        '@media(max-width:760px)'
    )) {
        if ($coreText.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-999 BLOCKED: Player Search result-hierarchy CSS marker is missing: $required"
        }
    }

    $detailCount = [regex]::Matches($search, [regex]::Escape('View Player Detail</a>')).Count
    if ($detailCount -ne 1) {
        throw "BF-999 BLOCKED: expected one Player Search Player Detail action template, found $detailCount."
    }

    $surface = $search
    if ($surface -match 'Invoke-RestMethod|Invoke-WebRequest|Invoke-ButlerReadOnly|Invoke-Bf742DashboardWorkerRead|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|returnUrl|redirectUrl|javascript:') {
        throw 'BF-999 BLOCKED: Player Search result hierarchy introduced provider, backend-read, optimizer, write, or open-redirect behavior.'
    }

    Write-Host 'BF-999 PLAYER SEARCH RESULT-HIERARCHY ACCEPTANCE: PASS'
    Write-Host 'Coverage: Player Detail primary drill-down, preserved exact search return, secondary compare/scout/waiver tools, responsive result cards, and no new reads or writes'
}
finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
