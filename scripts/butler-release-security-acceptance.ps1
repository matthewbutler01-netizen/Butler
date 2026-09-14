param(
    [ValidateRange(0, 65535)]
    [int]$Port = 0,

    [ValidateRange(1, 600)]
    [int]$StartupTimeoutSeconds = 180
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$appLauncher = Join-Path $scriptDir 'butler-app.ps1'
$securityCheck = Join-Path $scriptDir 'butler-release-security-check.ps1'
$powershell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$taskkill = Join-Path $env:SystemRoot 'System32\taskkill.exe'
$loopback = [System.Net.IPAddress]::Parse('127.0.0.1')

foreach ($required in @($appLauncher, $securityCheck, $powershell, $taskkill)) {
    if (-not (Test-Path -LiteralPath $required)) {
        throw "BF-768 BLOCKED: required release-security component not found at $required"
    }
}

function Get-Bf768Port {
    param([Parameter(Mandatory = $true)][int]$RequestedPort)

    $probePort = if ($RequestedPort -gt 0) { $RequestedPort } else { 0 }
    $probe = [System.Net.Sockets.TcpListener]::new($loopback, $probePort)
    try {
        $probe.Start()
        return ([System.Net.IPEndPoint]$probe.LocalEndpoint).Port
    }
    catch [System.Net.Sockets.SocketException] {
        if ($RequestedPort -gt 0) {
            throw "BF-768 BLOCKED: requested release-security port $RequestedPort is already in use."
        }
        throw 'BF-768 BLOCKED: unable to reserve a free loopback port.'
    }
    finally {
        try { $probe.Stop() } catch {}
    }
}

function Start-Bf768Butler {
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
        throw 'BF-768 BLOCKED: unable to launch Butler for release-security acceptance.'
    }

    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $process | Add-Member -NotePropertyName ButlerStdoutTask -NotePropertyValue $stdoutTask
    $process | Add-Member -NotePropertyName ButlerStderrTask -NotePropertyValue $stderrTask
    return $process
}

function Get-Bf768StartupOutput {
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
            if ($text.Length -gt 700) {
                $text = '...' + $text.Substring($text.Length - 700)
            }
            $parts.Add(($entry.Label + '=' + $text))
        }
        catch {
        }
    }
    return (($parts -join '; '))
}

function Test-Bf768Health {
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
        if ([int]$response.StatusCode -ne 200) { return $false }

        $reader = [System.IO.StreamReader]::new($response.GetResponseStream(), [System.Text.Encoding]::UTF8)
        try { $body = $reader.ReadToEnd() } finally { $reader.Dispose() }
        $health = $body | ConvertFrom-Json
        if ($null -eq $health -or
            [string]$health.service -cne 'butler-app-shell' -or
            [string]$health.status -cne 'ok' -or
            [string]$health.bind -cne '127.0.0.1') {
            throw 'BF-768 BLOCKED: selected port answered but did not identify as loopback-bound healthy butler-app-shell.'
        }
        return $true
    }
    finally {
        if ($null -ne $response) { $response.Close() }
    }
}

function Stop-Bf768ButlerTree {
    param([AllowNull()]$Process)

    if ($null -eq $Process) { return }
    try {
        if ($Process.HasExited) { return }
    }
    catch {
        return
    }

    & $taskkill /PID $Process.Id /T /F 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "BF-768 CLEANUP FAILED: taskkill exit code $LASTEXITCODE for owned Butler process $($Process.Id)."
    }
    try { [void]$Process.WaitForExit(5000) } catch {}
}

function Remove-Bf768RunState {
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
        Write-Warning ("BF-768 could not remove its stale run-state marker: {0}" -f $_.Exception.Message)
    }
}

$selectedPort = Get-Bf768Port -RequestedPort $Port
$root = "http://127.0.0.1:$selectedPort"
$process = $null
$ownedPid = 0
$failure = $null

Write-Host 'Butler release-security acceptance (BF-768)'
Write-Host "Selected port: $selectedPort"
Write-Host 'Boundary: fresh owned production-equivalent Butler instance; loopback probes only; no POST /refresh and no Butler/Sleeper transaction write.'

try {
    $process = Start-Bf768Butler -SelectedPort $selectedPort
    $ownedPid = $process.Id
    $deadline = [DateTime]::UtcNow.AddSeconds($StartupTimeoutSeconds)
    $healthy = $false
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($process.HasExited) {
            $diagnostic = Get-Bf768StartupOutput -Process $process
            if ([string]::IsNullOrWhiteSpace($diagnostic)) {
                throw "BF-768 FAILED: Butler exited during security startup with code $($process.ExitCode)."
            }
            throw "BF-768 FAILED: Butler exited during security startup with code $($process.ExitCode); startup=$diagnostic"
        }
        if (Test-Bf768Health -Root $root -TimeoutMs 1000) {
            $healthy = $true
            break
        }
        Start-Sleep -Milliseconds 250
    }
    if (-not $healthy) {
        throw "BF-768 FAILED: Butler did not become healthy within $StartupTimeoutSeconds seconds."
    }

    & $securityCheck -BaseUrl ($root + '/')
}
catch {
    $failure = $_
}
finally {
    try {
        Stop-Bf768ButlerTree -Process $process
    }
    catch {
        if ($null -eq $failure) { $failure = $_ }
        else { Write-Warning $_.Exception.Message }
    }
    if ($ownedPid -gt 0) {
        Remove-Bf768RunState -ProcessId $ownedPid -SelectedPort $selectedPort
    }
}

if ($null -ne $failure) {
    Write-Host "BF-768 RELEASE SECURITY ACCEPTANCE: FAIL (port $selectedPort)"
    throw $failure
}

Write-Host "BF-768 RELEASE SECURITY ACCEPTANCE: PASS (port $selectedPort)"
