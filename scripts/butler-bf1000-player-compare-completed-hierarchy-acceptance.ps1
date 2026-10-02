Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$transformPath = Join-Path $PSScriptRoot 'butler-app-bf1000-player-compare-completed-hierarchy-transform.ps1'
$tokens = $null
$errors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($transformPath, [ref]$tokens, [ref]$errors)
if (@($errors).Count -ne 0) {
    $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-1000 BLOCKED: transform failed PowerShell parse before staging: $summary"
}

$root = Join-Path ([IO.Path]::GetTempPath()) ('Butler-bf1000-player-compare-' + [guid]::NewGuid().ToString('N'))
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
        throw "BF-1000 BLOCKED: staged core failed PowerShell parse: $summary"
    }

    function Get-OneFunction([string]$Name) {
        $matches = @($ast.FindAll({
            param($node)
            $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $Name
        }, $true))
        if ($matches.Count -ne 1) {
            throw "BF-1000 BLOCKED: expected one $Name function, found $($matches.Count)."
        }
        return $matches[0].Extent.Text
    }

    $compare = Get-OneFunction 'ConvertTo-PlayerCompareHtml'
    $card = Get-OneFunction 'ConvertTo-PlayerCompareCardHtml'

    foreach ($required in @(
        '<div class="eyebrow">Completed comparison</div>',
        'Two exact rostered players are loaded side by side.',
        '<a class="btn btn-primary" href="/players">Compare different players</a>',
        '<a class="btn btn-secondary" href="$swapHref">Swap sides</a>',
        '$returnToLineupAction',
        'Back to Lineup Review',
        '$lineupContextSuffix = if ($Request.FromLineup)',
        '$swapHref = "/compare?left=$rightHref&right=$leftHref$lineupContextSuffix$swapSuffix"',
        'Butler does not choose a winner',
        'NOT A RANKING'
    )) {
        if ($compare.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-1000 BLOCKED: staged Player Compare marker is missing: $required"
        }
    }

    foreach ($required in @(
        'Compare with another $(ConvertTo-HtmlText $Player.Position)',
        'View Player Detail',
        'Scout franchise',
        '$waiverAction'
    )) {
        if ($card.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-1000 BLOCKED: Player Compare card continuation marker is missing: $required"
        }
    }

    $surface = $compare
    if ($surface -match 'Invoke-RestMethod|Invoke-WebRequest|Invoke-ButlerReadOnly|Invoke-Bf742DashboardWorkerRead|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|returnUrl|redirectUrl|javascript:') {
        throw 'BF-1000 BLOCKED: completed Player Compare hierarchy introduced provider, backend-read, optimizer, write, or open-redirect behavior.'
    }

    Write-Host 'BF-1000 PLAYER COMPARE COMPLETED-HIERARCHY ACCEPTANCE: PASS'
    Write-Host 'Coverage: completed-state orientation, different-pair primary action, preserved lineup return and exact swap, per-player continuation tools, and no new reads or writes'
}
finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
