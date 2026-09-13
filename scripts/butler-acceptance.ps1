param(
    [ValidateRange(0, 65535)]
    [int]$Port = 0,

    [ValidateRange(1, 600)]
    [int]$StartupTimeoutSeconds = 180,

    [ValidateRange(1, 32)]
    [int]$Concurrency = 6,

    [ValidateRange(1, 20)]
    [int]$RequestsPerPath = 3,

    [ValidateRange(1, 300)]
    [int]$RequestTimeoutSeconds = 60
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$appLauncher = Join-Path $scriptDir 'butler-app.ps1'
$loadCheck = Join-Path $scriptDir 'butler-read-load-check.ps1'
$powershell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$taskkill = Join-Path $env:SystemRoot 'System32\taskkill.exe'
$loopback = [System.Net.IPAddress]::Parse('127.0.0.1')

foreach ($required in @($appLauncher, $loadCheck, $powershell, $taskkill)) {
    if (-not (Test-Path -LiteralPath $required)) {
        throw "BF-698 BLOCKED: required acceptance component not found at $required"
    }
}

function Get-AcceptancePort {
    param([Parameter(Mandatory = $true)][int]$RequestedPort)

    $probePort = if ($RequestedPort -gt 0) { $RequestedPort } else { 0 }
    $probe = [System.Net.Sockets.TcpListener]::new($loopback, $probePort)
    try {
        $probe.Start()
        return ([System.Net.IPEndPoint]$probe.LocalEndpoint).Port
    }
    catch [System.Net.Sockets.SocketException] {
        if ($RequestedPort -gt 0) {
            throw "BF-698 BLOCKED: requested acceptance port $RequestedPort is already in use."
        }
        throw 'BF-698 BLOCKED: unable to reserve a free loopback port for acceptance.'
    }
    finally {
        try { $probe.Stop() } catch {}
    }
}

function Test-ButlerHealth {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][int]$TimeoutMs
    )

    $request = [System.Net.HttpWebRequest]::Create($Root + '/health')
    $request.Method = 'GET'
    $request.Timeout = $TimeoutMs
    $request.ReadWriteTimeout = $TimeoutMs
    $request.Proxy = $null
    $request.KeepAlive = $false
    $response = $null
    try {
        try {
            $response = $request.GetResponse()
        }
        catch [System.Net.WebException] {
            return $false
        }

        if ([int]$response.StatusCode -ne 200) {
            return $false
        }

        $reader = [System.IO.StreamReader]::new($response.GetResponseStream(), [System.Text.Encoding]::UTF8)
        try {
            $body = $reader.ReadToEnd()
        }
        finally {
            $reader.Dispose()
        }

        $health = $body | ConvertFrom-Json
        if ($null -eq $health -or [string]$health.service -cne 'butler-app-shell' -or [string]$health.status -cne 'ok') {
            throw 'BF-698 BLOCKED: selected port answered but did not identify as a healthy butler-app-shell.'
        }
        return $true
    }
    finally {
        if ($null -ne $response) { $response.Close() }
    }
}

function Start-OwnedButler {
    param([Parameter(Mandatory = $true)][int]$SelectedPort)

    $start = [System.Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $powershell
    $start.Arguments = "-NoLogo -NoProfile -ExecutionPolicy Bypass -File `"$appLauncher`" -Port $SelectedPort -NoBrowser"
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true

    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $start
    if (-not $process.Start()) {
        throw 'BF-698 BLOCKED: unable to launch Butler for acceptance.'
    }

    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $process | Add-Member -NotePropertyName ButlerStdoutTask -NotePropertyValue $stdoutTask
    $process | Add-Member -NotePropertyName ButlerStderrTask -NotePropertyValue $stderrTask
    return $process
}

function Get-BoundedStartupOutput {
    param([Parameter(Mandatory = $true)]$Process)

    try { [void]$Process.WaitForExit(2000) } catch {}

    $parts = New-Object System.Collections.Generic.List[string]
    foreach ($entry in @(
        [pscustomobject]@{ Label = 'stdout'; Property = 'ButlerStdoutTask' },
        [pscustomobject]@{ Label = 'stderr'; Property = 'ButlerStderrTask' }
    )) {
        try {
            $property = $Process.PSObject.Properties[$entry.Property]
            if ($null -eq $property -or $null -eq $property.Value) { continue }
            $task = $property.Value
            if (-not $task.IsCompleted) { continue }
            $text = [string]$task.Result
            if ([string]::IsNullOrWhiteSpace($text)) { continue }
            $text = [regex]::Replace($text, '\s+', ' ').Trim()
            if ($text.Length -gt 900) {
                $text = '...' + $text.Substring($text.Length - 900)
            }
            $parts.Add(($entry.Label + '=' + $text))
        }
        catch {
        }
    }

    $combined = ($parts -join '; ')
    if ($combined.Length -gt 1800) {
        $combined = '...' + $combined.Substring($combined.Length - 1800)
    }
    return $combined
}

function Stop-OwnedButlerTree {
    param([AllowNull()]$Process)

    if ($null -eq $Process) { return }
    try {
        if ($Process.HasExited) { return }
    }
    catch {
        return
    }

    try {
        & $taskkill /PID $Process.Id /T /F 2>$null | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "taskkill exit code $LASTEXITCODE"
        }
        try { [void]$Process.WaitForExit(5000) } catch {}
    }
    catch {
        throw "BF-698 CLEANUP FAILED: unable to stop the Butler process tree launched for acceptance. $($_.Exception.Message)"
    }
}

function Remove-OwnedRunState {
    param(
        [Parameter(Mandatory = $true)][int]$ProcessId,
        [Parameter(Mandatory = $true)][int]$SelectedPort
    )

    try {
        $localAppData = $env:LOCALAPPDATA
        if ([string]::IsNullOrWhiteSpace($localAppData)) {
            $localAppData = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)
        }
        if ([string]::IsNullOrWhiteSpace($localAppData)) { return }

        $runState = Join-Path (Join-Path $localAppData 'Butler') ("running-port-{0}.txt" -f $SelectedPort)
        if (-not (Test-Path -LiteralPath $runState)) { return }
        $raw = [IO.File]::ReadAllText($runState, [Text.Encoding]::ASCII).Trim()
        if ($raw.StartsWith("$ProcessId|", [System.StringComparison]::Ordinal)) {
            Remove-Item -LiteralPath $runState -Force
        }
    }
    catch {
        Write-Warning ("BF-698 could not remove its stale run-state marker: {0}" -f $_.Exception.Message)
    }
}

$selectedPort = Get-AcceptancePort -RequestedPort $Port
$root = "http://127.0.0.1:$selectedPort"
$process = $null
$ownedPid = 0
$failure = $null
$passed = $false

Write-Host 'Butler one-command Windows acceptance (BF-698)'
Write-Host "Selected port: $selectedPort"
Write-Host 'Boundary: owned local Butler process only; existing BF-688 GET-only workload; no write path is invoked.'

try {
    $process = Start-OwnedButler -SelectedPort $selectedPort
    $ownedPid = $process.Id

    $deadline = [DateTime]::UtcNow.AddSeconds($StartupTimeoutSeconds)
    $healthy = $false
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($process.HasExited) {
            $diagnostic = Get-BoundedStartupOutput -Process $process
            if ([string]::IsNullOrWhiteSpace($diagnostic)) {
                throw "BF-698 FAILED: Butler exited during startup with code $($process.ExitCode)."
            }
            throw "BF-698 FAILED: Butler exited during startup with code $($process.ExitCode); startup=$diagnostic"
        }
        if (Test-ButlerHealth -Root $root -TimeoutMs 1000) {
            $healthy = $true
            break
        }
        Start-Sleep -Milliseconds 250
    }

    if (-not $healthy) {
        throw "BF-698 FAILED: Butler did not become healthy within $StartupTimeoutSeconds seconds."
    }

    Write-Host 'Direct health: BUTLER_APP_SHELL_VERIFIED'
    & $loadCheck -BaseUrl ($root + '/') -Concurrency $Concurrency -RequestsPerPath $RequestsPerPath -TimeoutSeconds $RequestTimeoutSeconds
    $passed = $true
}
catch {
    $failure = $_
}
finally {
    try {
        Stop-OwnedButlerTree -Process $process
    }
    catch {
        if ($null -eq $failure) { $failure = $_ }
        else { Write-Warning $_.Exception.Message }
    }
    if ($ownedPid -gt 0) {
        Remove-OwnedRunState -ProcessId $ownedPid -SelectedPort $selectedPort
    }
}

if ($null -ne $failure) {
    Write-Host "BF-698 ACCEPTANCE: FAIL (port $selectedPort)"
    throw $failure
}

if (-not $passed) {
    throw 'BF-698 FAILED: acceptance ended without a result.'
}

Write-Host "BF-698 ACCEPTANCE: PASS (port $selectedPort)"
