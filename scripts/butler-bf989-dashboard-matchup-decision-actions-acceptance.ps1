Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$transformPath = Join-Path $PSScriptRoot 'butler-app-bf989-dashboard-matchup-decision-actions-transform.ps1'
$tokens = $null
$errors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($transformPath, [ref]$tokens, [ref]$errors)
if (@($errors).Count -ne 0) {
    $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-989 BLOCKED: transform failed PowerShell parse before staging: $summary"
}

$root = Join-Path ([IO.Path]::GetTempPath()) ('Butler-bf989-matchup-actions-' + [guid]::NewGuid().ToString('N'))
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
        throw "BF-989 BLOCKED: staged core failed PowerShell parse: $summary"
    }

    $matches = @($ast.FindAll({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq 'Add-DashboardMatchupSummary'
    }, $true))
    if ($matches.Count -ne 1) {
        throw "BF-989 BLOCKED: expected one Add-DashboardMatchupSummary function, found $($matches.Count)."
    }

    $matchup = $matches[0].Extent.Text

    foreach ($required in @(
        'href="/team/autofill">Review Lineup &rarr;</a>',
        '<strong>Matchup tools</strong>',
        'href="/matchup">Matchup</a>',
        'href="/franchise?id=',
        'href="/trade?opponent=',
        "Invoke-ButlerReadOnlyTask -Task ':bet:bet-cli:sleeperLiveWaiverTargetRosterContextAudit'"
    )) {
        if ($matchup.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-989 BLOCKED: staged Dashboard matchup marker is missing: $required"
        }
    }

    $oldPrimaryCount = [regex]::Matches($matchup, [regex]::Escape('Open Weekly Matchup &rarr;')).Count
    if ($oldPrimaryCount -ne 0) {
        throw "BF-989 BLOCKED: old generic matchup primary action remains $oldPrimaryCount time(s)."
    }

    $reviewCount = [regex]::Matches($matchup, [regex]::Escape('Review Lineup &rarr;')).Count
    if ($reviewCount -ne 1) {
        throw "BF-989 BLOCKED: expected one Review Lineup primary action, found $reviewCount."
    }

    $readCount = [regex]::Matches($matchup, [regex]::Escape('Invoke-ButlerReadOnlyTask')).Count
    if ($readCount -ne 1) {
        throw "BF-989 BLOCKED: expected one existing governed matchup read, found $readCount."
    }

    if ($matchup -match 'Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|returnUrl|redirectUrl|javascript:') {
        throw 'BF-989 BLOCKED: Dashboard matchup decision actions introduced optimizer, write, or open-redirect behavior.'
    }

    Write-Host 'BF-989 DASHBOARD MATCHUP DECISION ACTIONS ACCEPTANCE: PASS'
    Write-Host 'Coverage: Dashboard matchup promotes Review Lineup while keeping Matchup/Scout/Trade as read-only secondary tools'
}
finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
