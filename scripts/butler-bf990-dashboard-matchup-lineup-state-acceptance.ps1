Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$transformPath = Join-Path $PSScriptRoot 'butler-app-bf990-dashboard-matchup-lineup-state-transform.ps1'
$tokens = $null
$errors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($transformPath, [ref]$tokens, [ref]$errors)
if (@($errors).Count -ne 0) {
    $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-990 BLOCKED: transform failed PowerShell parse before staging: $summary"
}

$root = Join-Path ([IO.Path]::GetTempPath()) ('Butler-bf990-matchup-lineup-state-' + [guid]::NewGuid().ToString('N'))
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
        throw "BF-990 BLOCKED: staged core failed PowerShell parse: $summary"
    }

    $matches = @($ast.FindAll({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq 'Add-DashboardMatchupSummary'
    }, $true))
    if ($matches.Count -ne 1) {
        throw "BF-990 BLOCKED: expected one Add-DashboardMatchupSummary function, found $($matches.Count)."
    }

    $matchup = $matches[0].Extent.Text

    # BF-990 remains covered at the transform-source level because BF-992
    # intentionally removes its matchup/lineup coupling from the final staged app.
    $bf990Source = [IO.File]::ReadAllText($transformPath)
    foreach ($required in @(
        '$matchupPrimaryHref = ''/team/autofill''',
        '$matchupPrimaryLabel = ''Review Lineup''',
        '$matchupPrimaryHref = ''/matchup''',
        '$matchupPrimaryLabel = ''View Matchup''',
        '$lineupCardMatch = [regex]::Match(',
        '$matchupTool = if ($matchupPrimaryHref -ceq ''/matchup'')'
    )) {
        if ($bf990Source.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-990 BLOCKED: source state-aware matchup marker is missing: $required"
        }
    }

    # Final staging is governed by BF-992/BF-993 and BF-1004. Matchup must stay
    # a neutral matchup surface while Lineup owns review/refresh actions.
    foreach ($required in @(
        'href="/matchup">Open Weekly Matchup &rarr;</a>',
        '<strong>Opponent tools</strong>',
        'href="/franchise?id=',
        'href="/trade?opponent=',
        '$currentMatchupWeek = [int]$matchup.Week'
    )) {
        if ($matchup.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-990 BLOCKED: final staged matchup ownership marker is missing: $required"
        }
    }

    foreach ($forbidden in @(
        '$matchupPrimaryHref',
        '$matchupPrimaryLabel',
        '$lineupCardMatch = [regex]::Match(',
        '$matchupTool',
        'href="/team/autofill">Review Lineup &rarr;</a>'
    )) {
        if ($matchup.IndexOf($forbidden, [System.StringComparison]::Ordinal) -ge 0) {
            throw "BF-990 BLOCKED: obsolete matchup/lineup coupling remains in final staging: $forbidden"
        }
    }

    $readCount = [regex]::Matches($matchup, [regex]::Escape('Invoke-ButlerReadOnlyTask')).Count
    if ($readCount -ne 1) {
        throw "BF-990 BLOCKED: expected one existing governed matchup read, found $readCount."
    }

    if ($matchup -match 'Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|returnUrl|redirectUrl|javascript:') {
        throw 'BF-990 BLOCKED: state-aware matchup action introduced optimizer, write, or open-redirect behavior.'
    }

    Write-Host 'BF-990 DASHBOARD MATCHUP LINEUP-STATE ACTION ACCEPTANCE: PASS'
    Write-Host 'Coverage: BF-990 source state-awareness remains covered; final BF-992/BF-993/BF-1004 staging keeps Matchup neutral with one existing read'
}
finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
