Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$worker = Join-Path $PSScriptRoot 'butler-app-request-worker.ps1'
$tokens = $null
$errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($worker, [ref]$tokens, [ref]$errors)
if ($errors.Count -gt 0) { throw 'Butler worker parser failed.' }

# Exercise the actual single-flight cache functions with only the inner core GET stubbed.
foreach ($name in @(
    'Get-Bf856ElapsedMs',
    'New-Bf856RouteTiming',
    'Get-EvidenceRefreshGeneration',
    'Complete-DecisionRefreshAttempt',
    'Invoke-TeamSingleFlightGet',
    'Get-ExpensiveReadSingleFlightKey',
    'Invoke-ExpensiveReadSingleFlightGet'
)) {
    $node = $ast.Find({
        param($item)
        $item -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $item.Name -ceq $name
    }, $true)
    if ($null -eq $node) { throw "Missing cache function $name" }
    . ([scriptblock]::Create($node.Extent.Text))
}

$script:bf856RouteTimingEnabled = $false
$script:coreReads = 0
function Invoke-AppCoreGet {
    param([int]$Port, [string]$RequestTarget)
    $script:coreReads += 1
    return [pscustomobject]@{
        StatusCode = 200
        StatusText = 'OK'
        ContentType = 'text/html; charset=utf-8'
        Body = "core-read-$($script:coreReads)"
    }
}

$league = [Guid]::NewGuid().ToString('N')
$state = [hashtable]::Synchronized(@{ Token = 'a' * 64; InProgress = $true })
if ((Get-EvidenceRefreshGeneration -State $state) -ne 0) { throw 'Unexpected startup evidence generation.' }

foreach ($route in @('/team', '/waivers', '/', '/matchup')) {
    $script:coreReads = 0
    $run = {
        if ($route -ceq '/team') {
            return Invoke-TeamSingleFlightGet -Port 1 -RequestTarget $route -League $league -RefreshState $state
        }
        return Invoke-ExpensiveReadSingleFlightGet -Port 1 -RequestTarget $route -League $league -RefreshState $state
    }

    $first = & $run
    $second = & $run
    if ($first.Body -cne 'core-read-1' -or $second.Body -cne 'core-read-1' -or $script:coreReads -ne 1) {
        throw "$route failed to reuse a valid same-generation read."
    }

    # A completed governed refresh, including a partial failure, invalidates
    # old team/dashboard/waiver/matchup evidence before the browser reload.
    Complete-DecisionRefreshAttempt -State $state
    if ($state.InProgress -or (Get-EvidenceRefreshGeneration -State $state) -le 0) {
        throw "$route refresh completion failed to release and advance generation."
    }
    $third = & $run
    $fourth = & $run
    if ($third.Body -cne 'core-read-2' -or $fourth.Body -cne 'core-read-2' -or $script:coreReads -ne 2) {
        throw "$route served a pre-refresh cached response or broke same-generation reuse."
    }
    $state.InProgress = $true
}

Write-Host 'EVIDENCE REFRESH CACHE GENERATION ACCEPTANCE: PASS (team, waiver, dashboard, matchup)'
