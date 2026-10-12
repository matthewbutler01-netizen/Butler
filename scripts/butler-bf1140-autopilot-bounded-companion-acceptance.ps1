Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# BF-1140: invoke the actual single-flight worker helper with local stubs.
# No HTTP, Sleeper call, provider data, or league operation is performed.
$path = Join-Path $PSScriptRoot 'butler-app-request-worker.ps1'
$tokens = $null
$errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile(
    $path, [ref]$tokens, [ref]$errors)
if (@($errors).Count -ne 0) { throw 'BF-1140 BLOCKED: public worker does not parse.' }
$node = $ast.Find({
    param($item)
    $item -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
    $item.Name -ceq 'Invoke-ExpensiveReadSingleFlightGet'
}, $true)
if ($null -eq $node) { throw 'BF-1140 BLOCKED: actual single-flight helper missing.' }
. ([scriptblock]::Create($node.Extent.Text))

function Get-ExpensiveReadSingleFlightKey {
    param([string]$RequestTarget)
    if ($RequestTarget -ceq '/') { return 'BF1140_DASH' }
    if ($RequestTarget -ceq '/matchup') { return 'BF1140_MATCHUP' }
    return ''
}
function New-Bf856RouteTiming { param([string]$RequestTarget) return $null }
function Get-EvidenceRefreshGeneration { param([hashtable]$State) return [long]0 }
function Test-EvidenceRefreshInProgress { param([hashtable]$State) return $false }
$script:bf1140Calls = New-Object System.Collections.ArrayList
function Invoke-AppCoreGet {
    param([int]$Port, [string]$RequestTarget, [int]$TimeoutMs = 180000)
    [void]$script:bf1140Calls.Add([pscustomobject]@{
        Target = $RequestTarget
        Timeout = $TimeoutMs
        Port = $Port
    })
    return [pscustomobject]@{
        StatusCode = 503
        StatusText = 'Synthetic unavailable'
        ContentType = 'text/plain; charset=utf-8'
        Body = 'Synthetic local only'
    }
}
$state = @{ SyncRoot = [object]::new() }
$null = Invoke-ExpensiveReadSingleFlightGet -Port 9 -RequestTarget '/' `
    -League 'BF1140_SYNTHETIC' -RefreshState $state `
    -WaitTimeoutMs 3000 -CoreTimeoutMs 12000
$null = Invoke-ExpensiveReadSingleFlightGet -Port 9 -RequestTarget '/matchup' `
    -League 'BF1140_SYNTHETIC' -RefreshState $state `
    -WaitTimeoutMs 3000 -CoreTimeoutMs 12000
$null = Invoke-ExpensiveReadSingleFlightGet -Port 9 -RequestTarget '/' `
    -League 'BF1140_DEFAULT' -RefreshState $state

if ($script:bf1140Calls.Count -ne 3 -or
    $script:bf1140Calls[0].Timeout -ne 12000 -or
    $script:bf1140Calls[1].Timeout -ne 12000 -or
    $script:bf1140Calls[2].Timeout -ne 180000 -or
    $script:bf1140Calls[0].Target -cne '/' -or
    $script:bf1140Calls[1].Target -cne '/matchup' -or
    $script:bf1140Calls[2].Port -ne 9) {
    throw 'BF-1140 BLOCKED: companion read or historical default timeout drifted.'
}

$source = [IO.File]::ReadAllText($path, [Text.Encoding]::ASCII)
foreach ($required in @(
    '$lockTaken = $mutex.WaitOne($WaitTimeoutMs)',
    '$companionSlotTaken = $companionSemaphore.WaitOne($WaitTimeoutMs)',
    '[ValidateRange(1000, 180000)][int]$WaitTimeoutMs = 180000',
    '[ValidateRange(1000, 180000)][int]$CoreTimeoutMs = 180000'
)) {
    if ($source.IndexOf($required, [StringComparison]::Ordinal) -lt 0) {
        throw 'BF-1140 BLOCKED: optional read timeouts were not applied safely.'
    }
}
# Two bounded '/' core reads: Auto-Pilot post-recovery and the existing
# Start/Sit on-open refresh. The read-only watch gets scoped wait/core limits.
if ([regex]::Matches($source, '-WaitTimeoutMs 3000 -CoreTimeoutMs 12000').Count -ne 2 -or
    [regex]::Matches($source, "-RequestTarget '/matchup' -TimeoutMs 12000").Count -ne 1 -or
    [regex]::Matches($source, "-RequestTarget '/' -TimeoutMs 12000").Count -ne 2 -or
    $source.IndexOf('Invoke-AutomaticWeekRecovery -Root $Root -League $League',
        [StringComparison]::Ordinal) -lt 0) {
    throw 'BF-1140 BLOCKED: exact Auto-Pilot read-only/recovery scope drifted.'
}
# BF-1142: exercise the actual named two-slot semaphore under contention.
# Source-only matching cannot prove that a bounded Auto-Pilot GET stops
# waiting for a busy companion instead of entering the core after a timeout.
# This stays entirely in-process: no TCP, external service or league data.
$gateName = 'Local\Butler.Companion.Heavy.{0}' -f $PID
$occupiedGate = [System.Threading.Semaphore]::new(2, 2, $gateName)
$heldSlots = 0
try {
    for ($slot = 0; $slot -lt 2; $slot++) {
        if (-not $occupiedGate.WaitOne(0)) {
            throw 'BF-1142 BLOCKED: could not reserve the two synthetic companion slots.'
        }
        $heldSlots++
    }
    $beforeCalls = $script:bf1140Calls.Count
    $expired = $false
    $timer = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $null = Invoke-ExpensiveReadSingleFlightGet -Port 9 -RequestTarget '/matchup' `
            -League 'BF1142_BUSY_GATE' -RefreshState $state `
            -WaitTimeoutMs 1000 -CoreTimeoutMs 12000
    }
    catch {
        if ($_.Exception.Message -ceq 'BF-694 BLOCKED: finite wait for companion heavy read capacity expired.') {
            $expired = $true
        }
        else { throw }
    }
    finally { $timer.Stop() }
    if (-not $expired -or $timer.ElapsedMilliseconds -lt 700 -or
        $timer.ElapsedMilliseconds -gt 10000 -or
        $script:bf1140Calls.Count -ne $beforeCalls) {
        throw 'BF-1142 BLOCKED: busy companion bypassed its bounded wait or reached the local core.'
    }
}
finally {
    for ($slot = 0; $slot -lt $heldSlots; $slot++) {
        [void]$occupiedGate.Release()
    }
    $occupiedGate.Dispose()
}
Write-Host 'BF-1142 PASS: occupied two-slot companion times out without a core request.'

Write-Host 'BF-1140 PASS: bounded Auto-Pilot companion core reads, main-page defaults unchanged.'
