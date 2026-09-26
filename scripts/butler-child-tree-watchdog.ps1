param(
    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 2147483647)]
    [int]$SupervisorPid,

    [Parameter(Mandatory = $true)]
    [long]$SupervisorStartTicks,

    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 2147483647)]
    [int]$GuardPid,

    [Parameter(Mandatory = $true)]
    [long]$GuardStartTicks
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

function Get-TrackedCoreChildren {
    $children = @()
    try {
        $children = @(Get-CimInstance Win32_Process -Filter "ParentProcessId = $GuardPid" -ErrorAction Stop |
            Where-Object {
                $_.Name -ieq 'powershell.exe' -and
                -not [string]::IsNullOrWhiteSpace([string]$_.CommandLine) -and
                [string]$_.CommandLine -like '*butler-app-shell-core.ps1*'
            })
    }
    catch {
        return @()
    }
    return @($children)
}

function Stop-TrackedCoreTrees {
    foreach ($core in @(Get-TrackedCoreChildren)) {
        $corePid = [int]$core.ProcessId
        if ($corePid -le 0) { continue }

        if (Test-Path -LiteralPath $taskkill -PathType Leaf) {
            try {
                & $taskkill /PID $corePid /T /F 2>$null | Out-Null
                continue
            }
            catch {
            }
        }
        try { Stop-Process -Id $corePid -Force -ErrorAction SilentlyContinue } catch {}
    }
}

while ($true) {
    $supervisorAlive = Test-ProcessIdentity -ProcessId $SupervisorPid -ExpectedStartTicks $SupervisorStartTicks
    $guardAlive = Test-ProcessIdentity -ProcessId $GuardPid -ExpectedStartTicks $GuardStartTicks

    if ($supervisorAlive -and $guardAlive) {
        Start-Sleep -Milliseconds 200
        continue
    }

    # Normal guard completion should already have stopped its preserved core.
    # Ctrl+C can terminate the launcher chain before those finally blocks run;
    # the original Win32 parent PID remains available on the orphaned core,
    # so kill only exact Butler core children and their descendants.
    Stop-TrackedCoreTrees

    # If the supervisor disappeared but the exact guard process survived, stop
    # only that guard PID after its Butler core trees are contained. Do not use
    # /T here so unrelated shell-launched processes such as a browser are not
    # swept into cleanup.
    if (-not $supervisorAlive -and $guardAlive) {
        try { Stop-Process -Id $GuardPid -Force -ErrorAction SilentlyContinue } catch {}
    }

    for ($attempt = 0; $attempt -lt 50; $attempt++) {
        if (@(Get-TrackedCoreChildren).Count -eq 0) {
            exit 0
        }
        Stop-TrackedCoreTrees
        Start-Sleep -Milliseconds 100
    }

    throw 'BF-812 BLOCKED: preserved Butler core descendants survived launcher shutdown cleanup.'
}
