Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$checks = @(
    # Foundational presentation/navigation contracts.
    [pscustomobject]@{ Id = 'BF-969'; Script = 'butler-bf969-dashboard-history-shortcut-acceptance.ps1' },
    [pscustomobject]@{ Id = 'BF-970'; Script = 'butler-bf970-shared-manager-navigation-acceptance.ps1' },
    [pscustomobject]@{ Id = 'BF-971'; Script = 'butler-bf971-companion-navigation-parity-acceptance.ps1' },
    [pscustomobject]@{ Id = 'BF-972'; Script = 'butler-bf972-refresh-outcome-navigation-acceptance.ps1' },
    [pscustomobject]@{ Id = 'BF-973'; Script = 'butler-bf973-blocked-page-presentation-acceptance.ps1' },

    # Final manager-surface contracts. Each acceptance stages the authoritative
    # current transform chain so this suite closes the actual MVP presentation,
    # not only the older navigation shell.
    [pscustomobject]@{ Id = 'BF-988'; Script = 'butler-bf988-replacement-workflow-orientation-acceptance.ps1' },
    [pscustomobject]@{ Id = 'BF-991'; Script = 'butler-bf991-lineup-manager-return-acceptance.ps1' },
    [pscustomobject]@{ Id = 'BF-993'; Script = 'butler-bf993-dashboard-glance-scanability-acceptance.ps1' },
    [pscustomobject]@{ Id = 'BF-994'; Script = 'butler-bf994-my-team-roster-scanability-acceptance.ps1' },
    [pscustomobject]@{ Id = 'BF-995'; Script = 'butler-bf995-trade-analyzer-decision-flow-acceptance.ps1' },
    [pscustomobject]@{ Id = 'BF-996'; Script = 'butler-bf996-league-manager-orientation-acceptance.ps1' },
    [pscustomobject]@{ Id = 'BF-997'; Script = 'butler-bf997-franchise-scout-action-hierarchy-acceptance.ps1' },
    [pscustomobject]@{ Id = 'BF-998'; Script = 'butler-bf998-decision-history-scope-acceptance.ps1' },
    [pscustomobject]@{ Id = 'BF-999'; Script = 'butler-bf999-player-search-result-hierarchy-acceptance.ps1' },
    [pscustomobject]@{ Id = 'BF-1000'; Script = 'butler-bf1000-player-compare-completed-hierarchy-acceptance.ps1' },
    [pscustomobject]@{ Id = 'BF-1001'; Script = 'butler-bf1001-player-hub-action-hierarchy-acceptance.ps1' },
    [pscustomobject]@{ Id = 'BF-1003'; Script = 'butler-bf1003-current-week-dark-contrast-acceptance.ps1' }
)

Write-Host ''
Write-Host '=== BUTLER MVP MANAGER PRESENTATION CLOSEOUT ==='
Write-Host 'Boundary: presentation/navigation acceptance only; no provider refresh, optimizer, transaction, FAAB, or roster write.'

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
Write-Host 'BUTLER MVP MANAGER PRESENTATION CLOSEOUT: PASS'
Write-Host ('Checks passed: ' + (($checks | ForEach-Object { $_.Id }) -join ', '))
