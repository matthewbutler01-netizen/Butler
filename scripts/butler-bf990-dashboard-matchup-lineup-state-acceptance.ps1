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

    foreach ($required in @(
        '$matchupPrimaryHref = ''/team/autofill''',
        '$matchupPrimaryLabel = ''Review Lineup''',
        '$matchupPrimaryHref = ''/matchup''',
        '$matchupPrimaryLabel = ''View Matchup''',
        '$matchupPrimaryLabel = if ($lineupCardHtml -match ''>Refresh Lineup\s*&rarr;</a>'') { ''Refresh Lineup'' } else { ''Review Lineup'' }',
        '$matchupTool = if ($matchupPrimaryHref -ceq ''/matchup'')',
        '(ConvertTo-HtmlText $matchupPrimaryHref)',
        '(ConvertTo-HtmlText $matchupPrimaryLabel)',
        '<strong>Matchup tools</strong>',
        'href="/franchise?id=',
        'href="/trade?opponent='
    )) {
        if ($matchup.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-990 BLOCKED: staged lineup-state matchup marker is missing: $required"
        }
    }

    $oldFixedPrimary = [regex]::Matches(
        $matchup,
        [regex]::Escape('href="/team/autofill">Review Lineup &rarr;</a>')
    ).Count
    if ($oldFixedPrimary -ne 0) {
        throw "BF-990 BLOCKED: fixed BF-989 lineup primary remains $oldFixedPrimary time(s)."
    }

    $readCount = [regex]::Matches($matchup, [regex]::Escape('Invoke-ButlerReadOnlyTask')).Count
    if ($readCount -ne 1) {
        throw "BF-990 BLOCKED: expected one existing governed matchup read, found $readCount."
    }

    $lineupCardReadCount = [regex]::Matches($matchup, [regex]::Escape('$lineupCardMatch = [regex]::Match(')).Count
    if ($lineupCardReadCount -ne 1) {
        throw "BF-990 BLOCKED: expected one rendered Lineup-card state read, found $lineupCardReadCount."
    }

    if ($matchup -match 'Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|returnUrl|redirectUrl|javascript:') {
        throw 'BF-990 BLOCKED: state-aware matchup action introduced optimizer, write, or open-redirect behavior.'
    }

    Write-Host 'BF-990 DASHBOARD MATCHUP LINEUP-STATE ACTION ACCEPTANCE: PASS'
    Write-Host 'Coverage: matchup primary mirrors existing Dashboard lineup state (review/refresh/matchup) without another provider read or duplicate Matchup link'
}
finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
