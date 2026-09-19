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

$bf856RouteTimingEnabled = ([string]$env:BUTLER_APP_BF856_ROUTE_TIMING -ceq '1')
$bf856WorkerStartedTicks = if ($bf856RouteTimingEnabled) {
    [System.Diagnostics.Stopwatch]::GetTimestamp()
} else {
    [long]0
}

function Get-Bf856ElapsedMs {
    param([Parameter(Mandatory = $true)][long]$StartedTicks)

    $elapsedTicks = [System.Diagnostics.Stopwatch]::GetTimestamp() - $StartedTicks
    return ([double]$elapsedTicks * 1000.0) / [double][System.Diagnostics.Stopwatch]::Frequency
}

function New-Bf856RouteTiming {
    param([Parameter(Mandatory = $true)][string]$RequestTarget)

    if (-not $bf856RouteTimingEnabled -or $RequestTarget -cne '/') { return $null }
    return @{
        cache_hit = 0.0
        mutex_wait_ms = 0.0
        semaphore_wait_ms = 0.0
        core_proxy_ms = 0.0
        singleflight_total_ms = 0.0
        navigation_ms = 0.0
        presentation_ms = 0.0
        server_before_write_ms = 0.0
        _request_started_ticks = [long]$bf856WorkerStartedTicks
    }
}

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

function Get-ExpensiveReadSingleFlightKey {
    param([Parameter(Mandatory = $true)][string]$RequestTarget)

    switch -CaseSensitive ($RequestTarget) {
        '/' { return 'ROOT' }
        '/waivers' { return 'WAIVERS' }
        '/league' { return 'LEAGUE' }
        '/matchup' { return 'MATCHUP' }
        default { return $null }
    }
}

function Invoke-ExpensiveReadSingleFlightGet {
    param(
        [Parameter(Mandatory = $true)][int]$Port,
        [Parameter(Mandatory = $true)][string]$RequestTarget,
        [Parameter(Mandatory = $true)][string]$League
    )

    $routeKey = Get-ExpensiveReadSingleFlightKey -RequestTarget $RequestTarget
    if ([string]::IsNullOrWhiteSpace($routeKey)) {
        return Invoke-AppCoreGet -Port $Port -RequestTarget $RequestTarget
    }

    $bf856Timing = New-Bf856RouteTiming -RequestTarget $RequestTarget
    $bf856SingleFlightStarted = if ($null -ne $bf856Timing) {
        [System.Diagnostics.Stopwatch]::GetTimestamp()
    } else {
        [long]0
    }

    $mutex = [System.Threading.Mutex]::new($false, ("Local\Butler.Expensive.Read.{0}.{1}" -f $PID, $routeKey))
    $lockTaken = $false
    try {
        $bf856MutexStarted = if ($null -ne $bf856Timing) {
            [System.Diagnostics.Stopwatch]::GetTimestamp()
        } else {
            [long]0
        }
        try {
            $lockTaken = $mutex.WaitOne(180000)
        }
        catch [System.Threading.AbandonedMutexException] {
            $lockTaken = $true
        }
        if ($null -ne $bf856Timing) {
            $bf856Timing.mutex_wait_ms = Get-Bf856ElapsedMs -StartedTicks $bf856MutexStarted
        }
        if (-not $lockTaken) {
            throw ("BF-693 BLOCKED: finite wait for shared {0} read expired." -f $routeKey)
        }

        $cacheKey = "Butler.Expensive.SingleFlight.$PID.$League.$routeKey"
        $cached = [System.AppDomain]::CurrentDomain.GetData($cacheKey)
        $nowTicks = [DateTime]::UtcNow.Ticks
        if ($null -ne $cached -and [long]$cached.ExpiresUtcTicks -gt $nowTicks) {
            if ($null -ne $bf856Timing) {
                $bf856Timing.cache_hit = 1.0
                $bf856Timing.singleflight_total_ms = Get-Bf856ElapsedMs -StartedTicks $bf856SingleFlightStarted
            }
            return [pscustomobject]@{
                StatusCode = [int]$cached.StatusCode
                StatusText = [string]$cached.StatusText
                ContentType = [string]$cached.ContentType
                Body = [string]$cached.Body
                Bf856Timing = $bf856Timing
            }
        }

        $companionSemaphore = [System.Threading.Semaphore]::new(
            2,
            2,
            ("Local\Butler.Companion.Heavy.{0}" -f $PID))
        $companionSlotTaken = $false
        try {
            $bf856SemaphoreStarted = if ($null -ne $bf856Timing) {
                [System.Diagnostics.Stopwatch]::GetTimestamp()
            } else {
                [long]0
            }
            $companionSlotTaken = $companionSemaphore.WaitOne(180000)
            if ($null -ne $bf856Timing) {
                $bf856Timing.semaphore_wait_ms = Get-Bf856ElapsedMs -StartedTicks $bf856SemaphoreStarted
            }
            if (-not $companionSlotTaken) {
                throw 'BF-694 BLOCKED: finite wait for companion heavy read capacity expired.'
            }

            $bf856CoreStarted = if ($null -ne $bf856Timing) {
                [System.Diagnostics.Stopwatch]::GetTimestamp()
            } else {
                [long]0
            }
            $proxied = Invoke-AppCoreGet -Port $Port -RequestTarget $RequestTarget
            if ($null -ne $bf856Timing) {
                $bf856Timing.core_proxy_ms = Get-Bf856ElapsedMs -StartedTicks $bf856CoreStarted
            }
        }
        finally {
            if ($companionSlotTaken) {
                try { [void]$companionSemaphore.Release() } catch {}
            }
            $companionSemaphore.Dispose()
        }

        if ([int]$proxied.StatusCode -eq 200) {
            [System.AppDomain]::CurrentDomain.SetData($cacheKey, @{
                ExpiresUtcTicks = [DateTime]::UtcNow.AddSeconds(5).Ticks
                StatusCode = [int]$proxied.StatusCode
                StatusText = [string]$proxied.StatusText
                ContentType = [string]$proxied.ContentType
                Body = [string]$proxied.Body
            })
        }
        if ($null -ne $bf856Timing) {
            $bf856Timing.singleflight_total_ms = Get-Bf856ElapsedMs -StartedTicks $bf856SingleFlightStarted
            $proxied | Add-Member -NotePropertyName Bf856Timing -NotePropertyValue $bf856Timing -Force
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
        [Parameter(Mandatory = $true)][string]$Body,

        [hashtable]$DiagnosticTimings
    )

    $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($Body)
    $bf856Header = ''
    if ($bf856RouteTimingEnabled -and $null -ne $DiagnosticTimings) {
        if ($DiagnosticTimings.ContainsKey('_request_started_ticks')) {
            $DiagnosticTimings.server_before_write_ms =
                Get-Bf856ElapsedMs -StartedTicks ([long]$DiagnosticTimings._request_started_ticks)
        }
        $bf856Pairs = New-Object System.Collections.Generic.List[string]
        foreach ($bf856Key in @(
            'cache_hit',
            'mutex_wait_ms',
            'semaphore_wait_ms',
            'core_proxy_ms',
            'singleflight_total_ms',
            'navigation_ms',
            'presentation_ms',
            'server_before_write_ms'
        )) {
            $bf856Value = if ($DiagnosticTimings.ContainsKey($bf856Key)) {
                [double]$DiagnosticTimings[$bf856Key]
            } else {
                0.0
            }
            $bf856Pairs.Add(
                $bf856Key + '=' +
                [string]::Format([Globalization.CultureInfo]::InvariantCulture, '{0:0.0}', $bf856Value))
        }
        $bf856Header = 'X-Butler-BF856-Timing: ' + ($bf856Pairs -join ';') + "`r`n"
    }
    $headers = "HTTP/1.1 $StatusCode $StatusText`r`n" +
        "Content-Type: $ContentType`r`n" +
        "Content-Length: $($bodyBytes.Length)`r`n" +
        "Cache-Control: no-store`r`n" +
        "X-Content-Type-Options: nosniff`r`n" +
        "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'`r`n" +
        $bf856Header +
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

    if ($parts[0] -eq 'GET') {
        if ($path -eq '/health') {
            Send-HttpResponse -Stream $stream -StatusCode 200 -StatusText 'OK' -ContentType 'application/json; charset=utf-8' -Body '{"status":"ok","service":"butler-app-shell","core":"ready","tradeLab":"ready","history":"ready","decisionDetail":"ready","decisionRefresh":"manual-post-ready","bind":"127.0.0.1"}'
            return
        }
    }

    $requestParserOverride = (Get-Item Function:\ConvertFrom-TradeRequestTarget).ScriptBlock
    $selectionSetOverride = (Get-Item Function:\Get-TradeSelectionSet).ScriptBlock
    . $TradeHost
    . $TradeLab
    . $History
    . $Detail
    . $DecisionRefresh
    Set-Item -Path Function:\ConvertFrom-TradeRequestTarget -Value $requestParserOverride
    Set-Item -Path Function:\Get-TradeSelectionSet -Value $selectionSetOverride

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
            $errorHtml = "<!doctype html><html><body><h1>Butler Trade Analyzer blocked</h1><pre>$(ConvertTo-HtmlText $_.Exception.Message)</pre><p>No Butler or Sleeper write was executed.</p><p><a href=`"/trade`">Return to Trade Analyzer</a></p></body></html>"
            Send-HttpResponse -Stream $stream -StatusCode 400 -StatusText 'Bad Request' -ContentType 'text/html; charset=utf-8' -Body $errorHtml
        }
        return
    }

    try {
        $proxied = if ($requestTarget -ceq '/team') {
            Invoke-TeamSingleFlightGet -Port $InnerPort -RequestTarget $requestTarget -League $LeagueId
        }
        elseif ($requestTarget -ceq '/' -or $requestTarget -ceq '/waivers' -or $requestTarget -ceq '/league' -or $requestTarget -ceq '/matchup') {
            Invoke-ExpensiveReadSingleFlightGet -Port $InnerPort -RequestTarget $requestTarget -League $LeagueId
        }
        else {
            Invoke-AppCoreGet -Port $InnerPort -RequestTarget $requestTarget
        }
        $body = $proxied.Body
        $bf856Timings = $null
        if ($bf856RouteTimingEnabled -and
            $requestTarget -ceq '/' -and
            $null -ne $proxied.PSObject.Properties['Bf856Timing']) {
            $bf856Timings = $proxied.Bf856Timing
        }

        $bf856NavigationStarted = if ($null -ne $bf856Timings) {
            [System.Diagnostics.Stopwatch]::GetTimestamp()
        } else {
            [long]0
        }
        if ($proxied.ContentType -match '^text/html' -and $body -match '<nav class="nav" aria-label="Butler sections">') {
            $body = Add-AppNavigation -Html $body
            $body = Add-DecisionRefreshControl -Html $body -RequestTarget $requestTarget
        }
        if ($null -ne $bf856Timings) {
            $bf856Timings.navigation_ms = Get-Bf856ElapsedMs -StartedTicks $bf856NavigationStarted
        }

        Send-HttpResponse -Stream $stream -StatusCode $proxied.StatusCode -StatusText $proxied.StatusText -ContentType $proxied.ContentType -Body $body -DiagnosticTimings $bf856Timings
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
