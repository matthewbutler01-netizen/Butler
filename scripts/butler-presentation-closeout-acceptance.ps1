Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$checks = @(
    [pscustomobject]@{ Id = 'BF-969'; Script = 'butler-bf969-dashboard-history-shortcut-acceptance.ps1' },
    [pscustomobject]@{ Id = 'BF-970'; Script = 'butler-bf970-shared-manager-navigation-acceptance.ps1' },
    [pscustomobject]@{ Id = 'BF-971'; Script = 'butler-bf971-companion-navigation-parity-acceptance.ps1' },
    [pscustomobject]@{ Id = 'BF-972'; Script = 'butler-bf972-refresh-outcome-navigation-acceptance.ps1' },
    [pscustomobject]@{ Id = 'BF-973'; Script = 'butler-bf973-blocked-page-presentation-acceptance.ps1' }
)

Write-Host ''
Write-Host '=== BUTLER PRESENTATION CLOSEOUT SUITE ==='

foreach ($check in $checks) {
    $path = Join-Path $PSScriptRoot $check.Script
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "PRESENTATION CLOSEOUT BLOCKED: $($check.Id) acceptance script not found at $path"
    }

    Write-Host ''
    Write-Host ("--- {0} ---" -f $check.Id)
    & $path
}

Write-Host ''
Write-Host 'BUTLER PRESENTATION CLOSEOUT ACCEPTANCE: PASS'
Write-Host ('Checks passed: ' + (($checks | ForEach-Object { $_.Id }) -join ', '))
