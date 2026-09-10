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
$coreShell = Join-Path $scriptDir 'butler-app-shell-core.ps1'
$tradeHost = Join-Path $scriptDir 'butler-trade-lab-host.ps1'
$tradeLab = Join-Path $scriptDir 'butler-trade-lab.ps1'
$history = Join-Path $scriptDir 'butler-decision-history.ps1'
$gradle = Join-Path $repoRoot 'gradlew.bat'
$loopback = [System.Net.IPAddress]::Parse('127.0.0.1')

foreach ($required in @($coreShell, $tradeHost, $tradeLab, $history, $gradle)) {
    if (-not (Test-Path -LiteralPath $required)) {
        throw "BF-670 BLOCKED: required Butler app component not found at $required"
    }
}

# App modules define only read-only presentation/orchestration helpers.
. $tradeHost
. $tradeLab
. $history

# Windows PowerShell 5.1 can bind String.Split(char[], int) calls to the
# StringSplitOptions overload. Override only the request-query parser with
# IndexOf/Substring so BF-670 remains compatible with the supported launcher.
function ConvertFrom-TradeRequestTarget {
    param([Parameter(Mandatory = $true)][string]$RequestTarget)

    $query = @{}
    $question = $RequestTarget.IndexOf('?')
    if ($question -lt 0 -or $question + 1 -ge $RequestTarget.Length) { return $query }

    $rawQuery = $RequestTarget.Substring($question + 1)
    foreach ($pair in ($rawQuery -split '&')) {
        if ([string]::IsNullOrWhiteSpace($pair)) { continue }
        $equals = $pair.IndexOf('=')
        if ($equals -lt 0) {
            $rawKey = $pair.Replace('+', ' ')
            $rawValue = ''
        }
        else {
            $rawKey = $pair.Substring(0, $equals).Replace('+', ' ')
            $rawValue = $pair.Substring($equals + 1).Replace('+', ' ')
        }
        $key = [System.Uri]::UnescapeDataString($rawKey)
        $value = [System.Uri]::UnescapeDataString($rawValue)
        if ([string]::IsNullOrWhiteSpace($key)) { continue }
        if ($query.ContainsKey($key)) {
            $query[$key] = @($query[$key]) + @($value)
        }
        else {
            $query[$key] = @($value)
        }
    }
    return $query
}

# An empty HashSet is normally unrolled by the PowerShell pipeline into no
# output, which turns the caller's selection set into $null. Preserve the
# collection object even when nothing is selected so opponent loading can
# render unchecked asset boxes safely on Windows PowerShell 5.1.
function Get-TradeSelectionSet {
    param([object[]]$Values)
    $set = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    foreach ($value in @($Values)) { [void]$set.Add([string]$value) }
    Write-Output -NoEnumerate $set
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

function Start-AppCore {
    param([Parameter(Mandatory = $true)][int]$InnerPort)

    $powershell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    if (-not (Test-Path -LiteralPath $powershell)) {
        throw 'BF-670 BLOCKED: Windows PowerShell 5.1 executable not found.'
    }

    $start = [System.Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $powershell
    $start.Arguments = "-NoLogo -NoProfile -ExecutionPolicy Bypass -File `"$coreShell`" -LeagueId `"$LeagueId`" -Port $InnerPort -NoBrowser"
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $process = [System.Diagnostics.Process]::Start($start)
    if ($null -eq $process) {
        throw 'BF-670 BLOCKED: unable to start the preserved Butler app core.'
    }
    return $process
}

function Wait-ForAppCore {
    param(
        [Parameter(Mandatory = $true)][int]$InnerPort,
        [Parameter(Mandatory = $true)]$Process
    )

    for ($attempt = 0; $attempt -lt 100; $attempt++) {
        if ($Process.HasExited) {
            throw 'BF-670 BLOCKED: preserved Butler app core exited during startup.'
        }
        try {
            $request = [System.Net.HttpWebRequest]::Create("http://127.0.0.1:$InnerPort/health")
            $request.Method = 'GET'
            $request.Timeout = 750
            $request.Proxy = $null
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
    throw 'BF-670 BLOCKED: preserved Butler app core did not become healthy.'
}

function Invoke-AppCoreGet {
    param(
        [Parameter(Mandatory = $true)][int]$InnerPort,
        [Parameter(Mandatory = $true)][string]$RequestTarget
    )

    $request = [System.Net.HttpWebRequest]::Create("http://127.0.0.1:$InnerPort$RequestTarget")
    $request.Method = 'GET'
    $request.Timeout = 180000
    $request.Proxy = $null
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
            StatusText = [string]$response.StatusDescription
            ContentType = if ([string]::IsNullOrWhiteSpace($response.ContentType)) { 'text/plain; charset=utf-8' } else { [string]$response.ContentType }
            Body = $body
        }
    }
    finally {
        if ($null -ne $response) { $response.Close() }
    }
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
        "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'`r`n" +
        "Connection: close`r`n`r`n"
    $headerBytes = [System.Text.Encoding]::ASCII.GetBytes($headers)
    $Stream.Write($headerBytes, 0, $headerBytes.Length)
    $Stream.Write($bodyBytes, 0, $bodyBytes.Length)
    $Stream.Flush()
}

$innerPort = Get-FreeLoopbackPort
$coreProcess = $null
$listener = [System.Net.Sockets.TcpListener]::new($loopback, $Port)
Push-Location $repoRoot
try {
    $coreProcess = Start-AppCore -InnerPort $innerPort
    Wait-ForAppCore -InnerPort $innerPort -Process $coreProcess
    $listener.Start()

    $url = "http://127.0.0.1:$Port/"
    Write-Host 'Butler App Shell (BF-671)'
    Write-Host "Local URL: $url"
    Write-Host "My Team: http://127.0.0.1:$Port/team"
    Write-Host "Waiver Board: http://127.0.0.1:$Port/waivers"
    Write-Host "League: http://127.0.0.1:$Port/league"
    Write-Host "Trade Lab: http://127.0.0.1:$Port/trade"
    Write-Host "History: http://127.0.0.1:$Port/history"
    Write-Host 'Bind: 127.0.0.1 only'
    Write-Host "Preserved BF-668 app core: isolated on internal loopback port $innerPort"
    Write-Host 'Boundary: GET-only read-only app routing/presentation; no automatic Butler or Sleeper write.'
    Write-Host 'Press Ctrl+C to stop Butler.'

    if (-not $NoBrowser) { Start-Process $url }

    while ($true) {
        $client = $listener.AcceptTcpClient()
        try {
            $stream = $client.GetStream()
            $reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::ASCII, $false, 8192, $true)
            $requestLine = $reader.ReadLine()
            if ([string]::IsNullOrWhiteSpace($requestLine)) { continue }
            while ($true) {
                $headerLine = $reader.ReadLine()
                if ($null -eq $headerLine -or $headerLine.Length -eq 0) { break }
            }

            $parts = $requestLine.Split(' ')
            if ($parts.Length -lt 2 -or $parts[0] -ne 'GET') {
                Send-HttpResponse -Stream $stream -StatusCode 405 -StatusText 'Method Not Allowed' -ContentType 'text/plain; charset=utf-8' -Body 'GET only'
                continue
            }

            $requestTarget = $parts[1]
            if ($requestTarget.Length -gt 16384) {
                Send-HttpResponse -Stream $stream -StatusCode 414 -StatusText 'URI Too Long' -ContentType 'text/plain; charset=utf-8' -Body 'Butler request is too large.'
                continue
            }
            $path = $requestTarget.Split('?')[0]

            if ($path -eq '/health') {
                Send-HttpResponse -Stream $stream -StatusCode 200 -StatusText 'OK' -ContentType 'application/json; charset=utf-8' -Body '{"status":"ok","service":"butler-app-shell","core":"ready","tradeLab":"ready","history":"ready","bind":"127.0.0.1"}'
                continue
            }

            if ($path -eq '/history') {
                try {
                    $html = if ($requestTarget -ceq '/history') {
                        Get-DecisionHistoryLoadingHtml -LeagueId $LeagueId
                    }
                    else {
                        Invoke-DecisionHistoryHtml -LeagueId $LeagueId
                    }
                    Send-HttpResponse -Stream $stream -StatusCode 200 -StatusText 'OK' -ContentType 'text/html; charset=utf-8' -Body $html
                }
                catch {
                    $errorHtml = "<!doctype html><html><body><h1>Butler Decision History blocked</h1><pre>$(ConvertTo-HtmlText $_.Exception.Message)</pre><p>No Butler or Sleeper write was executed.</p><p><a href=`"/history`">Return to Decision History</a></p></body></html>"
                    Send-HttpResponse -Stream $stream -StatusCode 400 -StatusText 'Bad Request' -ContentType 'text/html; charset=utf-8' -Body $errorHtml
                }
                continue
            }

            if ($path -eq '/trade') {
                try {
                    $html = if ($requestTarget -ceq '/trade') {
                        Get-TradeLabLoadingHtml -LeagueId $LeagueId
                    }
                    else {
                        Invoke-TradeLabHtml -LeagueId $LeagueId -RequestTarget $requestTarget
                    }
                    Send-HttpResponse -Stream $stream -StatusCode 200 -StatusText 'OK' -ContentType 'text/html; charset=utf-8' -Body $html
                }
                catch {
                    $errorHtml = "<!doctype html><html><body><h1>Butler Trade Lab blocked</h1><pre>$(ConvertTo-HtmlText $_.Exception.Message)</pre><p>No Butler or Sleeper write was executed.</p><p><a href=`"/trade`">Return to Trade Lab</a></p></body></html>"
                    Send-HttpResponse -Stream $stream -StatusCode 400 -StatusText 'Bad Request' -ContentType 'text/html; charset=utf-8' -Body $errorHtml
                }
                continue
            }

            try {
                $proxied = Invoke-AppCoreGet -InnerPort $innerPort -RequestTarget $requestTarget
                $body = $proxied.Body
                if ($proxied.ContentType -match '^text/html' -and $body -match '<nav class="nav" aria-label="Butler sections">') {
                    $body = Add-AppNavigation -Html $body
                }
                Send-HttpResponse -Stream $stream -StatusCode $proxied.StatusCode -StatusText $proxied.StatusText -ContentType $proxied.ContentType -Body $body
            }
            catch {
                $errorHtml = "<!doctype html><html><body><h1>Butler app blocked</h1><pre>$(ConvertTo-HtmlText $_.Exception.Message)</pre><p>No Butler or Sleeper write was executed.</p></body></html>"
                Send-HttpResponse -Stream $stream -StatusCode 500 -StatusText 'Internal Server Error' -ContentType 'text/html; charset=utf-8' -Body $errorHtml
            }
        }
        finally {
            $client.Close()
        }
    }
}
finally {
    try { $listener.Stop() } catch {}
    if ($null -ne $coreProcess -and -not $coreProcess.HasExited) {
        try { $coreProcess.Kill() } catch {}
        try { $coreProcess.WaitForExit(5000) | Out-Null } catch {}
    }
    Pop-Location
}
