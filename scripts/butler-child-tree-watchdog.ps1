param(
    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 2147483647)]
    [int]$OwnerPid,

    [Parameter(Mandatory = $true)]
    [long]$OwnerStartTicks,

    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 2147483647)]
    [int]$ChildPid,

    [Parameter(Mandatory = $true)]
    [long]$ChildStartTicks
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$taskkill = Join-Path $env:SystemRoot 'System32\taskkill.exe'

function Get-ProcessStartTicks {
    param([Parameter(Mandatory = $true)][int]$ProcessId)

    try {
        $process = Get-Process -Id $ProcessId -ErrorAction Stop
        return [long]$process.StartTime.ToUniversalTime().Ticks
    }
    catch {
        return $null
    }
}

function Test-ProcessIdentity {
    param(
        [Parameter(Mandatory = $true)][int]$ProcessId,
        [Parameter(Mandatory = $true)][long]$ExpectedStartTicks
    )

    $actual = Get-ProcessStartTicks -ProcessId $ProcessId
    return ($null -ne $actual -and [long]$actual -eq $ExpectedStartTicks)
}

while ($true) {
    # If the exact tracked child is already gone, there is nothing left for this
    # watchdog to own. PID reuse must never cause an unrelated process to be killed.
    if (-not (Test-ProcessIdentity -ProcessId $ChildPid -ExpectedStartTicks $ChildStartTicks)) {
        exit 0
    }

    if (Test-ProcessIdentity -ProcessId $OwnerPid -ExpectedStartTicks $OwnerStartTicks) {
        Start-Sleep -Milliseconds 200
        continue
    }

    # The Butler app-shell owner disappeared without completing its normal finally
    # cleanup (for example Ctrl+C terminating the launcher process). Kill only the
    # exact tracked preserved-core tree. taskkill /T reaches the staged core pool,
    # dashboards, and their ButlerReadOnlyJvmWorker descendants.
    if (Test-Path -LiteralPath $taskkill -PathType Leaf) {
        try {
            & $taskkill /PID $ChildPid /T /F 2>$null | Out-Null
        }
        catch {
        }
    }
    else {
        try { Stop-Process -Id $ChildPid -Force -ErrorAction SilentlyContinue } catch {}
    }

    for ($attempt = 0; $attempt -lt 50; $attempt++) {
        if (-not (Test-ProcessIdentity -ProcessId $ChildPid -ExpectedStartTicks $ChildStartTicks)) {
            exit 0
        }
        Start-Sleep -Milliseconds 100
    }

    throw "BF-812 BLOCKED: tracked preserved-core process $ChildPid survived owner shutdown cleanup."
}
