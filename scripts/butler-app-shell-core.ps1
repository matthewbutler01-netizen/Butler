param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$LeagueId,

    [ValidateRange(1024, 65535)]
    [int]$Port = 8080,

    [switch]$NoBrowser
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $scriptDir
$coreSingleSource = Join-Path $scriptDir 'butler-app-shell-core-single.ps1'
$dashboardSource = Join-Path $scriptDir 'butler-dashboard.ps1'
$dashboardTransformSource = Join-Path $scriptDir 'butler-dashboard-bf715-transform.ps1'
$requestWorker = Join-Path $scriptDir 'butler-app-core-pool-worker.ps1'
$directDispatchSource = Join-Path $scriptDir 'butler-direct-java-dispatch.ps1'
$directProxySource = Join-Path $scriptDir 'butler-direct-java-gradle-proxy.cmd'
$gradle = Join-Path $repoRoot 'gradlew.bat'
$runtimeInstallDir = Join-Path $repoRoot 'bet\bet-cli\build\install\bet-cli'
$runtimeLibDir = Join-Path $runtimeInstallDir 'lib'
$localAppData = [Environment]::GetFolderPath('LocalApplicationData')
$runtimeRoot = Join-Path $localAppData ("Butler\app-runtime-{0}" -f $PID)
$runtimeScriptsDir = Join-Path $runtimeRoot 'scripts'
$runtimeTransformsDir = Join-Path $runtimeRoot 'transforms'
$runtimeCoreSingle = Join-Path $runtimeScriptsDir 'butler-app-shell-core-single.ps1'
$runtimeDashboard = Join-Path $runtimeScriptsDir 'butler-dashboard.ps1'
$loopback = [System.Net.IPAddress]::Parse('127.0.0.1')
$powershell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$taskkill = Join-Path $env:SystemRoot 'System32\taskkill.exe'
$originalGradleOpts = $env:GRADLE_OPTS
$originalRuntimeLib = $env:BUTLER_APP_RUNTIME_LIB
$gradleNoDaemonOpt = '-Dorg.gradle.daemon=false'
$coreSingleNavigationOriginal = 'if ($proxied.ContentType -match ''^text/html'') {'
$coreSingleNavigationReplacement = 'if ($proxied.StatusCode -ge 200 -and $proxied.StatusCode -lt 300 -and $proxied.ContentType -match ''^text/html'') {'

foreach ($required in @($coreSingleSource, $dashboardSource, $dashboardTransformSource, $requestWorker, $directDispatchSource, $directProxySource, $powershell)) {
    if (-not (Test-Path -LiteralPath $required)) {
        throw "BF-704 BLOCKED: required app runtime component not found at $required"
    }
}
if (-not (Test-Path -LiteralPath $gradle)) {
    throw "BF-702 BLOCKED: Gradle wrapper not found at $gradle"
}
if ([string]::IsNullOrWhiteSpace($localAppData)) {
    throw 'BF-704 BLOCKED: LocalApplicationData is unavailable.'
}

function Enable-ButlerGradleNoDaemon {
    $existing = [string]$env:GRADLE_OPTS
    if ([string]::IsNullOrWhiteSpace($existing)) {
        $env:GRADLE_OPTS = $gradleNoDaemonOpt
        return
    }
    if ($existing.IndexOf($gradleNoDaemonOpt, [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
        $env:GRADLE_OPTS = $existing.TrimEnd() + ' ' + $gradleNoDaemonOpt
    }
}

function Restore-GradleOpts {
    if ($null -eq $originalGradleOpts) {
        Remove-Item Env:GRADLE_OPTS -ErrorAction SilentlyContinue
    }
    else {
        $env:GRADLE_OPTS = $originalGradleOpts
    }
}

function Restore-RuntimeLib {
    if ($null -eq $originalRuntimeLib) {
        Remove-Item Env:BUTLER_APP_RUNTIME_LIB -ErrorAction SilentlyContinue
    }
    else {
        $env:BUTLER_APP_RUNTIME_LIB = $originalRuntimeLib
    }
}

function Initialize-ReadOnlyCliRuntime {
    $previousPreference = $ErrorActionPreference
    $lines = $null
    $exitCode = $null
    Push-Location $repoRoot
    try {
        try {
            $ErrorActionPreference = 'Continue'
            $lines = & $gradle '--no-daemon' ':bet:bet-cli:installDist' 2>&1
            $exitCode = $LASTEXITCODE
        }
        finally {
            $ErrorActionPreference = $previousPreference
        }
    }
    finally {
        Pop-Location
    }

    $text = ($lines | ForEach-Object { "$_" }) -join "`n"
    if ($exitCode -ne 0) {
        throw "BF-702 BLOCKED: read-only CLI runtime warm-up failed with Gradle exit code $exitCode.`n$text"
    }
    if (-not (Test-Path -LiteralPath $runtimeLibDir -PathType Container)) {
        throw "BF-704 BLOCKED: prepared Butler runtime library directory not found at $runtimeLibDir"
    }
    $runtimeJars = @(Get-ChildItem -LiteralPath $runtimeLibDir -Filter '*.jar' -File -ErrorAction Stop)
    if ($runtimeJars.Count -eq 0) {
        throw "BF-704 BLOCKED: prepared Butler runtime contains no jars at $runtimeLibDir"
    }
}

function Initialize-DirectJavaRuntime {
    if (Test-Path -LiteralPath $runtimeRoot) {
        Remove-Item -LiteralPath $runtimeRoot -Recurse -Force -ErrorAction Stop
    }
    New-Item -ItemType Directory -Path $runtimeScriptsDir -Force | Out-Null
    New-Item -ItemType Directory -Path $runtimeTransformsDir -Force | Out-Null
    Copy-Item -LiteralPath $coreSingleSource -Destination $runtimeCoreSingle -Force
    Copy-Item -Path (Join-Path $scriptDir '*.ps1') -Destination $runtimeTransformsDir -Force

    $coreSingleText = [System.IO.File]::ReadAllText($runtimeCoreSingle).Replace("`r`n", "`n")
    $navigationMatchCount = [regex]::Matches($coreSingleText, [regex]::Escape($coreSingleNavigationOriginal)).Count
    if ($navigationMatchCount -lt 1) {
        throw 'BF-707 BLOCKED: staged core navigation injection contract is missing.'
    }
    $coreSingleText = $coreSingleText.Replace($coreSingleNavigationOriginal, $coreSingleNavigationReplacement)
    [System.IO.File]::WriteAllText($runtimeCoreSingle, $coreSingleText, [System.Text.UTF8Encoding]::new($false))

    Copy-Item -LiteralPath $dashboardSource -Destination $runtimeDashboard -Force
    foreach ($stagedTransform in @(Get-ChildItem -LiteralPath $runtimeTransformsDir -Filter '*.ps1' -File)) {
        $stagedTransformText = [System.IO.File]::ReadAllText($stagedTransform.FullName).Replace("`r`n", "`n")
        [System.IO.File]::WriteAllText($stagedTransform.FullName, $stagedTransformText, [System.Text.UTF8Encoding]::new($false))
    }
    $runtimeDashboardTransform = Join-Path $runtimeTransformsDir 'butler-dashboard-bf715-transform.ps1'
    & $runtimeDashboardTransform -DashboardPath $runtimeDashboard
    Copy-Item -LiteralPath $directDispatchSource -Destination (Join-Path $runtimeScriptsDir 'butler-direct-java-dispatch.ps1') -Force
    Copy-Item -LiteralPath $directProxySource -Destination (Join-Path $runtimeRoot 'gradlew.bat') -Force
    $env:BUTLER_APP_RUNTIME_LIB = $runtimeLibDir
    $env:BUTLER_APP_REPO_ROOT = $repoRoot
}

function Get-FreeLoopbackPort {
    $probe = [System.Net.Sockets.TcpListener]::new($loopback, 0)
    try {
        $probe.Start()
        return ([System.Net.IPEndPoint]$probe.LocalEndpoint).Port
    }
    finally {
        $probe.Stop()
    }
}

function Start-PreservedCore {
    param([Parameter(Mandatory = $true)][int]$BackendPort)

    $start = [System.Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $powershell
    $start.Arguments = "-NoLogo -NoProfile -ExecutionPolicy Bypass -File `"$runtimeCoreSingle`" -LeagueId `"$LeagueId`" -Port $BackendPort -NoBrowser"
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $process = [System.Diagnostics.Process]::Start($start)
    if ($null -eq $process) {
        throw "BF-690 BLOCKED: unable to start preserved inner core on port $BackendPort."
    }
    return $process
}

function Wait-PreservedCore {
    param(
        [Parameter(Mandatory = $true)][int]$BackendPort,
        [Parameter(Mandatory = $true)]$Process
    )

    for ($attempt = 0; $attempt -lt 160; $attempt++) {
        if ($Process.HasExited) {
            throw "BF-690 BLOCKED: preserved inner core on port $BackendPort exited during startup."
        }
        try {
            $request = [System.Net.HttpWebRequest]::Create("http://127.0.0.1:$BackendPort/health")
            $request.Method = 'GET'
            $request.Timeout = 750
            $request.Proxy = $null
            $request.KeepAlive = $false
            $response = $request.GetResponse()
            try {
                if ([int]$response.StatusCode -eq 200) { return }
            }
            finally {
                $response.Close()
            }
        }
        catch {
        }
        Start-Sleep -Milliseconds 250
    }
    throw "BF-690 BLOCKED: preserved inner core on port $BackendPort did not become healthy."
}

function Invoke-PreservedCoreWarmup {
    param([Parameter(Mandatory = $true)][int]$BackendPort)

    if ([string]$env:BUTLER_APP_CORE_POOL_WARMUP -ceq '0') {
        return
    }

    $request = [System.Net.HttpWebRequest]::Create("http://127.0.0.1:$BackendPort/waivers")
    $request.Method = 'GET'
    $request.Timeout = 3000
    $request.ReadWriteTimeout = 3000
    $request.Proxy = $null
    $request.KeepAlive = $false
    $response = $null
    try {
        $response = $request.GetResponse()
        if ([int]$response.StatusCode -ne 200) {
            throw "HTTP $([int]$response.StatusCode)"
        }
        $reader = [System.IO.StreamReader]::new($response.GetResponseStream(), [System.Text.Encoding]::UTF8)
        try {
            [void]$reader.ReadToEnd()
        }
        finally {
            $reader.Dispose()
        }
    }
    catch {
        Write-Warning ("BF-751 preserved-core warmup skipped on port {0}: {1}" -f $BackendPort, $_.Exception.Message)
    }
    finally {
        if ($null -ne $response) { $response.Close() }
    }

    $teamRequest = [System.Net.HttpWebRequest]::Create("http://127.0.0.1:$BackendPort/team")
    $teamRequest.Method = 'GET'
    $teamRequest.Timeout = 3000
    $teamRequest.ReadWriteTimeout = 3000
    $teamRequest.Proxy = $null
    $teamRequest.KeepAlive = $false
    $teamResponse = $null
    try {
        $teamResponse = $teamRequest.GetResponse()
        if ([int]$teamResponse.StatusCode -ne 200) {
            throw "HTTP $([int]$teamResponse.StatusCode)"
        }
        $teamReader = [System.IO.StreamReader]::new($teamResponse.GetResponseStream(), [System.Text.Encoding]::UTF8)
        try {
            [void]$teamReader.ReadToEnd()
        }
        finally {
            $teamReader.Dispose()
        }
    }
    catch {
        Write-Warning ("BF-756 preserved-core team warmup skipped on port {0}: {1}" -f $BackendPort, $_.Exception.Message)
    }
    finally {
        if ($null -ne $teamResponse) { $teamResponse.Close() }
    }

    $dashboardRequest = [System.Net.HttpWebRequest]::Create("http://127.0.0.1:$BackendPort/")
    $dashboardRequest.Method = 'GET'
    $dashboardRequest.Timeout = 3000
    $dashboardRequest.ReadWriteTimeout = 3000
    $dashboardRequest.Proxy = $null
    $dashboardRequest.KeepAlive = $false
    $dashboardResponse = $null
    try {
        $dashboardResponse = $dashboardRequest.GetResponse()
        if ([int]$dashboardResponse.StatusCode -ne 200) {
            throw "HTTP $([int]$dashboardResponse.StatusCode)"
        }
        $dashboardReader = [System.IO.StreamReader]::new($dashboardResponse.GetResponseStream(), [System.Text.Encoding]::UTF8)
        try {
            [void]$dashboardReader.ReadToEnd()
        }
        finally {
            $dashboardReader.Dispose()
        }
    }
    catch {
        Write-Warning ("BF-861 preserved-core dashboard warmup skipped on port {0}: {1}" -f $BackendPort, $_.Exception.Message)
    }
    finally {
        if ($null -ne $dashboardResponse) { $dashboardResponse.Close() }
    }
}

function Stop-OwnedProcessTree {
    param([AllowNull()]$Process)

    if ($null -eq $Process) { return }
    try {
        if ($Process.HasExited) { return }
    }
    catch {
        return
    }

    if (Test-Path -LiteralPath $taskkill) {
        try {
            & $taskkill /PID $Process.Id /T /F 2>$null | Out-Null
            if ($LASTEXITCODE -eq 0) { return }
        }
        catch {
        }
    }

    try { $Process.Kill() } catch {}
}

function Restart-PreservedCore {
    param([Parameter(Mandatory = $true)][int]$BackendIndex)

    $previousBackend = $backendProcesses[$BackendIndex]
    $previousPort = [int]$previousBackend.Port
    do {
        $replacementPort = Get-FreeLoopbackPort
    } while ($backendPorts -contains $replacementPort)

    $replacementProcess = Start-PreservedCore -BackendPort $replacementPort
    try {
        Wait-PreservedCore -BackendPort $replacementPort -Process $replacementProcess
        Invoke-PreservedCoreWarmup -BackendPort $replacementPort
    }
    catch {
        Stop-OwnedProcessTree -Process $replacementProcess
        throw
    }

    $backendPorts[$BackendIndex] = $replacementPort
    $backendProcesses[$BackendIndex] = [pscustomobject]@{
        Port = $replacementPort
        Process = $replacementProcess
    }
    Write-Warning ("BF-757 recovered preserved core from port {0} to {1}." -f $previousPort, $replacementPort)
    return [int]$replacementPort
}

function Send-HttpResponse {
    param(
        [Parameter(Mandatory = $true)]$Stream,
        [Parameter(Mandatory = $true)][int]$StatusCode,
        [Parameter(Mandatory = $true)][string]$StatusText,
        [Parameter(Mandatory = $true)][string]$ContentType,
        [Parameter(Mandatory = $true)][string]$Body
    )

    $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($Body)
    $headers = "HTTP/1.1 $StatusCode $StatusText`r`n" +
        "Content-Type: $ContentType`r`n" +
        "Content-Length: $($bodyBytes.Length)`r`n" +
        "Cache-Control: no-store`r`n" +
        "X-Content-Type-Options: nosniff`r`n" +
        "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'`r`n" +
        "Connection: close`r`n`r`n"
    $headerBytes = [System.Text.Encoding]::ASCII.GetBytes($headers)
    $Stream.Write($headerBytes, 0, $headerBytes.Length)
    $Stream.Write($bodyBytes, 0, $bodyBytes.Length)
    $Stream.Flush()
}

$maxCoreWorkers = 6
$backendPorts = New-Object System.Collections.Generic.List[int]
$backendProcesses = New-Object System.Collections.Generic.List[object]
$requestPool = [System.Management.Automation.Runspaces.RunspaceFactory]::CreateRunspacePool(1, $maxCoreWorkers)
$activeRequests = New-Object System.Collections.Generic.List[object]
$listener = [System.Net.Sockets.TcpListener]::new($loopback, $Port)

function Remove-CompletedCoreJobs {
    param([switch]$WaitForOne)

    do {
        $removed = $false
        for ($index = $activeRequests.Count - 1; $index -ge 0; $index--) {
            $job = $activeRequests[$index]
            if (-not $job.Handle.IsCompleted) { continue }
            try {
                [void]$job.PowerShell.EndInvoke($job.Handle)
            }
            catch {
                Write-Warning ("BF-690 core-pool worker failed: {0}" -f $_.Exception.Message)
            }
            finally {
                $job.PowerShell.Dispose()
                $activeRequests.RemoveAt($index)
            }
            $removed = $true
        }
        if ($removed -or -not $WaitForOne) { return }
        Start-Sleep -Milliseconds 10
    } while ($true)
}

function Get-FreeBackendPort {
    $busyPorts = @($activeRequests | ForEach-Object { [int]$_.BackendPort })
    for ($index = 0; $index -lt $backendProcesses.Count; $index++) {
        $candidate = [int]$backendPorts[$index]
        if ($busyPorts -contains $candidate) { continue }

        $backend = $backendProcesses[$index]
        $hasExited = $true
        try {
            $hasExited = [bool]$backend.Process.HasExited
        }
        catch {
            $hasExited = $true
        }
        if (-not $hasExited) { return $candidate }

        try {
            return (Restart-PreservedCore -BackendIndex $index)
        }
        catch {
            Write-Warning ("BF-757 preserved-core recovery failed for port {0}: {1}" -f $candidate, $_.Exception.Message)
        }
    }
    return $null
}

try {
    Enable-ButlerGradleNoDaemon
    Initialize-ReadOnlyCliRuntime
    Initialize-DirectJavaRuntime

    for ($index = 0; $index -lt $maxCoreWorkers; $index++) {
        do {
            $backendPort = Get-FreeLoopbackPort
        } while ($backendPorts -contains $backendPort)

        $process = Start-PreservedCore -BackendPort $backendPort
        $backendPorts.Add($backendPort)
        $backendProcesses.Add([pscustomobject]@{
            Port = $backendPort
            Process = $process
        })
        Wait-PreservedCore -BackendPort $backendPort -Process $process
        Invoke-PreservedCoreWarmup -BackendPort $backendPort
    }

    $requestPool.Open()
    $listener.Start()

    while ($true) {
        Remove-CompletedCoreJobs
        while ($activeRequests.Count -ge $maxCoreWorkers) {
            Remove-CompletedCoreJobs -WaitForOne
        }

        $backendPort = $null
        while ($null -eq $backendPort) {
            $backendPort = Get-FreeBackendPort
            if ($null -ne $backendPort) { break }
            if ($activeRequests.Count -eq 0) {
                throw 'BF-757 BLOCKED: no healthy preserved inner-core worker is available.'
            }
            Remove-CompletedCoreJobs -WaitForOne
        }

        $client = $listener.AcceptTcpClient()

        $backendPort = $null
        while ($null -eq $backendPort) {
            $backendPort = Get-FreeBackendPort
            if ($null -ne $backendPort) { break }
            if ($activeRequests.Count -eq 0) {
                try { $client.Close() } catch {}
                throw 'BF-757 BLOCKED: no healthy preserved inner-core worker is available after accept.'
            }
            Remove-CompletedCoreJobs -WaitForOne
        }

        $client.ReceiveTimeout = 3000
        $client.SendTimeout = 10000
        $powerShell = [System.Management.Automation.PowerShell]::Create()
        try {
            $powerShell.RunspacePool = $requestPool
            [void]$powerShell.AddCommand($requestWorker)
            [void]$powerShell.AddParameter('Client', $client)
            [void]$powerShell.AddParameter('BackendPort', $backendPort)
            $handle = $powerShell.BeginInvoke()
            $activeRequests.Add([pscustomobject]@{
                PowerShell = $powerShell
                Handle = $handle
                BackendPort = $backendPort
            })
        }
        catch {
            try { $client.Close() } catch {}
            $powerShell.Dispose()
            throw
        }
    }
}
finally {
    try { $listener.Stop() } catch {}

    for ($index = $activeRequests.Count - 1; $index -ge 0; $index--) {
        $job = $activeRequests[$index]
        try {
            if (-not $job.Handle.IsCompleted) { $job.PowerShell.Stop() }
            [void]$job.PowerShell.EndInvoke($job.Handle)
        }
        catch {
        }
        finally {
            try { $job.PowerShell.Dispose() } catch {}
        }
    }
    $activeRequests.Clear()
    try { $requestPool.Close() } catch {}
    try { $requestPool.Dispose() } catch {}

    for ($index = $backendProcesses.Count - 1; $index -ge 0; $index--) {
        $backend = $backendProcesses[$index]
        Stop-OwnedProcessTree -Process $backend.Process
    }
    $backendProcesses.Clear()

    if (Test-Path -LiteralPath $runtimeRoot) {
        try { Remove-Item -LiteralPath $runtimeRoot -Recurse -Force -ErrorAction Stop } catch {}
    }
    Restore-RuntimeLib
    Restore-GradleOpts
}
