param([Parameter(Mandatory = $true)][string]$RunDirectory)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
try {
    $run = [IO.File]::ReadAllText((Join-Path $RunDirectory 'run.json')) | ConvertFrom-Json
    $owned = Get-Process -Id ([int]$run.pid) -ErrorAction SilentlyContinue
    if ($null -eq $owned) { Write-Output 'The setup-launched app has already stopped.'; exit 0 }
    if ($owned.StartTime.ToUniversalTime().Ticks -ne [long]$run.startTicks) { throw 'The process ID now belongs to a different process; nothing was stopped.' }
    $prefix = "$($run.pid)|$($run.startTicks)|"
    if (-not (Test-Path -LiteralPath $run.marker) -or -not [IO.File]::ReadAllText($run.marker).StartsWith($prefix, [StringComparison]::Ordinal)) { throw 'Launcher ownership marker does not match; nothing was stopped.' }
    & (Join-Path $env:SystemRoot 'System32\taskkill.exe') /PID $owned.Id /T /F | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'The owned app could not be stopped.' }
    if ((Test-Path -LiteralPath $run.marker) -and [IO.File]::ReadAllText($run.marker).StartsWith($prefix, [StringComparison]::Ordinal)) { Remove-Item -LiteralPath $run.marker -Force }
    Write-Output 'BUTLER SETUP STOP: PASS'
}
catch { Write-Output ('BUTLER SETUP STOP: BLOCKED - ' + $_.Exception.Message); exit 1 }
