param([int]$Port, [string]$LogPath, [string]$GatePath)
$ErrorActionPreference = 'Stop'
# Do not launch descendants until the parent has assigned this process to its job.
$deadline = [DateTime]::UtcNow.AddSeconds(30)
while (-not (Test-Path -LiteralPath $GatePath)) {
    if ([DateTime]::UtcNow -ge $deadline) { exit 1 }
    Start-Sleep -Milliseconds 50
}
try {
    & (Join-Path $PSScriptRoot 'butler-app.ps1') -Port $Port -NoBrowser *> $LogPath
}
catch {
    ($_ | Out-String) | Add-Content -LiteralPath $LogPath
    exit 1
}
