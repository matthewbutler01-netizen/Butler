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
        throw "BF-845 BLOCKED: required component not found at $required"
    }
}

function Get-WorkingTreeState {
    Push-Location $repoRoot
    try {
        $lines = & $git status --porcelain=v1 --untracked-files=all 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "BF-845 BLOCKED: git status failed with exit code $LASTEXITCODE."
        }
        return (($lines | ForEach-Object { "$_" }) -join [Environment]::NewLine).Trim()
    }
    finally {
        Pop-Location
    }
}

function Get-FreePort {
    $probe = [System.Net.Sockets.TcpListener]::new($loopback, 0)
    try {
        $probe.Start()
        return ([System.Net.IPEndPoint]$probe.LocalEndpoint).Port
    }
    finally {
        try { $probe.Stop() } catch {}
    }
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
    if ($null -eq $process) {
        throw 'BF-845 BLOCKED: unable to launch owned Butler process.'
    }
    return $process
}

function Stop-OwnedButler {
    param([AllowNull()]$Process, [int]$Port)

    if ($null -ne $Process -and -not $Process.HasExited) {
        & $taskkill /PID $Process.Id /T /F 2>$null | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "BF-845 CLEANUP FAILED: taskkill exited with code $LASTEXITCODE."
        }
    }

    try {
        $localAppData = $env:LOCALAPPDATA
        if ([string]::IsNullOrWhiteSpace($localAppData)) {
            $localAppData = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)
        }
        if (-not [string]::IsNullOrWhiteSpace($localAppData)) {
            $runState = Join-Path (Join-Path $localAppData 'Butler') ("running-port-{0}.txt" -f $Port)
            if (Test-Path -LiteralPath $runState -PathType Leaf) {
                $raw = [IO.File]::ReadAllText($runState, [Text.Encoding]::ASCII).Trim()
                if ($null -ne $Process -and $raw.StartsWith(([string]$Process.Id + '|'), [System.StringComparison]::Ordinal)) {
                    Remove-Item -LiteralPath $runState -Force
                }
            }
        }
    }
    catch {
        Write-Warning ("BF-845 could not remove owned run-state marker: {0}" -f $_.Exception.Message)
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
        try {
            $response = $request.GetResponse()
        }
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

function Assert-Matchup {
    param($Response, [string]$Stage)

    if ($Response.StatusCode -ne 200) {
        throw "BF-845 BLOCKED: $Stage returned HTTP $($Response.StatusCode)."
    }
    foreach ($marker in @('Weekly matchup','Lineup advisor','READ ONLY.')) {
        if ($Response.Body.IndexOf($marker, [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
            throw "BF-845 BLOCKED: $Stage is missing governed Matchup marker: $marker"
        }
    }
    $verified = $Response.Body.IndexOf('PAIRING VERIFIED', [System.StringComparison]::OrdinalIgnoreCase) -ge 0
    $unavailable = $Response.Body.IndexOf('Opponent pairing unavailable', [System.StringComparison]::OrdinalIgnoreCase) -ge 0
    if (-not $verified -and -not $unavailable) {
        throw "BF-845 BLOCKED: $Stage exposed neither verified pairing nor the governed fail-closed pairing state."
    }
}

$before = Get-WorkingTreeState
if (-not [string]::IsNullOrWhiteSpace($before)) {
    throw "BF-845 BLOCKED: repository must be clean before acceptance. status=$before"
}

$port = Get-FreePort
$root = "http://127.0.0.1:$port"
$process = $null
$failure = $null
$passed = $false

Write-Host 'Butler passive Matchup single-flight acceptance (BF-845)'
Write-Host "Target: $root"
Write-Host 'Journey: health -> cold passive Matchup -> immediate warm passive Matchup.'
Write-Host 'Boundary: GET-only /matchup reads; /matchup/autofill and /refresh are not invoked.'

try {
    $process = Start-OwnedButler -Port $port
    $deadline = [DateTime]::UtcNow.AddSeconds($StartupTimeoutSeconds)
    $healthy = $false
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($process.HasExited) {
            throw "BF-845 FAILED: Butler exited during startup with code $($process.ExitCode)."
        }
        try {
            $health = Invoke-TimedGet -Url ($root + '/health') -TimeoutMs 1000
            if ($health.StatusCode -eq 200) {
                $identity = $health.Body | ConvertFrom-Json
                if ($null -ne $identity -and [string]$identity.service -ceq 'butler-app-shell' -and [string]$identity.status -ceq 'ok') {
                    $healthy = $true
                    break
                }
                throw 'BF-845 BLOCKED: selected port is not the expected butler-app-shell.'
            }
        }
        catch [System.Net.WebException] {}
        Start-Sleep -Milliseconds 250
    }
    if (-not $healthy) {
        throw "BF-845 FAILED: Butler did not become healthy within $StartupTimeoutSeconds seconds."
    }
    Write-Host 'Health: BUTLER_APP_SHELL_VERIFIED'

    $timeoutMs = $RequestTimeoutSeconds * 1000

    $cold = Invoke-TimedGet -Url ($root + '/matchup') -TimeoutMs $timeoutMs
    Assert-Matchup -Response $cold -Stage 'Cold Matchup'

    $warm = Invoke-TimedGet -Url ($root + '/matchup') -TimeoutMs $timeoutMs
    Assert-Matchup -Response $warm -Stage 'Warm Matchup'

    if ($warm.Milliseconds -ge $cold.Milliseconds) {
        throw ("BF-845 FAILED: immediate passive Matchup warm read ({0} ms) did not beat the cold read ({1} ms)." -f $warm.Milliseconds, $cold.Milliseconds)
    }

    Write-Host ("Cold Matchup: {0:N1} ms" -f $cold.Milliseconds)
    Write-Host ("Warm Matchup: {0:N1} ms" -f $warm.Milliseconds)
    Write-Host 'Warm cache: VERIFIED'
    Write-Host 'AutoFill cache boundary: PRESERVED'
    $passed = $true
}
catch {
    $failure = $_
}
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
    $failure = [System.Exception]::new("BF-845 FAILED: repository became dirty during acceptance. status=$after")
}

if ($null -ne $failure) {
    Write-Host 'BF-845 RESULT: FAIL'
    throw $failure
}
if (-not $passed) {
    throw 'BF-845 FAILED: acceptance ended without a result.'
}

Write-Host 'Working tree: CLEAN'
Write-Host 'BF-845 RESULT: COMPLETE'
