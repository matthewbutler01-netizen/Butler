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
$guard = Join-Path $scriptDir "butler-app-guard.ps1"
if (-not (Test-Path -LiteralPath $guard)) {
    throw "BF-683 BLOCKED: Butler app guard not found at $guard"
}

$forward = @{}
foreach ($name in @("LeagueId", "Port", "NoBrowser", "ResetLeague")) {
    if ($PSBoundParameters.ContainsKey($name)) {
        $forward[$name] = $PSBoundParameters[$name]
    }
}

$retryMessage = "BF-670 BLOCKED: preserved Butler app core exited during startup."
$maxAttempts = 3
for ($attempt = 1; $attempt -le $maxAttempts; $attempt++) {
    try {
        & $guard @forward
        exit 0
    }
    catch {
        $message = $_.Exception.Message
        if ($message -cne $retryMessage -or $attempt -ge $maxAttempts) {
            throw
        }

        Write-Host "BF-683: transient Butler internal startup handoff failed on attempt $attempt of $maxAttempts; retrying with fresh internal loopback ports."
        Start-Sleep -Milliseconds 250
    }
}

throw "BF-683 BLOCKED: Butler startup retry loop exited unexpectedly."
