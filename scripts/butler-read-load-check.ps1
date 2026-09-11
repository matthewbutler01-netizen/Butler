param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$BaseUrl,

    [ValidateRange(1, 32)]
    [int]$Concurrency = 6,

    [ValidateRange(1, 20)]
    [int]$RequestsPerPath = 3,

    [ValidateRange(1, 300)]
    [int]$TimeoutSeconds = 60
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$paths = @('/health', '/', '/team', '/waivers', '/league', '/trade', '/history')
if ($paths -contains '/refresh') {
    throw 'BF-688 BLOCKED: /refresh is forbidden in the read-only load path set.'
}

function Get-ButlerBaseUri {
    param([Parameter(Mandatory = $true)][string]$Value)

    $uri = $null
    if (-not [Uri]::TryCreate($Value, [UriKind]::Absolute, [ref]$uri)) {
        throw 'BF-688 BLOCKED: BaseUrl must be an absolute HTTP loopback URL.'
    }
    if ($uri.Scheme -cne 'http') {
        throw 'BF-688 BLOCKED: BaseUrl must use http.'
    }
    if ($uri.Host -ne '127.0.0.1' -and $uri.Host -ne 'localhost') {
        throw 'BF-688 BLOCKED: BaseUrl must target 127.0.0.1 or localhost only.'
    }
    if (-not [string]::IsNullOrEmpty($uri.UserInfo) -or
        -not [string]::IsNullOrEmpty($uri.Query) -or
        -not [string]::IsNullOrEmpty($uri.Fragment)) {
        throw 'BF-688 BLOCKED: BaseUrl cannot contain user info, a query, or a fragment.'
    }
    if ($uri.AbsolutePath -ne '/') {
        throw 'BF-688 BLOCKED: BaseUrl must identify the Butler app root.'
    }
    if ($uri.Port -le 0) {
        throw 'BF-688 BLOCKED: BaseUrl must include a valid local port.'
    }

    return ('http://{0}:{1}' -f $uri.Host, $uri.Port)
}

function Invoke-ButlerHealthCheck {
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
        $response = $request.GetResponse()
        if ([int]$response.StatusCode -ne 200) {
            throw "BF-688 BLOCKED: Butler /health returned HTTP $([int]$response.StatusCode)."
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
            throw 'BF-688 BLOCKED: target did not identify as a healthy butler-app-shell.'
        }
        return $true
    }
    finally {
        if ($null -ne $response) { $response.Close() }
    }
}

function Get-PercentileMilliseconds {
    param(
        [Parameter(Mandatory = $true)][double[]]$Values,
        [Parameter(Mandatory = $true)][ValidateRange(0.0, 1.0)][double]$Percentile
    )

    if ($Values.Count -eq 0) { return 0 }
    $sorted = @($Values | Sort-Object)
    $rank = [int][Math]::Ceiling($Percentile * $sorted.Count) - 1
    if ($rank -lt 0) { $rank = 0 }
    if ($rank -ge $sorted.Count) { $rank = $sorted.Count - 1 }
    return [Math]::Round([double]$sorted[$rank], 1)
}

$root = Get-ButlerBaseUri -Value $BaseUrl
$timeoutMs = $TimeoutSeconds * 1000

Write-Host 'Butler read-only peak-load baseline (BF-688)'
Write-Host "Target: $root"
Write-Host "Concurrency: $Concurrency"
Write-Host "Requests per path: $RequestsPerPath"
Write-Host ('Paths: ' + ($paths -join ', '))
Write-Host 'Boundary: GET-only local Butler reads; /refresh excluded; no Butler or Sleeper write path is invoked.'

[void](Invoke-ButlerHealthCheck -Root $root -TimeoutMs $timeoutMs)
Write-Host 'Preflight health: BUTLER_APP_SHELL_VERIFIED'

$worker = {
    param($Root, $Path, $TimeoutMs, $Sequence)

    $request = $null
    $response = $null
    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    $statusCode = 0
    $errorText = $null
    try {
        $request = [System.Net.HttpWebRequest]::Create($Root + $Path)
        $request.Method = 'GET'
        $request.Timeout = $TimeoutMs
        $request.ReadWriteTimeout = $TimeoutMs
        $request.Proxy = $null
        $request.KeepAlive = $false
        $request.AllowAutoRedirect = $false
        $request.UserAgent = 'Butler-BF688-ReadLoadCheck/1.0'
        $response = $request.GetResponse()
        $statusCode = [int]$response.StatusCode
        $reader = [System.IO.StreamReader]::new($response.GetResponseStream(), [System.Text.Encoding]::UTF8)
        try { [void]$reader.ReadToEnd() } finally { $reader.Dispose() }
    }
    catch [System.Net.WebException] {
        if ($null -ne $_.Exception.Response) {
            $response = $_.Exception.Response
            try { $statusCode = [int]$response.StatusCode } catch { $statusCode = 0 }
        }
        $errorText = $_.Exception.Message
    }
    catch {
        $errorText = $_.Exception.Message
    }
    finally {
        if ($null -ne $response) { $response.Close() }
        $stopwatch.Stop()
    }

    [pscustomobject]@{
        Path = [string]$Path
        Sequence = [int]$Sequence
        StatusCode = [int]$statusCode
        Milliseconds = [Math]::Round($stopwatch.Elapsed.TotalMilliseconds, 1)
        Ok = ($statusCode -ge 200 -and $statusCode -lt 300 -and [string]::IsNullOrEmpty($errorText))
        Error = if ($null -eq $errorText) { '' } else { [string]$errorText }
    }
}

$pool = [System.Management.Automation.Runspaces.RunspaceFactory]::CreateRunspacePool(1, $Concurrency)
$jobs = New-Object System.Collections.Generic.List[object]
$results = New-Object System.Collections.Generic.List[object]
$overall = [System.Diagnostics.Stopwatch]::StartNew()
try {
    $pool.Open()
    foreach ($path in $paths) {
        for ($sequence = 1; $sequence -le $RequestsPerPath; $sequence++) {
            $powerShell = [System.Management.Automation.PowerShell]::Create()
            $powerShell.RunspacePool = $pool
            [void]$powerShell.AddScript($worker.ToString())
            [void]$powerShell.AddArgument($root)
            [void]$powerShell.AddArgument($path)
            [void]$powerShell.AddArgument($timeoutMs)
            [void]$powerShell.AddArgument($sequence)
            $handle = $powerShell.BeginInvoke()
            $jobs.Add([pscustomobject]@{ PowerShell = $powerShell; Handle = $handle; Path = $path; Sequence = $sequence })
        }
    }

    foreach ($job in $jobs) {
        try {
            $items = @($job.PowerShell.EndInvoke($job.Handle))
            foreach ($item in $items) {
                if ($null -ne $item) { $results.Add($item) }
            }
        }
        catch {
            $results.Add([pscustomobject]@{
                Path = [string]$job.Path
                Sequence = [int]$job.Sequence
                StatusCode = 0
                Milliseconds = 0.0
                Ok = $false
                Error = [string]$_.Exception.Message
            })
        }
        finally {
            $job.PowerShell.Dispose()
        }
    }
}
finally {
    $overall.Stop()
    try { $pool.Close() } catch {}
    $pool.Dispose()
}

Write-Host ''
Write-Host 'Per-path results'
Write-Host ('{0,-10} {1,8} {2,8} {3,8} {4,10} {5,10} {6,10}' -f 'Path', 'Requests', 'Success', 'Failures', 'P50Ms', 'P95Ms', 'MaxMs')
foreach ($path in $paths) {
    $pathResults = @($results | Where-Object { $_.Path -ceq $path })
    $successes = @($pathResults | Where-Object { $_.Ok }).Count
    $failures = $pathResults.Count - $successes
    $elapsed = @($pathResults | ForEach-Object { [double]$_.Milliseconds })
    $p50 = Get-PercentileMilliseconds -Values $elapsed -Percentile 0.50
    $p95 = Get-PercentileMilliseconds -Values $elapsed -Percentile 0.95
    $max = if ($elapsed.Count -eq 0) { 0 } else { [Math]::Round([double](($elapsed | Measure-Object -Maximum).Maximum), 1) }
    Write-Host ('{0,-10} {1,8} {2,8} {3,8} {4,10:N1} {5,10:N1} {6,10:N1}' -f $path, $pathResults.Count, $successes, $failures, $p50, $p95, $max)
}

$total = $results.Count
$totalSuccess = @($results | Where-Object { $_.Ok }).Count
$totalFailures = $total - $totalSuccess
Write-Host ''
Write-Host "Overall requests: $total"
Write-Host "Overall success: $totalSuccess"
Write-Host "Overall failures: $totalFailures"
Write-Host ('Overall elapsed ms: {0:N1}' -f $overall.Elapsed.TotalMilliseconds)

if ($totalFailures -gt 0) {
    Write-Host ''
    Write-Host 'Failures'
    foreach ($failure in @($results | Where-Object { -not $_.Ok })) {
        Write-Host ("{0} #{1}: HTTP {2}; {3}" -f $failure.Path, $failure.Sequence, $failure.StatusCode, $failure.Error)
    }
}

[void](Invoke-ButlerHealthCheck -Root $root -TimeoutMs $timeoutMs)
Write-Host 'Post-run health: BUTLER_APP_SHELL_VERIFIED'

if ($totalFailures -gt 0) {
    throw "BF-688 FAILED: $totalFailures read-only requests failed. No Butler or Sleeper write was attempted."
}

Write-Host 'BF-688 RESULT: COMPLETE'
