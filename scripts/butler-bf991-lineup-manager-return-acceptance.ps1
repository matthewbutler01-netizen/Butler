Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$transformPath = Join-Path $PSScriptRoot 'butler-app-bf991-lineup-manager-return-transform.ps1'
$tokens = $null
$errors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($transformPath, [ref]$tokens, [ref]$errors)
if (@($errors).Count -ne 0) {
    $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-991 BLOCKED: transform failed PowerShell parse before staging: $summary"
}

$root = Join-Path ([IO.Path]::GetTempPath()) ('Butler-bf991-lineup-return-' + [guid]::NewGuid().ToString('N'))
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
        throw "BF-991 BLOCKED: staged core failed PowerShell parse: $summary"
    }

    $matches = @($ast.FindAll({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq 'ConvertTo-AutoFillHtml'
    }, $true))
    if ($matches.Count -ne 1) {
        throw "BF-991 BLOCKED: expected one ConvertTo-AutoFillHtml function, found $($matches.Count)."
    }

    $lineup = $matches[0].Extent.Text

    foreach ($required in @(
        'href=`"/`">Back to Dashboard</a>',
        'href=`"/matchup`">Back to Matchup</a>',
        'href=`"/team`">Back to My Team</a>',
        'href=`"/team/autofill`">Refresh projection</a>',
        'Review queue'
    )) {
        if ($lineup.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-991 BLOCKED: staged Lineup Review return marker is missing: $required"
        }
    }

    $completedReturnCluster = '<a class=`"btn btn-secondary`" href=`"/`">Back to Dashboard</a><a class=`"btn btn-secondary`" href=`"/matchup`">Back to Matchup</a><a class=`"btn btn-secondary`" href=`"/team`">Back to My Team</a>'
    $clusterCount = [regex]::Matches($lineup, [regex]::Escape($completedReturnCluster)).Count
    if ($clusterCount -ne 1) {
        throw "BF-991 BLOCKED: expected one completed manager-return action cluster, found $clusterCount."
    }

    $dashboardReturnCount = [regex]::Matches($lineup, [regex]::Escape('Back to Dashboard')).Count
    if ($dashboardReturnCount -ne 1) {
        throw "BF-991 BLOCKED: expected one Back to Dashboard action, found $dashboardReturnCount."
    }

    if ($lineup -match 'Invoke-RestMethod|Invoke-WebRequest|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|returnUrl|redirectUrl|window\.history|javascript:') {
        throw 'BF-991 BLOCKED: Lineup Review manager return navigation introduced provider, optimizer, write, history, or open-redirect behavior.'
    }

    Write-Host 'BF-991 LINEUP REVIEW MANAGER RETURN ACCEPTANCE: PASS'
    Write-Host 'Coverage: completed Lineup Review exposes Dashboard, Matchup, and My Team returns while preserving refresh and read-only behavior'
}
finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
