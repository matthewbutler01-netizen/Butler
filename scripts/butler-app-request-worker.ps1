param(
    [Parameter(Mandatory = $true)]
    [System.Net.Sockets.TcpClient]$Client,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$LeagueId,

    [Parameter(Mandatory = $true)]
    [int]$InnerPort,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$TradeHost,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$TradeLab,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$History,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$Detail,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DecisionRefresh,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DecisionRefreshRunner,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$RepoRoot,

    [Parameter(Mandatory = $true)]
    [hashtable]$RefreshState
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. $TradeHost
. $TradeLab
. $History
. $Detail
. $DecisionRefresh

# Keep the Windows PowerShell 5.1 request parser override used by the shell.
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

function Get-TradeSelectionSet {
    param([object[]]$Values)
    $set = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    foreach ($value in @($Values)) { [void]$set.Add([string]$value) }
    Write-Output -NoEnumerate $set
}

function Invoke-AppCoreGet {
    param(
        [Parameter(Mandatory = $true)][int]$Port,
        [Parameter(Mandatory = $true)][string]$RequestTarget
    )

    $request = [System.Net.HttpWebRequest]::Create("http://127.0.0.1:$Port$RequestTarget")
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
        $bodyReader = [System.IO.StreamReader]::new($response.GetResponseStream(), [System.Text.Encoding]::UTF8)
        try { $body = $bodyReader.ReadToEnd() } finally { $bodyReader.Dispose() }
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

function Invoke-TeamSingleFlightGet {
    param(
        [Parameter(Mandatory = $true)][int]$Port,
        [Parameter(Mandatory = $true)][string]$RequestTarget,
        [Parameter(Mandatory = $true)][string]$League
    )

    if ($RequestTarget -cne '/team') {
        return Invoke-AppCoreGet -Port $Port -RequestTarget $RequestTarget
    }

    $mutex = [System.Threading.Mutex]::new($false, ("Local\Butler.Team.Read.{0}" -f $PID))
    $lockTaken = $false
    try {
        try {
            $lockTaken = $mutex.WaitOne(180000)
        }
        catch [System.Threading.AbandonedMutexException] {
            $lockTaken = $true
        }
        if (-not $lockTaken) {
            throw 'BF-691 BLOCKED: finite wait for the shared My Team read expired.'
        }

        $cacheKey = "Butler.Team.SingleFlight.$PID.$League"
        $cached = [System.AppDomain]::CurrentDomain.GetData($cacheKey)
        $nowTicks = [DateTime]::UtcNow.Ticks
        if ($null -ne $cached -and [long]$cached.ExpiresUtcTicks -gt $nowTicks) {
            return [pscustomobject]@{
                StatusCode = [int]$cached.StatusCode
                StatusText = [string]$cached.StatusText
                ContentType = [string]$cached.ContentType
                Body = [string]$cached.Body
            }
        }

        $proxied = Invoke-AppCoreGet -Port $Port -RequestTarget $RequestTarget
        if ([int]$proxied.StatusCode -eq 200) {
            [System.AppDomain]::CurrentDomain.SetData($cacheKey, @{
                ExpiresUtcTicks = [DateTime]::UtcNow.AddSeconds(5).Ticks
                StatusCode = [int]$proxied.StatusCode
                StatusText = [string]$proxied.StatusText
                ContentType = [string]$proxied.ContentType
                Body = [string]$proxied.Body
            })
        }
        return $proxied
    }
    finally {
        if ($lockTaken) {
            try { $mutex.ReleaseMutex() } catch {}
        }
        $mutex.Dispose()
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

function Get-RefreshTokenSnapshot {
    param([Parameter(Mandatory = $true)][hashtable]$State)

    $lockTaken = $false
    try {
        [System.Threading.Monitor]::Enter($State.SyncRoot)
        $lockTaken = $true
        return [string]$State.Token
    }
    finally {
        if ($lockTaken) { [System.Threading.Monitor]::Exit($State.SyncRoot) }
    }
}

function Consume-RefreshToken {
    param(
        [Parameter(Mandatory = $true)][hashtable]$State,
        [Parameter(Mandatory = $true)][string]$SubmittedToken
    )

    $lockTaken = $false
    try {
        [System.Threading.Monitor]::Enter($State.SyncRoot)
        $lockTaken = $true
        if ([string]::IsNullOrWhiteSpace($SubmittedToken) -or $SubmittedToken -cne [string]$State.Token) {
            throw 'BF-675 BLOCKED: refresh one-use token is missing, expired, replayed, or invalid.'
        }

        # Invalidate atomically before any Butler write. A concurrent replay sees
        # the replacement token and cannot execute a second refresh.
        $State.Token = New-DecisionRefreshToken
    }
    finally {
        if ($lockTaken) { [System.Threading.Monitor]::Exit($State.SyncRoot) }
    }
}

$stream = $null
$reader = $null
Push-Location $RepoRoot
try {
    $stream = $Client.GetStream()
    $reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::ASCII, $false, 8192, $true)
    $requestLine = $reader.ReadLine()
    if ([string]::IsNullOrWhiteSpace($requestLine)) { return }

    $requestHeaders = @{}
    while ($true) {
        $headerLine = $reader.ReadLine()
        if ($null -eq $headerLine -or $headerLine.Length -eq 0) { break }
        $colon = $headerLine.IndexOf(':')
        if ($colon -gt 0) {
            $headerName = $headerLine.Substring(0, $colon).Trim()
            $headerValue = $headerLine.Substring($colon + 1).Trim()
            if ($requestHeaders.ContainsKey($headerName)) {
                $requestHeaders[$headerName] = ([string]$requestHeaders[$headerName]) + ',' + $headerValue
            }
            else {
                $requestHeaders[$headerName] = $headerValue
            }
        }
    }

    $parts = $requestLine.Split(' ')
    if ($parts.Length -lt 2) {
        Send-HttpResponse -Stream $stream -StatusCode 400 -StatusText 'Bad Request' -ContentType 'text/plain; charset=utf-8' -Body 'Malformed Butler request.'
        return
    }

    $requestTarget = $parts[1]
    if ($requestTarget.Length -gt 16384) {
        Send-HttpResponse -Stream $stream -StatusCode 414 -StatusText 'URI Too Long' -ContentType 'text/plain; charset=utf-8' -Body 'Butler request is too large.'
        return
    }
    $path = $requestTarget.Split('?')[0]

    if ($parts[0] -eq 'POST') {
        if ($requestTarget -cne '/refresh') {
            Send-HttpResponse -Stream $stream -StatusCode 405 -StatusText 'Method Not Allowed' -ContentType 'text/plain; charset=utf-8' -Body 'GET only'
            return
        }
        try {
            $formBody = Read-DecisionRefreshFormBody -Reader $reader -Headers $requestHeaders
            $submittedToken = Get-DecisionRefreshSubmittedToken -Body $formBody
            Consume-RefreshToken -State $RefreshState -SubmittedToken $submittedToken

            $resultText = Invoke-DecisionRefreshRunner -LeagueId $LeagueId -RunnerPath $DecisionRefreshRunner
            $html = Get-DecisionRefreshSuccessHtml -LeagueId $LeagueId -ResultText $resultText
            Send-HttpResponse -Stream $stream -StatusCode 200 -StatusText 'OK' -ContentType 'text/html; charset=utf-8' -Body $html
        }
        catch {
            $errorHtml = Get-DecisionRefreshFailureHtml -Message $_.Exception.Message
            Send-HttpResponse -Stream $stream -StatusCode 400 -StatusText 'Bad Request' -ContentType 'text/html; charset=utf-8' -Body $errorHtml
        }
        return
    }

    if ($parts[0] -ne 'GET') {
        Send-HttpResponse -Stream $stream -StatusCode 405 -StatusText 'Method Not Allowed' -ContentType 'text/plain; charset=utf-8' -Body 'GET only'
        return
    }

    if ($path -eq '/health') {
        Send-HttpResponse -Stream $stream -StatusCode 200 -StatusText 'OK' -ContentType 'application/json; charset=utf-8' -Body '{"status":"ok","service":"butler-app-shell","core":"ready","tradeLab":"ready","history":"ready","decisionDetail":"ready","decisionRefresh":"manual-post-ready","bind":"127.0.0.1"}'
        return
    }

    if ($path -eq '/refresh') {
        if ($requestTarget -cne '/refresh') {
            Send-HttpResponse -Stream $stream -StatusCode 400 -StatusText 'Bad Request' -ContentType 'text/plain; charset=utf-8' -Body 'BF-675 refresh confirmation accepts no query parameters.'
            return
        }
        try {
            $token = Get-RefreshTokenSnapshot -State $RefreshState
            $html = Get-DecisionRefreshConfirmationHtml -LeagueId $LeagueId -Token $token
            Send-HttpResponse -Stream $stream -StatusCode 200 -StatusText 'OK' -ContentType 'text/html; charset=utf-8' -Body $html
        }
        catch {
            $errorHtml = Get-DecisionRefreshFailureHtml -Message $_.Exception.Message
            Send-HttpResponse -Stream $stream -StatusCode 400 -StatusText 'Bad Request' -ContentType 'text/html; charset=utf-8' -Body $errorHtml
        }
        return
    }

    if ($path -eq '/history') {
        try {
            $html = if ($requestTarget -ceq '/history') {
                Get-DecisionHistoryLoadingHtml -LeagueId $LeagueId
            }
            else {
                Invoke-DecisionHistoryHtml -LeagueId $LeagueId -RequestTarget $requestTarget
            }
            Send-HttpResponse -Stream $stream -StatusCode 200 -StatusText 'OK' -ContentType 'text/html; charset=utf-8' -Body $html
        }
        catch {
            $errorHtml = "<!doctype html><html><body><h1>Butler Decision History blocked</h1><pre>$(ConvertTo-HtmlText $_.Exception.Message)</pre><p>No Butler or Sleeper write was executed.</p><p><a href=`"/history`">Return to Decision History</a></p></body></html>"
            Send-HttpResponse -Stream $stream -StatusCode 400 -StatusText 'Bad Request' -ContentType 'text/html; charset=utf-8' -Body $errorHtml
        }
        return
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
        return
    }

    try {
        $proxied = if ($requestTarget -ceq '/team') {
            Invoke-TeamSingleFlightGet -Port $InnerPort -RequestTarget $requestTarget -League $LeagueId
        }
        else {
            Invoke-AppCoreGet -Port $InnerPort -RequestTarget $requestTarget
        }
        $body = $proxied.Body
        if ($proxied.ContentType -match '^text/html' -and $body -match '<nav class="nav" aria-label="Butler sections">') {
            $body = Add-AppNavigation -Html $body
            $body = Add-DecisionRefreshControl -Html $body -RequestTarget $requestTarget
        }
        Send-HttpResponse -Stream $stream -StatusCode $proxied.StatusCode -StatusText $proxied.StatusText -ContentType $proxied.ContentType -Body $body
    }
    catch {
        $errorHtml = "<!doctype html><html><body><h1>Butler app blocked</h1><pre>$(ConvertTo-HtmlText $_.Exception.Message)</pre><p>No Butler or Sleeper write was executed.</p></body></html>"
        Send-HttpResponse -Stream $stream -StatusCode 500 -StatusText 'Internal Server Error' -ContentType 'text/html; charset=utf-8' -Body $errorHtml
    }
}
finally {
    if ($null -ne $reader) {
        try { $reader.Dispose() } catch {}
    }
    try { $Client.Close() } catch {}
    Pop-Location
}
