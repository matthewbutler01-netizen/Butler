param(
    [ValidateRange(1, 5)]
    [int]$Samples = 3
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-Bf750MedianMilliseconds {
    param([Parameter(Mandatory = $true)][long[]]$Values)

    if ($null -eq $Values -or $Values.Count -eq 0) {
        throw 'BF-750 BLOCKED: median requires at least one timing sample.'
    }
    $ordered = @($Values | Sort-Object)
    $count = $ordered.Count
    $middle = [int][Math]::Floor($count / 2.0)
    if (($count % 2) -eq 1) { return [long]$ordered[$middle] }
    $lower = [long]$ordered[$middle - 1]
    $upper = [long]$ordered[$middle]
    return [long][Math]::Round(($lower + $upper) / 2.0, [MidpointRounding]::AwayFromZero)
}

function Get-Bf750FreeLoopbackPort {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Parse('127.0.0.1'), 0)
    try {
        $listener.Start()
        return ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
    }
    finally {
        $listener.Stop()
    }
}

function Get-Bf750ProcessTail {
    param([AllowNull()][string]$Text)

    if ([string]::IsNullOrWhiteSpace($Text)) { return '' }
    $normalized = [regex]::Replace($Text, '\s+', ' ').Trim()
    if ($normalized.Length -gt 1200) {
        return '...' + $normalized.Substring($normalized.Length - 1200)
    }
    return $normalized
}

function Invoke-Bf750Get {
    param(
        [Parameter(Mandatory = $true)][int]$Port,
        [Parameter(Mandatory = $true)][string]$RequestTarget
    )

    $request = [System.Net.HttpWebRequest]::Create("http://127.0.0.1:$Port$RequestTarget")
    $request.Method = 'GET'
    $request.Timeout = 180000
    $request.ReadWriteTimeout = 180000
    $request.Proxy = $null
    $request.KeepAlive = $false
    $response = $null
    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $response = $request.GetResponse()
        $reader = [System.IO.StreamReader]::new($response.GetResponseStream(), [System.Text.Encoding]::UTF8)
        try { $body = $reader.ReadToEnd() } finally { $reader.Dispose() }
        $stopwatch.Stop()
        if ([int]$response.StatusCode -ne 200) {
            throw "BF-750 BLOCKED: $RequestTarget returned HTTP $([int]$response.StatusCode)."
        }
        if ([string]::IsNullOrWhiteSpace($body)) {
            throw "BF-750 BLOCKED: $RequestTarget returned an empty response body."
        }
        return [long]$stopwatch.ElapsedMilliseconds
    }
    catch {
        if ($stopwatch.IsRunning) { $stopwatch.Stop() }
        throw
    }
    finally {
        if ($null -ne $response) { $response.Close() }
    }
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $scriptDir
$appShell = Join-Path $scriptDir 'butler-app-shell.ps1'
$powershell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$taskkill = Join-Path $env:SystemRoot 'System32\taskkill.exe'

if (-not (Test-Path -LiteralPath $appShell -PathType Leaf)) {
    throw "BF-750 BLOCKED: Butler app shell not found at $appShell"
}
if (-not (Test-Path -LiteralPath $powershell -PathType Leaf)) {
    throw 'BF-750 BLOCKED: Windows PowerShell 5.1 executable is unavailable.'
}

$localAppData = $env:LOCALAPPDATA
if ([string]::IsNullOrWhiteSpace($localAppData)) {
    $localAppData = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)
}
if ([string]::IsNullOrWhiteSpace($localAppData)) {
    throw 'BF-750 BLOCKED: LocalApplicationData is unavailable.'
}
$configPath = Join-Path (Join-Path $localAppData 'Butler') 'app-league.txt'
if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
    throw 'BF-750 BLOCKED: Butler app league configuration is unavailable.'
}
$leagueId = [IO.File]::ReadAllText($configPath, [Text.Encoding]::ASCII).Trim()
$parsedLeagueId = [Guid]::Empty
if ([string]::IsNullOrWhiteSpace($leagueId) -or -not [Guid]::TryParse($leagueId, [ref]$parsedLeagueId)) {
    throw 'BF-750 BLOCKED: Butler app league configuration is invalid.'
}
$leagueId = $parsedLeagueId.ToString('D').ToLowerInvariant()

$port = Get-Bf750FreeLoopbackPort
$start = [System.Diagnostics.ProcessStartInfo]::new()
$start.FileName = $powershell
$start.Arguments = "-NoLogo -NoProfile -ExecutionPolicy Bypass -File `"$appShell`" -LeagueId `"$leagueId`" -Port $port -NoBrowser"
$start.WorkingDirectory = $repoRoot
$start.UseShellExecute = $false
$start.CreateNoWindow = $true
$start.RedirectStandardOutput = $true
$start.RedirectStandardError = $true
$start.EnvironmentVariables['BUTLER_APP_PERSISTENT_CORE_WORKER'] = ''
$start.EnvironmentVariables['BUTLER_APP_SLEEPER_TRANSPORT_PREWARM'] = ''

$process = [System.Diagnostics.Process]::new()
$process.StartInfo = $start
$stdoutTask = $null
$stderrTask = $null
try {
    if (-not $process.Start()) {
        throw 'BF-750 BLOCKED: unable to start the temporary Butler app shell.'
    }
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()

    $healthy = $false
    for ($attempt = 0; $attempt -lt 480; $attempt++) {
        if ($process.HasExited) {
            $stderr = if ($null -ne $stderrTask -and $stderrTask.IsCompleted) { [string]$stderrTask.Result } else { '' }
            throw "BF-750 BLOCKED: temporary Butler app shell exited during startup. $(Get-Bf750ProcessTail -Text $stderr)"
        }
        try {
            $health = [System.Net.HttpWebRequest]::Create("http://127.0.0.1:$port/health")
            $health.Method = 'GET'
            $health.Timeout = 750
            $health.Proxy = $null
            $health.KeepAlive = $false
            $healthResponse = $health.GetResponse()
            try {
                if ([int]$healthResponse.StatusCode -eq 200) {
                    $healthy = $true
                    break
                }
            }
            finally {
                $healthResponse.Close()
            }
        }
        catch {
        }
        Start-Sleep -Milliseconds 250
    }
    if (-not $healthy) {
        throw 'BF-750 BLOCKED: temporary Butler app shell did not become healthy.'
    }

    $nonce = [Guid]::NewGuid().ToString('N')
    $primeMs = Invoke-Bf750Get -Port $port -RequestTarget ("/league?bf750=prime-{0}" -f $nonce)
    $teamSamples = @()
    $waiverSamples = @()
    $leagueSamples = @()

    for ($sample = 1; $sample -le $Samples; $sample++) {
        $teamMs = Invoke-Bf750Get -Port $port -RequestTarget ("/team?bf750=team-{0}-{1}" -f $sample, $nonce)
        $waiverMs = Invoke-Bf750Get -Port $port -RequestTarget ("/waivers?bf750=waivers-{0}-{1}" -f $sample, $nonce)
        $leagueMs = Invoke-Bf750Get -Port $port -RequestTarget ("/league?bf750=league-{0}-{1}" -f $sample, $nonce)

        $teamSamples += [long]$teamMs
        $waiverSamples += [long]$waiverMs
        $leagueSamples += [long]$leagueMs
        Write-Host ("BF-750 sample {0}: team_ms={1}; waivers_ms={2}; league_ms={3}" -f $sample, $teamMs, $waiverMs, $leagueMs)
    }

    $teamMedian = Get-Bf750MedianMilliseconds -Values $teamSamples
    $waiverMedian = Get-Bf750MedianMilliseconds -Values $waiverSamples
    $leagueMedian = Get-Bf750MedianMilliseconds -Values $leagueSamples

    Write-Host ''
    Write-Host ("BF-750 warm same-core route timing: prime_ms={0}; team_ms={1}; waivers_ms={2}; league_ms={3}; samples={4}" -f `
        $primeMs, $teamMedian, $waiverMedian, $leagueMedian, $Samples)
    Write-Host 'BF-750 diagnostic boundary: sequential GET-only production-path reads with diagnostic query strings; outer exact-request single-flight cache bypassed only for measurement; /refresh excluded; no Butler or Sleeper write path is invoked.'
}
finally {
    if ($null -ne $process) {
        try {
            if (-not $process.HasExited) {
                if (Test-Path -LiteralPath $taskkill -PathType Leaf) {
                    & $taskkill /PID $process.Id /T /F 2>$null | Out-Null
                }
                else {
                    $process.Kill()
                }
                try { [void]$process.WaitForExit(5000) } catch {}
            }
        }
        catch {
            try { if (-not $process.HasExited) { $process.Kill() } } catch {}
        }
        try { $process.Dispose() } catch {}
    }
}
