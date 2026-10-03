Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = Join-Path ([IO.Path]::GetTempPath()) ('Butler-bf1004-week-rollover-' + [guid]::NewGuid().ToString('N'))
try {
    [IO.Directory]::CreateDirectory($root) | Out-Null
    foreach ($name in @('butler-dashboard.ps1','butler-app-shell-core-single.ps1')) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination $root
    }

    & (Join-Path $PSScriptRoot 'butler-dashboard-bf715-transform.ps1') -DashboardPath (Join-Path $root 'butler-dashboard.ps1')

    $core = [IO.File]::ReadAllText((Join-Path $root 'butler-app-shell-core-single.ps1'))
    $dashboard = [IO.File]::ReadAllText((Join-Path $root 'butler-dashboard.ps1'))

    foreach ($required in @(
        'butler-lineup-snapshot-frame',
        'data-lineup-week=',
        'dashboard-weekly-attention',
        '$bf1004SnapshotWeek = if ($null -ne $lineupSnapshot)'
    )) {
        if ($dashboard.IndexOf($required,[System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-1004 BLOCKED: staged Dashboard marker is missing: $required"
        }
    }

    foreach ($required in @(
        '$currentMatchupWeek = [int]$matchup.Week',
        'The saved Lineup Review is from Week ',
        'Butler will not carry those player holds or lineup recommendations into Week ',
        'Old week expired',
        'Old lineup advice expired',
        'href="/matchup">Open Weekly Matchup &rarr;</a>',
        '(?<week>\d+)'
    )) {
        if ($core.IndexOf($required,[System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-1004 BLOCKED: staged week-rollover marker is missing: $required"
        }
    }

    $tokens = $null
    $errors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseInput($core,[ref]$tokens,[ref]$errors)
    if (@($errors).Count -gt 0) {
        throw 'BF-1004 BLOCKED: staged core failed PowerShell parse.'
    }
    $matches = @($ast.FindAll({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Add-DashboardMatchupSummary'
    },$true))
    if ($matches.Count -ne 1) {
        throw "BF-1004 BLOCKED: expected one Add-DashboardMatchupSummary, found $($matches.Count)."
    }
    $fn = $matches[0].Extent.Text
    if ([regex]::Matches($fn,[regex]::Escape('Invoke-ButlerReadOnlyTask')).Count -ne 1) {
        throw 'BF-1004 BLOCKED: rollover reconciliation added a Dashboard read.'
    }

    Write-Host 'BF-1004 WEEK-ROLLOVER LINEUP SNAPSHOT ACCEPTANCE: PASS'
    Write-Host 'Coverage: old weekly Lineup Review data is suppressed when the proven Sleeper matchup week changes, with no additional provider read or write.'
}
finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
