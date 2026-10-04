Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = Join-Path ([IO.Path]::GetTempPath()) ('Butler-bf1007-' + [guid]::NewGuid().ToString('N'))
try {
    [IO.Directory]::CreateDirectory($root) | Out-Null
    foreach ($name in @('butler-dashboard.ps1','butler-app-shell-core-single.ps1')) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination $root
    }

    & (Join-Path $PSScriptRoot 'butler-dashboard-bf715-transform.ps1') -DashboardPath (Join-Path $root 'butler-dashboard.ps1')

    $core = [IO.File]::ReadAllText((Join-Path $root 'butler-app-shell-core-single.ps1'))
    $dashboard = [IO.File]::ReadAllText((Join-Path $root 'butler-dashboard.ps1'))

    foreach ($required in @(
        'ManagerMoveCount = [int]$managerMoveCount',
        'Title = "Review $managerMoveCount lineup $moveWord"',
        'these are not separate manager moves',
        'Review $managerMoveCount lineup $moveNoun',
        'Manager moves</strong>'
    )) {
        if ($core.IndexOf($required,[System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-1007 BLOCKED: core manager-action marker is missing: $required"
        }
    }

    foreach ($required in @(
        'Latest AutoFill recommends $managerMoveCount lineup $moveWord',
        'Lineup review complete; no change proven',
        'these are not separate manager moves'
    )) {
        if ($dashboard.IndexOf($required,[System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-1007 BLOCKED: Dashboard manager-action marker is missing: $required"
        }
    }

    $tokens = $null
    $errors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseInput($core,[ref]$tokens,[ref]$errors)
    if (@($errors).Count -gt 0) {
        throw 'BF-1007 BLOCKED: staged core failed parse.'
    }

    $matches = @($ast.FindAll({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq 'Get-MatchupLineupDecisionView'
    },$true))
    if ($matches.Count -ne 1) {
        throw "BF-1007 BLOCKED: expected one Get-MatchupLineupDecisionView function, found $($matches.Count)."
    }

    Invoke-Expression $matches[0].Extent.Text

    $assignments = @(
        [pscustomobject]@{ Changed = $true },
        [pscustomobject]@{ Changed = $true },
        [pscustomobject]@{ Changed = $true }
    )
    $promotions = @([pscustomobject]@{ Name = 'Tank Bigsby' })
    $benchMoves = @([pscustomobject]@{ Name = 'Jadarian Price' })

    $full = [pscustomobject]@{
        Requested = $true
        Ready = $true
        Assignments = $assignments
        ProjectionHolds = @()
        ProjectionCoverage = 'COMPLETE'
        Promotions = $promotions
        BenchMoves = $benchMoves
        Gain = '+3.41'
    }

    $decision = Get-MatchupLineupDecisionView -AutoFill $full -SavedReview $null
    if ([string]$decision.Title -cne 'Review 1 lineup move') {
        throw "BF-1007 BLOCKED: expected one manager move, got title: $($decision.Title)"
    }
    if ([string]$decision.Detail -notmatch 'Tank Bigsby' -or
        [string]$decision.Detail -notmatch 'Jadarian Price' -or
        [string]$decision.Detail -notmatch '3 scoreable slot assignments') {
        throw "BF-1007 BLOCKED: manager move detail did not preserve promotion, bench move, and slot-placement evidence."
    }

    $partial = [pscustomobject]@{
        Requested = $true
        Ready = $true
        Assignments = $assignments
        ProjectionHolds = @([pscustomobject]@{ Name = 'KC Concepcion' })
        ProjectionCoverage = 'PARTIAL'
        Promotions = $promotions
        BenchMoves = $benchMoves
        Gain = '+3.41'
    }

    $partialDecision = Get-MatchupLineupDecisionView -AutoFill $partial -SavedReview $null
    if ([string]$partialDecision.Detail -notmatch '^1 lineup move') {
        throw "BF-1007 BLOCKED: partial review still counts slot assignments as manager moves: $($partialDecision.Detail)"
    }
    if ([string]$partialDecision.Detail -notmatch '3 scoreable slot assignments') {
        throw 'BF-1007 BLOCKED: partial review lost internal slot-assignment disclosure.'
    }

    Write-Host 'BF-1007 MANAGER-ACTION COUNTING ACCEPTANCE: PASS'
    Write-Host 'Coverage: 3 optimizer slot assignments + 1 promotion/bench decision -> 1 manager move, with slot evidence preserved.'
}
finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
