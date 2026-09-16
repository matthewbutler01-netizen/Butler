param(
    [string]$LeagueId,

    [ValidateRange(1024, 65535)]
    [int]$Port = 8080,

    [switch]$NoBrowser,

    [switch]$ResetLeague
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$guard = Join-Path $scriptDir 'butler-app-guard.ps1'
$watchdog = Join-Path $scriptDir 'butler-child-tree-watchdog.ps1'
$powershell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$taskkill = Join-Path $env:SystemRoot 'System32\taskkill.exe'

foreach ($required in @($guard, $watchdog, $powershell)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "BF-812 BLOCKED: required Butler launcher component not found at $required"
    }
}

function Get-ProcessStartTicks {
    param([Parameter(Mandatory = $true)][int]$ProcessId)
    $process = Get-Process -Id $ProcessId -ErrorAction Stop
    return [long]$process.StartTime.ToUniversalTime().Ticks
}

function Start-ButlerGuard {
    $arguments = "-NoLogo -NoProfile -ExecutionPolicy Bypass -File `"$guard`" -Port $Port"
    if (-not [string]::IsNullOrWhiteSpace($LeagueId)) {
        $arguments += " -LeagueId `"$LeagueId`""
    }
    if ($NoBrowser) { $arguments += ' -NoBrowser' }
    if ($ResetLeague) { $arguments += ' -ResetLeague' }

    $start = [System.Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $powershell
    $start.Arguments = $arguments
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $false

    $process = [System.Diagnostics.Process]::Start($start)
    if ($null -eq $process) {
        throw 'BF-812 BLOCKED: unable to start guarded Butler launcher.'
    }
    return $process
}

function Start-ButlerWatchdog {
    param(
        [Parameter(Mandatory = $true)]$GuardProcess,
        [Parameter(Mandatory = $true)][long]$SupervisorStartTicks
    )

    $guardStartTicks = [long]$GuardProcess.StartTime.ToUniversalTime().Ticks
    $arguments = "-NoLogo -NoProfile -ExecutionPolicy Bypass -File `"$watchdog`" " +
        "-SupervisorPid $PID -SupervisorStartTicks $SupervisorStartTicks " +
        "-GuardPid $($GuardProcess.Id) -GuardStartTicks $guardStartTicks"

    $start = [System.Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $powershell
    $start.Arguments = $arguments
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true

    $process = [System.Diagnostics.Process]::Start($start)
    if ($null -eq $process) {
        throw 'BF-812 BLOCKED: unable to start Butler process-lifetime watchdog.'
    }
    return $process
}

function Get-GuardCoreChildren {
    param([Parameter(Mandatory = $true)][int]$GuardProcessId)

    try {
        return @(Get-CimInstance Win32_Process -Filter "ParentProcessId = $GuardProcessId" -ErrorAction Stop |
            Where-Object {
                $_.Name -ieq 'powershell.exe' -and
                -not [string]::IsNullOrWhiteSpace([string]$_.CommandLine) -and
                [string]$_.CommandLine -like '*butler-app-shell-core.ps1*'
            })
    }
    catch {
        return @()
    }
}

function Stop-GuardTreeBestEffort {
    param([AllowNull()]$GuardProcess)

    if ($null -eq $GuardProcess) { return }

    $guardProcessId = [int]$GuardProcess.Id
    foreach ($core in @(Get-GuardCoreChildren -GuardProcessId $guardProcessId)) {
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

    try {
        if (-not $GuardProcess.HasExited) {
            $GuardProcess.Kill()
            [void]$GuardProcess.WaitForExit(3000)
        }
    }
    catch {
    }
}

$guardProcess = $null
$watchdogProcess = $null
$supervisorStartTicks = Get-ProcessStartTicks -ProcessId $PID

try {
    $guardProcess = Start-ButlerGuard
    try {
        $watchdogProcess = Start-ButlerWatchdog -GuardProcess $guardProcess -SupervisorStartTicks $supervisorStartTicks
    }
    catch {
        Stop-GuardTreeBestEffort -GuardProcess $guardProcess
        throw
    }

    $guardProcess.WaitForExit()
    $exitCode = [int]$guardProcess.ExitCode

    if ($null -ne $watchdogProcess) {
        try { [void]$watchdogProcess.WaitForExit(3000) } catch {}
    }

    exit $exitCode
}
finally {
    if ($null -ne $guardProcess) {
        try {
            if (-not $guardProcess.HasExited) {
                Stop-GuardTreeBestEffort -GuardProcess $guardProcess
            }
        }
        catch {
        }
        try { $guardProcess.Dispose() } catch {}
    }

    if ($null -ne $watchdogProcess) {
        try {
            if (-not $watchdogProcess.HasExited) {
                $watchdogProcess.Kill()
                [void]$watchdogProcess.WaitForExit(3000)
            }
        }
        catch {
        }
        try { $watchdogProcess.Dispose() } catch {}
    }
}
