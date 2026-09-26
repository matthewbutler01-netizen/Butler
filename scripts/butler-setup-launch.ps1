param(
    [string]$RuntimeZip,
    [switch]$VerifyOnly,
    [ValidateRange(1, 600)][int]$StartupTimeoutSeconds = 180,
    [ValidateRange(1, 300)][int]$RequestTimeoutSeconds = 120
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$shell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$job = $null
$process = $null
$runDir = $null
$handedOff = $false
$marker = $null
$ownedMarkerPrefix = $null
$launchFailed = $false

function Read-Page {
    param([string]$Url, [int]$TimeoutMs)
    $request = [Net.HttpWebRequest]::Create($Url)
    $request.Method = 'GET'
    $request.Proxy = $null
    $request.AllowAutoRedirect = $false
    $request.KeepAlive = $false
    $request.Timeout = $TimeoutMs
    $request.ReadWriteTimeout = $TimeoutMs
    $response = $request.GetResponse()
    try {
        if ([int]$response.StatusCode -ne 200) { throw "Expected HTTP 200 at $Url" }
        $reader = [IO.StreamReader]::new($response.GetResponseStream())
        try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
    }
    finally { $response.Close() }
}
function Assert-Health {
    param([string]$Body)
    $health = $Body | ConvertFrom-Json
    if ([string]$health.service -cne 'butler-app-shell' -or [string]$health.status -cne 'ok' -or [string]$health.bind -cne '127.0.0.1') {
        throw 'Unexpected health identity on the selected port.'
    }
}

try {
    if ([string]::IsNullOrWhiteSpace($RuntimeZip)) { throw 'Supply -RuntimeZip with the downloaded runtime ZIP path.' }
    & $shell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'butler-setup-check.ps1') -RuntimeZip $RuntimeZip
    if ($LASTEXITCODE -ne 0) { throw 'Setup prerequisites failed; resolve the reported blockers before launch.' }
    . (Join-Path $PSScriptRoot 'butler-setup-process.ps1')
    $localData = [string]$env:LOCALAPPDATA
    if ([string]::IsNullOrWhiteSpace($localData)) { $localData = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData) }
    $runDir = Join-Path $localData ('Butler\setup-runs\' + [Guid]::NewGuid().ToString('N'))
    [IO.Directory]::CreateDirectory($runDir) | Out-Null
    $logPath = Join-Path $runDir 'startup.log'
    $gate = Join-Path $runDir 'start.ready'
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    try { $listener.Start(); $port = $listener.LocalEndpoint.Port } finally { $listener.Stop() }
    $url = "http://127.0.0.1:$port"
    $marker = Join-Path $localData ("Butler\running-port-$port.txt")
    $job = New-Object ButlerSetupJob
    $runner = Join-Path $PSScriptRoot 'butler-setup-runner.ps1'
    $process = Start-Process -FilePath $shell -ArgumentList @('-NoLogo','-NoProfile','-ExecutionPolicy','Bypass','-File',('"'+$runner+'"'),'-Port',$port,'-LogPath',('"'+$logPath+'"'),'-GatePath',('"'+$gate+'"')) -WindowStyle Hidden -PassThru
    try { $job.Assign($process) }
    catch {
        # The gate remains closed, so this process cannot yet have app descendants.
        if (-not $process.HasExited) { $process.Kill() }
        throw
    }
    $ownedMarkerPrefix = "$($process.Id)|$($process.StartTime.ToUniversalTime().Ticks)|"
    [IO.File]::WriteAllText($gate, 'start')
    $deadline = [DateTime]::UtcNow.AddSeconds($StartupTimeoutSeconds)
    $healthy = $false
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($process.HasExited) { throw "Butler exited during startup (code $($process.ExitCode))." }
        try {
            $body = Read-Page "$url/health" 800
            Assert-Health $body
            $healthy = $true
            break
        }
        catch [Net.WebException] {}
        Start-Sleep -Milliseconds 100
    }
    if (-not $healthy) { throw "Butler startup timed out after $StartupTimeoutSeconds seconds." }
    # The marker binds this successful check to the launcher we started.
    if (-not (Test-Path -LiteralPath $marker) -or -not [IO.File]::ReadAllText($marker).StartsWith($ownedMarkerPrefix, [StringComparison]::Ordinal)) { throw 'Butler launcher ownership marker does not match this setup run.' }
    $routes = [ordered]@{
        '/' = 'Your fantasy week in one view'
        '/team' = 'My Team'
        '/matchup' = 'Matchup'
        '/waivers' = 'Players Butler authorized for review'
        '/league' = 'League'
        '/trade?load=1' = 'No new trade score is created here.'
        '/history?load=1' = 'Your waiver decision timeline'
    }
    foreach ($route in $routes.Keys) {
        if ($process.HasExited) { throw "Butler exited before checking $route" }
        $body = Read-Page ($url + $route) ($RequestTimeoutSeconds * 1000)
        if ($body -notmatch '<html' -or $body -notmatch '(?i)Butler' -or $body -notmatch [regex]::Escape($routes[$route]) -or $body -match '(?i)http-equiv=["'']refresh') { throw "Invalid manager page at $route" }
        Write-Output "SETUP PAGE: PASS $route"
    }
    Assert-Health (Read-Page "$url/health" 1000)
    if ($process.HasExited) { throw 'Butler exited before handoff.' }
    if (-not $VerifyOnly) {
        [IO.File]::WriteAllText((Join-Path $runDir 'run.json'), (@{ pid = $process.Id; startTicks = $process.StartTime.ToUniversalTime().Ticks; port = $port; marker = $marker } | ConvertTo-Json))
        $job.Disarm()
        $handedOff = $true
        Write-Output "BUTLER DASHBOARD: $url/"
        Write-Output "Butler remains running. Startup log: $logPath"
        Write-Output ('Stop this app: scripts\butler-setup-stop.cmd -RunDirectory "' + $runDir + '"')
    }
    Write-Output 'BUTLER FIRST LAUNCH: PASS'
}
catch {
    Write-Output ('BUTLER FIRST LAUNCH: BLOCKED - ' + $_.Exception.Message)
    if ($runDir) { Write-Output "Startup diagnostics: $runDir" }
    $script:launchFailed = $true
}
finally {
    if ($null -ne $job) { $job.Dispose() }
    if (-not $handedOff -and $null -ne $process) {
        [void]$process.WaitForExit(5000)
        if ($marker -and $ownedMarkerPrefix -and (Test-Path -LiteralPath $marker)) {
            if ([IO.File]::ReadAllText($marker).StartsWith($ownedMarkerPrefix, [StringComparison]::Ordinal)) { Remove-Item -LiteralPath $marker -Force }
        }
        if ($runDir -and (Test-Path -LiteralPath (Join-Path $runDir 'startup.log'))) {
            $tail = (Get-Content -LiteralPath (Join-Path $runDir 'startup.log') -Tail 12) -join "`n"
            if ($tail.Length -gt 2000) { $tail = $tail.Substring($tail.Length - 2000) }
            if ($tail) { Write-Output $tail }
        }
    }
}
if ($launchFailed) { exit 1 }
if ($VerifyOnly) { Write-Output 'Owned verification runtime stopped.' }
