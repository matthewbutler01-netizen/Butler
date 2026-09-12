param(
    [Parameter(Mandatory = $true)]
    [System.Net.Sockets.TcpClient]$Client,

    [Parameter(Mandatory = $true)]
    [int]$BackendPort
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

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

function Invoke-PreservedCoreGet {
    param([Parameter(Mandatory = $true)][string]$RequestTarget)

    $request = [System.Net.HttpWebRequest]::Create("http://127.0.0.1:$BackendPort$RequestTarget")
    $request.Method = 'GET'
    $request.Timeout = 180000
    $request.ReadWriteTimeout = 180000
    $request.Proxy = $null
    $request.KeepAlive = $false
    $request.AllowAutoRedirect = $false
    $response = $null
    try {
        try {
            $response = $request.GetResponse()
        }
        catch [System.Net.WebException] {
            if ($null -eq $_.Exception.Response) { throw }
            $response = $_.Exception.Response
        }

        $bodyReader = [System.IO.StreamReader]::new($response.GetResponseStream(), [System.Text.Encoding]::UTF8)
        try {
            $body = $bodyReader.ReadToEnd()
        }
        finally {
            $bodyReader.Dispose()
        }

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

$stream = $null
$reader = $null
try {
    $stream = $Client.GetStream()
    $reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::ASCII, $false, 8192, $true)
    $requestLine = $reader.ReadLine()
    if ([string]::IsNullOrWhiteSpace($requestLine)) { return }

    while ($true) {
        $headerLine = $reader.ReadLine()
        if ($null -eq $headerLine -or $headerLine.Length -eq 0) { break }
    }

    $parts = $requestLine.Split(' ')
    if ($parts.Length -lt 2) {
        Send-HttpResponse -Stream $stream -StatusCode 400 -StatusText 'Bad Request' -ContentType 'text/plain; charset=utf-8' -Body 'Malformed Butler core request.'
        return
    }

    if ($parts[0] -ne 'GET') {
        Send-HttpResponse -Stream $stream -StatusCode 405 -StatusText 'Method Not Allowed' -ContentType 'text/plain; charset=utf-8' -Body 'GET only'
        return
    }

    $requestTarget = $parts[1]
    if ($requestTarget.Length -gt 16384) {
        Send-HttpResponse -Stream $stream -StatusCode 414 -StatusText 'URI Too Long' -ContentType 'text/plain; charset=utf-8' -Body 'Butler core request is too large.'
        return
    }

    $path = $requestTarget.Split('?')[0]
    if ($path -eq '/refresh') {
        Send-HttpResponse -Stream $stream -StatusCode 404 -StatusText 'Not Found' -ContentType 'text/plain; charset=utf-8' -Body 'Not found'
        return
    }

    if ($path -eq '/health') {
        Send-HttpResponse -Stream $stream -StatusCode 200 -StatusText 'OK' -ContentType 'application/json; charset=utf-8' -Body '{"status":"ok","service":"butler-app-shell-core-pool","workers":6,"bind":"127.0.0.1"}'
        return
    }

    try {
        $proxied = Invoke-PreservedCoreGet -RequestTarget $requestTarget
        Send-HttpResponse -Stream $stream -StatusCode $proxied.StatusCode -StatusText $proxied.StatusText -ContentType $proxied.ContentType -Body $proxied.Body
    }
    catch {
        $message = [System.Net.WebUtility]::HtmlEncode($_.Exception.Message)
        $errorHtml = "<!doctype html><html><body><h1>Butler inner core unavailable</h1><pre>$message</pre><p>No Butler or Sleeper write was executed.</p></body></html>"
        Send-HttpResponse -Stream $stream -StatusCode 502 -StatusText 'Bad Gateway' -ContentType 'text/html; charset=utf-8' -Body $errorHtml
    }
}
finally {
    if ($null -ne $reader) {
        try { $reader.Dispose() } catch {}
    }
    try { $Client.Close() } catch {}
}