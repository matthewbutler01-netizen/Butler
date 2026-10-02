Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$transformPath = Join-Path $PSScriptRoot 'butler-app-bf992-dashboard-matchup-lineup-role-separation-transform.ps1'
$tokens = $null
$errors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($transformPath, [ref]$tokens, [ref]$errors)
if (@($errors).Count -ne 0) {
    $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-992 BLOCKED: transform failed PowerShell parse before staging: $summary"
}

$root = Join-Path ([IO.Path]::GetTempPath()) ('Butler-bf992-matchup-role-' + [guid]::NewGuid().ToString('N'))
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
        throw "BF-992 BLOCKED: staged core failed PowerShell parse: $summary"
    }

    $matches = @($ast.FindAll({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq 'Add-DashboardMatchupSummary'
    }, $true))
    if ($matches.Count -ne 1) {
        throw "BF-992 BLOCKED: expected one Add-DashboardMatchupSummary function, found $($matches.Count)."
    }

    $matchup = $matches[0].Extent.Text

    foreach ($required in @(
        'href="/matchup">Open Weekly Matchup &rarr;</a>',
        '<strong>Opponent tools</strong>',
        'href="/franchise?id=',
        'href="/trade?opponent=',
        "Invoke-ButlerReadOnlyTask -Task ':bet:bet-cli:sleeperLiveWaiverTargetRosterContextAudit'"
    )) {
        if ($matchup.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-992 BLOCKED: staged matchup ownership marker is missing: $required"
        }
    }

    foreach ($forbidden in @(
        '$matchupPrimaryHref',
        '$matchupPrimaryLabel',
        '$lineupCardMatch',
        '$lineupCardHtml',
        '$matchupTool',
        'href="/team/autofill">Review Lineup &rarr;</a>',
        '<strong>Matchup tools</strong>'
    )) {
        if ($matchup.IndexOf($forbidden, [System.StringComparison]::Ordinal) -ge 0) {
            throw "BF-992 BLOCKED: duplicated lineup/matchup behavior remains: $forbidden"
        }
    }

    $readCount = [regex]::Matches($matchup, [regex]::Escape('Invoke-ButlerReadOnlyTask')).Count
    if ($readCount -ne 1) {
        throw "BF-992 BLOCKED: expected one existing governed matchup read, found $readCount."
    }

    if ($matchup -match 'Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|returnUrl|redirectUrl|javascript:') {
        throw 'BF-992 BLOCKED: matchup/lineup role separation introduced optimizer, write, or open-redirect behavior.'
    }

    Write-Host 'BF-992 DASHBOARD MATCHUP/LINEUP ROLE SEPARATION ACCEPTANCE: PASS'
    Write-Host 'Coverage: Matchup card owns matchup/opponent context; Lineup card remains the only review/refresh lineup action surface'
}
finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
