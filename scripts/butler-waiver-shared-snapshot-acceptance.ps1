param(
    [ValidateRange(1, 600)]
    [int]$StartupTimeoutSeconds = 180,

    [ValidateRange(1, 300)]
    [int]$RequestTimeoutSeconds = 120
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $scriptDir
$appLauncher = Join-Path $scriptDir 'butler-app.ps1'
$powershell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$taskkill = Join-Path $env:SystemRoot 'System32\taskkill.exe'
$git = (Get-Command git.exe -ErrorAction Stop).Source
$loopback = [System.Net.IPAddress]::Parse('127.0.0.1')

foreach ($required in @($appLauncher, $powershell, $taskkill, $git)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "BF-850 BLOCKED: required component not found at $required"
    }
}

function Get-WorkingTreeState {
    Push-Location $repoRoot
    try {
        $lines = & $git status --porcelain=v1 --untracked-files=all 2>&1
        if ($LASTEXITCODE -ne 0) { throw "BF-850 BLOCKED: git status failed with exit code $LASTEXITCODE." }
        return (($lines | ForEach-Object { "$_" }) -join [Environment]::NewLine).Trim()
    }
    finally { Pop-Location }
}

function Get-FreePort {
    $probe = [System.Net.Sockets.TcpListener]::new($loopback, 0)
    try {
        $probe.Start()
        return ([System.Net.IPEndPoint]$probe.LocalEndpoint).Port
    }
    finally { try { $probe.Stop() } catch {} }
}

function Start-OwnedButler {
    param([int]$Port)
    $quote = [char]34
    $start = [System.Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $powershell
    $start.Arguments = '-NoLogo -NoProfile -ExecutionPolicy Bypass -File ' + $quote + $appLauncher + $quote + ' -Port ' + $Port + ' -NoBrowser'
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $process = [System.Diagnostics.Process]::Start($start)
    if ($null -eq $process) { throw 'BF-850 BLOCKED: unable to launch owned Butler process.' }
    return $process
}

function Stop-OwnedButler {
    param([AllowNull()]$Process, [int]$Port)
    if ($null -ne $Process -and -not $Process.HasExited) {
        & $taskkill /PID $Process.Id /T /F 2>$null | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "BF-850 CLEANUP FAILED: taskkill exited with code $LASTEXITCODE." }
    }
}

function Invoke-TimedGet {
    param([string]$Url, [int]$TimeoutMs)
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    $request = [System.Net.HttpWebRequest]::Create($Url)
    $request.Method = 'GET'
    $request.Timeout = $TimeoutMs
    $request.ReadWriteTimeout = $TimeoutMs
    $request.Proxy = $null
    $request.KeepAlive = $false
    $response = $null
    try {
        try { $response = $request.GetResponse() }
        catch [System.Net.WebException] {
            if ($null -eq $_.Exception.Response) { throw }
            $response = $_.Exception.Response
        }
        $reader = [System.IO.StreamReader]::new($response.GetResponseStream(), [System.Text.Encoding]::UTF8)
        try { $body = $reader.ReadToEnd() } finally { $reader.Dispose() }
        return [pscustomobject]@{
            StatusCode = [int]$response.StatusCode
            Body = $body
            Milliseconds = [Math]::Round($watch.Elapsed.TotalMilliseconds, 1)
        }
    }
    finally {
        $watch.Stop()
        if ($null -ne $response) { $response.Close() }
    }
}

function Assert-Waiver {
    param($Response, [string]$Stage)
    if ($Response.StatusCode -ne 200) {
        $plain = [regex]::Replace([string]$Response.Body, '<[^>]+>', ' ')
        $plain = [System.Net.WebUtility]::HtmlDecode($plain)
        $plain = [regex]::Replace($plain, '\s+', ' ').Trim()
        if ($plain.Length -gt 900) { $plain = $plain.Substring(0, 900) + '...' }
        throw "BF-850 BLOCKED: $Stage returned HTTP $($Response.StatusCode). body=$plain"
    }
    foreach ($marker in @('Butler waiver decision','Next step','Authorized review pool','Technical and audit details','READ ONLY')) {
        if ($Response.Body.IndexOf($marker, [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
            throw "BF-850 BLOCKED: $Stage is missing Waiver Board marker: $marker"
        }
    }
}

$before = Get-WorkingTreeState
if (-not [string]::IsNullOrWhiteSpace($before)) {
    throw "BF-850 BLOCKED: repository must be clean before acceptance. status=$before"
}

$port = Get-FreePort
$root = "http://127.0.0.1:$port"
$process = $null
$failure = $null
$passed = $false

Write-Host 'Butler Waiver Board shared-snapshot acceptance (BF-850)'
Write-Host "Target: $root"
Write-Host 'Journey: health -> cold Waiver Board -> immediate warm Waiver Board.'
Write-Host 'Boundary: GET-only /waivers reads; /refresh excluded; no Butler or Sleeper write path is invoked.'

try {
    $process = Start-OwnedButler -Port $port
    $deadline = [DateTime]::UtcNow.AddSeconds($StartupTimeoutSeconds)
    $healthy = $false
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($process.HasExited) { throw "BF-850 FAILED: Butler exited during startup with code $($process.ExitCode)." }
        try {
            $health = Invoke-TimedGet -Url ($root + '/health') -TimeoutMs 1000
            if ($health.StatusCode -eq 200) {
                $identity = $health.Body | ConvertFrom-Json
                if ($null -ne $identity -and [string]$identity.service -ceq 'butler-app-shell' -and [string]$identity.status -ceq 'ok') {
                    $healthy = $true
                    break
                }
                throw 'BF-850 BLOCKED: selected port is not the expected butler-app-shell.'
            }
        }
        catch [System.Net.WebException] {}
        Start-Sleep -Milliseconds 250
    }
    if (-not $healthy) { throw "BF-850 FAILED: Butler did not become healthy within $StartupTimeoutSeconds seconds." }
    Write-Host 'Health: BUTLER_APP_SHELL_VERIFIED'

    $timeoutMs = $RequestTimeoutSeconds * 1000
    $cold = Invoke-TimedGet -Url ($root + '/waivers') -TimeoutMs $timeoutMs
    Assert-Waiver -Response $cold -Stage 'Cold Waiver Board'
    $warm = Invoke-TimedGet -Url ($root + '/waivers') -TimeoutMs $timeoutMs
    Assert-Waiver -Response $warm -Stage 'Warm Waiver Board'

    if ($warm.Milliseconds -ge $cold.Milliseconds) {
        throw ("BF-850 FAILED: immediate Waiver Board warm read ({0} ms) did not beat cold read ({1} ms)." -f $warm.Milliseconds, $cold.Milliseconds)
    }

    Write-Host ("Cold Waivers: {0:N1} ms" -f $cold.Milliseconds)
    Write-Host ("Warm Waivers: {0:N1} ms" -f $warm.Milliseconds)
    Write-Host 'Warm cache: VERIFIED'
    Write-Host 'Shared live snapshot boundary: PRESERVED'
    $passed = $true
}
catch { $failure = $_ }
finally {
    try { Stop-OwnedButler -Process $process -Port $port }
    catch {
        if ($null -eq $failure) { $failure = $_ } else { Write-Warning $_.Exception.Message }
    }
}

$after = $null
try { $after = Get-WorkingTreeState }
catch {
    if ($null -eq $failure) { $failure = $_ } else { Write-Warning $_.Exception.Message }
}
if ($null -eq $failure -and -not [string]::IsNullOrWhiteSpace($after)) {
    $failure = [System.Exception]::new("BF-850 FAILED: repository became dirty during acceptance. status=$after")
}

if ($null -ne $failure) {
    Write-Host 'BF-850 RESULT: FAIL'
    throw $failure
}
if (-not $passed) { throw 'BF-850 FAILED: acceptance ended without a result.' }

Write-Host 'Working tree: CLEAN'
Write-Host 'BF-850 RESULT: COMPLETE'
