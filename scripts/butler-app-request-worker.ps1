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
$bf857CoreTimingEnabled = ([string]$env:BUTLER_APP_BF857_CORE_TIMING -ceq '1')
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
        [Parameter(Mandatory = $true)][string]$RequestTarget,
        [ValidateRange(1000, 180000)][int]$TimeoutMs = 180000
    )

    $request = [System.Net.HttpWebRequest]::Create("http://127.0.0.1:$Port$RequestTarget")
    $request.Method = 'GET'
    $request.Timeout = $TimeoutMs
    $request.ReadWriteTimeout = $TimeoutMs
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
        $bf857Timing = if ($bf857CoreTimingEnabled -and $RequestTarget -ceq '/') {
            [string]$response.Headers['X-Butler-BF857-Timing']
        } else {
            $null
        }
        return [pscustomobject]@{
            StatusCode = [int]$response.StatusCode
            StatusText = [string]$response.StatusDescription
            ContentType = if ([string]::IsNullOrWhiteSpace($response.ContentType)) { 'text/plain; charset=utf-8' } else { [string]$response.ContentType }
            Body = $body
            Bf857Timing = $bf857Timing
        }
    }
    finally {
        if ($null -ne $response) { $response.Close() }
    }
}

function Test-StartSitRosterDriftResponse {
    param(
        [Parameter(Mandatory = $true)][string]$RequestTarget,
        [AllowNull()][string]$Body,
        [int]$StatusCode = 500
    )

    # Only exact My Team and Start/Sit roster-drift messages authorize the
    # already-governed BF-723 local evidence repair. Other routes stay read-only.
    if ($StatusCode -ne 500 -or @('/matchup/autofill', '/team') -cnotcontains $RequestTarget -or
        [string]::IsNullOrWhiteSpace($Body)) {
        return $false
    }

    $markers=[regex]::Matches($Body,'data-butler-roster-recovery=')
    if ($markers.Count -gt 0) {
        return $markers.Count -eq 1 -and [regex]::Matches($Body,'<div data-butler-roster-recovery="bf610-exact-drift-v1" role="status">').Count -eq 1
    }
    return $Body.IndexOf(
        'current roster membership drifted',
        [System.StringComparison]::OrdinalIgnoreCase) -ge 0 -and
        $Body.IndexOf(
            'downstream live evidence before target-roster review',
            [System.StringComparison]::OrdinalIgnoreCase) -ge 0
}

# BF-1060: only a *rendered, source-verified* mismatched week on the
# exact weekly manager routes may attempt governed Butler-local repair.
# Missing/duplicate/unknown week proof and other routes are read-only.
function Test-AutomaticWeekRecoveryCandidate {
    param(
        [Parameter(Mandatory = $true)][string]$RequestTarget,
        [AllowNull()][string]$Body,
        [int]$StatusCode = 200
    )
    if (@('/matchup', '/matchup/autofill') -cnotcontains $RequestTarget -or
        $StatusCode -ne 200 -or [string]::IsNullOrWhiteSpace($Body)) {
        return $false
    }
    $markers = [regex]::Matches($Body, 'data-butler-week-state=')
    $mismatch = [regex]::Matches($Body,
        '(?s)<section class="panel butler-live-week-status" role="status" data-butler-week-state="MISMATCH"><strong>SAVED MATCHUP OUTDATED</strong>')
    if ($markers.Count -ne 1 -or $mismatch.Count -ne 1) { return $false }
    return [regex]::IsMatch($Body, '(?:Saved matchup not usable|Start/Sit review held)') -and
        $Body.IndexOf('DO NOT ACT', [StringComparison]::Ordinal) -ge 0
}

# Atomically acquire the SAME evidence-writer claim used by BF-723 and
# token-gated POST /refresh. A five-minute bounded cooldown stops repeated
# provider/DB work when a league is offline or provider weeks still conflict.
function Claim-AutomaticWeekRecovery {
    param([Parameter(Mandatory = $true)][hashtable]$State)
    $lockTaken = $false
    try {
        [Threading.Monitor]::Enter($State.SyncRoot)
        $lockTaken = $true
        $now = [DateTime]::UtcNow.Ticks
        if (($State.ContainsKey('InProgress') -and [bool]$State.InProgress) -or
            ($State.ContainsKey('AutoWeekRetryAfterUtcTicks') -and
             [long]$State.AutoWeekRetryAfterUtcTicks -gt $now)) {
            return $false
        }
        $State.AutoWeekRetryAfterUtcTicks = $now + [TimeSpan]::FromMinutes(5).Ticks
        $State.InProgress = $true
        return $true
    }
    finally {
        if ($lockTaken) { [Threading.Monitor]::Exit($State.SyncRoot) }
    }
}

function Invoke-AutomaticWeekRecovery {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$League
    )
    $scriptPath = Join-Path $Root 'scripts\butler-current-week-onopen-recovery.ps1'
    if (-not (Test-Path -LiteralPath $scriptPath -PathType Leaf)) {
        throw 'BF-1060 BLOCKED: governed week recovery runner is missing.'
    }
    $powershell = Join-Path $PSHOME 'powershell.exe'
    if (-not (Test-Path -LiteralPath $powershell -PathType Leaf)) {
        $powershell = 'powershell.exe'
    }
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $lines = & $powershell '-NoProfile' '-ExecutionPolicy' 'Bypass' '-File' $scriptPath '-LeagueId' $League 2>&1
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }
    $output = (($lines | ForEach-Object { "$_" }) -join "`n")
    if ($exitCode -ne 0 -or
        $output.IndexOf('BF-1060 WEEK RECOVERY: COMPLETE', [StringComparison]::Ordinal) -lt 0) {
        throw 'BF-1060 BLOCKED: current-week repair not verified.'
    }
}

# BF-1061: Auto-Pilot performs the same exact, governed week recovery as
# Matchup on page-open. Never infer an actionable watch from cached pre-write
# Dashboard cards: after a completed recovery read BOTH pages from core again
# and re-run their independent technical/sleeper-week proof gates.
function Resolve-V04AutoPilotOnOpenWeek {
    param(
        [Parameter(Mandatory = $true)]$InitialWatchState,
        [AllowEmptyString()][string]$DashboardHtml = '',
        [AllowEmptyString()][string]$MatchupHtml = '',
        [Parameter(Mandatory = $true)][int]$InnerPort,
        [Parameter(Mandatory = $true)][string]$League,
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][hashtable]$RefreshState
    )

    $verifiedDashboard = $DashboardHtml
    $verifiedMatchup = $MatchupHtml
    $watch = $InitialWatchState
    if (Test-AutomaticWeekRecoveryCandidate -RequestTarget '/matchup' -Body $MatchupHtml) {
        if (Claim-AutomaticWeekRecovery -State $RefreshState) {
            try {
                try {
                    Invoke-AutomaticWeekRecovery -Root $Root -League $League
                    # A completed local evidence write invalidates every
                    # warmed manager route. Recompute from the raw core, not
                    # an old single-flight Dashboard or an old week badge.
                    $updatedDashboard = Invoke-AppCoreGet -Port $InnerPort -RequestTarget '/' -TimeoutMs 12000
                    $updatedMatchup = Invoke-AppCoreGet -Port $InnerPort -RequestTarget '/matchup' -TimeoutMs 12000
                    if ([int]$updatedDashboard.StatusCode -eq 200 -and
                        $updatedDashboard.ContentType -match '^text/html' -and
                        [int]$updatedMatchup.StatusCode -eq 200 -and
                        $updatedMatchup.ContentType -match '^text/html') {
                        $verifiedDashboard = [string]$updatedDashboard.Body
                        $verifiedMatchup = [string]$updatedMatchup.Body
                        $watch = Get-V04AutoPilotWatchState -DashboardHtml $verifiedDashboard
                    }
                    else {
                        # If either independent source cannot be read, do
                        # not reuse a pre-write READY Dashboard snapshot.
                        $verifiedDashboard = ''
                        $verifiedMatchup = ''
                        $watch = Limit-V04AutoPilotToVerifiedWeek -WatchState $InitialWatchState -MatchupHtml ''
                    }
                }
                catch {
                    # An attempted or partial evidence write is not proof of
                    # completion; withhold actions until both reads verify.
                    $verifiedDashboard = ''
                    $verifiedMatchup = ''
                    $watch = Limit-V04AutoPilotToVerifiedWeek -WatchState $InitialWatchState -MatchupHtml ''
                }
            }
            finally {
                Complete-DecisionRefreshAttempt -State $RefreshState
            }
        }
    }

    if ([bool]$watch.Ready) {
        $watch = Limit-V04AutoPilotToVerifiedWeek -WatchState $watch -MatchupHtml $verifiedMatchup
    }
    return [pscustomobject]@{
        WatchState = $watch
        DashboardHtml = $verifiedDashboard
        MatchupHtml = $verifiedMatchup
    }
}

function Save-LocalRosterRecoveryDiagnostic {
    param(
        [ValidateSet('COMPLETE','FAILED')][string]$Outcome,
        [AllowEmptyString()][string]$Text,
        [ValidateRange(0,[long]::MaxValue)][long]$ElapsedMilliseconds
    )
    # Best-effort, process-local file. Never persist raw CLI output or errors.
    try {
        $base = [string]$env:LOCALAPPDATA
        if ([string]::IsNullOrWhiteSpace($base) -or -not [IO.Path]::IsPathRooted($base)) { return }
        $directory = Join-Path $base 'Butler\diagnostics'
        [void][IO.Directory]::CreateDirectory($directory)
        $rows = [Collections.Generic.List[string]]::new()
        $rows.Add('BF-723 LOCAL RECOVERY DIAGNOSTIC')
        $rows.Add('Outcome: ' + $Outcome)
        $rows.Add('Elapsed ms: ' + $ElapsedMilliseconds)
        $rows.Add('UTC: ' + [DateTimeOffset]::UtcNow.ToString('o'))
        $pattern = '(?m)^BF-723 STAGE\|BF-(?:840|602|603|605|606|607|612|610|615/BF-617)\|(?:PASS|FAIL)\|[0-9]{1,12}\r?$'
        foreach ($match in @([regex]::Matches($Text, $pattern) | Select-Object -First 16)) {
            $rows.Add($match.Value.TrimEnd("`r"))
        }
        $rows.Add('Stage markers are diagnostic only; no source freshness or action approval is implied.')
        $path = Join-Path $directory ("v04-roster-recovery-{0}.txt" -f $PID)
        [IO.File]::WriteAllText($path, ($rows -join [Environment]::NewLine), [Text.UTF8Encoding]::new($false))
    }
    catch { } # Diagnostic storage must never change the route/recovery outcome.
}

function Invoke-StartSitRosterDriftAutoRecovery {
    param([Parameter(Mandatory = $true)][string]$Root)

    $scriptPath = Join-Path $Root 'scripts\butler-recover-roster-drift.ps1'
    if (-not (Test-Path -LiteralPath $scriptPath -PathType Leaf)) {
        throw "BF-1037 BLOCKED: roster-drift recovery script missing at $scriptPath"
    }

    $powershell = Join-Path $PSHOME 'powershell.exe'
    if (-not (Test-Path -LiteralPath $powershell -PathType Leaf)) {
        $powershell = 'powershell.exe'
    }

    $mutex = [System.Threading.Mutex]::new($false, ("Local\Butler.StartSit.RosterRecovery.{0}" -f $PID))
    $lockTaken = $false
    $recoveryTimer = [Diagnostics.Stopwatch]::StartNew()
    $recoveryOutcome = 'FAILED'
    $text = ''
    try {
        try {
            $lockTaken = $mutex.WaitOne(180000)
        }
        catch [System.Threading.AbandonedMutexException] {
            $lockTaken = $true
        }
        if (-not $lockTaken) {
            throw 'BF-1037 BLOCKED: finite wait for automatic roster recovery expired.'
        }

        $arguments = @(
            '-NoProfile',
            '-ExecutionPolicy', 'Bypass',
            '-File', $scriptPath,
            '-AppAutoRecovery'
        )
        if (-not [string]::IsNullOrWhiteSpace([string]$env:JAVA_HOME)) {
            $arguments += @('-JavaHome', [string]$env:JAVA_HOME)
        }

        $previousPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            $lines = & $powershell @arguments 2>&1
            $exitCode = $LASTEXITCODE
        }
        finally {
            $ErrorActionPreference = $previousPreference
        }

        $text = (($lines | ForEach-Object { "$_" }) -join "`n")
        if ($exitCode -ne 0 -or
            $text.IndexOf('BF-723 APP AUTO RECOVERY: COMPLETE', [System.StringComparison]::Ordinal) -lt 0) {
            $tail = [regex]::Replace([string]$text, '\s+', ' ').Trim()
            if ($tail.Length -gt 1800) { $tail = '...' + $tail.Substring($tail.Length - 1800) }
            throw "BF-1037 BLOCKED: automatic roster recovery failed; output=$tail"
        }
        $recoveryOutcome = 'COMPLETE'
    }
    finally {
        Save-LocalRosterRecoveryDiagnostic -Outcome $recoveryOutcome -Text $text -ElapsedMilliseconds $recoveryTimer.ElapsedMilliseconds
        if ($lockTaken) {
            try { $mutex.ReleaseMutex() } catch {}
        }
        $mutex.Dispose()
    }
}

function Invoke-TeamSingleFlightGet {
    param(
        [Parameter(Mandatory = $true)][int]$Port,
        [Parameter(Mandatory = $true)][string]$RequestTarget,
        [Parameter(Mandatory = $true)][string]$League,
        [Parameter(Mandatory = $true)][hashtable]$RefreshState
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
        $evidenceGeneration = Get-EvidenceRefreshGeneration -State $RefreshState
        if ($null -ne $cached -and [long]$cached.ExpiresUtcTicks -gt $nowTicks -and
            $cached.ContainsKey('EvidenceGeneration') -and
            [long]$cached.EvidenceGeneration -eq $evidenceGeneration -and
            -not (Test-EvidenceRefreshInProgress -State $RefreshState)) {
            return [pscustomobject]@{
                StatusCode = [int]$cached.StatusCode
                StatusText = [string]$cached.StatusText
                ContentType = [string]$cached.ContentType
                Body = [string]$cached.Body
            }
        }

        $proxied = Invoke-AppCoreGet -Port $Port -RequestTarget $RequestTarget
        # If a refresh finished while the core rendered this response, do not
        # populate a new cache entry with the pre-refresh generation.
        if ([int]$proxied.StatusCode -eq 200 -and
            $evidenceGeneration -eq (Get-EvidenceRefreshGeneration -State $RefreshState) -and
            -not (Test-EvidenceRefreshInProgress -State $RefreshState)) {
            [System.AppDomain]::CurrentDomain.SetData($cacheKey, @{
                EvidenceGeneration = $evidenceGeneration
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
        [Parameter(Mandatory = $true)][string]$League,
        [Parameter(Mandatory = $true)][hashtable]$RefreshState,
        # BF-1140: Auto-Pilot is an optional read-only companion. A slow
        # Dashboard or Matchup must not monopolize its browser GET for the
        # default three-minute core/mutex deadlines. Other routes retain
        # historical defaults and single-flight cache semantics.
        [ValidateRange(1000, 180000)][int]$WaitTimeoutMs = 180000,
        [ValidateRange(1000, 180000)][int]$CoreTimeoutMs = 180000
    )

    $routeKey = Get-ExpensiveReadSingleFlightKey -RequestTarget $RequestTarget
    if ([string]::IsNullOrWhiteSpace($routeKey)) {
        return Invoke-AppCoreGet -Port $Port -RequestTarget $RequestTarget -TimeoutMs $CoreTimeoutMs
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
            $lockTaken = $mutex.WaitOne($WaitTimeoutMs)
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
        $evidenceGeneration = Get-EvidenceRefreshGeneration -State $RefreshState
        if ($null -ne $cached -and [long]$cached.ExpiresUtcTicks -gt $nowTicks -and
            $cached.ContainsKey('EvidenceGeneration') -and
            [long]$cached.EvidenceGeneration -eq $evidenceGeneration -and
            -not (Test-EvidenceRefreshInProgress -State $RefreshState)) {
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
            $companionSlotTaken = $companionSemaphore.WaitOne($WaitTimeoutMs)
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
            $proxied = Invoke-AppCoreGet -Port $Port -RequestTarget $RequestTarget -TimeoutMs $CoreTimeoutMs
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

        # If a refresh finished while the core rendered this response, do not
        # populate a new cache entry with the pre-refresh generation.
        if ([int]$proxied.StatusCode -eq 200 -and
            $evidenceGeneration -eq (Get-EvidenceRefreshGeneration -State $RefreshState) -and
            -not (Test-EvidenceRefreshInProgress -State $RefreshState)) {
            [System.AppDomain]::CurrentDomain.SetData($cacheKey, @{
                EvidenceGeneration = $evidenceGeneration
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

function Add-ButlerAccessibility {
    param([Parameter(Mandatory = $true)][string]$Html)

    # Apply once at the public HTML boundary, after all page-specific styling.
    if ($Html.Contains('id="butler-accessibility-style"')) { return $Html }
    $result = [regex]::Replace($Html, '(?i)<html(?![^>]*\blang\s*=)([^>]*)>', '<html lang="en"$1>')
    $nav = [regex]::Match($result, '(?is)<nav\b[^>]*aria-label="Butler sections"[^>]*>.*?</nav>')
    if (-not $nav.Success) { return $result }

    $navigation = [regex]::Replace($nav.Value, '<a\b[^>]*>', [System.Text.RegularExpressions.MatchEvaluator]{
        param($link)
        if ($link.Value -match 'class="[^"]*\bactive\b[^"]*"' -and $link.Value -notmatch '\baria-current=') {
            return $link.Value.Insert(2, ' aria-current="page"')
        }
        return $link.Value
    })

    # BF-1016: My Team already owns the original Playbook rail with its
    # page-local section jumps. Every other manager surface receives the same
    # Playbook navigation shell here at the final public HTML boundary.
    $hasTeamWorkspace = $result.IndexOf('aria-label="Team workspace"', [System.StringComparison]::OrdinalIgnoreCase) -ge 0
    if ($hasTeamWorkspace) {
        # The desktop team rail is hidden below 1200px. Keep its weekly tools
        # reachable from the compact navigation too, without duplicating links
        # supplied by a newer page renderer or changing the current-page state.
        foreach ($tool in @(
            @{ Href = '/matchup/autofill'; Label = 'Start/Sit Assistant' },
            @{ Href = '/autopilot'; Label = 'Auto-Pilot' }
        )) {
            if ($navigation -notmatch ('href="' + [regex]::Escape($tool.Href) + '"')) {
                $toolLink = '<a href="' + $tool.Href + '">' + $tool.Label + '</a>'
                $anchor = '<a href="/matchup">'
                if ($navigation.Contains($anchor)) {
                    $navigation = $navigation.Replace($anchor, $toolLink + $anchor)
                }
                else { $navigation = $navigation.Replace('</nav>', $toolLink + '</nav>') }
            }
        }
    }
    if (-not $hasTeamWorkspace) {
        $currentMatch = [regex]::Match($navigation, '(?is)<a\b[^>]*aria-current="page"[^>]*>(?<label>[^<]+)</a>')
        $currentLabel = if ($currentMatch.Success) {
            [System.Net.WebUtility]::HtmlDecode($currentMatch.Groups['label'].Value).Trim()
        }
        else { '' }

        # BF-1021: exact public route identity. /matchup remains Matchup; only
        # /matchup/autofill owns the Start/Sit Assistant current-page state.
        $publicTarget = ''
        $publicTargetVariable = Get-Variable -Name ButlerPublicRequestTarget -Scope Script -ErrorAction SilentlyContinue
        if ($null -ne $publicTargetVariable) { $publicTarget = [string]$publicTargetVariable.Value }
        if ($publicTarget -ceq '/matchup/autofill') {
            $currentLabel = 'Start/Sit Assistant'
        }

        $targetMatch = [regex]::Match($result, '(?is)<(?:div|header)\b[^>]*class="[^"]*\btarget\b[^"]*"[^>]*>(?<target>.*?)</(?:div|header)>')
        $targetText = ''
        if ($targetMatch.Success) {
            $targetText = [regex]::Replace($targetMatch.Groups['target'].Value, '<[^>]+>', ' ')
            $targetText = [System.Net.WebUtility]::HtmlDecode($targetText)
            $targetText = [regex]::Replace($targetText, '\s+', ' ').Trim()
        }

        $playbookContext = 'Manager tools'
        $rosterContext = [regex]::Match($targetText, '(?i)(?:^|[|\u00B7])\s*(?<team>[^|\u00B7]+?)\s*\|\s*roster\s+\d+\b')
        if ($rosterContext.Success) {
            $playbookContext = $rosterContext.Groups['team'].Value.Trim()
        }
        elseif (-not [string]::IsNullOrWhiteSpace($targetText) -and
                $targetText.Length -le 52 -and
                $targetText -notmatch '(?i)^Request stopped safely$|[0-9a-f]{8}-[0-9a-f]{4}-') {
            $playbookContext = $targetText
        }
        $safePlaybookContext = [System.Net.WebUtility]::HtmlEncode($playbookContext)

        $makeLink = {
            param([string]$Href, [string]$Label, [string]$ExtraClass)
            $classes = New-Object System.Collections.Generic.List[string]
            if (-not [string]::IsNullOrWhiteSpace($ExtraClass)) { $classes.Add($ExtraClass) }
            $isCurrent = $currentLabel -ceq $Label
            if ($isCurrent) { $classes.Add('active') }
            $classAttr = if ($classes.Count -gt 0) { ' class="' + ($classes -join ' ') + '"' } else { '' }
            $currentAttr = if ($isCurrent) { ' aria-current="page"' } else { '' }
            return '<a' + $classAttr + $currentAttr + ' href="' + $Href + '">' + $Label + '</a>'
        }

        $playbook = @(
            '<aside class="manager-playbook" aria-label="Butler sections">',
            '<div class="playbook-title">MY PLAYBOOK</div>',
            ('<div class="playbook-team">' + $safePlaybookContext + '</div>'),
            (& $makeLink '/' 'Dashboard' ''),
            '<div class="playbook-label">LINEUP</div>',
            (& $makeLink '/team' 'My Team' ''),
            (& $makeLink '/matchup/autofill' 'Start/Sit Assistant' 'playbook-start-sit'),
            (& $makeLink '/matchup' 'Matchup' ''),
            (& $makeLink '/autopilot' 'Auto-Pilot' 'playbook-autopilot'),
            '<div class="playbook-label">WAIVER</div>',
            (& $makeLink '/waivers' 'Waiver Board' ''),
            (& $makeLink '/players' 'Player Search' ''),
            '<div class="playbook-label">TRADE</div>',
            (& $makeLink '/trade' 'Trade Analyzer' ''),
            '<div class="playbook-label">LEAGUE</div>',
            (& $makeLink '/league' 'League' ''),
            '<div class="playbook-label">TOOLS</div>',
            (& $makeLink '/compare' 'Player Compare' ''),
            (& $makeLink '/history?load=1' 'History' ''),
            '</aside>'
        ) -join ''

        $navigation = $playbook
    }

    $result = $result.Remove($nav.Index, $nav.Length).Insert($nav.Index, $navigation)

    if (-not $hasTeamWorkspace) {
        $result = [regex]::Replace(
            $result,
            '(?i)<main\b(?<before>[^>]*\bclass=")(?<classes>[^"]*\bshell\b[^"]*)"',
            [System.Text.RegularExpressions.MatchEvaluator]{
                param($main)
                $classes = $main.Groups['classes'].Value
                if ($classes -notmatch '(?:^|\s)playbook-shell(?:\s|$)') {
                    $classes += ' playbook-shell'
                }
                return '<main' + $main.Groups['before'].Value + $classes + '"'
            },
            1
        )
    }

    # Focus the first content panel, beyond the repeated brand and navigation.
    # Retain an existing fragment id so links into that panel keep working.
    # Dashboard inserts a hidden recovery contract immediately after nav.
    # Skip that diagnostic markup and land on the first visible content section.
    $navigationElement = [regex]::Match($result, '(?is)<(?:nav|aside)\b[^>]*aria-label="Butler sections"[^>]*>.*?</(?:nav|aside)>')
    $afterNav = if ($navigationElement.Success) { $navigationElement.Index + $navigationElement.Length } else { 0 }
    $content = [regex]::Match($result.Substring($afterNav), '(?is)<section\b(?![^>]*\bhidden\b)(?<attrs>[^>]*)>')
    if ($content.Success) {
        $opening = $content.Value
        $id = [regex]::Match($content.Groups['attrs'].Value, '\bid="(?<id>[^"]+)"')
        $target = 'butler-main-content'
        if ($id.Success) { $target = $id.Groups['id'].Value }
        else { $opening = $opening.Insert($opening.Length - 1, ' id="butler-main-content"') }
        if ($content.Groups['attrs'].Value -notmatch '\btabindex=') {
            $opening = $opening.Insert($opening.Length - 1, ' tabindex="-1"')
        }
        $contentIndex = $afterNav + $content.Index
        $result = $result.Remove($contentIndex, $content.Length).Insert($contentIndex, $opening)
        $body = [regex]::Match($result, '(?i)<body\b[^>]*>')
        if ($body.Success) {
            $result = $result.Insert($body.Index + $body.Length, '<a class="butler-skip-link" href="#' + $target + '">Skip to main content</a>')
        }
    }
    $style = @'
<style id="butler-accessibility-style">
.butler-skip-link{position:fixed;left:12px;top:12px;transform:translateY(-200%);z-index:10000;padding:12px 16px;background:#fff;color:#111315;font:700 16px Arial,sans-serif;border:2px solid #111315;border-radius:4px}
.butler-skip-link:focus{transform:none}
a:focus-visible,button:focus-visible,input:focus-visible,select:focus-visible,textarea:focus-visible,summary:focus-visible,[tabindex="-1"]:focus{outline:3px solid #28543D;outline-offset:3px}
@media(min-width:1200px){
main.playbook-shell{max-width:1580px;display:grid;grid-template-columns:235px minmax(0,1fr);gap:20px;align-items:start;padding-top:18px}
main.playbook-shell>.top{grid-column:1/-1;margin-bottom:0}
main.playbook-shell>.manager-playbook{grid-column:1;grid-row:2/span 64;display:flex;flex-direction:column;position:sticky;top:16px;padding:14px 8px;border:1px solid var(--line);border-radius:15px;background:var(--surface);max-height:calc(100vh - 32px);overflow-y:auto}
main.playbook-shell>.manager-playbook~*{grid-column:2;min-width:0}
.manager-playbook a{display:block;color:var(--muted);padding:8px 11px;border-radius:8px;text-decoration:none;font-size:13px;font-weight:700}
.manager-playbook a:hover,.manager-playbook a:focus-visible{background:var(--surface-2);color:var(--ink)}
.manager-playbook a.active{background:rgba(105,162,125,.18);color:var(--turf-deep)}
.playbook-title{padding:3px 12px;color:var(--turf);font-size:12px;font-weight:900;letter-spacing:.12em}
.playbook-team{padding:7px 11px 11px;color:var(--ink);font-size:14px;font-weight:800;overflow-wrap:anywhere}
.playbook-label{padding:16px 12px 5px;color:var(--muted);font-size:11px;font-weight:900;letter-spacing:.09em;border-top:1px solid var(--line)}.manager-playbook .playbook-autopilot::after{content:"BETA";margin-left:8px;padding:2px 5px;border:1px solid var(--line);border-radius:999px;font-size:8px;letter-spacing:.06em;color:var(--muted)}
}
@media(max-width:1199px){
.manager-playbook{display:flex;gap:4px;margin:0 0 24px;flex-wrap:nowrap;padding:8px 12px 9px;border:1px solid var(--line);border-top:0;border-radius:0 0 14px 14px;background:var(--surface);max-width:100%;overflow-x:auto;overflow-y:hidden;-webkit-overflow-scrolling:touch;scroll-snap-type:x proximity;scroll-padding-inline:8px;scrollbar-width:none;-ms-overflow-style:none;overscroll-behavior-x:contain;touch-action:pan-x}
.manager-playbook::-webkit-scrollbar{display:none;width:0;height:0}
.manager-playbook .playbook-title,.manager-playbook .playbook-team,.manager-playbook .playbook-label{display:none}
.manager-playbook a{display:inline-flex;align-items:center;justify-content:center;flex:0 0 auto;min-height:44px;white-space:nowrap;scroll-snap-align:start;color:var(--muted);text-decoration:none;padding:9px 12px;font-weight:700;font-size:13px;border:1px solid transparent;border-radius:8px;background:transparent}
.manager-playbook a:hover{color:var(--ink);background:var(--surface-2)}
.manager-playbook a.active{color:var(--turf-deep);background:var(--surface-2);border-color:var(--line)}
}
@media(prefers-color-scheme:dark){
a:focus-visible,button:focus-visible,input:focus-visible,select:focus-visible,textarea:focus-visible,summary:focus-visible,[tabindex="-1"]:focus{outline-color:#A8D3B5}
html body .command-button:not(.secondary),html body .btn-primary,html body a.button,html body .trade-button{color:#111315!important}
html body .history-action-primary{background:#26352C;color:#A8D3B5;border-color:#35483C}
html body .history-action-primary:hover{background:#202426}
}
@media(forced-colors:active){a:focus-visible,button:focus-visible,input:focus-visible,select:focus-visible,textarea:focus-visible,summary:focus-visible,[tabindex="-1"]:focus{outline-color:Highlight}}
</style>
'@
    $headEnd = $result.IndexOf('</head>', [StringComparison]::OrdinalIgnoreCase)
    if ($headEnd -ge 0) { $result = $result.Insert($headEnd, $style) }
    return $result
}

function ConvertTo-V04StartSitRouteHtml {
    param(
        [Parameter(Mandatory = $true)][string]$Html,
        [Parameter(Mandatory = $true)][string]$RequestTarget
    )

    if ($RequestTarget -cne '/matchup/autofill' -or
        $Html.IndexOf('start-sit-assistant', [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
        return $Html
    }

    $result = $Html.Replace('<title>Butler - Weekly Matchup</title>', '<title>Butler - Start/Sit Assistant</title>')

    # BF-1029: exact Start/Sit route owns current-page identity before the shared Playbook is generated.
    $result = $result.Replace('<a class="active" href="/matchup">Matchup</a>', '<a class="active" href="/matchup/autofill">Start/Sit Assistant</a>')

    # BF-1031: loading /matchup/autofill already reruns the current read-only
    # recommendation/evidence path and every public response is no-store.
    # Remove legacy manual retry/refresh links that only reload the same route.
    $result = [regex]::Replace(
        $result,
        '(?is)<a\b[^>]*href="/matchup/autofill"[^>]*>\s*Retry Lineup Review\s*</a>',
        ''
    )
    $result = [regex]::Replace(
        $result,
        '(?is)<a\b[^>]*>\s*Refresh projections?\s*</a>',
        ''
    )
    $assistantPanel = [regex]::Match(
        $result,
        '(?is)<section\b[^>]*class="[^"]*\bstart-sit-assistant\b[^"]*"[^>]*>'
    )
    # BF-1055: when Sleeper's season/week is mismatched or unverified,
    # Start/Sit is intentionally withheld, not silently "auto-rechecked".
    if ($assistantPanel.Success -and
        $result.IndexOf('data-butler-week-state="MISMATCH"', [System.StringComparison]::OrdinalIgnoreCase) -lt 0 -and
        $result.IndexOf('data-butler-week-state="UNVERIFIED"', [System.StringComparison]::OrdinalIgnoreCase) -lt 0 -and
        $result.IndexOf('start-sit-auto-recheck', [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
        $autoRecheck = '<p class="meta start-sit-auto-recheck"><strong>Auto-recheck:</strong> This page reruns current read-only lineup evidence every time it loads. Reloading the page is enough; no manual retry is required.</p>'
        $result = $result.Insert($assistantPanel.Index + $assistantPanel.Length, $autoRecheck)
    }

    # The explicit Start/Sit route should not repeat the Matchup decision hero
    # above the exact same lineup decision. Ordinary /matchup remains unchanged.
    $hero = [regex]::Match($result, '(?is)<section\b[^>]*class="[^"]*\bhero-panel\b[^"]*"[^>]*>.*?</section>\s*')
    if ($hero.Success) {
        $result = $result.Remove($hero.Index, $hero.Length)
    }

    # BF-1029: blocked Start/Sit reviews surface the exact evidence reason
    # before the disclosure so the manager sees the real recovery blocker.
    $detail = [regex]::Match($result, '(?is)<details[^>]*>\s*<summary>\s*View evidence details\s*</summary>\s*<div[^>]*class="[^"]*callout-danger[^"]*"[^>]*>(?<reason>.*?)</div>\s*</details>')
    if ($detail.Success) {
        $visibleReason = '<div class="callout callout-danger start-sit-blocker"><strong>Blocking evidence:</strong> ' + $detail.Groups['reason'].Value + '</div>'
        $result = $result.Insert($detail.Index, $visibleReason)
    }

    $result = $result.Replace(
        'Weekly Matchup leads with the existing Start/Sit Assistant decision, then shows confirmed-opponent context.',
        'Start/Sit Assistant keeps the weekly lineup decision first and leaves opponent context available as secondary evidence.'
    )
    $result = $result.Replace(
        'Weekly Matchup leads with the existing Lineup Advisor decision, then shows confirmed-opponent context.',
        'Start/Sit Assistant keeps the weekly lineup decision first and leaves opponent context available as secondary evidence.'
    )

    return $result
}

# BF-1113: fail-closed, manager-first recovery when the outer worker
# cannot deliver a requested page. No raw exception (including user league,
# private filesystem paths or tokens) may appear in public HTML. Retry
# navigates only to an exact known GET route and never executes a write.
function Get-ButlerManagerFailureSpec {
    param(
        [AllowNull()][string]$RequestTarget,
        [AllowNull()][string]$FailurePhase
    )

    $spec = [ordered]@{
        Title = 'Butler page unavailable'
        Active = 'dashboard'
        PrimaryHref = '/'
        PrimaryLabel = 'Return to Dashboard'
        SecondaryHref = '/team'
        SecondaryLabel = 'Review My Team'
    }
    switch -CaseSensitive ($RequestTarget) {
        '/' {
            $spec.Title = 'Dashboard unavailable'
            $spec.PrimaryLabel = 'Try Dashboard again'
        }
        '/team' {
            $spec.Title = 'My Team unavailable'
            $spec.Active = 'team'
            $spec.PrimaryHref = '/team'
            $spec.PrimaryLabel = 'Try My Team again'
            $spec.SecondaryHref = '/'
            $spec.SecondaryLabel = 'Dashboard'
        }
        '/waivers' {
            $spec.Title = 'Waiver Board unavailable'
            $spec.Active = 'waivers'
            $spec.PrimaryHref = '/waivers'
            $spec.PrimaryLabel = 'Try Waiver Board again'
            $spec.SecondaryHref = '/'
            $spec.SecondaryLabel = 'Dashboard'
        }
        '/matchup' {
            $spec.Title = 'Matchup unavailable'
            $spec.Active = 'matchup'
            $spec.PrimaryHref = '/matchup'
            $spec.PrimaryLabel = 'Try Matchup again'
            $spec.SecondaryHref = '/'
            $spec.SecondaryLabel = 'Dashboard'
        }
        '/matchup/autofill' {
            $spec.Title = 'Start/Sit unavailable'
            $spec.Active = 'matchup'
            $spec.PrimaryHref = '/matchup/autofill'
            $spec.PrimaryLabel = 'Try Start/Sit again'
            $spec.SecondaryHref = '/team'
            $spec.SecondaryLabel = 'Review My Team'
        }
        '/league' {
            $spec.Title = 'League unavailable'
            $spec.Active = 'league'
            $spec.PrimaryHref = '/league'
            $spec.PrimaryLabel = 'Try League again'
            $spec.SecondaryHref = '/'
            $spec.SecondaryLabel = 'Dashboard'
        }
        '/autopilot' {
            # BF-1139: the exact read-only Auto-Pilot watch has a safe retry
            # destination; never use untrusted queries or partial route text.
            $spec.Title = 'Auto-Pilot unavailable'
            $spec.Active = 'dashboard'
            $spec.PrimaryHref = '/autopilot'
            $spec.PrimaryLabel = 'Try Auto-Pilot again'
            $spec.SecondaryHref = '/'
            $spec.SecondaryLabel = 'Dashboard'
        }
    }
    $spec.Message = if ($FailurePhase -ceq 'CORE_READ') {
        'The local Butler engine could not return this page. The current roster, matchup and recommendation have not been verified here. Retry the page after the engine is available.'
    }
    else {
        'Butler could not finish showing this page. No lineup or waiver advice is available from this response. Retry the page or return to the Dashboard.'
    }
    return [pscustomobject]$spec
}

# BF-1117: the ordinary blocked-page renderer depends on styled core
# modules. If those modules are the very cause of the failure, still return
# a tiny, accessible, private-data-free HTTP 500 page. No backend reads.
function Get-ButlerMinimalBlockedPageHtml {
    param(
        [Parameter(Mandatory = $true)][string]$Title,
        [Parameter(Mandatory = $true)][string]$PrimaryHref,
        [Parameter(Mandatory = $true)][string]$PrimaryLabel
    )

    if (@('/', '/team', '/waivers', '/matchup',
          '/matchup/autofill', '/league', '/autopilot') -cnotcontains $PrimaryHref) {
        $PrimaryHref = '/'
        $PrimaryLabel = 'Return to Dashboard'
    }
    $safeTitle = [System.Net.WebUtility]::HtmlEncode($Title)
    $safeHref = [System.Net.WebUtility]::HtmlEncode($PrimaryHref)
    $safeLabel = [System.Net.WebUtility]::HtmlEncode($PrimaryLabel)
    return '<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Butler - Page unavailable</title><style>body{font:16px/1.5 system-ui,Arial,sans-serif;background:#101827;color:#f4f6fa;margin:0;padding:32px}main{max-width:650px;margin:10vh auto;padding:25px;border:1px solid #687b90;border-radius:12px}a{color:#d7f4d4;font-weight:700}a:focus-visible{outline:3px solid white;outline-offset:4px}</style></head><body><main><h1>' +
        $safeTitle + '</h1><p>The Butler page is temporarily unavailable. Current roster and decision evidence cannot be verified here. No Sleeper transaction was submitted by this page.</p><p><a href="' +
        $safeHref + '">' + $safeLabel + '</a> or <a href="/">Dashboard</a>.</p></main></body></html>'
}

function Get-ButlerBlockedPageHtml {
    param(
        [Parameter(Mandatory = $true)][string]$Title,
        [Parameter(Mandatory = $true)][string]$Message,
        [Parameter(Mandatory = $true)][string]$Active,
        [Parameter(Mandatory = $true)][string]$PrimaryHref,
        [Parameter(Mandatory = $true)][string]$PrimaryLabel,
        [string]$SecondaryHref = '/',
        [string]$SecondaryLabel = 'Dashboard'
    )

    $css = Get-AppCss
    $nav = Get-AppNav -Active $Active
    $safeTitle = ConvertTo-HtmlText $Title
    $safeMessage = ConvertTo-HtmlText $Message
    $safePrimaryHref = ConvertTo-HtmlText $PrimaryHref
    $safePrimaryLabel = ConvertTo-HtmlText $PrimaryLabel
    $safeSecondaryHref = ConvertTo-HtmlText $SecondaryHref
    $safeSecondaryLabel = ConvertTo-HtmlText $SecondaryLabel

    return @"
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Butler - $safeTitle</title>
<style>$css
.blocked-actions{display:flex;gap:12px;align-items:center;flex-wrap:wrap;margin-top:18px}
.blocked-actions a{display:inline-block;border:1px solid var(--line);border-radius:10px;padding:10px 14px;text-decoration:none;font-weight:700}
.blocked-actions a:first-child{background:var(--surface-2);color:var(--ink)}
.blocked-detail{margin-top:16px;white-space:pre-wrap;overflow-wrap:anywhere}
</style>
</head>
<body>
<main class="shell">
<div class="top"><div class="brand"><h1>BUTLER</h1><p>We're here to serve you. Less Research. Better Decisions.</p></div><div class="target">Request stopped safely</div></div>
$nav
<section class="panel">
<div class="eyebrow">Safe stop</div>
<div class="statusrow"><div><h1 class="headline">$safeTitle</h1><p class="lede">Butler stopped this request instead of guessing or continuing with an unsafe state.</p></div><span class="status warn">STOPPED SAFELY</span></div>
<pre class="blocked-detail">$safeMessage</pre>
<p>No Sleeper transaction was submitted by this page. Butler may have attempted governed local evidence recovery before stopping. Check current source status before acting.</p>
<div class="blocked-actions"><a href="$safePrimaryHref">$safePrimaryLabel</a><a href="$safeSecondaryHref">$safeSecondaryLabel</a></div>
</section>
</main>
</body>
</html>
"@
}

function Get-V04AutoPilotWatchState {
    param([Parameter(Mandatory = $true)][string]$DashboardHtml)

    $result = [ordered]@{
        Ready = $false
        Attention = 'UNAVAILABLE'
        StartSit = 'UNAVAILABLE'
        Waivers = 'UNAVAILABLE'
        Roster = 'Manager tools'
        EvidenceStatus = 'UNVERIFIED'
    }

    if ([string]::IsNullOrWhiteSpace($DashboardHtml)) {
        return [pscustomobject]$result
    }

    $labels = @('Attention', 'Start/Sit', 'Waivers', 'Roster')
    foreach ($label in $labels) {
        # BF-1045: duplicate or empty summary cards are ambiguous manager
        # evidence. Never pick the first plausible lineup/waiver signal.
        $matches = [regex]::Matches(
            $DashboardHtml,
            '(?is)<div\b[^>]*class="[^"]*\bdashboard-summary-card\b[^"]*"[^>]*>\s*<span>\s*' +
                [regex]::Escape($label) +
                '\s*</span>\s*<strong>(?<value>.*?)</strong>\s*</div>'
        )
        if ($matches.Count -ne 1) { continue }

        $plain = [regex]::Replace($matches[0].Groups['value'].Value, '<[^>]+>', ' ')
        $plain = [System.Net.WebUtility]::HtmlDecode($plain)
        $plain = [regex]::Replace($plain, '\s+', ' ').Trim()
        if ([string]::IsNullOrWhiteSpace($plain)) { continue }
        switch -CaseSensitive ($label) {
            'Attention' { $result.Attention = $plain }
            'Start/Sit' { $result.StartSit = $plain }
            'Waivers' { $result.Waivers = $plain }
            'Roster' { $result.Roster = $plain }
        }
    }

    # BF-1042: presence of summary cards proves a readable local snapshot,
    # not that its audited evidence remains current. Auto-Pilot must not show
    # READY or prepare approval actions from stale/unknown decision evidence.
    # This is the same unique Dashboard technical state used by BF-677.
    $decisionState = Get-DecisionRefreshTechnicalField -Html $DashboardHtml -Label 'Decision state:'
    $bf629 = Get-DecisionRefreshTechnicalField -Html $DashboardHtml -Label 'BF-629:'
    $bf631 = Get-DecisionRefreshTechnicalField -Html $DashboardHtml -Label 'BF-631:'
    # BF-1044: independently prove the actionability and lineage behind a
    # displayed CURRENT decision. A populated summary card or state label
    # alone cannot authorize a manager Start/Sit or waiver recommendation.
    $currentEvidence = (
        ($decisionState -ceq 'CURRENT_AND_ACTIONABLE' -and
         $bf629 -ceq 'LIVE_ACTIONABLE_VERIFIED' -and
         $bf631 -ceq 'LATEST_EVIDENCE_LINEAGE_VERIFIED') -or
        ($decisionState -ceq 'NO_TRANSACTION_TO_ACT_ON' -and
         $bf629 -ceq 'NO_TRANSACTION_TO_REVALIDATE' -and
         $bf631 -ceq 'LATEST_EVIDENCE_LINEAGE_VERIFIED')
    )
    # BF-1046: BF-677 correctly permits a manual recheck of no-transaction
    # decisions with superseded lineage; that does NOT make their old summary
    # a current Auto-Pilot recommendation. Never silently treat an old NO MOVE
    # decision as a freshly checked roster/waiver state.
    $outdatedNoMove = (
        $decisionState -ceq 'NO_TRANSACTION_TO_ACT_ON' -and
        $bf629 -ceq 'NO_TRANSACTION_TO_REVALIDATE' -and
        @('MARKET_LINEAGE_SUPERSEDED', 'WAIVER_LINEAGE_SUPERSEDED',
          'MARKET_AND_WAIVER_LINEAGE_SUPERSEDED') -ccontains $bf631
    )
    $result.EvidenceStatus = if ($currentEvidence) {
        'CURRENT'
    }
    elseif ($outdatedNoMove -or
            @('STALE_DO_NOT_ACT', 'CURRENT_REFRESH_RECOMMENDED') -ccontains $decisionState) {
        'STALE'
    }
    else {
        'UNVERIFIED'
    }

    # Do not leak an apparently actionable lineup or waiver signal from stale
    # cards when the audited proof fails. Show a clear hold until fresh data
    # can be read; preserve only non-actionable roster context.
    if ($result.EvidenceStatus -ceq 'STALE') {
        $result.Attention = 'NEEDS REFRESH'
        $result.StartSit = 'REFRESH'
        $result.Waivers = 'DO NOT ACT'
    }
    elseif ($result.EvidenceStatus -ceq 'UNVERIFIED') {
        $result.Attention = 'UNAVAILABLE'
        $result.StartSit = 'UNAVAILABLE'
        $result.Waivers = 'UNAVAILABLE'
    }
    $result.Ready =
        $currentEvidence -and
        $result.Attention -cne 'UNAVAILABLE' -and
        $result.StartSit -cne 'UNAVAILABLE' -and
        $result.Waivers -cne 'UNAVAILABLE' -and
        $result.Roster -cne 'Manager tools'

    return [pscustomobject]$result
}

# BF-1057: A current Dashboard decision does not establish that the saved
# matchup belongs to the *current* public Sleeper NFL week. Reuse the exact
# read-only Matchup route's BF-1054 source proof before Auto-Pilot presents a
# prepared Start/Sit or waiver decision. Missing/ambiguous/unverified proof
# fails closed without inventing a refreshed roster or mutating Sleeper.
function Limit-V04AutoPilotToVerifiedWeek {
    param(
        [Parameter(Mandatory = $true)]$WatchState,
        [string]$MatchupHtml = ''
    )

    if (-not [bool]$WatchState.Ready) { return $WatchState }

    $state = 'UNVERIFIED'
    $markers = [regex]::Matches($MatchupHtml, 'data-butler-week-state=')
    $proof = [regex]::Matches(
        $MatchupHtml,
        '(?is)<section\b[^>]*class="[^"]*\bbutler-live-week-status\b[^"]*"[^>]*data-butler-week-state="(?<state>MATCH|MISMATCH|UNVERIFIED)"[^>]*>'
    )
    if ($markers.Count -eq 1 -and $proof.Count -eq 1) {
        $state = [string]$proof[0].Groups['state'].Value
    }
    if ($state -ceq 'MATCH') {
        # BF-1062: source current-week proof is not confirmation of the
        # persisted pairing. Confirm the sole rendered matchup header is
        # for that exact verified season/week; unavailable opponent pages
        # have no pairing metadata and cannot authorize manager approval.
        $sourceWeek = [regex]::Matches(
            $MatchupHtml,
            '(?is)<section\b[^>]*class="[^"]*\bbutler-live-week-status\b[^"]*"[^>]*data-butler-week-state="MATCH"[^>]*data-butler-week-season="(?<season>20[0-9]{2})"[^>]*data-butler-week-number="(?<week>[1-9]|1[0-8])"[^>]*>'
        )
        $pairing = [regex]::Matches(
            $MatchupHtml,
            '(?is)<div\b[^>]*class="target"[^>]*data-butler-matchup-season="(?<season>20[0-9]{2})"[^>]*>[^<]*\bWeek\s+(?<week>[1-9]|1[0-8])\s*</div>'
        )
        if ($markers.Count -eq 1 -and $sourceWeek.Count -eq 1 -and $pairing.Count -eq 1 -and
            [regex]::Matches($MatchupHtml, 'data-butler-week-season=').Count -eq 1 -and
            [regex]::Matches($MatchupHtml, 'data-butler-week-number=').Count -eq 1 -and
            [regex]::Matches($MatchupHtml, 'data-butler-matchup-season=').Count -eq 1 -and
            $sourceWeek[0].Groups['season'].Value -ceq $pairing[0].Groups['season'].Value -and
            $sourceWeek[0].Groups['week'].Value -ceq $pairing[0].Groups['week'].Value) {
            # BF-1102: a unique, current public Sleeper MATCH proof plus
            # aligned saved pairing is the only source of the week label.
            $WatchState | Add-Member -NotePropertyName VerifiedWeekSeason -NotePropertyValue ([string]$sourceWeek[0].Groups['season'].Value) -Force
            $WatchState | Add-Member -NotePropertyName VerifiedWeekNumber -NotePropertyValue ([string]$sourceWeek[0].Groups['week'].Value) -Force
            return $WatchState
        }
        $state = 'UNVERIFIED'
    }

    $WatchState.Ready = $false
    if ($state -ceq 'MISMATCH') {
        $WatchState.EvidenceStatus = 'WEEK_MISMATCH'
        $WatchState.Attention = 'SYNC SAVED WEEK'
        $WatchState.StartSit = 'DO NOT ACT'
        $WatchState.Waivers = 'DO NOT ACT'
    }
    else {
        $WatchState.EvidenceStatus = 'WEEK_UNVERIFIED'
        $WatchState.Attention = 'WEEK NOT VERIFIED'
        $WatchState.StartSit = 'UNAVAILABLE'
        $WatchState.Waivers = 'UNAVAILABLE'
    }
    return $WatchState
}

# BF-1074: a Dashboard START/SIT card is not evidence that any lineup
# change survived exact Sleeper status checks. An actionable Auto-Pilot packet
# requires a second, independently rendered, read-only Start/Sit review.
# Fail closed without blocking independently reviewed waiver decisions.
function Get-V04AutoPilotExactPageFrame {
    param([AllowEmptyString()][string]$Html = '')
    $weekMarkers = [regex]::Matches($Html, 'data-butler-week-state=')
    $seasonMarkers = [regex]::Matches($Html, 'data-butler-week-season=')
    $numberMarkers = [regex]::Matches($Html, 'data-butler-week-number=')
    $pairingMarkers = [regex]::Matches($Html, 'data-butler-matchup-season=')
    if ($weekMarkers.Count -ne 1 -or $seasonMarkers.Count -ne 1 -or
        $numberMarkers.Count -ne 1 -or $pairingMarkers.Count -ne 1) { return $null }
    $week = [regex]::Matches($Html,
        '(?is)<section\b[^>]*class="[^"]*\bbutler-live-week-status\b[^"]*"[^>]*data-butler-week-state="MATCH"[^>]*data-butler-week-season="(?<season>20[0-9]{2})"[^>]*data-butler-week-number="(?<week>[1-9]|1[0-8])"[^>]*>')
    $pair = [regex]::Matches($Html,
        '(?is)<div\b[^>]*class="target"[^>]*data-butler-matchup-season="(?<season>20[0-9]{2})"[^>]*>(?<league>[^<]*?)\s*&middot;\s*Week\s+(?<week>[1-9]|1[0-8])\s*</div>')
    if ($week.Count -ne 1 -or $pair.Count -ne 1 -or
        $week[0].Groups['season'].Value -cne $pair[0].Groups['season'].Value -or
        $week[0].Groups['week'].Value -cne $pair[0].Groups['week'].Value) { return $null }
    $league = [regex]::Replace(
        [System.Net.WebUtility]::HtmlDecode($pair[0].Groups['league'].Value), '\s+', ' ').Trim()
    if ([string]::IsNullOrWhiteSpace($league)) { return $null }
    return [pscustomobject]@{
        Season = $week[0].Groups['season'].Value
        Week = $week[0].Groups['week'].Value
        League = $league
    }
}

function Limit-V04AutoPilotToSourcedStartSit {
    param(
        [Parameter(Mandatory = $true)]$WatchState,
        [AllowEmptyString()][string]$MatchupHtml = '',
        [AllowEmptyString()][string]$StartSitHtml = '',
        [DateTimeOffset]$CheckedAtUtc = [DateTimeOffset]::UtcNow
    )
    if (-not [bool]$WatchState.Ready) { return $WatchState }

    # An observed hold explains a blocked packet; it never grants approval.
    # Clear it on every evaluation so a reused watch cannot retain old reasons.
    $WatchState.PSObject.Properties.Remove('StartSitObservedEvidenceGap')

    # The current week and opponent must match on BOTH routes, not just
    # independently show a green "MATCH" badge.
    $matchup = Get-V04AutoPilotExactPageFrame -Html $MatchupHtml
    $startSit = Get-V04AutoPilotExactPageFrame -Html $StartSitHtml
    $valid = $null -ne $matchup -and $null -ne $startSit
    if ($valid) {
        $valid = $matchup.Season -ceq $startSit.Season -and
            $matchup.Week -ceq $startSit.Week -and
            $matchup.League -ceq $startSit.League
    }
    if ($valid) {
        $observedProof = [regex]::Matches($StartSitHtml,
            '(?is)<p\s+class="meta butler-startsit-source-proof"\s+role="status"\s*>(?<text>[^<]*)</p>')
        if ($observedProof.Count -eq 1) {
            $observedMessage = [System.Net.WebUtility]::HtmlDecode($observedProof[0].Groups['text'].Value)
            $observedHolds = [regex]::Matches($observedMessage, '\b[0-9]+ player holds?\b')
            $partialClaims = [regex]::Matches($observedMessage,
                'Partial projection coverage; missing projections are not zeros')
            if ($observedHolds.Count -eq 1 -and
                $observedHolds[0].Value -cmatch '^[1-9][0-9]{0,2} player holds?$' -and
                $partialClaims.Count -eq 1 -and
                $observedMessage.IndexOf('Full scoreable projection coverage', [StringComparison]::Ordinal) -lt 0) {
                $WatchState | Add-Member -NotePropertyName StartSitObservedEvidenceGap -NotePropertyValue 'PARTIAL_WITH_HOLDS' -Force
            }
        }
    }
    if ($valid -and
        ($StartSitHtml.IndexOf('class="callout callout-danger start-sit-blocker"', [StringComparison]::OrdinalIgnoreCase) -ge 0 -or
         $StartSitHtml.IndexOf('Butler could not prove a complete weekly lineup recommendation.', [StringComparison]::OrdinalIgnoreCase) -ge 0)) {
        $valid = $false
    }
    if ($valid) {
        $proof = [regex]::Matches($StartSitHtml,
            '(?is)<p\s+class="meta butler-startsit-source-proof"\s+role="status"\s*>(?<text>[^<]*)</p>')
        if ($proof.Count -ne 1) { $valid = $false }
    }
    if ($valid) {
        $message = [System.Net.WebUtility]::HtmlDecode($proof[0].Groups['text'].Value)

        # BF-1088: verified "keep starters" is not a proposed lineup swap.
        # The audited Dashboard must independently say NO CHANGE, and the
        # complete zero-hold Start/Sit proof must have a recent projection.
        if ($message.IndexOf('No lineup change is ready.', [StringComparison]::Ordinal) -ge 0) {
            $validNoChange = [string]$WatchState.StartSit -ceq 'NO CHANGE' -and
                $message.StartsWith('No lineup change is ready. Full scoreable projection coverage; 0 player holds.',
                    [StringComparison]::Ordinal) -and
                [regex]::Matches($message, '\b[0-9]+ player holds?\b').Count -eq 1 -and
                [regex]::Matches($message, 'Full scoreable projection coverage').Count -eq 1 -and
                $message.IndexOf('proposed lineup changes', [StringComparison]::OrdinalIgnoreCase) -lt 0 -and
                $message.IndexOf('UNVERIFIED', [StringComparison]::OrdinalIgnoreCase) -lt 0
            $projectionNoChange = [regex]::Matches($message,
                'projection snapshot retrieved (?<time>20[0-9]{2}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12][0-9]|3[01])T(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](?:\.[0-9]{1,9})?Z) UTC')
            if ($validNoChange -and $projectionNoChange.Count -eq 1) {
                $projectionTime = [string]$projectionNoChange[0].Groups['time'].Value
                $wholeSecond = [regex]::Replace($projectionTime, '\.[0-9]{1,9}Z$', 'Z')
                $observed = [DateTimeOffset]::MinValue
                $validNoChange = [DateTimeOffset]::TryParseExact($wholeSecond, "yyyy-MM-dd'T'HH:mm:ss'Z'",
                    [Globalization.CultureInfo]::InvariantCulture,
                    [Globalization.DateTimeStyles]::AssumeUniversal, [ref]$observed)
                if ($validNoChange) {
                    $age = $CheckedAtUtc.ToUniversalTime() - $observed.ToUniversalTime()
                    $validNoChange = $age -ge [TimeSpan]::FromSeconds(-90) -and
                        $age -le [TimeSpan]::FromMinutes(120)
                }
            }
            else { $validNoChange = $false }
            if ($validNoChange) {
                $WatchState.StartSit = 'NO CHANGE VERIFIED'
                $WatchState | Add-Member -NotePropertyName StartSitReviewOutcome -NotePropertyValue 'VERIFIED_NO_CHANGE' -Force
                $WatchState | Add-Member -NotePropertyName StartSitProjectionFetchedAtUtc -NotePropertyValue $projectionTime -Force
                return $WatchState
            }
            $WatchState.StartSit = 'BLOCKED - CHECK START/SIT'
            return $WatchState
        }
        # BF-1089: only a separately audited Dashboard review signal
        # can agree with a new proposal. NO CHANGE, NOT REVIEWED, REFRESH
        # and arbitrary cards must never authorize a changed-lineup packet.
        $dashboardRequestsReview = [string]$WatchState.StartSit -ceq 'REVIEW' -or
            [regex]::IsMatch([string]$WatchState.StartSit, '^START [1-9][0-9]* / SIT [1-9][0-9]*$')
        if (-not $dashboardRequestsReview) { $valid = $false }
        # Require the actual BF-1067 sourced-change message. A held swap,
        # unverified timestamp, no-change decision, or generic Dashboard
        # signal cannot authorize an Auto-Pilot prepared packet.
        # BF-1085: a proposal is not a complete ready-to-review lineup
        # when ANY player has unresolved projection/availability evidence.
        # Check the full scoreable projection coverage and exact zero-hold
        # boundary, not the substring '0 player holds' in '10 player holds'.
        # BF-1087: an exact sourced-change assertion must begin with a
        # positive changed-assignment count, and the rendered source must
        # contain one and only one change count, projection-coverage claim,
        # and numeric player-hold count. A zero-change, duplicate or
        # contradictory proof cannot prepare an Auto-Pilot lineup packet.
        $positiveChanges = [regex]::IsMatch($message,
            '^[1-9][0-9]* proposed lineup changes have exact player-status checks from Sleeper at the recorded fetch time\.')
        # BF-1106: keep the exact change count only when the separate
        # recent, full-coverage, zero-hold proof succeeds. This number
        # cannot come from the Dashboard card or a guessed player roster.
        $verifiedProposalCountText = ''
        $changeCounts = [regex]::Matches($message, '\b[0-9]+ proposed lineup changes\b')
        $coverageClaims = [regex]::Matches($message, 'Full scoreable projection coverage')
        $holdCounts = [regex]::Matches($message, '\b[0-9]+ player holds?\b')
        if (-not $positiveChanges -or $changeCounts.Count -ne 1 -or
            $coverageClaims.Count -ne 1 -or $holdCounts.Count -ne 1 -or
            $holdCounts[0].Value -cne '0 player holds') {
            $valid = $false
        }
        if ($message.IndexOf('proposed lineup changes have exact player-status checks from Sleeper at the recorded fetch time', [StringComparison]::Ordinal) -lt 0 -or
            $message.IndexOf('Full scoreable projection coverage', [StringComparison]::Ordinal) -lt 0 -or
            -not [regex]::IsMatch($message, '(?<![0-9])0 player holds\b') -or
            $message.IndexOf('no Sleeper move was submitted', [StringComparison]::Ordinal) -lt 0 -or
            $message.IndexOf('UNVERIFIED', [StringComparison]::OrdinalIgnoreCase) -ge 0) {
            $valid = $false
        }
    }
    if ($valid) {
        # A source fetch is not clinical clearance. Bound both observation
        # times; status rows older than two 5-minute Sleeper cache TTLs
        # cannot be advertised as CURRENT on another page.
        $iso = '20[0-9]{2}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12][0-9]|3[01])T(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](?:\.[0-9]{1,9})?Z'
        $status = [regex]::Matches($message, 'swap players status map retrieved (?<time>' + $iso + ') UTC')
        $projection = [regex]::Matches($message, 'projection snapshot retrieved (?<time>' + $iso + ') UTC')
        if ($status.Count -ne 1 -or $projection.Count -ne 1) {
            $valid = $false
        }
        else {
            foreach ($stamp in @(
                @{ Text = $status[0].Groups['time'].Value; Minutes = 10 },
                @{ Text = $projection[0].Groups['time'].Value; Minutes = 120 }
            )) {
                # DateTimeOffset accepts max 7 fractional digits. Preserve
                # up to 9 original digits in the evidence but validate the
                # actual calendar and compare whole-second UTC observations.
                $wholeSecond = [regex]::Replace($stamp.Text, '\.[0-9]{1,9}Z$', 'Z')
                $parsed = [DateTimeOffset]::MinValue
                if (-not [DateTimeOffset]::TryParseExact($wholeSecond,
                    "yyyy-MM-dd'T'HH:mm:ss'Z'", [Globalization.CultureInfo]::InvariantCulture,
                    [Globalization.DateTimeStyles]::AssumeUniversal, [ref]$parsed)) {
                    $valid = $false
                    break
                }
                $age = $CheckedAtUtc.ToUniversalTime() - $parsed.ToUniversalTime()
                if ($age -lt [TimeSpan]::FromSeconds(-90) -or
                    $age -gt [TimeSpan]::FromMinutes($stamp.Minutes)) {
                    $valid = $false
                    break
                }
            }
        }
    }
    if ($valid) {
        # BF-1086: propagate ONLY independently validated source timestamps.
        # Neither is an injury report publication time.
        $WatchState | Add-Member -NotePropertyName StartSitStatusFetchedAtUtc -NotePropertyValue ([string]$status[0].Groups['time'].Value) -Force
        $WatchState | Add-Member -NotePropertyName StartSitProjectionFetchedAtUtc -NotePropertyValue ([string]$projection[0].Groups['time'].Value) -Force
        # BF-1090: proof was accepted by the same narrow current league/week,
        # source-age, coverage, and player-hold gate. A Dashboard card alone
        # must never turn into a manager-approval packet.
        $WatchState | Add-Member -NotePropertyName StartSitReviewOutcome -NotePropertyValue 'VERIFIED_CHANGE' -Force
        # The BF-1087 valid positive count is unique. Present it as a
        # manager review count only if it fits a realistic roster boundary.
        $verifiedProposalCountText = [regex]::Match($message,
            '^(?<count>[1-9][0-9]?) proposed lineup changes have exact player-status checks')
        if ($verifiedProposalCountText.Success) {
            $WatchState | Add-Member -NotePropertyName StartSitVerifiedChangeCount -NotePropertyValue ([int]$verifiedProposalCountText.Groups['count'].Value) -Force
        }
    }
    if (-not $valid) {
        $WatchState.StartSit = 'BLOCKED - CHECK START/SIT'
        # Do not change dashboard/waiver readiness: one source-specific
        # lineup hold must not invent an unrelated waiver outage.
    }
    return $WatchState
}

function Get-V04AutoPilotApprovalPolicy {
    return [pscustomobject]@{
        Mode = 'MANAGER APPROVAL REQUIRED'
        StartSit = 'PREPARE ONLY'
        Waivers = 'RECOMMEND ONLY'
        Trades = 'NEVER AUTO-EXECUTE'
        HardBlockers = @(
            'Evidence gap',
            'Stale or incomplete weekly data',
            'Roster drift or identity mismatch',
            'Unverified kickoff or game-lock state'
        )
    }
}

function Get-V04AutoPilotApprovalQueue {
    param(
        [Parameter(Mandatory = $true)]$WatchState,
        [Parameter(Mandatory = $true)]$ApprovalPolicy
    )

    $startSitSignal = [string]$WatchState.StartSit
    $waiverSignal = [string]$WatchState.Waivers

    $startSitNext = if (-not [bool]$WatchState.Ready -or $startSitSignal -ceq 'UNAVAILABLE') {
        'Blocked until the weekly manager snapshot is complete.'
    }
    # BF-1091: queue text must obey the same source outcome gate as the
    # rendered approval packet. A raw Dashboard signal is not enough proof.
    elseif ($startSitSignal -ceq 'NO CHANGE VERIFIED') {
        if ($null -ne $WatchState.PSObject.Properties['StartSitReviewOutcome'] -and
            [string]$WatchState.StartSitReviewOutcome -ceq 'VERIFIED_NO_CHANGE' -and
            $null -ne $WatchState.PSObject.Properties['StartSitProjectionFetchedAtUtc']) {
            'No lineup swap is recommended from the verified projection frame. Recheck injury updates before kickoff.'
        }
        else {
            'No verified keep-lineup result is available yet. Open Start/Sit Assistant to review the source evidence.'
        }
    }
    elseif ($startSitSignal -ceq 'REVIEW' -or
            $startSitSignal -cmatch '^START [1-9][0-9]* / SIT [1-9][0-9]*$') {
        if ($null -ne $WatchState.PSObject.Properties['StartSitReviewOutcome'] -and
            [string]$WatchState.StartSitReviewOutcome -ceq 'VERIFIED_CHANGE' -and
            $null -ne $WatchState.PSObject.Properties['StartSitStatusFetchedAtUtc'] -and
            $null -ne $WatchState.PSObject.Properties['StartSitProjectionFetchedAtUtc']) {
            # BF-1106: the verified count belongs to the source proof,
            # not to an unverified Dashboard START/SIT headline.
            if ($null -ne $WatchState.PSObject.Properties['StartSitVerifiedChangeCount'] -and
                [string]$WatchState.StartSitVerifiedChangeCount -cmatch '^[1-9][0-9]?$') {
                $noun = if ([int]$WatchState.StartSitVerifiedChangeCount -eq 1) { 'change' } else { 'changes' }
                'Open Start/Sit Assistant and review the verified current lineup proposal: ' +
                    [string]$WatchState.StartSitVerifiedChangeCount + ' sourced lineup ' + $noun +
                    '. Manager approval is still required.'
            }
            else {
                'Open Start/Sit Assistant and review the verified current lineup proposal. Manager approval is still required.'
            }
        }
        else {
            'Lineup proposal source has not been independently verified. Open Start/Sit Assistant to check injuries and projections before acting.'
        }
    }
    elseif ($startSitSignal -ceq 'BLOCKED - CHECK START/SIT') {
        'Open Start/Sit Assistant and review exact current injuries and projections. This packet is withheld until its own source check is verified; pressing Refresh is not a substitute.'
    }
    elseif ($startSitSignal -match '(?i)DO NOT ACT') {
        'The current lineup decision explicitly holds action. Open Start/Sit Assistant for the reason; Butler will not prepare a swap from this signal.'
    }
    elseif ($startSitSignal -match '(?i)REFRESH|EVIDENCE|BLOCK|HOLD') {
        'Refresh or resolve the current evidence state before Butler prepares a lineup change.'
    }
    else {
        'Open Start/Sit Assistant and review the current lineup recommendation.'
    }

    $waiverNext = if (-not [bool]$WatchState.Ready -or $waiverSignal -ceq 'UNAVAILABLE') {
        'Blocked until the weekly manager snapshot is complete.'
    }
    elseif ($waiverSignal -match '(?i)DO NOT ACT|BLOCK|HOLD') {
        'No waiver action should be taken from the current Butler state.'
    }
    else {
        'Review the current Waiver Board recommendation. Butler will not submit a claim.'
    }

    return [pscustomobject]@{
        StartSitSignal = $startSitSignal
        StartSitPolicy = [string]$ApprovalPolicy.StartSit
        StartSitNext = $startSitNext
        WaiverSignal = $waiverSignal
        WaiverPolicy = [string]$ApprovalPolicy.Waivers
        WaiverNext = $waiverNext
        TradePolicy = [string]$ApprovalPolicy.Trades
        TradeNext = 'Trades remain manager-only and are never queued for automatic execution.'
    }
}

# BF-M2: Auto-Pilot remains usable (and strictly read-only) when the
# optional TradeHost UI CSS provider is absent or throws on this worker.
# A stylesheet is presentation, not evidence; the source/current-week
# approval gates remain unchanged and fail closed.
function Get-V04AutoPilotCss {
    $fallback = @'
/* autopilot-safe-css-fallback */
:root{font-family:Inter,Segoe UI,Arial,sans-serif;line-height:1.5;--bg:#0e1214;--surface:#171d1f;--surface-2:#20282b;--line:#36423e;--turf:#78b698;--turf-deep:#a3dabd;--gold:#d4ab65;--ink:#f5f7f6;--muted:#b4c4bf;--brick:#df9385;--font-display:Inter,Segoe UI,Arial,sans-serif;--font-body:Inter,Segoe UI,Arial,sans-serif}
*{box-sizing:border-box}html,body{margin:0;min-height:100%;background:var(--bg);color:var(--ink)}body{font-family:var(--font-body)}a{color:var(--turf-deep)}h1,h2,h3,strong{color:var(--ink)}.shell{max-width:1160px;margin:0 auto;padding:24px}.top,.panel,.nav{border:1px solid var(--line);border-radius:10px;background:var(--surface)}.top{padding:18px;margin-bottom:12px}.layout{display:grid;grid-template-columns:188px minmax(0,1fr);gap:16px}.nav{display:flex;flex-direction:column;padding:12px;gap:5px}.nav a{display:block;padding:9px 10px;border-radius:6px;text-decoration:none;font-size:13px}.nav a:hover,.nav a.active{background:var(--surface-2)}.main{min-width:0}.panel{margin:0 0 16px;padding:20px}.muted,.subtle,.meta{color:var(--muted)}.status,.badge,.pill{display:inline-block;padding:5px 9px;border:1px solid var(--line);border-radius:999px;font-size:11px;font-weight:700}.warn{color:var(--gold)}.good{color:var(--turf-deep)}button,.button{padding:9px 12px;background:var(--turf);color:#0e1214;border-radius:6px;border:0;cursor:pointer}a:focus-visible,button:focus-visible{outline:3px solid var(--gold);outline-offset:2px}@media(max-width:760px){.shell{padding:12px}.layout{display:block}.nav{display:flex;flex-direction:row;overflow:auto;margin-bottom:12px}.nav a{flex:0 0 auto}.panel{padding:14px}}
'@
    try {
        $provider = Get-Command -Name Get-AppCss -CommandType Function -ErrorAction SilentlyContinue
        if ($null -ne $provider) {
            $candidate = Get-AppCss
            if ($candidate -is [string] -and
                -not [string]::IsNullOrWhiteSpace($candidate) -and
                $candidate.Length -le 45000) {
                return $candidate
            }
        }
    }
    catch {
        # This optional appearance provider cannot turn a non-actionable
        # Auto-Pilot screen into HTTP 500. Never log its error to a response.
    }
    return $fallback
}

function Get-V04AutoPilotHtml {
    param(
        [Parameter(Mandatory = $true)]$WatchState,
        [Parameter(Mandatory = $true)]$ApprovalPolicy,
        [Parameter(Mandatory = $true)]$ApprovalQueue
    )

    # Fixed diagnostic phases only; no exception, IDs, or player text enters public output.
    $script:ButlerAutoPilotRenderSubstage = 'RENDER_CSS'
    $css = Get-V04AutoPilotCss
    $script:ButlerAutoPilotRenderSubstage = 'RENDER_STATE'
    $attention = [System.Net.WebUtility]::HtmlEncode([string]$WatchState.Attention)
    $startSit = [System.Net.WebUtility]::HtmlEncode([string]$WatchState.StartSit)
    $waivers = [System.Net.WebUtility]::HtmlEncode([string]$WatchState.Waivers)
    $roster = [System.Net.WebUtility]::HtmlEncode([string]$WatchState.Roster)
    $evidenceStatus = if ($null -ne $WatchState.PSObject.Properties['EvidenceStatus']) {
        [string]$WatchState.EvidenceStatus
    } else { 'UNVERIFIED' }
    $localReadBusy = -not [bool]$WatchState.Ready -and
        $evidenceStatus -ceq 'UNVERIFIED' -and
        $null -ne $WatchState.PSObject.Properties['LocalReadIssue'] -and
        [string]$WatchState.LocalReadIssue -ceq 'BUSY'
    # BF-1090: the renderer requires proof that its deep Start/Sit
    # source gate actually completed, not merely a green Dashboard card.
    $sourceChanged = [bool]$WatchState.Ready -and
        $null -ne $WatchState.PSObject.Properties['StartSitReviewOutcome'] -and
        [string]$WatchState.StartSitReviewOutcome -ceq 'VERIFIED_CHANGE' -and
        $null -ne $WatchState.PSObject.Properties['StartSitStatusFetchedAtUtc'] -and
        $null -ne $WatchState.PSObject.Properties['StartSitProjectionFetchedAtUtc'] -and
        ([string]$WatchState.StartSit -ceq 'REVIEW' -or
         [string]$WatchState.StartSit -cmatch '^START [1-9][0-9]* / SIT [1-9][0-9]*$')
    $sourceNoChange = [bool]$WatchState.Ready -and
        $null -ne $WatchState.PSObject.Properties['StartSitReviewOutcome'] -and
        [string]$WatchState.StartSitReviewOutcome -ceq 'VERIFIED_NO_CHANGE' -and
        [string]$WatchState.StartSit -ceq 'NO CHANGE VERIFIED' -and
        $null -ne $WatchState.PSObject.Properties['StartSitProjectionFetchedAtUtc']
    $lineupApprovalIntent = ([string]$WatchState.StartSit -ceq 'REVIEW' -or
        [string]$WatchState.StartSit -ceq 'NO CHANGE VERIFIED' -or
        [string]$WatchState.StartSit -cmatch '^START [1-9][0-9]* / SIT [1-9][0-9]*$')
    $lineupSourceHeld = [bool]$WatchState.Ready -and
        ([string]$WatchState.StartSit -ceq 'BLOCKED - CHECK START/SIT' -or
         ($lineupApprovalIntent -and -not ($sourceChanged -or $sourceNoChange)))
    # BF-1083: a fresh Dashboard audit can coexist with a deliberate
    # REFRESH/DO NOT ACT/HOLD lineup signal. Do not render that state as
    # an all-green "CURRENT SNAPSHOT" while the prepared packet is blocked.
    $lineupDecisionHeld = [bool]$WatchState.Ready -and
        ($lineupSourceHeld -or
         [string]$WatchState.StartSit -match '(?i)UNAVAILABLE|REFRESH|EVIDENCE|BLOCK|HOLD|DO NOT ACT' -or
         @('NO CHANGE', 'NOT REVIEWED') -ccontains [string]$WatchState.StartSit)
    $snapshotStatus = if ($lineupSourceHeld) {
        'LINEUP SOURCE NOT VERIFIED'
    }
    elseif ($lineupDecisionHeld) {
        'LINEUP ACTION HELD'
    }
    # BF-1092: a current Dashboard snapshot is not by itself a verified
    # lineup. Green requires the actual deep Start/Sit source outcome.
    elseif ([bool]$WatchState.Ready -and ($sourceChanged -or $sourceNoChange)) {
        'CURRENT SNAPSHOT'
    }
    elseif ([bool]$WatchState.Ready) {
        'LINEUP SOURCE NOT VERIFIED'
    }
    elseif ($evidenceStatus -ceq 'STALE') {
        'EVIDENCE NEEDS REFRESH'
    }
    elseif ($evidenceStatus -ceq 'WEEK_MISMATCH') {
        'SAVED WEEK OUTDATED'
    }
    elseif ($evidenceStatus -ceq 'WEEK_UNVERIFIED') {
        'WEEK NOT VERIFIED'
    }
    elseif ($localReadBusy) {
        'LOCAL DATA BUSY'
    }
    else {
        'WATCH DATA UNAVAILABLE'
    }
    $snapshotClass = if ([bool]$WatchState.Ready -and -not $lineupDecisionHeld -and
        ($sourceChanged -or $sourceNoChange)) { 'good' } else { 'warn' }
    # BF-1086: show actual source observations, never host-clock freshness.
    $lineupSourceTiming = 'Lineup source fetch times not independently verified for this view.'
    if ([bool]$WatchState.Ready -and -not $lineupDecisionHeld -and
        $null -ne $WatchState.PSObject.Properties['StartSitStatusFetchedAtUtc'] -and
        $null -ne $WatchState.PSObject.Properties['StartSitProjectionFetchedAtUtc']) {
        $sourceStatus = [string]$WatchState.StartSitStatusFetchedAtUtc
        $sourceProjection = [string]$WatchState.StartSitProjectionFetchedAtUtc
        $strictUtc = '^20[0-9]{2}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12][0-9]|3[01])T(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](?:\.[0-9]{1,9})?Z$'
        if ($sourceStatus -cmatch $strictUtc -and $sourceProjection -cmatch $strictUtc) {
            $lineupSourceTiming = 'Sleeper status map retrieved ' + $sourceStatus + ' UTC; projections retrieved ' +
                $sourceProjection + ' UTC. These are fetch times, not injury report publication times.'
        }
    }
    # BF-1088: show verified projections without claiming swap-status proof
    # when the actual checked lineup did not propose any change.
    if ([bool]$WatchState.Ready -and -not $lineupDecisionHeld -and
        $null -ne $WatchState.PSObject.Properties['StartSitReviewOutcome'] -and
        [string]$WatchState.StartSitReviewOutcome -ceq 'VERIFIED_NO_CHANGE' -and
        $null -ne $WatchState.PSObject.Properties['StartSitProjectionFetchedAtUtc']) {
        $noChangeProjection = [string]$WatchState.StartSitProjectionFetchedAtUtc
        if ($noChangeProjection -cmatch '^20[0-9]{2}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12][0-9]|3[01])T(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](?:\.[0-9]{1,9})?Z$') {
            $lineupSourceTiming = 'Projections retrieved ' + $noChangeProjection +
                ' UTC. No swap player-status fetch required because no lineup change was proposed. Recheck injuries before kickoff.'
        }
    }
    $lineupSourceTiming = [System.Net.WebUtility]::HtmlEncode($lineupSourceTiming)
    # BF-1099: make proof visible at reading size, rather than relying on
    # a muted 11px timestamp buried beside the watch header. These labels
    # describe fetch evidence only; they never certify injury clearance.
    $sourceProofState = if ($sourceChanged) { 'verified-change' }
        elseif ($sourceNoChange) { 'verified-no-change' }
        else { 'held' }
    $sourceProofLabel = if ($sourceChanged) { 'SOURCED LINEUP CHANGE' }
        elseif ($sourceNoChange) { 'SOURCED NO CHANGE' }
        else { 'SOURCE CHECK REQUIRED' }
    $sourceProofTone = if ($sourceChanged -or $sourceNoChange) { 'good' } else { 'warn' }

    # BF-1100: a generic SOURCE CHECK REQUIRED badge does not tell the
    # manager which verified condition is holding the recommendation. Use
    # only the existing governed watch status: no new player or provider
    # read, no guessed injury reason, and never interpret a page GET as
    # clearance to set or change a Sleeper lineup.
    $sourceReasonCode = 'source-hold'
    $sourceReason = 'The complete current-week Start/Sit source check did not verify. No lineup swap is prepared.'
    $sourceNextHref = '/matchup/autofill'
    $sourceNextLabel = 'Review Start/Sit evidence'
    if ($sourceChanged) {
        $sourceReasonCode = 'verified-change'
        $sourceReason = 'A sourced change is ready for your review, not submission. Confirm player status again before kickoff.'
        if ($null -ne $WatchState.PSObject.Properties['StartSitVerifiedChangeCount'] -and
            [string]$WatchState.StartSitVerifiedChangeCount -cmatch '^[1-9][0-9]?$') {
            $changeCount = [int]$WatchState.StartSitVerifiedChangeCount
            $changeNoun = if ($changeCount -eq 1) { 'change' } else { 'changes' }
            $changeVerb = if ($changeCount -eq 1) { 'needs' } else { 'need' }
            $sourceReason = [string]$changeCount + ' sourced lineup ' + $changeNoun + ' ' + $changeVerb +
                ' your review. Nothing was submitted. Recheck player status before kickoff.'
        }
    }
    elseif ($sourceNoChange) {
        $sourceReasonCode = 'verified-no-change'
        $sourceReason = 'The complete projection frame supports keeping the current lineup. Recheck injury news before kickoff.'
    }
    elseif ($lineupSourceHeld -and
            $null -ne $WatchState.PSObject.Properties['StartSitObservedEvidenceGap'] -and
            [string]$WatchState.StartSitObservedEvidenceGap -ceq 'PARTIAL_WITH_HOLDS') {
        $sourceReasonCode = 'partial-projections-player-holds'
        $sourceReason = 'The matching current-week Start/Sit page reports partial projection coverage and player holds. Review the missing projections and player evidence there. No lineup swap is prepared; source freshness is not independently verified.'
    }
    elseif ($null -ne $WatchState.PSObject.Properties['StartSitReadIssue'] -and
            [string]$WatchState.StartSit -ceq 'BLOCKED - CHECK START/SIT' -and
            [string]$WatchState.StartSitReadIssue -ceq 'TIMEOUT') {
        $sourceReasonCode = 'source-timeout'
        $sourceReason = 'The Start/Sit evidence request exceeded its 12-second safety limit. The lineup is held; review the page when the source responds.'
    }
    elseif ($null -ne $WatchState.PSObject.Properties['StartSitReadIssue'] -and
            [string]$WatchState.StartSit -ceq 'BLOCKED - CHECK START/SIT' -and
            [string]$WatchState.StartSitReadIssue -ceq 'UNAVAILABLE') {
        $sourceReasonCode = 'source-unavailable'
        $sourceReason = 'The Start/Sit evidence page did not return a usable response. Butler is holding the lineup; check the source page before acting.'
    }
    elseif ($evidenceStatus -ceq 'STALE') {
        $sourceReasonCode = 'stale-evidence'
        $sourceReason = 'The Dashboard decision evidence needs an update. Butler can refresh on open only when the existing audited rules permit it.'
        $sourceNextHref = '/'
        $sourceNextLabel = 'Check Dashboard freshness'
    }
    elseif ($evidenceStatus -ceq 'WEEK_MISMATCH') {
        $sourceReasonCode = 'week-mismatch'
        $sourceReason = 'The saved matchup does not match the verified current NFL week. No lineup change is prepared.'
        $sourceNextHref = '/matchup'
        $sourceNextLabel = 'Check weekly matchup'
    }
    elseif ($evidenceStatus -ceq 'WEEK_UNVERIFIED') {
        $sourceReasonCode = 'week-unverified'
        $sourceReason = 'The current NFL week or exact saved matchup could not be independently verified. Lineup actions remain held.'
        $sourceNextHref = '/matchup'
        $sourceNextLabel = 'Check weekly matchup'
    }
    elseif ($localReadBusy) {
        $sourceReasonCode = 'local-data-busy'
        $sourceReason = 'Butler could not read the local snapshot because the manager reader was busy. No lineup change is prepared. Retry Auto-Pilot after the other request finishes.'
        $sourceNextHref = '/autopilot'
        $sourceNextLabel = 'Retry Auto-Pilot'
    }
    elseif (-not [bool]$WatchState.Ready) {
        $sourceReasonCode = 'snapshot-unavailable'
        $sourceReason = 'The current manager snapshot is incomplete. Check the Dashboard before reviewing a lineup change.'
        $sourceNextHref = '/'
        $sourceNextLabel = 'Open Dashboard'
    }
    $sourceReason = [System.Net.WebUtility]::HtmlEncode($sourceReason)

    # BF-1102: only display the season/week proven by this live Matchup.
    # Never infer from the host clock or the previously saved matchup.
    $watchWeekState = 'UNVERIFIED'
    $watchWeekText = 'NFL week not independently verified for this view.'
    if ([bool]$WatchState.Ready -and
        $null -ne $WatchState.PSObject.Properties['VerifiedWeekSeason'] -and
        $null -ne $WatchState.PSObject.Properties['VerifiedWeekNumber']) {
        $weekSeason = [string]$WatchState.VerifiedWeekSeason
        $weekNumber = [string]$WatchState.VerifiedWeekNumber
        if ($weekSeason -cmatch '^20[0-9]{2}$' -and
            $weekNumber -cmatch '^([1-9]|1[0-8])$') {
            $watchWeekState = 'MATCH'
            $watchWeekText = 'NFL ' + $weekSeason + ' season - Week ' + $weekNumber + ' verified against the public Sleeper week.'
        }
    }

    $script:ButlerAutoPilotRenderSubstage = 'RENDER_POLICY'
    $approvalMode = [System.Net.WebUtility]::HtmlEncode([string]$ApprovalPolicy.Mode)
    $startSitPolicy = [System.Net.WebUtility]::HtmlEncode([string]$ApprovalPolicy.StartSit)
    $waiverPolicy = [System.Net.WebUtility]::HtmlEncode([string]$ApprovalPolicy.Waivers)
    $tradePolicy = [System.Net.WebUtility]::HtmlEncode([string]$ApprovalPolicy.Trades)
    $blockers = @($ApprovalPolicy.HardBlockers | ForEach-Object {
        '<li>' + [System.Net.WebUtility]::HtmlEncode([string]$_) + '</li>'
    }) -join ''

    $queueStartSignal = [System.Net.WebUtility]::HtmlEncode([string]$ApprovalQueue.StartSitSignal)
    $queueStartPolicy = [System.Net.WebUtility]::HtmlEncode([string]$ApprovalQueue.StartSitPolicy)
    $queueStartNext = [System.Net.WebUtility]::HtmlEncode([string]$ApprovalQueue.StartSitNext)
    $queueWaiverSignal = [System.Net.WebUtility]::HtmlEncode([string]$ApprovalQueue.WaiverSignal)
    $queueWaiverPolicy = [System.Net.WebUtility]::HtmlEncode([string]$ApprovalQueue.WaiverPolicy)
    $queueWaiverNext = [System.Net.WebUtility]::HtmlEncode([string]$ApprovalQueue.WaiverNext)
    $queueTradePolicy = [System.Net.WebUtility]::HtmlEncode([string]$ApprovalQueue.TradePolicy)
    $queueTradeNext = [System.Net.WebUtility]::HtmlEncode([string]$ApprovalQueue.TradeNext)

    # BF-1028: a prepared Start/Sit review packet is a read-only object derived
    # from the already-governed weekly watch. It never invents a lineup when
    # evidence is incomplete.
    $preparedStartSit = if ($sourceNoChange) {
        [pscustomobject]@{
            State = 'NO ACTION REQUIRED'
            Decision = 'KEEP CURRENT LINEUP'
            Reason = 'No improvement was proven from the complete, recent projection frame. Check injury news before kickoff; no lineup swap is prepared.'
            Policy = [string]$ApprovalPolicy.StartSit
            ReviewHref = '/matchup/autofill'
        }
    }
    elseif (-not $sourceChanged -or $lineupDecisionHeld) {
        [pscustomobject]@{
            State = 'BLOCKED'
            Decision = 'NO RECOMMENDATION PREPARED'
            Reason = [string]$ApprovalQueue.StartSitNext
            Policy = [string]$ApprovalPolicy.StartSit
            ReviewHref = '/matchup/autofill'
        }
    }
    else {
        [pscustomobject]@{
            State = 'READY FOR MANAGER REVIEW'
            Decision = [string]$WatchState.StartSit
            Reason = 'The current Butler lineup signal is ready to review. Manager approval is still required.'
            Policy = [string]$ApprovalPolicy.StartSit
            ReviewHref = '/matchup/autofill'
        }
    }

    $preparedState = [System.Net.WebUtility]::HtmlEncode([string]$preparedStartSit.State)
    $preparedDecision = [System.Net.WebUtility]::HtmlEncode([string]$preparedStartSit.Decision)
    $preparedReason = [System.Net.WebUtility]::HtmlEncode([string]$preparedStartSit.Reason)
    $preparedPolicy = [System.Net.WebUtility]::HtmlEncode([string]$preparedStartSit.Policy)
    $preparedHref = [System.Net.WebUtility]::HtmlEncode([string]$preparedStartSit.ReviewHref)
    $preparedClass = if ($preparedStartSit.State -ceq 'BLOCKED') { 'warn' } else { 'good' }

    $script:ButlerAutoPilotRenderSubstage = 'RENDER_HTML'
    return @"
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Butler - Auto-Pilot</title>
<style>$css
.autopilot-shell{padding:22px}
.autopilot-head{display:flex;align-items:flex-start;justify-content:space-between;gap:18px;padding-bottom:16px;border-bottom:1px solid var(--line)}
.autopilot-head h1{margin:4px 0 6px;font-size:clamp(25px,2.2vw,32px);line-height:1.1}
.autopilot-head p{margin:0;max-width:78ch}
.autopilot-preview{white-space:nowrap}
.autopilot-state{display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin-top:16px;padding:12px 14px;border:1px solid var(--line);border-radius:10px;background:var(--surface-2)}
.autopilot-off{display:inline-flex;padding:6px 10px;border-radius:999px;border:1px solid var(--line);font-size:10px;font-weight:900;letter-spacing:.08em}
.autopilot-state-copy{color:var(--muted);font-size:12px}
.autopilot-watch{margin-top:16px;padding-top:16px;border-top:1px solid var(--line)}
.autopilot-watch-head{display:flex;align-items:center;justify-content:space-between;gap:14px;margin-bottom:10px}
.autopilot-watch-head h2{margin:4px 0 0;font-size:18px}
.autopilot-watch-week{display:block;color:var(--ink);font-size:13px;font-weight:750;line-height:1.45;margin-top:8px;overflow-wrap:anywhere}
.autopilot-watch-week[data-butler-watch-week-state="UNVERIFIED"]{color:var(--muted);font-weight:600}
.autopilot-watch-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:9px}
.autopilot-watch-card{min-width:0;padding:12px;border:1px solid var(--line);border-radius:10px;background:var(--surface)}
.autopilot-watch-card span{display:block;color:var(--muted);font-size:9px;font-weight:900;letter-spacing:.09em;text-transform:uppercase}
.autopilot-watch-card strong{display:block;margin-top:5px;color:var(--ink);font-size:12px;line-height:1.35;overflow-wrap:anywhere}
.autopilot-source-proof{display:flex;justify-content:space-between;align-items:center;gap:14px;flex-wrap:wrap;margin-top:12px;padding:15px;border:1px solid var(--line);border-radius:10px;background:var(--surface)}
.autopilot-source-copy{min-width:220px;flex:1 1 300px}
.autopilot-source-copy .eyebrow{margin-bottom:5px}
.autopilot-source-copy p{color:var(--muted);font-size:12px;line-height:1.55;margin:7px 0 0;overflow-wrap:anywhere}
.autopilot-source-copy .autopilot-source-reason{font-size:13px;line-height:1.55;color:var(--ink);font-weight:600}
.autopilot-source-proof a{display:inline-flex;align-items:center;justify-content:center;min-height:38px;padding:9px 13px;border:1px solid var(--line);border-radius:9px;background:var(--surface-2);color:var(--turf-deep);font-size:12px;font-weight:800;text-decoration:none}
.autopilot-source-proof a:hover,.autopilot-source-proof a:focus-visible{border-color:var(--turf)}
.autopilot-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;margin-top:16px}
.autopilot-card{padding:16px;border:1px solid var(--line);border-radius:12px;background:var(--surface-2)}
.autopilot-card .eyebrow{margin-bottom:5px}
.autopilot-card h3{margin:0 0 7px;font-size:17px}
.autopilot-card p{margin:0;color:var(--muted);line-height:1.55}
.autopilot-card strong{color:var(--ink)}
.autopilot-policy{margin-top:16px;padding-top:16px;border-top:1px solid var(--line)}
.autopilot-policy-head{display:flex;align-items:center;justify-content:space-between;gap:14px;margin-bottom:10px}
.autopilot-policy-head h2{margin:4px 0 0;font-size:18px}
.autopilot-policy-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px}
.autopilot-policy-card{padding:14px;border:1px solid var(--line);border-radius:10px;background:var(--surface)}
.autopilot-policy-card span{display:block;color:var(--muted);font-size:9px;font-weight:900;letter-spacing:.09em;text-transform:uppercase}
.autopilot-policy-card strong{display:block;margin-top:5px;color:var(--ink);font-size:13px}
.autopilot-blockers{margin:10px 0 0;padding:12px 14px;border:1px solid color-mix(in srgb,var(--danger) 45%,var(--line));border-radius:10px;background:color-mix(in srgb,var(--danger) 6%,var(--surface))}
.autopilot-blockers h3{margin:0 0 7px;font-size:13px}
.autopilot-blockers ul{margin:0;padding-left:19px;color:var(--muted);font-size:12px;line-height:1.55}
.autopilot-queue{margin-top:16px;padding-top:16px;border-top:1px solid var(--line)}
.autopilot-queue-head{display:flex;align-items:center;justify-content:space-between;gap:14px;margin-bottom:10px}
.autopilot-queue-head h2{margin:4px 0 0;font-size:18px}
.autopilot-queue-list{display:grid;gap:9px}
.autopilot-queue-row{display:grid;grid-template-columns:140px 130px minmax(0,1fr) auto;gap:12px;align-items:center;padding:12px 14px;border:1px solid var(--line);border-radius:10px;background:var(--surface)}
.autopilot-queue-row span{font-size:9px;font-weight:900;letter-spacing:.08em;color:var(--muted);text-transform:uppercase}
.autopilot-queue-row strong{display:block;margin-top:4px;color:var(--ink);font-size:12px}
.autopilot-queue-next{color:var(--muted);font-size:12px;line-height:1.45}
.autopilot-queue-action{display:inline-flex;align-items:center;justify-content:center;min-height:34px;padding:7px 10px;border:1px solid var(--line);border-radius:8px;background:var(--surface-2);color:var(--turf-deep);text-decoration:none;font-size:11px;font-weight:800;white-space:nowrap}.autopilot-queue-action:hover,.autopilot-queue-action:focus-visible{border-color:var(--turf)}
.autopilot-prepared{margin-top:16px;padding-top:16px;border-top:1px solid var(--line)}
.autopilot-prepared-head{display:flex;align-items:center;justify-content:space-between;gap:14px;margin-bottom:10px}
.autopilot-prepared-head h2{margin:4px 0 0;font-size:18px}
.autopilot-prepared-card{display:grid;grid-template-columns:150px 170px minmax(0,1fr) auto;gap:12px;align-items:center;padding:13px 14px;border:1px solid var(--line);border-radius:10px;background:var(--surface)}
.autopilot-prepared-card span{display:block;color:var(--muted);font-size:9px;font-weight:900;letter-spacing:.08em;text-transform:uppercase}
.autopilot-prepared-card strong{display:block;margin-top:4px;color:var(--ink);font-size:12px}
.autopilot-prepared-reason{color:var(--muted);font-size:12px;line-height:1.45}
.autopilot-actions{display:flex;align-items:center;gap:9px;flex-wrap:wrap;margin-top:18px}
.autopilot-actions a{display:inline-flex;align-items:center;justify-content:center;min-height:38px;padding:9px 13px;border:1px solid var(--line);border-radius:9px;text-decoration:none;font-size:12px;font-weight:800}
.autopilot-actions .primary{background:var(--turf);border-color:var(--turf);color:#111315}
.autopilot-actions .secondary{background:var(--surface-2);color:var(--turf-deep)}
.autopilot-actions a:hover,.autopilot-actions a:focus-visible{border-color:var(--turf)}
.autopilot-boundary{margin-top:14px}
@media(max-width:1000px){.autopilot-watch-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}
@media(max-width:900px){.autopilot-grid,.autopilot-policy-grid{grid-template-columns:1fr}.autopilot-head{display:block}.autopilot-preview{margin-top:10px}.autopilot-queue-row,.autopilot-prepared-card{grid-template-columns:1fr}.autopilot-queue-action{justify-self:start}.autopilot-actions a{flex:1 1 auto}}
@media(max-width:620px){.autopilot-watch-grid{grid-template-columns:1fr}}
</style>
</head>
<body>
<main class="shell">
<div class="top"><div class="brand"><h1>BUTLER</h1><p>We're here to serve you. Less Research. Better Decisions.</p></div><div class="target">$roster</div></div>
<nav class="nav" aria-label="Butler sections"><a href="/">Dashboard</a><a href="/team">My Team</a><a href="/matchup/autofill">Start/Sit Assistant</a><a href="/matchup">Matchup</a><a class="active" href="/autopilot">Auto-Pilot</a><a href="/waivers">Waiver Board</a><a href="/players">Player Search</a><a href="/trade">Trade Analyzer</a><a href="/league">League</a><a href="/compare">Player Compare</a><a href="/history?load=1">History</a></nav>
<section class="panel autopilot-shell">
<div class="autopilot-head"><div><div class="eyebrow">AUTO-PILOT</div><h1>Let Butler watch the week for you</h1><p class="lede">Auto-Pilot is being built as Butler's weekly monitoring and approval layer. This page now reuses Butler's current manager snapshot so you can see what would need attention before any future automation is allowed to act.</p></div><span class="status autopilot-preview">PREVIEW ONLY</span></div>
<div class="autopilot-state"><span class="autopilot-off">AUTOMATION OFF</span><span class="autopilot-state-copy">No background job or Sleeper lineup, waiver, trade, or FAAB write is enabled in this build.</span></div>
<div class="autopilot-watch"><div class="autopilot-watch-head"><div><div class="eyebrow">CURRENT WEEKLY WATCH</div><h2>What Butler sees right now</h2><span class="autopilot-watch-week" role="status" data-butler-watch-week-state="$watchWeekState">$watchWeekText</span></div><span class="status $snapshotClass">$snapshotStatus</span></div><div class="autopilot-watch-grid"><div class="autopilot-watch-card"><span>Attention</span><strong>$attention</strong></div><div class="autopilot-watch-card"><span>Start/Sit</span><strong>$startSit</strong></div><div class="autopilot-watch-card"><span>Waivers</span><strong>$waivers</strong></div><div class="autopilot-watch-card"><span>Roster</span><strong>$roster</strong></div></div></div>
<div class="autopilot-source-proof" role="status" aria-label="Start/Sit source evidence" data-butler-lineup-proof="$sourceProofState" data-butler-lineup-reason="$sourceReasonCode"><div class="autopilot-source-copy"><div class="eyebrow">LINEUP EVIDENCE</div><span class="status $sourceProofTone">$sourceProofLabel</span><p class="autopilot-source-reason">$sourceReason</p><p>$lineupSourceTiming</p><p>Source fetch times are not injury clearance. Check player status again before kickoff.</p></div><a href="$sourceNextHref">$sourceNextLabel</a></div>
<div class="autopilot-grid">
<div class="autopilot-card"><div class="eyebrow">WATCH</div><h3>Start/Sit changes</h3><p><strong>Current input:</strong> $startSit. Butler may prepare a lineup review, but the manager remains the approval boundary.</p><p><a href="/matchup/autofill">Open the sourced Start/Sit recommendations</a></p></div>
<div class="autopilot-card"><div class="eyebrow">WATCH</div><h3>Waiver attention</h3><p><strong>Current input:</strong> $waivers. Butler can surface the current waiver posture without placing or canceling a claim.</p></div>
<div class="autopilot-card"><div class="eyebrow">CONTROL</div><h3>Approval rules</h3><p><strong>Default policy:</strong> $approvalMode. Future automation must obey the rules below before any Sleeper write capability is considered.</p></div>
</div>
<div class="autopilot-policy"><div class="autopilot-policy-head"><div><div class="eyebrow">APPROVAL POLICY</div><h2>What Auto-Pilot is allowed to do</h2></div><span class="status warn">$approvalMode</span></div><div class="autopilot-policy-grid"><div class="autopilot-policy-card"><span>Start/Sit</span><strong>$startSitPolicy</strong></div><div class="autopilot-policy-card"><span>Waivers</span><strong>$waiverPolicy</strong></div><div class="autopilot-policy-card"><span>Trades</span><strong>$tradePolicy</strong></div></div><div class="autopilot-blockers"><h3>Hard blockers always stop action</h3><ul>$blockers</ul></div></div>
<div class="autopilot-queue"><div class="autopilot-queue-head"><div><div class="eyebrow">APPROVAL QUEUE</div><h2>What needs your decision</h2></div><span class="status">NOTHING AUTO-EXECUTES</span></div><div class="autopilot-queue-list"><div class="autopilot-queue-row"><div><span>Start/Sit signal</span><strong>$queueStartSignal</strong></div><div><span>Allowed</span><strong>$queueStartPolicy</strong></div><div class="autopilot-queue-next">$queueStartNext</div><a class="autopilot-queue-action" href="/matchup/autofill">Review lineup</a></div><div class="autopilot-queue-row"><div><span>Waiver signal</span><strong>$queueWaiverSignal</strong></div><div><span>Allowed</span><strong>$queueWaiverPolicy</strong></div><div class="autopilot-queue-next">$queueWaiverNext</div><a class="autopilot-queue-action" href="/waivers">Review waivers</a></div><div class="autopilot-queue-row"><div><span>Trades</span><strong>MANUAL</strong></div><div><span>Allowed</span><strong>$queueTradePolicy</strong></div><div class="autopilot-queue-next">$queueTradeNext</div><a class="autopilot-queue-action" href="/trade">Open Trade Analyzer</a></div></div></div>
<div class="autopilot-prepared"><div class="autopilot-prepared-head"><div><div class="eyebrow">PREPARED START/SIT REVIEW</div><h2>Recommendation packet</h2></div><span class="status $preparedClass">$preparedState</span></div><div class="autopilot-prepared-card"><div><span>Decision</span><strong>$preparedDecision</strong></div><div><span>Policy</span><strong>$preparedPolicy</strong></div><div class="autopilot-prepared-reason">$preparedReason</div><a class="autopilot-queue-action" href="$preparedHref">Open review</a></div></div>
<div class="autopilot-actions"><a class="primary" href="/matchup/autofill">Open Start/Sit Assistant</a><a class="secondary" href="/waivers">Open Waiver Board</a><a class="secondary" href="/team">Review My Team</a><a class="secondary" href="/matchup">View Matchup</a></div>
</section>
<section class="panel boundary autopilot-boundary"><strong>READ ONLY PREVIEW.</strong> The watch snapshot reuses Butler's current read-only manager state. Auto-Pilot does not currently run background monitoring or submit a Sleeper transaction or lineup change.</section>
</main>
</body>
</html>
"@
}

function Send-HttpResponse {
    param(
        [Parameter(Mandatory = $true)]$Stream,
        [Parameter(Mandatory = $true)][int]$StatusCode,
        [Parameter(Mandatory = $true)][string]$StatusText,
        [Parameter(Mandatory = $true)][string]$ContentType,
        [Parameter(Mandatory = $true)][string]$Body,

        [hashtable]$DiagnosticTimings,

        [string]$Bf857Timing,
        [string]$ScriptNonce,
        [string]$FailureLayer = '',
        [ValidateSet('', 'READ_SOURCES', 'RECONCILE_WEEK', 'SOURCED_STARTSIT', 'PREPARE_POLICY', 'APPROVAL_QUEUE', 'RENDER_WATCH', 'RENDER_CSS', 'RENDER_STATE', 'RENDER_POLICY', 'RENDER_HTML', 'WATCH_ACCESSIBILITY', 'BROWSER_REFRESH')]
        [string]$AutoPilotFailureStage = '',
        [switch]$SkipOptionalHtmlPresentation
    )

    $scriptPolicy = ''
    if (-not [string]::IsNullOrWhiteSpace($ScriptNonce)) {
        if ($ScriptNonce -cnotmatch '^[0-9a-f]{64}$') { throw 'Invalid script nonce' }
        $scriptPolicy = "script-src 'nonce-$ScriptNonce'; connect-src 'self'; "
    }
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
    $bf857Header = ''
    if ($bf857CoreTimingEnabled -and -not [string]::IsNullOrWhiteSpace($Bf857Timing)) {
        $bf857Header = 'X-Butler-BF857-Timing: ' + $Bf857Timing + "`r`n"
    }
    # BF-1112: an intentionally tiny, fixed-enum local failure-layer
    # header makes core HTTP errors distinguishable from app worker errors.
    # Never emit exception text, source paths, league/player data or a header
    # supplied by any HTTP client. Success pages expose no failure layer.
    $failureHeader = ''
    if (-not [string]::IsNullOrWhiteSpace($FailureLayer) -and
        @('CORE_HTTP', 'CORE_READ', 'PUBLIC_WORKER') -cnotcontains $FailureLayer) {
        throw 'BF-1112 BLOCKED: invalid local response failure layer.'
    }
    if ($StatusCode -ge 400 -and -not [string]::IsNullOrWhiteSpace($FailureLayer)) {
        $failureHeader = 'X-Butler-Failure-Layer: ' + $FailureLayer + "`r`n"
    }
    # Read-only diagnostic phase uses a fixed enum; never include exceptions,
    # roster details or source identifiers in response headers.
    $autoPilotFailureHeader = ''
    if ($StatusCode -eq 500 -and $FailureLayer -ceq 'PUBLIC_WORKER' -and
        -not [string]::IsNullOrWhiteSpace($AutoPilotFailureStage)) {
        $autoPilotFailureHeader = 'X-Butler-Autopilot-Failure-Stage: ' +
            $AutoPilotFailureStage + "`r`n"
    }
    $headers = "HTTP/1.1 $StatusCode $StatusText`r`n" +
        "Content-Type: $ContentType`r`n" +
        "Content-Length: $($bodyBytes.Length)`r`n" +
        "Cache-Control: no-store`r`n" +
        "X-Content-Type-Options: nosniff`r`n" +
        "Content-Security-Policy: ${scriptPolicy}default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'`r`n" +
        $bf856Header +
        $bf857Header +
        $failureHeader +
        $autoPilotFailureHeader +
        "Connection: close`r`n`r`n"
    $headerBytes = [System.Text.Encoding]::ASCII.GetBytes($headers)
    try {
        $Stream.Write($headerBytes, 0, $headerBytes.Length)
        $Stream.Write($bodyBytes, 0, $bodyBytes.Length)
        $Stream.Flush()
    }
    catch [System.IO.IOException], [System.Net.Sockets.SocketException] {
        # A closed or timed-out client must not terminate its server/worker.
        if ($Stream -is [System.Net.Sockets.NetworkStream]) { return }
        throw
    }
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

# The local read cache must never survive a completed governed evidence-update attempt.
# Generation 0 is the startup state; the counter advances even after partial/failed runs.
function Get-EvidenceRefreshGeneration {
    param([Parameter(Mandatory = $true)][hashtable]$State)
    if (-not $State.ContainsKey('EvidenceGeneration')) { return [long]0 }
    return [long]$State.EvidenceGeneration
}

function Test-EvidenceRefreshInProgress {
    param([Parameter(Mandatory = $true)][hashtable]$State)
    $lockTaken = $false
    try {
        [System.Threading.Monitor]::Enter($State.SyncRoot)
        $lockTaken = $true
        return ($State.ContainsKey('InProgress') -and [bool]$State.InProgress)
    }
    finally {
        if ($lockTaken) { [System.Threading.Monitor]::Exit($State.SyncRoot) }
    }
}

# GET may invoke only the pre-authorized BF-723 exact roster repair.
# Share ownership with the POST refresh runner so two independent local
# evidence writers can never run against Butler's database concurrently.
function Claim-LocalEvidenceRecovery {
    param([Parameter(Mandatory = $true)][hashtable]$State)
    $lockTaken = $false
    try {
        [System.Threading.Monitor]::Enter($State.SyncRoot)
        $lockTaken = $true
        if ($State.ContainsKey('InProgress') -and $State.InProgress) {
            throw 'BF-723 BLOCKED: another governed evidence recovery is already running.'
        }
        $State.InProgress = $true
    }
    finally {
        if ($lockTaken) { [System.Threading.Monitor]::Exit($State.SyncRoot) }
    }
}

function Complete-DecisionRefreshAttempt {
    param([Parameter(Mandatory = $true)][hashtable]$State)

    $lockTaken = $false
    try {
        [System.Threading.Monitor]::Enter($State.SyncRoot)
        $lockTaken = $true
        $State.EvidenceGeneration = (Get-EvidenceRefreshGeneration -State $State) + [long]1
        $State.InProgress = $false
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
        if ($State.ContainsKey('InProgress') -and $State.InProgress) { throw 'Butler evidence update already running.' }
        if ([string]::IsNullOrWhiteSpace($SubmittedToken) -or $SubmittedToken -cne [string]$State.Token) {
            throw 'BF-675 BLOCKED: refresh one-use token is missing, expired, replayed, or invalid.'
        }

        # Invalidate atomically before any Butler write. A concurrent replay sees
        # the replacement token and cannot execute a second refresh.
        $State.Token = New-DecisionRefreshToken
        $State.InProgress = $true
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
            Send-HttpResponse -Stream $stream -StatusCode 200 -StatusText 'OK' -ContentType 'application/json; charset=utf-8' -Body '{"status":"ok","service":"butler-app-shell","core":"ready","tradeLab":"ready","history":"ready","decisionDetail":"ready","decisionRefresh":"manual-post-ready","featureSet":"v04-audited-onopen-freshness-bf1048","readinessContract":"v04-sourced-onopen-bf1098","liveCheckContract":"v04-current-page-proof-bf1110","workerRecoveryContract":"v04-safe-page-recovery-bf1134","localReadContract":"v04-bounded-local-read-bf1146","bind":"127.0.0.1"}'
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
        $refreshOwned = $false
        $refreshSucceeded = $false
        try {
            $formBody = Read-DecisionRefreshFormBody -Reader $reader -Headers $requestHeaders
            $submittedToken = Get-DecisionRefreshSubmittedToken -Body $formBody
            Consume-RefreshToken -State $RefreshState -SubmittedToken $submittedToken
            $refreshOwned = $true

            $resultText = Invoke-DecisionRefreshRunner -LeagueId $LeagueId -RunnerPath $DecisionRefreshRunner
            $responseHtml = Get-DecisionRefreshSuccessHtml -LeagueId $LeagueId -ResultText $resultText
            $refreshSucceeded = $true
        }
        catch {
            $responseHtml = Get-DecisionRefreshFailureHtml -Message $_.Exception.Message
        }
        finally {
            # Release the claim and invalidate all local single-flight read caches
            # BEFORE responding; the browser's redirect must see the updated evidence.
            # Even a failed runner can have completed earlier local evidence stages.
            if ($refreshOwned) { Complete-DecisionRefreshAttempt -State $RefreshState }
        }
        if ($refreshSucceeded) {
            Send-HttpResponse -Stream $stream -StatusCode 200 -StatusText 'OK' -ContentType 'text/html; charset=utf-8' -Body $responseHtml
        }
        else {
            Send-HttpResponse -Stream $stream -StatusCode 400 -StatusText 'Bad Request' -ContentType 'text/html; charset=utf-8' -Body $responseHtml
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

    if ($path -eq '/autopilot') {
        if ($requestTarget -cne '/autopilot') {
            Send-HttpResponse -Stream $stream -StatusCode 400 -StatusText 'Bad Request' -ContentType 'text/plain; charset=utf-8' -Body 'Auto-Pilot accepts no query parameters in this build.'
            return
        }

        # BF-1138: Auto-Pilot's read-only watch is a separate public route.
        # A broken CSS or refresh helper used to throw outside the core GET
        # catch and close the socket with no HTTP response. Prepare the page
        # completely before writing headers and return fixed, safe HTTP 500
        # if any presentation dependency fails. No provider or local retry.
        $autoPilotFailureStage = 'READ_SOURCES'
        try {
        $dashboardHtmlForRefresh = ''
        $dashboardReadBusy = $false
        $matchupReadBusy = $false
        $watchState = [pscustomobject]@{
            Ready = $false
            Attention = 'UNAVAILABLE'
            StartSit = 'UNAVAILABLE'
            Waivers = 'UNAVAILABLE'
            Roster = 'Manager tools'
        }
        try {
            $dashboard = Invoke-ExpensiveReadSingleFlightGet -Port $InnerPort -RequestTarget '/' -League $LeagueId -RefreshState $RefreshState -WaitTimeoutMs 3000 -CoreTimeoutMs 12000
            if ([int]$dashboard.StatusCode -eq 200 -and $dashboard.ContentType -match '^text/html') {
                $dashboardHtmlForRefresh = [string]$dashboard.Body
                $watchState = Get-V04AutoPilotWatchState -DashboardHtml $dashboardHtmlForRefresh
            }
        }
        catch {
            # BF-1144: expose only a fixed local-capacity category, never a
            # raw exception, player ID, league context or private core detail.
            # Other failures remain generic/unverified.
            $dashboardReadBusy = @(
                'BF-693 BLOCKED: finite wait for shared ROOT read expired.',
                'BF-694 BLOCKED: finite wait for companion heavy read capacity expired.'
            ) -ccontains [string]$_.Exception.Message
        }

        # BF-1061: even a locally stale Dashboard can have an old weekly
        # pairing. Check the read-only public week on opening Auto-Pilot;
        # if exact MISMATCH is proven, the common writer claim permits one
        # bounded Butler-local pairing recovery and fresh dual-page audit.
        $matchupHtmlForWeekProof = ''
        try {
            $matchupPage = Invoke-ExpensiveReadSingleFlightGet -Port $InnerPort -RequestTarget '/matchup' -League $LeagueId -RefreshState $RefreshState -WaitTimeoutMs 3000 -CoreTimeoutMs 12000
            if ([int]$matchupPage.StatusCode -eq 200 -and $matchupPage.ContentType -match '^text/html') {
                $matchupHtmlForWeekProof = [string]$matchupPage.Body
            }
        }
        catch {
            # Missing week proof still blocks every recommendation. A known
            # local capacity stop may be shown as a fixed retryable message.
            $matchupReadBusy = @(
                'BF-693 BLOCKED: finite wait for shared MATCHUP read expired.',
                'BF-694 BLOCKED: finite wait for companion heavy read capacity expired.'
            ) -ccontains [string]$_.Exception.Message
        }
        $autoPilotFailureStage = 'RECONCILE_WEEK'
        $resolvedWatch = Resolve-V04AutoPilotOnOpenWeek -InitialWatchState $watchState -DashboardHtml $dashboardHtmlForRefresh -MatchupHtml $matchupHtmlForWeekProof -InnerPort $InnerPort -League $LeagueId -Root $RepoRoot -RefreshState $RefreshState
        $watchState = $resolvedWatch.WatchState
        # Do not overwrite stronger audited STALE or WEEK_MISMATCH proof.
        # Incomplete source data remains non-actionable; this only explains
        # why the read-only preview could not fill its cards this visit.
        $watchEvidence = if ($null -ne $watchState.PSObject.Properties['EvidenceStatus']) {
            [string]$watchState.EvidenceStatus
        } else { 'UNVERIFIED' }
        if (-not [bool]$watchState.Ready -and $watchEvidence -ceq 'UNVERIFIED' -and
            ($dashboardReadBusy -or $matchupReadBusy)) {
            $watchState | Add-Member -NotePropertyName LocalReadIssue -NotePropertyValue 'BUSY' -Force
        }
        $dashboardHtmlForRefresh = [string]$resolvedWatch.DashboardHtml

        $autoPilotFailureStage = 'SOURCED_STARTSIT'
        # BF-1074: dashboard/matchup source week alone cannot prepare a
        # player change. Read the actual Start/Sit page once only when the
        # weekly watch is otherwise ready. All failures withhold ONLY the
        # Start/Sit proposal, leaving independently checked waivers intact.
        if ([bool]$watchState.Ready) {
            $startSitHtmlForReview = ''
            $startSitReadIssue = ''
            try {
                # BF-1076: this optional deeper review must not hold the
                # Auto-Pilot page for the 180-second core default during a
                # source outage. Timeouts block only the lineup proposal.
                $startSitPage = Invoke-AppCoreGet -Port $InnerPort -RequestTarget '/matchup/autofill' -TimeoutMs 12000
                if ([int]$startSitPage.StatusCode -eq 200 -and
                    $startSitPage.ContentType -match '^text/html' -and
                    -not [string]::IsNullOrWhiteSpace([string]$startSitPage.Body)) {
                    $startSitHtmlForReview = [string]$startSitPage.Body
                }
                else {
                    $startSitReadIssue = 'UNAVAILABLE'
                }
            }
            catch [System.Net.WebException] {
                # BF-1101: only this exact WebException status proves a
                # deadline expiry. Never leak the exception, player names,
                # league context or raw HTTP body into Auto-Pilot.
                $startSitReadIssue = if ($_.Exception.Status -eq
                    [System.Net.WebExceptionStatus]::Timeout) { 'TIMEOUT' } else { 'UNAVAILABLE' }
            }
            catch {
                $startSitReadIssue = 'UNAVAILABLE'
            }
            $watchState = Limit-V04AutoPilotToSourcedStartSit -WatchState $watchState `
                -MatchupHtml ([string]$resolvedWatch.MatchupHtml) -StartSitHtml $startSitHtmlForReview
            if ($startSitReadIssue -ne '' -and
                [string]$watchState.StartSit -ceq 'BLOCKED - CHECK START/SIT') {
                $watchState | Add-Member -NotePropertyName StartSitReadIssue -NotePropertyValue $startSitReadIssue -Force
            }
        }

        $autoPilotFailureStage = 'PREPARE_POLICY'
        $approvalPolicy = Get-V04AutoPilotApprovalPolicy
        $autoPilotFailureStage = 'APPROVAL_QUEUE'
        $approvalQueue = Get-V04AutoPilotApprovalQueue -WatchState $watchState -ApprovalPolicy $approvalPolicy
        $autoPilotFailureStage = 'RENDER_WATCH'
        $script:ButlerAutoPilotRenderSubstage = 'UNSET'
        $html = Get-V04AutoPilotHtml -WatchState $watchState -ApprovalPolicy $approvalPolicy -ApprovalQueue $approvalQueue
        $autoPilotFailureStage = 'WATCH_ACCESSIBILITY'
        $html = Add-ButlerAccessibility -Html $html
        $autoPilotFailureStage = 'BROWSER_REFRESH'
        $autoRefresh = Add-AutomaticAutoPilotRefresh -Html $html -RequestTarget $requestTarget -DashboardHtml $dashboardHtmlForRefresh -Token (Get-RefreshTokenSnapshot -State $RefreshState)
        }
        catch {
            # Fixed phases identify failing local renderer stage without
            # exposing exception, roster, league, file paths, or player text.
            if ($autoPilotFailureStage -ceq 'RENDER_WATCH' -and
                @('RENDER_CSS', 'RENDER_STATE', 'RENDER_POLICY', 'RENDER_HTML') -ccontains
                    [string]$script:ButlerAutoPilotRenderSubstage) {
                $autoPilotFailureStage = [string]$script:ButlerAutoPilotRenderSubstage
            }
            # No headers have been written. Never reflect the raw exception,
            # player/league context, local paths or a partial approval queue.
            $blocked = Get-ButlerMinimalBlockedPageHtml -Title 'Auto-Pilot unavailable' -PrimaryHref '/autopilot' -PrimaryLabel 'Try Auto-Pilot again'
            Send-HttpResponse -Stream $stream -StatusCode 500 -StatusText 'Internal Server Error' -ContentType 'text/html; charset=utf-8' -Body $blocked -FailureLayer 'PUBLIC_WORKER' -AutoPilotFailureStage $autoPilotFailureStage -SkipOptionalHtmlPresentation
            return
        }
        Send-HttpResponse -Stream $stream -StatusCode 200 -StatusText 'OK' -ContentType 'text/html; charset=utf-8' -Body $autoRefresh.Html -ScriptNonce $autoRefresh.Nonce
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
            $errorHtml = Get-ButlerBlockedPageHtml -Title 'Decision History blocked' -Message 'Butler could not display this history request. Return to Decision History and retry after current evidence is available.' -Active 'history' -PrimaryHref '/history?load=1' -PrimaryLabel 'Return to Decision History'
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
            $errorHtml = Get-ButlerBlockedPageHtml -Title 'Trade Analyzer blocked' -Message 'Butler could not finish this trade review. Reopen the Trade Analyzer and check the selected assets before trying again. No Sleeper trade was submitted.' -Active 'trade' -PrimaryHref '/trade' -PrimaryLabel 'Return to Trade Analyzer'
            Send-HttpResponse -Stream $stream -StatusCode 400 -StatusText 'Bad Request' -ContentType 'text/html; charset=utf-8' -Body $errorHtml
        }
        return
    }

    # Core routing failures and presentation failures require separate,
    # private-data-free triage on the real Windows readiness run.
    $failurePhase = 'CORE_READ'
    try {
        $proxied = if ($requestTarget -ceq '/team') {
            Invoke-TeamSingleFlightGet -Port $InnerPort -RequestTarget $requestTarget -League $LeagueId -RefreshState $RefreshState
        }
        elseif ($requestTarget -ceq '/' -or $requestTarget -ceq '/waivers' -or $requestTarget -ceq '/league' -or $requestTarget -ceq '/matchup') {
            Invoke-ExpensiveReadSingleFlightGet -Port $InnerPort -RequestTarget $requestTarget -League $LeagueId -RefreshState $RefreshState
        }
        elseif ($requestTarget -ceq '/matchup/autofill') {
            # BF-1031: every Start/Sit page load goes directly to the current
            # read-only recommendation/evidence path; it is not served from the
            # manager-page single-flight cache.
            Invoke-AppCoreGet -Port $InnerPort -RequestTarget $requestTarget
        }
        else {
            Invoke-AppCoreGet -Port $InnerPort -RequestTarget $requestTarget
        }
        $failurePhase = 'PUBLIC_WORKER'
        $body = $proxied.Body

        # BF-1037/continued: My Team and Start/Sit may encounter the same
        # exact proven BF-610 roster drift. The BF-723 script rechecks its own
        # strict authorization before any Butler-local evidence write; it never
        # submits a Sleeper transaction.
        if (Test-StartSitRosterDriftResponse -RequestTarget $requestTarget -Body ([string]$body) -StatusCode ([int]$proxied.StatusCode)) {
            try {
                Claim-LocalEvidenceRecovery -State $RefreshState
                try {
                    Invoke-StartSitRosterDriftAutoRecovery -Root $RepoRoot
                }
                finally {
                    # Even partial BF-723 evidence recovery may have written to
                    # Butler's database. Release the shared write claim and
                    # invalidate old warm reads BEFORE the read-only retry.
                    Complete-DecisionRefreshAttempt -State $RefreshState
                }
                $proxied = Invoke-AppCoreGet -Port $InnerPort -RequestTarget $requestTarget
                $body = $proxied.Body
            }
            catch {
                # Preserve the original fail-closed page with its manual path if
                # exact recovery is unavailable or another refresh owns writes.
            }
        }

        # BF-1060: when an ordinary Matchup/Start-Sit page proves a
        # precise public-week mismatch, try one guarded local evidence sync
        # *before returning the page*, without requiring a Refresh click.
        # The Java writer rechecks both live league and public NFL week
        # before DB writes. Failures keep the original advice-free hold.
        if (Test-AutomaticWeekRecoveryCandidate -RequestTarget $requestTarget -Body ([string]$body) -StatusCode ([int]$proxied.StatusCode)) {
            if (Claim-AutomaticWeekRecovery -State $RefreshState) {
                try {
                    try {
                        Invoke-AutomaticWeekRecovery -Root $RepoRoot -League $LeagueId
                        $retried = Invoke-AppCoreGet -Port $InnerPort -RequestTarget $requestTarget
                        if ([int]$retried.StatusCode -eq 200 -and
                            $retried.ContentType -match '^text/html') {
                            $proxied = $retried
                            $body = $retried.Body
                        }
                    }
                    catch {
                        # A failed/partial local repair never returns an
                        # invented CURRENT badge or stale lineup action.
                    }
                }
                finally {
                    # Invalidate all four five-second read caches even after
                    # a partially completed guarded local evidence write.
                    Complete-DecisionRefreshAttempt -State $RefreshState
                }
            }
        }

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
        if ([int]$proxied.StatusCode -ge 200 -and [int]$proxied.StatusCode -lt 300 -and
            $proxied.ContentType -match '^text/html' -and
            $body -match '<nav class="nav" aria-label="Butler sections">') {
            $body = ConvertTo-V04StartSitRouteHtml -Html $body -RequestTarget $requestTarget
            $script:ButlerPublicRequestTarget = $requestTarget
            try {
                $body = Add-AppNavigation -Html $body
            }
            finally {
                $script:ButlerPublicRequestTarget = ''
            }
            $body = Add-DecisionRefreshControl -Html $body -RequestTarget $requestTarget
        }
        if ($null -ne $bf856Timings) {
            $bf856Timings.navigation_ms = Get-Bf856ElapsedMs -StartedTicks $bf856NavigationStarted
        }

        $bf857Timing = if ($bf857CoreTimingEnabled -and
            $requestTarget -ceq '/' -and
            $null -ne $proxied.PSObject.Properties['Bf857Timing']) {
            [string]$proxied.Bf857Timing
        } else {
            $null
        }
        $autoRefresh = @{ Html = $body; Nonce = '' }
        if ($proxied.StatusCode -eq 200 -and $requestTarget -ceq '/') {
            if ($body.Contains('<aside class="manager-playbook" aria-label="Butler sections">')) {
                $body = Add-DecisionRefreshControl -Html $body -RequestTarget $requestTarget
            }
            $autoRefresh = Add-AutomaticDashboardRefresh -Html $body -RequestTarget $requestTarget -Token (Get-RefreshTokenSnapshot -State $RefreshState)
        }
        elseif ($proxied.StatusCode -eq 200 -and $requestTarget -ceq '/waivers') {
            $autoRefresh = Add-AutomaticWaiverRefresh -Html $body -RequestTarget $requestTarget -Token (Get-RefreshTokenSnapshot -State $RefreshState)
        }
        elseif ($proxied.StatusCode -eq 200 -and
                $requestTarget -ceq '/matchup/autofill' -and
                (Test-AutomaticStartSitRefreshCandidate -Html ([string]$body) -RequestTarget $requestTarget)) {
            # BF-1104: only a uniquely blocked Start/Sit source causes this
            # optional, bounded Dashboard check. A verified Start/Sit page
            # never gets a second expensive read. The same Dashboard proof,
            # one-use token, server preflight and shared audit cooldown used
            # by Auto-Pilot gate the local evidence update.
            try {
                $refreshDashboard = Invoke-AppCoreGet -Port $InnerPort -RequestTarget '/' -TimeoutMs 12000
                if ([int]$refreshDashboard.StatusCode -eq 200 -and
                    $refreshDashboard.ContentType -match '^text/html') {
                    $autoRefresh = Add-AutomaticStartSitRefresh -Html ([string]$body) -RequestTarget $requestTarget -DashboardHtml ([string]$refreshDashboard.Body) -Token (Get-RefreshTokenSnapshot -State $RefreshState)
                }
            }
            catch {
                # A failed optional Dashboard read must not mask the
                # actual source-blocked Start/Sit page or attempt a write.
            }
        }
        Send-HttpResponse -Stream $stream -StatusCode $proxied.StatusCode -StatusText $proxied.StatusText -ContentType $proxied.ContentType -Body $autoRefresh.Html -DiagnosticTimings $bf856Timings -Bf857Timing $bf857Timing -ScriptNonce $autoRefresh.Nonce -FailureLayer 'CORE_HTTP'
    }
    catch {
        # BF-1113: raw exception messages can embed a private league ID,
        # full local path, player name or token. The diagnostic already
        # reports a safe failure-layer enum; show a useful retry screen
        # instead of reflecting exception details into browser HTML.
        $failure = Get-ButlerManagerFailureSpec -RequestTarget $requestTarget -FailurePhase $failurePhase
        $bf1129MinimalFallback = $false
        try {
            $errorHtml = Get-ButlerBlockedPageHtml -Title $failure.Title -Message $failure.Message -Active $failure.Active -PrimaryHref $failure.PrimaryHref -PrimaryLabel $failure.PrimaryLabel -SecondaryHref $failure.SecondaryHref -SecondaryLabel $failure.SecondaryLabel
        }
        catch {
            # BF-1117/BF-1129: optional CSS/navigation already failed.
            # This independent, accessible error page must not be sent back
            # through optional HTML presentation, which could also fail.
            $errorHtml = Get-ButlerMinimalBlockedPageHtml -Title $failure.Title -PrimaryHref $failure.PrimaryHref -PrimaryLabel $failure.PrimaryLabel
            $bf1129MinimalFallback = $true
        }
        Send-HttpResponse -Stream $stream -StatusCode 500 -StatusText 'Internal Server Error' -ContentType 'text/html; charset=utf-8' -Body $errorHtml -FailureLayer $failurePhase -SkipOptionalHtmlPresentation:$bf1129MinimalFallback
    }
}
finally {
    if ($null -ne $reader) {
        try { $reader.Dispose() } catch {}
    }
    try { $Client.Close() } catch {}
    Pop-Location
}
