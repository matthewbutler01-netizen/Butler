Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = Join-Path ([IO.Path]::GetTempPath()) ('Butler-bf1003-current-week-readability-' + [Guid]::NewGuid().ToString('N'))
try {
    [IO.Directory]::CreateDirectory($root) | Out-Null
    foreach ($name in @('butler-dashboard.ps1', 'butler-app-shell-core-single.ps1')) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination $root
    }

    & (Join-Path $PSScriptRoot 'butler-dashboard-bf715-transform.ps1') -DashboardPath (Join-Path $root 'butler-dashboard.ps1')

    $corePath = Join-Path $root 'butler-app-shell-core-single.ps1'
    $dashboardPath = Join-Path $root 'butler-dashboard.ps1'
    $core = [IO.File]::ReadAllText($corePath)
    $dashboard = [IO.File]::ReadAllText($dashboardPath)

    foreach ($pair in @(
        [pscustomobject]@{ Label = 'core'; Text = $core },
        [pscustomobject]@{ Label = 'Dashboard'; Text = $dashboard }
    )) {
        foreach ($required in @(
            'BF-1003 current-week readability.',
            'main .panel a:not(.btn):not(.button):not(.command-button):not(.week-tool)',
            'color:#A8D3B5!important',
            'color:#B9DCC3!important',
            'background:#69A27D!important',
            'color:#C1CAC4!important'
        )) {
            if ($pair.Text.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
                throw "BF-1003 BLOCKED: $($pair.Label) staged readability marker is missing: $required"
            }
        }
    }

    $bf723 = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'butler-recover-roster-drift.ps1'))
    $bf823 = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'butler-lineup-evidence-recovery.ps1'))

    foreach ($pair in @(
        [pscustomobject]@{ Label = 'BF-723'; Text = $bf723 },
        [pscustomobject]@{ Label = 'BF-823'; Text = $bf823 }
    )) {
        if ([regex]::Matches($pair.Text, 'io\.butler\.bet\.cli\.ButlerSleeperCurrentWeekMatchupSyncCli').Count -ne 1) {
            throw "BF-1003 BLOCKED: $($pair.Label) must contain exactly one BF-840 current matchup sync."
        }
        $matchup = $pair.Text.IndexOf('BF-840 current weekly matchup sync', [System.StringComparison]::Ordinal)
        $postRoster = $pair.Text.IndexOf('BF-610 post-recovery target-roster verification', [System.StringComparison]::Ordinal)
        if ($matchup -lt 0 -or $postRoster -le $matchup) {
            throw "BF-1003 BLOCKED: $($pair.Label) current matchup sync must precede post-recovery roster verification."
        }
    }

    Write-Host 'BF-1003 CURRENT-WEEK + DARK-CONTRAST ACCEPTANCE: PASS'
    Write-Host 'Coverage: roster recovery now synchronizes exact current matchup/week, and dark manager surfaces use readable links, muted text, and primary actions.'
}
finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
