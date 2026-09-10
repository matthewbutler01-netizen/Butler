param(
    [string]$LeagueId,

    [ValidateRange(1024, 65535)]
    [int]$Port = 8080,

    [switch]$NoBrowser,

    [switch]$ResetLeague
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

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

# Resetting the persisted league selection preserves the existing BF-666 behavior
# and does not represent an app-server launch.
if ($ResetLeague) {
    Invoke-ButlerLauncher
    exit 0
}

$mutexName = "Local\Butler.App.Port.$Port"
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
