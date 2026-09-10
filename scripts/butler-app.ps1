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
$dashboard = Join-Path $scriptDir "butler-dashboard.ps1"
$localAppData = $env:LOCALAPPDATA
if ([string]::IsNullOrWhiteSpace($localAppData)) {
    $localAppData = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)
}
if ([string]::IsNullOrWhiteSpace($localAppData)) {
    throw "BF-666 BLOCKED: LocalApplicationData is unavailable."
}

$configDir = Join-Path $localAppData "Butler"
$configPath = Join-Path $configDir "app-league.txt"

if (-not (Test-Path -LiteralPath $dashboard)) {
    throw "BF-666 BLOCKED: governed Butler dashboard not found at $dashboard"
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

Write-Host "Butler App"
Write-Host "League: $selectedLeagueId"
Write-Host "Launching governed local dashboard on port $Port."

if ($NoBrowser) {
    & $dashboard -LeagueId $selectedLeagueId -Port $Port -NoBrowser
}
else {
    & $dashboard -LeagueId $selectedLeagueId -Port $Port
}
