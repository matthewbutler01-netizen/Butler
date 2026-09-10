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

# Resetting the persisted league selection preserves the existing BF-666 behavior
# and does not represent an app-server launch.
if ($ResetLeague) {
    Invoke-ButlerLauncher
    exit 0
}

$localAppData = $env:LOCALAPPDATA
if ([string]::IsNullOrWhiteSpace($localAppData)) {
    $localAppData = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)
}
if ([string]::IsNullOrWhiteSpace($localAppData)) {
    throw "BF-673 BLOCKED: LocalApplicationData is unavailable for Butler app instance locks."
}
$lockDirectory = Join-Path (Join-Path $localAppData "Butler") "app-port-locks"
[IO.Directory]::CreateDirectory($lockDirectory) | Out-Null

function Get-ButlerPortLockPath {
    param([Parameter(Mandatory = $true)][int]$CandidatePort)
    return Join-Path $lockDirectory ("port-{0}.lock" -f $CandidatePort)
}

function Test-ButlerPortLockHeld {
    param([Parameter(Mandatory = $true)][int]$CandidatePort)

    $candidatePath = Get-ButlerPortLockPath -CandidatePort $CandidatePort
    $candidateStream = $null
    try {
        # An unlocked stale file is harmless: opening it exclusively succeeds,
        # so only a live guard that still holds the FileStream counts as Butler.
        $candidateStream = [System.IO.File]::Open(
            $candidatePath,
            [System.IO.FileMode]::OpenOrCreate,
            [System.IO.FileAccess]::ReadWrite,
            [System.IO.FileShare]::None
        )
        return $false
    }
    catch [System.IO.IOException] {
        return $true
    }
    finally {
        if ($null -ne $candidateStream) {
            $candidateStream.Dispose()
        }
    }
}

function Get-ExistingManagedButlerPort {
    foreach ($candidatePort in $managedPorts) {
        if (Test-ButlerPortLockHeld -CandidatePort $candidatePort) {
            return [int]$candidatePort
        }
    }
    return $null
}

function Use-ExistingManagedButler {
    param([Parameter(Mandatory = $true)][int]$ExistingPort)

    $existingUrl = "http://127.0.0.1:$ExistingPort/"
    Write-Host "Butler is already running on port $ExistingPort."
    Write-Host "Local URL: $existingUrl"
    if (-not $NoBrowser) {
        Start-Process -FilePath $existingUrl
    }
}

function Open-ButlerPortLock {
    param([Parameter(Mandatory = $true)][int]$SelectedPort)

    $selectedPath = Get-ButlerPortLockPath -CandidatePort $SelectedPort
    try {
        return [System.IO.File]::Open(
            $selectedPath,
            [System.IO.FileMode]::OpenOrCreate,
            [System.IO.FileAccess]::ReadWrite,
            [System.IO.FileShare]::None
        )
    }
    catch [System.IO.IOException] {
        throw "BF-669 BLOCKED: Butler is already running on port $SelectedPort. Use the existing browser window, or stop its PowerShell window with Ctrl+C before relaunching newly pulled code."
    }
}

# BF-673 changes only the normal no-port app launch. Explicit -Port remains the
# exact BF-669 contract and never silently moves to a different local port.
if (-not $portWasExplicit) {
    $existingManagedPort = Get-ExistingManagedButlerPort
    if ($null -ne $existingManagedPort) {
        # BF-674 turns a proven BF-673 duplicate discovery into normal app reuse.
        # Do not reacquire its lock or mutex; explicit -Port still reaches BF-669.
        $Port = [int]$existingManagedPort
        Use-ExistingManagedButler -ExistingPort $Port
        exit 0
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

$portLock = $null
$mutexName = "Local\Butler.App.Port.$Port"
$createdNew = $false
$instanceMutex = $null
try {
    # The file lock is the BF-673 cross-port discovery source. The named mutex
    # remains the proven BF-669 exact-port duplicate guard.
    $portLock = Open-ButlerPortLock -SelectedPort $Port
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
    if ($null -ne $portLock) {
        $portLock.Dispose()
    }
}
