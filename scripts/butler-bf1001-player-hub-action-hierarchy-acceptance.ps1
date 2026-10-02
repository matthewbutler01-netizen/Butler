Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$transformPath = Join-Path $PSScriptRoot 'butler-app-bf1001-player-hub-action-hierarchy-transform.ps1'
$tokens = $null
$errors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($transformPath, [ref]$tokens, [ref]$errors)
if (@($errors).Count -ne 0) {
    $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-1001 BLOCKED: transform failed PowerShell parse before staging: $summary"
}

$root = Join-Path ([IO.Path]::GetTempPath()) ('Butler-bf1001-player-hub-' + [guid]::NewGuid().ToString('N'))
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
        throw "BF-1001 BLOCKED: staged core failed PowerShell parse: $summary"
    }

    $matches = @($ast.FindAll({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq 'Add-PlayerHubPresentation'
    }, $true))
    if ($matches.Count -ne 1) {
        throw "BF-1001 BLOCKED: expected one Player Hub presentation function, found $($matches.Count)."
    }

    $hub = $matches[0].Extent.Text
    foreach ($required in @(
        '<div class="player-hub-primary-action">',
        'Primary player action',
        'Compare this exact player',
        'href="/compare?left=$hrefId">Compare this player</a>',
        '<div class="button-row player-hub-secondary-actions">',
        'href="/players?q=$positionHref">Find more $(ConvertTo-HtmlText $View.Position)</a>',
        '$waiverAction',
        'href="/franchise?id=$teamHrefId">Scout franchise</a>',
        'href="/trade">Open Trade Analyzer</a>',
        'href="/matchup">Review Matchup</a>'
    )) {
        if ($hub.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-1001 BLOCKED: staged Player Hub marker is missing: $required"
        }
    }

    $coreText = [IO.File]::ReadAllText($corePath)
    foreach ($required in @(
        'BF-1001 Player Hub action hierarchy.',
        '.player-hub-primary-action{',
        '.player-hub-secondary-actions{',
        '@media(max-width:760px)'
    )) {
        if ($coreText.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-1001 BLOCKED: Player Hub hierarchy CSS marker is missing: $required"
        }
    }

    if ($hub -match 'Invoke-RestMethod|Invoke-WebRequest|Invoke-ButlerReadOnly|Invoke-Bf742DashboardWorkerRead|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|returnUrl|redirectUrl|javascript:') {
        throw 'BF-1001 BLOCKED: Player Hub action hierarchy introduced provider, backend-read, optimizer, write, or open-redirect behavior.'
    }

    Write-Host 'BF-1001 PLAYER HUB ACTION-HIERARCHY ACCEPTANCE: PASS'
    Write-Host 'Coverage: exact-player Compare primary action, preserved matchup/discovery/waiver/scout/trade paths, responsive hierarchy, and no new reads or writes'
}
finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
