param(
    [string]$LeagueId,

    [ValidateRange(1024, 65535)]
    [int]$Port = 8080,

    [switch]$NoBrowser,

    [switch]$ResetLeague
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$portWasExplicit = $PSBoundParameters.ContainsKey("Port")
$managedPorts = 8080..8099
$loopback = [System.Net.IPAddress]::Parse("127.0.0.1")

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$appLauncher = Join-Path $scriptDir "butler-app.ps1"
if (-not (Test-Path -LiteralPath $appLauncher)) {
    throw "BF-669 BLOCKED: Butler app launcher not found at $appLauncher"
}

function Invoke-ButlerLauncher {
    $arguments = @{
        Port = $Port
    }
    if (-not [string]::IsNullOrWhiteSpace($LeagueId)) {
        $arguments.LeagueId = $LeagueId
    }
    if ($NoBrowser) {
        $arguments.NoBrowser = $true
    }
    if ($ResetLeague) {
        $arguments.ResetLeague = $true
    }

    & $appLauncher @arguments
}

function Test-LoopbackPortBindable {
    param([Parameter(Mandatory = $true)][int]$CandidatePort)

    $probe = [System.Net.Sockets.TcpListener]::new($loopback, $CandidatePort)
    try {
        $probe.Start()
        return $true
    }
    catch [System.Net.Sockets.SocketException] {
        return $false
    }
    finally {
        try { $probe.Stop() } catch {}
    }
}

function Get-ExistingManagedButlerPort {
    foreach ($candidatePort in $managedPorts) {
        $candidateMutexName = "Local\\Butler.App.Port.$candidatePort"
        $candidateCreatedNew = $false
        $candidateMutex = $null
        try {
            # Use the same constructor/createdNew mechanism as the proven BF-669
            # duplicate-port guard. If we created the mutex, no Butler owned that
            # managed port; dispose the temporary handle immediately. If we did
            # not create it, an existing Butler guard already owns the name.
            $candidateMutex = [System.Threading.Mutex]::new(
                $false,
                $candidateMutexName,
                [ref]$candidateCreatedNew
            )
            if (-not $candidateCreatedNew) {
                return [int]$candidatePort
            }
        }
        finally {
            if ($null -ne $candidateMutex) {
                $candidateMutex.Dispose()
            }
        }
    }
    return $null
}

# Resetting the persisted league selection preserves the existing BF-666 behavior
# and does not represent an app-server launch.
if ($ResetLeague) {
    Invoke-ButlerLauncher
    exit 0
}

# BF-673 changes only the normal no-port app launch. Explicit -Port remains the
# exact BF-669 contract and never silently moves to a different local port.
if (-not $portWasExplicit) {
    $existingManagedPort = Get-ExistingManagedButlerPort
    if ($null -ne $existingManagedPort) {
        $Port = [int]$existingManagedPort
    }
    else {
        $selectedPort = $null
        foreach ($candidatePort in $managedPorts) {
            if (Test-LoopbackPortBindable -CandidatePort $candidatePort) {
                $selectedPort = [int]$candidatePort
                break
            }
        }
        if ($null -eq $selectedPort) {
            throw "BF-673 BLOCKED: no free Butler loopback port is available from 8080 through 8099. Butler did not stop or modify any listener."
        }
        $Port = [int]$selectedPort
        if ($Port -ne 8080) {
            Write-Host "Butler default port 8080 is unavailable. Using local port $Port instead."
        }
    }
}

$mutexName = "Local\\Butler.App.Port.$Port"
$createdNew = $false
$instanceMutex = $null
try {
    $instanceMutex = [System.Threading.Mutex]::new($false, $mutexName, [ref]$createdNew)
    if (-not $createdNew) {
        throw "BF-669 BLOCKED: Butler is already running on port $Port. Use the existing browser window, or stop its PowerShell window with Ctrl+C before relaunching newly pulled code."
    }

    Invoke-ButlerLauncher
}
finally {
    if ($null -ne $instanceMutex) {
        $instanceMutex.Dispose()
    }
}
