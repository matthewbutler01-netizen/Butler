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
$appShell = Join-Path $scriptDir "butler-app-shell.ps1"
$localAppData = $env:LOCALAPPDATA
if ([string]::IsNullOrWhiteSpace($localAppData)) {
    $localAppData = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)
}
if ([string]::IsNullOrWhiteSpace($localAppData)) {
    throw "BF-666 BLOCKED: LocalApplicationData is unavailable."
}

$configDir = Join-Path $localAppData "Butler"
$configPath = Join-Path $configDir "app-league.txt"
$runStatePath = Join-Path $configDir ("running-port-{0}.txt" -f $Port)

if (-not (Test-Path -LiteralPath $appShell)) {
    throw "BF-667 BLOCKED: Butler app shell not found at $appShell"
}

function ConvertTo-ButlerLeagueId {
    param([Parameter(Mandatory = $true)][string]$Value)

    $candidate = $Value.Trim()
    $parsed = [Guid]::Empty
    if ([string]::IsNullOrWhiteSpace($candidate) -or -not [Guid]::TryParse($candidate, [ref]$parsed)) {
        throw "BF-666 BLOCKED: Butler league id must be an exact UUID."
    }
    return $parsed.ToString("D").ToLowerInvariant()
}

function Read-ConfiguredLeagueId {
    if (-not (Test-Path -LiteralPath $configPath)) { return $null }
    $raw = [IO.File]::ReadAllText($configPath, [Text.Encoding]::ASCII).Trim()
    if ([string]::IsNullOrWhiteSpace($raw)) {
        throw "BF-666 BLOCKED: Butler app league configuration is empty. Run scripts\butler-app.cmd -ResetLeague and configure it again."
    }
    try {
        return ConvertTo-ButlerLeagueId -Value $raw
    }
    catch {
        throw "BF-666 BLOCKED: Butler app league configuration is invalid. Run scripts\butler-app.cmd -ResetLeague and configure it again."
    }
}

function Remove-StaleRunState {
    try {
        if (Test-Path -LiteralPath $runStatePath) {
            Remove-Item -LiteralPath $runStatePath -Force
        }
    }
    catch {
    }
}

function Test-LiveButlerRunState {
    if (-not (Test-Path -LiteralPath $runStatePath)) { return $false }

    try {
        $raw = [IO.File]::ReadAllText($runStatePath, [Text.Encoding]::ASCII).Trim()
        $parts = $raw.Split('|')
        if ($parts.Length -ne 3) {
            Remove-StaleRunState
            return $false
        }

        $markerPid = 0
        $markerStartTicks = 0L
        if (-not [int]::TryParse($parts[0], [ref]$markerPid)) {
            Remove-StaleRunState
            return $false
        }
        if (-not [long]::TryParse($parts[1], [ref]$markerStartTicks)) {
            Remove-StaleRunState
            return $false
        }

        $process = Get-Process -Id $markerPid -ErrorAction SilentlyContinue
        if ($null -eq $process) {
            Remove-StaleRunState
            return $false
        }

        $actualStartTicks = $process.StartTime.ToUniversalTime().Ticks
        if ($actualStartTicks -ne $markerStartTicks) {
            Remove-StaleRunState
            return $false
        }

        return $true
    }
    catch {
        Remove-StaleRunState
        return $false
    }
}

function Write-ButlerRunState {
    param([Parameter(Mandatory = $true)][string]$SelectedLeagueId)

    [IO.Directory]::CreateDirectory($configDir) | Out-Null
    $self = Get-Process -Id $PID
    $startTicks = $self.StartTime.ToUniversalTime().Ticks
    $text = "$PID|$startTicks|$SelectedLeagueId`r`n"
    [IO.File]::WriteAllText($runStatePath, $text, [Text.Encoding]::ASCII)
}

function Remove-OwnButlerRunState {
    try {
        if (-not (Test-Path -LiteralPath $runStatePath)) { return }
        $raw = [IO.File]::ReadAllText($runStatePath, [Text.Encoding]::ASCII).Trim()
        if ($raw.StartsWith("$PID|", [System.StringComparison]::Ordinal)) {
            Remove-Item -LiteralPath $runStatePath -Force
        }
    }
    catch {
    }
}

function Get-AppPortState {
    param([Parameter(Mandatory = $true)][int]$RequestedPort)

    $request = [System.Net.HttpWebRequest]::Create("http://127.0.0.1:$RequestedPort/health")
    $request.Method = "GET"
    $request.Timeout = 600
    $request.Proxy = $null
    $response = $null
    try {
        try {
            $response = $request.GetResponse()
        }
        catch [System.Net.WebException] {
            if ($null -ne $_.Exception.Response) {
                $response = $_.Exception.Response
            }
        }

        if ($null -ne $response) {
            $reader = [System.IO.StreamReader]::new($response.GetResponseStream(), [System.Text.Encoding]::UTF8)
            try {
                $body = $reader.ReadToEnd()
            }
            finally {
                $reader.Dispose()
            }

            if ([int]$response.StatusCode -eq 200 -and $body -match '"service"\s*:\s*"butler-app-shell"') {
                return "OCCUPIED_BUTLER"
            }
            return "OCCUPIED_OTHER"
        }
    }
    finally {
        if ($null -ne $response) { $response.Close() }
    }

    $probe = [System.Net.Sockets.TcpListener]::new(
        [System.Net.IPAddress]::Parse("127.0.0.1"),
        $RequestedPort
    )
    try {
        $probe.Start()
        return "FREE"
    }
    catch [System.Net.Sockets.SocketException] {
        return "OCCUPIED_OTHER"
    }
    finally {
        try { $probe.Stop() } catch {}
    }
}

if ($ResetLeague) {
    if (-not [string]::IsNullOrWhiteSpace($LeagueId)) {
        throw "BF-666 BLOCKED: -ResetLeague cannot be combined with -LeagueId."
    }
    if (Test-Path -LiteralPath $configPath) {
        Remove-Item -LiteralPath $configPath -Force
    }
    Write-Host "Butler app league selection reset."
    Write-Host "Configure the next launch with: scripts\butler-app.cmd -LeagueId <butler-league-id>"
    exit 0
}

$configuredLeagueId = Read-ConfiguredLeagueId
$requestedLeagueId = $null
if (-not [string]::IsNullOrWhiteSpace($LeagueId)) {
    $requestedLeagueId = ConvertTo-ButlerLeagueId -Value $LeagueId
}

if ($null -ne $configuredLeagueId -and $null -ne $requestedLeagueId -and $configuredLeagueId -cne $requestedLeagueId) {
    throw "BF-666 BLOCKED: Butler app is already configured for a different league. Run scripts\butler-app.cmd -ResetLeague before changing the app target."
}

$selectedLeagueId = if ($null -ne $configuredLeagueId) { $configuredLeagueId } else { $requestedLeagueId }
if ($null -eq $selectedLeagueId) {
    throw "BF-666 BLOCKED: Butler app is not configured. Run: scripts\butler-app.cmd -LeagueId <butler-league-id>"
}

if ($null -eq $configuredLeagueId) {
    [IO.Directory]::CreateDirectory($configDir) | Out-Null
    [IO.File]::WriteAllText($configPath, $selectedLeagueId + "`r`n", [Text.Encoding]::ASCII)
    Write-Host "Butler app league selection saved."
}
elseif ($null -ne $requestedLeagueId) {
    Write-Host "Butler app league selection already matches."
}

$liveRunState = Test-LiveButlerRunState
$portState = Get-AppPortState -RequestedPort $Port
if ($liveRunState -and $portState -ceq "OCCUPIED_OTHER") {
    $portState = "OCCUPIED_BUTLER"
}
if ($liveRunState -and $portState -ceq "FREE") {
    throw "BF-669 BLOCKED: Butler is already starting on port $Port. Wait for the existing Butler window to finish launching before trying again."
}
if ($portState -ceq "OCCUPIED_BUTLER") {
    throw "BF-669 BLOCKED: Butler is already running on port $Port. Use the existing browser window, or stop its PowerShell window with Ctrl+C before relaunching newly pulled code."
}
if ($portState -ceq "OCCUPIED_OTHER") {
    throw "BF-669 BLOCKED: local port $Port is already in use by another process. Butler will not stop it automatically. Stop that process yourself or launch Butler with -Port <free-port>."
}

Write-ButlerRunState -SelectedLeagueId $selectedLeagueId
try {
    Write-Host "Butler App"
    Write-Host "League: $selectedLeagueId"
    Write-Host "Launching Butler app shell on port $Port."

    if ($NoBrowser) {
        & $appShell -LeagueId $selectedLeagueId -Port $Port -NoBrowser
    }
    else {
        & $appShell -LeagueId $selectedLeagueId -Port $Port
    }
}
finally {
    Remove-OwnButlerRunState
}
