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
        [AllowNull()][string]$Body
    )

    # Only exact My Team and Start/Sit roster-drift messages authorize the
    # already-governed BF-723 local evidence repair. Other routes stay read-only.
    if (@('/matchup/autofill', '/team') -cnotcontains $RequestTarget -or
        [string]::IsNullOrWhiteSpace($Body)) {
        return $false
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
                    $updatedDashboard = Invoke-AppCoreGet -Port $InnerPort -RequestTarget '/'
                    $updatedMatchup = Invoke-AppCoreGet -Port $InnerPort -RequestTarget '/matchup'
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
    }
    finally {
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
        [Parameter(Mandatory = $true)][hashtable]$RefreshState
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
<p>No Butler or Sleeper write was executed.</p>
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
        # Require the actual BF-1067 sourced-change message. A held swap,
        # unverified timestamp, no-change decision, or generic Dashboard
        # signal cannot authorize an Auto-Pilot prepared packet.
        if ($message.IndexOf('proposed lineup changes have exact player-status checks from Sleeper at the recorded fetch time', [StringComparison]::Ordinal) -lt 0 -or
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

function Get-V04AutoPilotHtml {
    param(
        [Parameter(Mandatory = $true)]$WatchState,
        [Parameter(Mandatory = $true)]$ApprovalPolicy,
        [Parameter(Mandatory = $true)]$ApprovalQueue
    )

    $css = Get-AppCss
    $attention = [System.Net.WebUtility]::HtmlEncode([string]$WatchState.Attention)
    $startSit = [System.Net.WebUtility]::HtmlEncode([string]$WatchState.StartSit)
    $waivers = [System.Net.WebUtility]::HtmlEncode([string]$WatchState.Waivers)
    $roster = [System.Net.WebUtility]::HtmlEncode([string]$WatchState.Roster)
    $evidenceStatus = if ($null -ne $WatchState.PSObject.Properties['EvidenceStatus']) {
        [string]$WatchState.EvidenceStatus
    } else { 'UNVERIFIED' }
    $snapshotStatus = if ([bool]$WatchState.Ready) {
        'CURRENT SNAPSHOT'
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
    else {
        'WATCH DATA UNAVAILABLE'
    }
    $snapshotClass = if ([bool]$WatchState.Ready) { 'good' } else { 'warn' }

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
    $preparedStartSit = if (-not [bool]$WatchState.Ready -or
                            [string]$WatchState.StartSit -match '(?i)UNAVAILABLE|REFRESH|EVIDENCE|BLOCK|HOLD') {
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
.autopilot-watch-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:9px}
.autopilot-watch-card{min-width:0;padding:12px;border:1px solid var(--line);border-radius:10px;background:var(--surface)}
.autopilot-watch-card span{display:block;color:var(--muted);font-size:9px;font-weight:900;letter-spacing:.09em;text-transform:uppercase}
.autopilot-watch-card strong{display:block;margin-top:5px;color:var(--ink);font-size:12px;line-height:1.35;overflow-wrap:anywhere}
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
<div class="autopilot-watch"><div class="autopilot-watch-head"><div><div class="eyebrow">CURRENT WEEKLY WATCH</div><h2>What Butler sees right now</h2></div><span class="status $snapshotClass">$snapshotStatus</span></div><div class="autopilot-watch-grid"><div class="autopilot-watch-card"><span>Attention</span><strong>$attention</strong></div><div class="autopilot-watch-card"><span>Start/Sit</span><strong>$startSit</strong></div><div class="autopilot-watch-card"><span>Waivers</span><strong>$waivers</strong></div><div class="autopilot-watch-card"><span>Roster</span><strong>$roster</strong></div></div></div>
<div class="autopilot-grid">
<div class="autopilot-card"><div class="eyebrow">WATCH</div><h3>Start/Sit changes</h3><p><strong>Current input:</strong> $startSit. Butler may prepare a lineup review, but the manager remains the approval boundary.</p></div>
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
        [string]$ScriptNonce
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
    $headers = "HTTP/1.1 $StatusCode $StatusText`r`n" +
        "Content-Type: $ContentType`r`n" +
        "Content-Length: $($bodyBytes.Length)`r`n" +
        "Cache-Control: no-store`r`n" +
        "X-Content-Type-Options: nosniff`r`n" +
        "Content-Security-Policy: ${scriptPolicy}default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'`r`n" +
        $bf856Header +
        $bf857Header +
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
            Send-HttpResponse -Stream $stream -StatusCode 200 -StatusText 'OK' -ContentType 'application/json; charset=utf-8' -Body '{"status":"ok","service":"butler-app-shell","core":"ready","tradeLab":"ready","history":"ready","decisionDetail":"ready","decisionRefresh":"manual-post-ready","featureSet":"v04-audited-onopen-freshness-bf1048","bind":"127.0.0.1"}'
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

        $dashboardHtmlForRefresh = ''
        $watchState = [pscustomobject]@{
            Ready = $false
            Attention = 'UNAVAILABLE'
            StartSit = 'UNAVAILABLE'
            Waivers = 'UNAVAILABLE'
            Roster = 'Manager tools'
        }
        try {
            $dashboard = Invoke-ExpensiveReadSingleFlightGet -Port $InnerPort -RequestTarget '/' -League $LeagueId -RefreshState $RefreshState
            if ([int]$dashboard.StatusCode -eq 200 -and $dashboard.ContentType -match '^text/html') {
                $dashboardHtmlForRefresh = [string]$dashboard.Body
                $watchState = Get-V04AutoPilotWatchState -DashboardHtml $dashboardHtmlForRefresh
            }
        }
        catch {
            # Auto-Pilot is a read-only preview; a snapshot read failure must
            # remain visible as unavailable instead of crashing or guessing.
        }

        # BF-1061: even a locally stale Dashboard can have an old weekly
        # pairing. Check the read-only public week on opening Auto-Pilot;
        # if exact MISMATCH is proven, the common writer claim permits one
        # bounded Butler-local pairing recovery and fresh dual-page audit.
        $matchupHtmlForWeekProof = ''
        try {
            $matchupPage = Invoke-ExpensiveReadSingleFlightGet -Port $InnerPort -RequestTarget '/matchup' -League $LeagueId -RefreshState $RefreshState
            if ([int]$matchupPage.StatusCode -eq 200 -and $matchupPage.ContentType -match '^text/html') {
                $matchupHtmlForWeekProof = [string]$matchupPage.Body
            }
        }
        catch {
            # No league write or fallback to a guessed current week.
        }
        $resolvedWatch = Resolve-V04AutoPilotOnOpenWeek -InitialWatchState $watchState -DashboardHtml $dashboardHtmlForRefresh -MatchupHtml $matchupHtmlForWeekProof -InnerPort $InnerPort -League $LeagueId -Root $RepoRoot -RefreshState $RefreshState
        $watchState = $resolvedWatch.WatchState
        $dashboardHtmlForRefresh = [string]$resolvedWatch.DashboardHtml

        # BF-1074: dashboard/matchup source week alone cannot prepare a
        # player change. Read the actual Start/Sit page once only when the
        # weekly watch is otherwise ready. All failures withhold ONLY the
        # Start/Sit proposal, leaving independently checked waivers intact.
        if ([bool]$watchState.Ready) {
            $startSitHtmlForReview = ''
            try {
                $startSitPage = Invoke-AppCoreGet -Port $InnerPort -RequestTarget '/matchup/autofill'
                if ([int]$startSitPage.StatusCode -eq 200 -and
                    $startSitPage.ContentType -match '^text/html') {
                    $startSitHtmlForReview = [string]$startSitPage.Body
                }
            }
            catch { }
            $watchState = Limit-V04AutoPilotToSourcedStartSit -WatchState $watchState `
                -MatchupHtml ([string]$resolvedWatch.MatchupHtml) -StartSitHtml $startSitHtmlForReview
        }

        $approvalPolicy = Get-V04AutoPilotApprovalPolicy
        $approvalQueue = Get-V04AutoPilotApprovalQueue -WatchState $watchState -ApprovalPolicy $approvalPolicy
        $html = Get-V04AutoPilotHtml -WatchState $watchState -ApprovalPolicy $approvalPolicy -ApprovalQueue $approvalQueue
        $html = Add-ButlerAccessibility -Html $html
        $autoRefresh = Add-AutomaticAutoPilotRefresh -Html $html -RequestTarget $requestTarget -DashboardHtml $dashboardHtmlForRefresh -Token (Get-RefreshTokenSnapshot -State $RefreshState)
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
            $errorHtml = Get-ButlerBlockedPageHtml -Title 'Decision History blocked' -Message $_.Exception.Message -Active 'history' -PrimaryHref '/history?load=1' -PrimaryLabel 'Return to Decision History'
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
            $errorHtml = Get-ButlerBlockedPageHtml -Title 'Trade Analyzer blocked' -Message $_.Exception.Message -Active 'trade' -PrimaryHref '/trade' -PrimaryLabel 'Return to Trade Analyzer'
            Send-HttpResponse -Stream $stream -StatusCode 400 -StatusText 'Bad Request' -ContentType 'text/html; charset=utf-8' -Body $errorHtml
        }
        return
    }

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
        $body = $proxied.Body

        # BF-1037/continued: My Team and Start/Sit may encounter the same
        # exact proven BF-610 roster drift. The BF-723 script rechecks its own
        # strict authorization before any Butler-local evidence write; it never
        # submits a Sleeper transaction.
        if (Test-StartSitRosterDriftResponse -RequestTarget $requestTarget -Body ([string]$body)) {
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
            $autoRefresh = Add-AutomaticDashboardRefresh -Html $body -RequestTarget $requestTarget -Token (Get-RefreshTokenSnapshot -State $RefreshState)
        }
        elseif ($proxied.StatusCode -eq 200 -and $requestTarget -ceq '/waivers') {
            $autoRefresh = Add-AutomaticWaiverRefresh -Html $body -RequestTarget $requestTarget -Token (Get-RefreshTokenSnapshot -State $RefreshState)
        }
        Send-HttpResponse -Stream $stream -StatusCode $proxied.StatusCode -StatusText $proxied.StatusText -ContentType $proxied.ContentType -Body $autoRefresh.Html -DiagnosticTimings $bf856Timings -Bf857Timing $bf857Timing -ScriptNonce $autoRefresh.Nonce
    }
    catch {
        $errorHtml = Get-ButlerBlockedPageHtml -Title 'Butler app blocked' -Message $_.Exception.Message -Active 'dashboard' -PrimaryHref '/' -PrimaryLabel 'Return to Dashboard' -SecondaryHref '/team' -SecondaryLabel 'Review My Team'
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
