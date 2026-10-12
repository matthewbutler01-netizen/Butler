param(
    [ValidateRange(0, 65535)][int]$Port = 0,
    [ValidateRange(5, 180)][int]$TimeoutSeconds = 120,
    [switch]$CheckSleeperWeek,
    [switch]$RequireReady
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Use the identical unique-field parser and lineage allowlist as the running
# refresh gate. This local diagnostic reads HTML only and never invokes its
# POST runner, changes a league, or initiates a Sleeper transaction.
. (Join-Path $PSScriptRoot 'butler-decision-refresh.ps1')

# Loopback-only diagnostic. Never POST, submit to Sleeper, or output
# private player/league information. GET can trigger existing narrowly
# governed Butler-local BF-723 recovery if exact roster drift is proven.
function Invoke-ButlerLocalGet {
    param([int]$SelectedPort, [string]$Route, [int]$Seconds)
    if ($SelectedPort -lt 1024 -or $SelectedPort -gt 65535 -or
        @('/health', '/', '/team', '/waivers', '/league', '/matchup', '/matchup/autofill', '/autopilot') -cnotcontains $Route) {
        throw 'BF-1040 BLOCKED: invalid diagnostic port or route.'
    }
    $uri = 'http://127.0.0.1:' + $SelectedPort + $Route
    $request = [System.Net.HttpWebRequest]::Create($uri)
    $request.Method = 'GET'
    $request.Proxy = $null
    $request.AllowAutoRedirect = $false
    $request.Timeout = $Seconds * 1000
    $request.ReadWriteTimeout = $Seconds * 1000
    $response = $null
    $reader = $null
    try {
        $response = $request.GetResponse()
        $reader = [System.IO.StreamReader]::new($response.GetResponseStream(), [System.Text.Encoding]::UTF8)
        return [pscustomobject]@{
            Status = [int]$response.StatusCode
            Type = [string]$response.ContentType
            Cache = [string]$response.Headers['Cache-Control']
            Csp = [string]$response.Headers['Content-Security-Policy']
            Body = $reader.ReadToEnd()
        }
    }
    finally {
        if ($null -ne $reader) { $reader.Dispose() }
        if ($null -ne $response) { $response.Close() }
    }
}

# BF-1107: status-only transport triage for one-click readiness. Never copy
# WebException.Message, Response.Body, target URL, player or league data into
# the user-shareable diagnostic. An HTTP 500 must not look like a timeout.
function Get-ButlerLocalGetFailureCategory {
    param([AllowNull()]$Failure)
    $exception = if ($Failure -is [System.Management.Automation.ErrorRecord]) {
        $Failure.Exception
    } else { $Failure }
    while ($null -ne $exception -and
           $exception -isnot [System.Net.WebException]) {
        if ($exception -isnot [System.Exception]) { return 'GET ERROR' }
        $exception = $exception.InnerException
    }
    if ($null -eq $exception) { return 'GET ERROR' }
    if ($exception.Status -eq [System.Net.WebExceptionStatus]::ProtocolError) {
        $http = $exception.Response -as [System.Net.HttpWebResponse]
        try {
            if ($null -ne $http) {
                $code = [int]$http.StatusCode
                if ($code -ge 400 -and $code -le 599) {
                    $category = 'HTTP ' + $code
                    # BF-1112: an allowlisted header emitted by the current
                    # localhost app narrows a failed GET to its handling
                    # layer. Do not trust arbitrary HTTP header values or
                    # include server text, error messages or private evidence.
                    $layer = [string]$http.Headers['X-Butler-Failure-Layer']
                    if (@('CORE_HTTP', 'CORE_READ', 'PUBLIC_WORKER') -ccontains $layer) {
                        $category += ' ' + $layer
                    }
                    return $category
                }
            }
            return 'HTTP ERROR'
        }
        finally {
            if ($null -ne $exception.Response) { $exception.Response.Close() }
        }
    }
    switch ([string]$exception.Status) {
        'Timeout' { return 'GET TIMEOUT' }
        'ConnectFailure' { return 'CONNECT FAILED' }
        'NameResolutionFailure' { return 'CONNECT FAILED' }
        'ConnectionClosed' { return 'CONNECTION CLOSED' }
        'ReceiveFailure' { return 'RECEIVE FAILED' }
        'SendFailure' { return 'SEND FAILED' }
        'RequestCanceled' { return 'GET CANCELED' }
        'ServerProtocolViolation' { return 'BAD HTTP' }
        default { return 'GET NETWORK' }
    }
}

# BF-1053: optional direct read-only check against Sleeper's public NFL
# current-week endpoint. This request does not include Butler's league ID,
# owner identity, tokens, roster, or any other private context.
function Get-ButlerPublicNflState {
    $request = [System.Net.HttpWebRequest]::Create('https://api.sleeper.app/v1/state/nfl')
    $request.Method = 'GET'
    $request.Proxy = $null
    $request.AllowAutoRedirect = $false
    $request.Timeout = 5000
    $request.ReadWriteTimeout = 5000
    $response = $null
    $reader = $null
    try {
        $response = $request.GetResponse()
        if ([int]$response.StatusCode -ne 200 -or
            [string]$response.ContentType -notmatch '^application/json') {
            throw 'BF-1053 BLOCKED: public NFL state was not a JSON success.'
        }
        $reader = [System.IO.StreamReader]::new($response.GetResponseStream(), [System.Text.Encoding]::UTF8)
        $buffer = New-Object char[] 8193
        $length = $reader.ReadBlock($buffer, 0, $buffer.Length)
        if ($length -le 0 -or $length -gt 8192) {
            throw 'BF-1053 BLOCKED: public NFL state exceeded the small expected response size.'
        }
        return [string]::new($buffer, 0, $length)
    }
    finally {
        if ($null -ne $reader) { $reader.Dispose() }
        if ($null -ne $response) { $response.Close() }
    }
}

function Test-ButlerSleeperWeekMatch {
    param(
        [string]$MatchupHtml,
        [string]$PublicNflState
    )
    $result = [ordered]@{ Status = 'WARN'; Evidence = 'WEEK UNVERIFIED' }
    try {
        $state = ConvertFrom-Json -InputObject $PublicNflState -ErrorAction Stop
        $season = [string]$state.season
        $type = [string]$state.season_type
        $weekText = [string]$state.week
        if ($season -cnotmatch '^20[0-9]{2}$' -or
            $type -cne 'regular' -or
            $weekText -cnotmatch '^(?:[1-9]|1[0-8])$') {
            return [pscustomobject]$result
        }
        # BF-1077: external NFL state cannot certify a saved matchup merely
        # because a plausible Week label happens to match. Require the exact
        # independently verified source-week metadata and rendered opponent
        # frame to agree before comparing that frame with Sleeper.
        if ([regex]::Matches($MatchupHtml, 'data-butler-week-state=').Count -ne 1 -or
            [regex]::Matches($MatchupHtml, 'data-butler-week-season=').Count -ne 1 -or
            [regex]::Matches($MatchupHtml, 'data-butler-week-number=').Count -ne 1 -or
            [regex]::Matches($MatchupHtml, 'data-butler-matchup-season=').Count -ne 1) {
            return [pscustomobject]$result
        }
        $sourceWeek = [regex]::Matches($MatchupHtml,
            '(?is)<section\b[^>]*class="[^"]*\bbutler-live-week-status\b[^"]*"[^>]*data-butler-week-state="MATCH"[^>]*data-butler-week-season="(?<season>20[0-9]{2})"[^>]*data-butler-week-number="(?<week>[1-9]|1[0-8])"[^>]*>')
        $savedWeek = [regex]::Matches($MatchupHtml,
            '(?is)<div\b[^>]*class="target"[^>]*data-butler-matchup-season="(?<season>20[0-9]{2})"[^>]*>[^<]*\bWeek\s+(?<week>[1-9]|1[0-8])\s*</div>')
        if ($sourceWeek.Count -ne 1 -or $savedWeek.Count -ne 1 -or
            $sourceWeek[0].Groups['season'].Value -cne $savedWeek[0].Groups['season'].Value -or
            $sourceWeek[0].Groups['week'].Value -cne $savedWeek[0].Groups['week'].Value) {
            return [pscustomobject]$result
        }
        if ($season -ceq $sourceWeek[0].Groups['season'].Value -and
            $weekText -ceq $sourceWeek[0].Groups['week'].Value) {
            $result.Status = 'PASS'
            $result.Evidence = 'WEEK MATCH'
        }
        else {
            $result.Evidence = 'WEEK MISMATCH'
        }
    }
    catch {
        # Failed/malformed source reads do not justify a live week claim.
        return [pscustomobject]$result
    }
    return [pscustomobject]$result
}

# BF-1072: the two independent GETs can each display a locally valid
# source-MATCH week while referring to different saved seasons, weeks or
# league targets. This synthetic diagnostic cross-check needs no extra HTTP
# request and must not present two individually passing pages as consistent.
function Test-ButlerCrossRouteWeek {
    param(
        [AllowNull()][string]$MatchupHtml,
        [AllowNull()][string]$StartSitHtml,
        [AllowNull()]$MatchupCheck,
        [AllowNull()]$StartSitCheck
    )

    if ($null -eq $MatchupCheck -or $null -eq $StartSitCheck -or
        [string]$MatchupCheck.Status -cne 'PASS' -or
        [string]$StartSitCheck.Status -cne 'PASS' -or
        [string]$MatchupCheck.Evidence -cne 'PAIRING VERIFIED' -or
        [string]$StartSitCheck.Evidence -cne 'PAIRING VERIFIED') {
        return [pscustomobject]@{ Status = 'SKIP'; Evidence = 'PAGE NOT VERIFIED' }
    }

    $frames = @()
    foreach ($html in @($MatchupHtml, $StartSitHtml)) {
        $targets = [regex]::Matches([string]$html,
            '(?is)<div\b[^>]*class="target"[^>]*data-butler-matchup-season="(?<season>20[0-9]{2})"[^>]*>(?<league>[^<]*?)\s*&middot;\s*Week\s+(?<week>[1-9]|1[0-8])\s*</div>')
        if ($targets.Count -ne 1) {
            return [pscustomobject]@{ Status = 'FAIL'; Evidence = 'CROSS-ROUTE PROOF' }
        }
        $frames += [pscustomobject]@{
            Season = $targets[0].Groups['season'].Value
            Week = $targets[0].Groups['week'].Value
            League = [regex]::Replace(
                [System.Net.WebUtility]::HtmlDecode($targets[0].Groups['league'].Value), '\s+', ' ').Trim()
        }
    }
    if ([string]::IsNullOrWhiteSpace($frames[0].League) -or
        [string]::IsNullOrWhiteSpace($frames[1].League) -or
        $frames[0].Season -cne $frames[1].Season -or
        $frames[0].Week -cne $frames[1].Week -or
        $frames[0].League -cne $frames[1].League) {
        return [pscustomobject]@{ Status = 'FAIL'; Evidence = 'CROSS-ROUTE CONFLICT' }
    }
    return [pscustomobject]@{ Status = 'PASS'; Evidence = 'WEEK/LEAGUE ALIGNED' }
}

# BF-1102: three user-visible routes must agree on the SAME sourced
# NFL week. This read-only check compares the already-fetched Matchup and
# Auto-Pilot pages; it never guesses today's week from a local calendar.
function Test-ButlerCrossRouteWatchWeek {
    param(
        [AllowNull()][string]$MatchupHtml,
        [AllowNull()][string]$AutoPilotHtml,
        [AllowNull()]$MatchupCheck,
        [AllowNull()]$AutoPilotCheck
    )
    if ($null -eq $MatchupCheck -or $null -eq $AutoPilotCheck -or
        [string]$MatchupCheck.Status -cne 'PASS' -or
        [string]$MatchupCheck.Evidence -cne 'PAIRING VERIFIED' -or
        [string]$AutoPilotCheck.Status -cne 'PASS' -or
        [string]$AutoPilotCheck.Evidence -cne 'WATCH CURRENT') {
        return [pscustomobject]@{ Status = 'SKIP'; Evidence = 'PAGE NOT VERIFIED' }
    }
    $matchup = [regex]::Matches([string]$MatchupHtml,
        '(?is)<section\b[^>]*class="[^"]*\bbutler-live-week-status\b[^"]*"[^>]*data-butler-week-state="MATCH"[^>]*data-butler-week-season="(?<season>20[0-9]{2})"[^>]*data-butler-week-number="(?<week>[1-9]|1[0-8])"[^>]*>')
    $markers = [regex]::Matches([string]$AutoPilotHtml, 'data-butler-watch-week-state=')
    $watch = [regex]::Matches([string]$AutoPilotHtml,
        '(?is)<span class="autopilot-watch-week" role="status" data-butler-watch-week-state="MATCH">NFL (?<season>20[0-9]{2}) season - Week (?<week>[1-9]|1[0-8]) verified against the public Sleeper week\.</span>')
    if ($matchup.Count -ne 1) {
        return [pscustomobject]@{ Status = 'WARN'; Evidence = 'WEEK SOURCE UNKNOWN' }
    }
    if ($markers.Count -ne 1 -or $watch.Count -ne 1) {
        return [pscustomobject]@{ Status = 'WARN'; Evidence = 'WATCH WEEK MISSING' }
    }
    if ($matchup[0].Groups['season'].Value -cne $watch[0].Groups['season'].Value -or
        $matchup[0].Groups['week'].Value -cne $watch[0].Groups['week'].Value) {
        return [pscustomobject]@{ Status = 'WARN'; Evidence = 'WATCH WEEK DRIFT' }
    }
    return [pscustomobject]@{ Status = 'PASS'; Evidence = 'WATCH WEEK ALIGNED' }
}

# BF-1094: compare two already-fetched localhost decisions. A green
# Dashboard and a green Auto-Pilot may still show different lineup signals
# when a local roster/weekly evidence update occurred between requests.
# Never print the player/league/roster values in the diagnostic.
function Test-ButlerCrossRouteLineup {
    param(
        [AllowNull()][string]$DashboardHtml,
        [AllowNull()][string]$AutoPilotHtml,
        [AllowNull()]$DashboardCheck,
        [AllowNull()]$AutoPilotCheck
    )

    if ($null -eq $DashboardCheck -or $null -eq $AutoPilotCheck -or
        [string]$DashboardCheck.Status -cne 'PASS' -or
        [string]$DashboardCheck.Evidence -cne 'AUDIT CURRENT' -or
        [string]$AutoPilotCheck.Status -cne 'PASS' -or
        [string]$AutoPilotCheck.Evidence -cne 'WATCH CURRENT') {
        return [pscustomobject]@{ Status = 'SKIP'; Evidence = 'PAGE NOT VERIFIED' }
    }

    $dashCards = [regex]::Matches([string]$DashboardHtml,
        '(?is)<div\b[^>]*class="[^"]*\bdashboard-summary-card\b[^"]*"[^>]*>\s*<span>\s*Start/Sit\s*</span>\s*<strong>(?<value>[^<]*)</strong>\s*</div>')
    $pilotCards = [regex]::Matches([string]$AutoPilotHtml,
        '(?is)<div\b[^>]*class="[^"]*\bautopilot-watch-card\b[^"]*"[^>]*>\s*<span>\s*Start/Sit\s*</span>\s*<strong>(?<value>[^<]*)</strong>\s*</div>')
    if ($dashCards.Count -ne 1 -or $pilotCards.Count -ne 1) {
        return [pscustomobject]@{ Status = 'WARN'; Evidence = 'LINEUP CARD MISSING' }
    }
    $dashboardSignal = [regex]::Replace(
        [System.Net.WebUtility]::HtmlDecode($dashCards[0].Groups['value'].Value), '\s+', ' ').Trim()
    $pilotSignal = [regex]::Replace(
        [System.Net.WebUtility]::HtmlDecode($pilotCards[0].Groups['value'].Value), '\s+', ' ').Trim()
    $aligned = ($dashboardSignal -ceq 'NO CHANGE' -and $pilotSignal -ceq 'NO CHANGE VERIFIED') -or
        ($dashboardSignal -ceq 'REVIEW' -and $pilotSignal -ceq 'REVIEW') -or
        ($dashboardSignal -cmatch '^START [1-9][0-9]* / SIT [1-9][0-9]*$' -and
         $pilotSignal -ceq $dashboardSignal)
    if (-not $aligned) {
        return [pscustomobject]@{ Status = 'WARN'; Evidence = 'LINEUP SIGNAL DRIFT' }
    }
    return [pscustomobject]@{ Status = 'PASS'; Evidence = 'LINEUP ALIGNED' }
}

# BF-1095: independently passing pages may disagree about whether a
# lineup swap was actually proposed. This check compares the already
# fetched Start/Sit proof with the source-checked Auto-Pilot decision.
function Test-ButlerCrossRouteStartSit {
    param(
        [AllowNull()][string]$AutoPilotHtml,
        [AllowNull()][string]$StartSitHtml,
        [AllowNull()]$AutoPilotCheck,
        [AllowNull()]$StartSitCheck,
        [AllowNull()]$LineupPair
    )
    if ($null -eq $AutoPilotCheck -or $null -eq $StartSitCheck -or
        $null -eq $LineupPair -or
        [string]$AutoPilotCheck.Status -cne 'PASS' -or
        [string]$AutoPilotCheck.Evidence -cne 'WATCH CURRENT' -or
        [string]$StartSitCheck.Status -cne 'PASS' -or
        [string]$StartSitCheck.Evidence -cne 'PAIRING VERIFIED' -or
        [string]$LineupPair.Status -cne 'PASS') {
        return [pscustomobject]@{ Status = 'SKIP'; Evidence = 'PAGE NOT VERIFIED' }
    }
    $card = [regex]::Matches([string]$AutoPilotHtml,
        '(?is)<div\b[^>]*class="[^"]*\bautopilot-watch-card\b[^"]*"[^>]*>\s*<span>\s*Start/Sit\s*</span>\s*<strong>(?<value>[^<]*)</strong>\s*</div>')
    $proof = [regex]::Matches([string]$StartSitHtml,
        '(?is)<p\s+class="meta butler-startsit-source-proof"\s+role="status"\s*>(?<text>[^<]*)</p>')
    if ($card.Count -ne 1 -or $proof.Count -ne 1) {
        return [pscustomobject]@{ Status = 'WARN'; Evidence = 'START/SIT SOURCE UNKNOWN' }
    }
    $pilotSignal = [regex]::Replace(
        [System.Net.WebUtility]::HtmlDecode($card[0].Groups['value'].Value), '\s+', ' ').Trim()
    $message = [System.Net.WebUtility]::HtmlDecode($proof[0].Groups['text'].Value)
    $changeCounts = [regex]::Matches($message, '\b[0-9]+ proposed lineup changes\b')
    $noChange = $message.StartsWith(
        'No lineup change is ready. Full scoreable projection coverage; 0 player holds.',
        [StringComparison]::Ordinal) -and $changeCounts.Count -eq 0
    $changed = [regex]::IsMatch($message,
        '^[1-9][0-9]* proposed lineup changes have exact player-status checks from Sleeper at the recorded fetch time\.') -and
        $changeCounts.Count -eq 1 -and
        [regex]::Matches($message, '\b[0-9]+ player holds?\b').Count -eq 1 -and
        [regex]::IsMatch($message, '(?<![0-9])0 player holds\b') -and
        $message.IndexOf('Full scoreable projection coverage', [StringComparison]::Ordinal) -ge 0
    if (-not $noChange -and -not $changed) {
        return [pscustomobject]@{ Status = 'WARN'; Evidence = 'START/SIT SOURCE UNKNOWN' }
    }
    $pilotNoChange = $pilotSignal -ceq 'NO CHANGE VERIFIED'
    $pilotChanged = $pilotSignal -ceq 'REVIEW' -or
        $pilotSignal -cmatch '^START [1-9][0-9]* / SIT [1-9][0-9]*$'
    if (($noChange -and -not $pilotNoChange) -or
        ($changed -and -not $pilotChanged)) {
        return [pscustomobject]@{ Status = 'WARN'; Evidence = 'START/SIT DECISION DRIFT' }
    }
    return [pscustomobject]@{ Status = 'PASS'; Evidence = 'START/SIT ALIGNED' }
}

# BF-1097: clicking Auto-Pilot should automatically recheck *eligible*
# stale Dashboard evidence; it must never offer a refresh without the
# identical governing Dashboard proof. Compare the two existing localhost
# GETs. Re-run only the pure HTML entitlement helpers with a dummy token;
# no diagnostic HTTP write, browser script execution, or provider request.
function Test-ButlerCrossRouteOnOpen {
    param(
        [AllowNull()][string]$DashboardHtml,
        [AllowNull()][string]$AutoPilotHtml,
        [AllowNull()]$DashboardCheck,
        [AllowNull()]$AutoPilotCheck
    )
    $result = [pscustomobject]@{ Status = 'SKIP'; Evidence = 'PROOF UNAVAILABLE' }
    if ($null -eq $DashboardCheck -or $null -eq $AutoPilotCheck -or
        [string]$DashboardCheck.Status -ceq 'FAIL' -or
        [string]$AutoPilotCheck.Status -ceq 'FAIL') { return $result }

    $scriptMarkers = [regex]::Matches([string]$AutoPilotHtml, 'id="butler-auto-refresh-status"')
    $armed = $scriptMarkers.Count -eq 1 -and
        [string]$AutoPilotCheck.AutoCheck -ceq 'ARMED'
    $eligible = $false
    try {
        if ([string]::IsNullOrWhiteSpace($DashboardHtml) -or
            [regex]::Matches($DashboardHtml,
                '<nav class="nav" aria-label="Butler sections">').Count -ne 1) {
            if ($scriptMarkers.Count -gt 0) {
                return [pscustomobject]@{ Status = 'WARN'; Evidence = 'AUTO-PILOT SOURCE UNKNOWN' }
            }
            return $result
        }
        $governed = Add-DecisionRefreshControl -Html $DashboardHtml -RequestTarget '/'
        $entitlement = Add-AutomaticDashboardRefresh -Html $governed -RequestTarget '/' -Token ('a' * 64)
        $eligible = -not [string]::IsNullOrWhiteSpace([string]$entitlement.Nonce)
    }
    catch {
        if ($scriptMarkers.Count -gt 0) {
            return [pscustomobject]@{ Status = 'WARN'; Evidence = 'AUTO-PILOT SOURCE UNKNOWN' }
        }
        return $result
    }

    if ($eligible -and -not $armed) {
        return [pscustomobject]@{ Status = 'WARN'; Evidence = 'AUTO-PILOT AUTO MISSING' }
    }
    if (-not $eligible -and $scriptMarkers.Count -gt 0) {
        return [pscustomobject]@{ Status = 'WARN'; Evidence = 'AUTO-PILOT AUTO UNEXPECTED' }
    }
    return [pscustomobject]@{
        Status = 'PASS'
        Evidence = if ($eligible) { 'ON-OPEN ARMED' } else { 'ON-OPEN GATED' }
    }
}

function Test-ButlerLocalHealth {
    param($Response)
    if ($null -eq $Response -or [int]$Response.Status -ne 200 -or
        [string]$Response.Type -notmatch '^application/json') { return $false }
    try {
        $health = ConvertFrom-Json -InputObject ([string]$Response.Body)
        # An older frozen Butler build may answer the same loopback health
        # route. Never mistake that for the v0.4 code under test.
        return $health.status -ceq 'ok' -and
            $health.service -ceq 'butler-app-shell' -and
            $health.featureSet -ceq 'v04-audited-onopen-freshness-bf1048' -and
            $health.readinessContract -ceq 'v04-sourced-onopen-bf1098' -and
            # BF-1110: a pre-1107/1108 worker can still answer the old
            # BF-1098 feature gate after tests update their checkout.
            # Do not certify that old cached worker as today's v0.4 runtime.
            $health.liveCheckContract -ceq 'v04-current-page-proof-bf1110' -and
            # BF-1134: a formerly "healthy" pre-BF-1124 worker can still
            # reset My Team/Waiver connections on module-load failures.
            # Never certify it as the currently tested manager runtime.
            $health.workerRecoveryContract -ceq 'v04-safe-page-recovery-bf1134' -and
            # BF-1146: previous draft runtimes share BF-1134 health but lack
            # the bounded Auto-Pilot companion and local-read diagnostics.
            $health.localReadContract -ceq 'v04-bounded-local-read-bf1146' -and
            $health.bind -ceq '127.0.0.1'
    }
    catch { return $false }
}

function Test-ButlerLivePage {
    param([string]$Route, $Response, [switch]$RequireStartSitProvenance)
    $result = [ordered]@{ Route = $Route; Status = 'PASS'; Evidence = 'PAGE RESPONSE'; AutoCheck = 'N/A' }
    $marker = switch -CaseSensitive ($Route) {
        '/' { 'dashboard-summary-row' }
        '/team' { 'My Team' }
        '/waivers' { 'Waiver Board' }
        '/matchup' { 'Matchup' }
        '/matchup/autofill' { 'Start/Sit Assistant' }
        '/league' { 'League' }
        '/autopilot' { 'CURRENT WEEKLY WATCH' }
        default { throw 'BF-1040 BLOCKED: unsupported manager page fixture.' }
    }
    if ([int]$Response.Status -ne 200 -or
        [string]$Response.Type -notmatch '^text/html' -or
        [string]$Response.Body -notmatch '(?i)<html\b' -or
        [string]$Response.Body -notmatch [regex]::Escape($marker)) {
        $result.Status = 'FAIL'
        $result.Evidence = 'PAGE CONTRACT'
        return [pscustomobject]$result
    }
    if ([string]$Response.Cache -notmatch '(?i)\bno-store\b' -or
        [string]$Response.Csp -notmatch "default-src 'none'" -or
        [string]$Response.Csp -notmatch "frame-ancestors 'none'") {
        $result.Status = 'FAIL'
        $result.Evidence = 'HTTP SAFETY'
        return [pscustomobject]$result
    }
    # BF-1049: route-navigation labels alone cannot prove that the actual
    # roster, matchup, waiver, or Start/Sit data rendered. A generic error page
    # can contain all the navigation names while providing no usable evidence.
    $body = [string]$Response.Body
    $requiredContent = switch -CaseSensitive ($Route) {
        '/team' { @('Roster hub', 'Lineup and depth at a glance', 'id="roster-starters"') }
        '/waivers' { @('waiver-decision-hero', 'Butler waiver decision') }
        '/matchup' { @('Weekly matchup', 'hero-panel') }
        '/matchup/autofill' { @('recommendation-panel start-sit-assistant') }
        '/league' {
            # BF-1108: BF-909's actual manager-first League Hub replaced
            # the older BF-835 headings. Require real franchise and governed
            # action containers, not old copy or sidebar navigation labels.
            @('class="league-franchise-board"', 'class="league-governance"',
              'View governed league actions', 'League hub')
        }
        default { @() }
    }
    foreach ($required in $requiredContent) {
        if ($body.IndexOf($required, [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
            $result.Status = 'FAIL'
            $result.Evidence = 'PAGE CONTENT'
            return [pscustomobject]$result
        }
    }
    # BF-1109: a League Hub can be structurally healthy while its governed
    # franchise rankings are explicitly unavailable. Do not report a ready
    # league simply because the empty-state panel rendered. The source view
    # emits one of these two mutually exclusive card/empty variants.
    if ($Route -ceq '/league') {
        $franchiseCard = [regex]::IsMatch($body,
            '(?is)<div class="league-franchise-board">\s*<article class="league-franchise-card">')
        $franchiseEmpty = [regex]::IsMatch($body,
            '(?is)<div class="league-franchise-board">\s*<div class="empty">')
        if ($franchiseCard -eq $franchiseEmpty) {
            $result.Status = 'FAIL'
            $result.Evidence = 'FRANCHISE CONTENT'
            return [pscustomobject]$result
        }
        if ($franchiseEmpty) {
            $result.Status = 'WARN'
            $result.Evidence = 'LEAGUE RANKINGS HOLD'
        }
    }
    if ($Route -ceq '/team' -and
        $body.IndexOf('<div class="player-row">', [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
        $result.Status = 'WARN'
        $result.Evidence = 'ROSTER NOT SHOWN'
    }
    if ($Route -ceq '/matchup' -and
        $body -match '(?i)>\s*Opponent not confirmed\s*<') {
        $result.Status = 'WARN'
        $result.Evidence = 'OPPONENT UNKNOWN'
    }

    # BF-1056: the v0.4 Matchup and Start/Sit pages must show their
    # actual source-week state. A HTTP 200 and a pretty lineup card are NOT
    # evidence of this week's advice. Catch missing/ambiguous source proof,
    # stale/unverified weeks, and recommendations leaking into held pages.
    if (@('/matchup', '/matchup/autofill') -ccontains $Route) {
        $weekProofs = [regex]::Matches($body, 'data-butler-week-state="(?<state>MATCH|MISMATCH|UNVERIFIED)"')
        $weekMarkers = [regex]::Matches($body, 'data-butler-week-state=')
        if ($weekProofs.Count -ne 1 -or $weekMarkers.Count -ne 1) {
            $result.Status = 'FAIL'
            $result.Evidence = 'WEEK PROOF'
            return [pscustomobject]$result
        }
        $sourceWeekState = $weekProofs[0].Groups['state'].Value
        if ($sourceWeekState -ceq 'MISMATCH' -or $sourceWeekState -ceq 'UNVERIFIED') {
            # A passive unknown-week Matchup may link to the guarded Start/Sit
            # page, but must not expose actual player-change advice. A proven
            # mismatched week may not offer a stale-week review CTA either.
            if ($body -match '(?i)Promote to lineup|Recommended starter|Start this player' -or
                ($sourceWeekState -ceq 'MISMATCH' -and $body -match '(?i)>\s*Review Lineup\s*<')) {
                $result.Status = 'FAIL'
                $result.Evidence = 'HELD ADVICE'
                return [pscustomobject]$result
            }
            $result.Status = 'WARN'
            $result.Evidence = if ($sourceWeekState -ceq 'MISMATCH') { 'WEEK MISMATCH' } else { 'WEEK UNVERIFIED' }
        }
        else {
            # BF-1071: MATCH as a decorative badge is not a checked league
            # opponent. A source-verified numeric season/week must agree with
            # the exact saved pairing target before this diagnostic says PASS.
            # This is still LOCAL evidence, not a fresh injury/roster check.
            $rawSeason = [regex]::Matches($body, 'data-butler-week-season=')
            $rawWeek = [regex]::Matches($body, 'data-butler-week-number=')
            $rawPairing = [regex]::Matches($body, 'data-butler-matchup-season=')
            if ($rawSeason.Count -gt 1 -or $rawWeek.Count -gt 1 -or $rawPairing.Count -gt 1) {
                $result.Status = 'FAIL'
                $result.Evidence = 'PAIRING PROOF'
                return [pscustomobject]$result
            }
            $source = [regex]::Matches($body,
                '(?is)<section\b[^>]*class="[^"]*\bbutler-live-week-status\b[^"]*"[^>]*data-butler-week-state="MATCH"[^>]*data-butler-week-season="(?<season>20[0-9]{2})"[^>]*data-butler-week-number="(?<week>[1-9]|1[0-8])"[^>]*>')
            $pair = [regex]::Matches($body,
                '(?is)<div\b[^>]*class="target"[^>]*data-butler-matchup-season="(?<season>20[0-9]{2})"[^>]*>[^<]*\bWeek\s+(?<week>[1-9]|1[0-8])\s*</div>')
            if ($source.Count -ne 1 -or $pair.Count -ne 1 -or
                $rawSeason.Count -ne 1 -or $rawWeek.Count -ne 1 -or $rawPairing.Count -ne 1) {
                if ($result.Status -cne 'WARN') { $result.Status = 'WARN' }
                if ($result.Evidence -cne 'OPPONENT UNKNOWN') { $result.Evidence = 'PAIRING UNVERIFIED' }
            }
            elseif ($source[0].Groups['season'].Value -cne $pair[0].Groups['season'].Value -or
                    $source[0].Groups['week'].Value -cne $pair[0].Groups['week'].Value) {
                $result.Status = 'FAIL'
                $result.Evidence = 'PAIRING CONFLICT'
                return [pscustomobject]$result
            }
            elseif ($result.Status -ceq 'PASS') {
                $result.Evidence = 'PAIRING VERIFIED'
            }
        }
    }

    # BF-1073: matching season and opponent does not imply a usable
    # player recommendation. The read-only Start/Sit route can correctly
    # report a held lineup after provider/injury/projection evidence fails.
    # Surface that hold rather than printing an all-green page check.
    if ($Route -ceq '/matchup/autofill' -and
        $result.Status -ceq 'PASS' -and
        ($body.IndexOf('class="callout callout-danger start-sit-blocker"', [StringComparison]::OrdinalIgnoreCase) -ge 0 -or
         $body.IndexOf('Butler could not prove a complete weekly lineup recommendation.', [StringComparison]::OrdinalIgnoreCase) -ge 0)) {
        $result.Status = 'WARN'
        $result.Evidence = 'START/SIT BLOCKED'
    }

    # BF-1079: opt-in release readiness must inspect the actual Start/Sit
    # decision source, not merely the rendered recommendation-panel shell.
    # The ordinary GET-only smoke retains its older broad page-contract gate.
    if ($Route -ceq '/matchup/autofill' -and $RequireStartSitProvenance -and
        $result.Status -ceq 'PASS') {
        $proofs = [regex]::Matches($body,
            '(?is)<p\s+class="meta butler-startsit-source-proof"\s+role="status"\s*>(?<text>[^<]*)</p>')
        $readyEvidence = $proofs.Count -eq 1
        if ($readyEvidence) {
            $message = [System.Net.WebUtility]::HtmlDecode($proofs[0].Groups['text'].Value)
            # BF-1096: a loose substring such as "0 player holds" inside
            # a contradictory or duplicated paragraph does not prove a
            # completed, zero-hold recommendation. This release gate must
            # agree with the actual Auto-Pilot source review boundary.
            $holdCounts = [regex]::Matches($message, '\b[0-9]+ player holds?\b')
            $coverageClaims = [regex]::Matches($message, 'Full scoreable projection coverage')
            $changeCounts = [regex]::Matches($message, '\b[0-9]+ proposed lineup changes\b')
            $hasChanges = [regex]::IsMatch($message,
                '^[1-9][0-9]* proposed lineup changes have exact player-status checks from Sleeper at the recorded fetch time\.')
            $noChanges = $message.StartsWith(
                'No lineup change is ready. Full scoreable projection coverage; 0 player holds.',
                [StringComparison]::Ordinal)
            $readyEvidence = $holdCounts.Count -eq 1 -and
                $holdCounts[0].Value -ceq '0 player holds' -and
                $coverageClaims.Count -eq 1 -and
                $message.IndexOf('UNVERIFIED', [StringComparison]::OrdinalIgnoreCase) -lt 0 -and
                ($hasChanges -xor $noChanges)
            if ($hasChanges) {
                $readyEvidence = $readyEvidence -and
                    $changeCounts.Count -eq 1 -and
                    $message.IndexOf('no Sleeper move was submitted', [StringComparison]::Ordinal) -ge 0
            }
            else {
                $readyEvidence = $readyEvidence -and $changeCounts.Count -eq 0
            }
        }
        if ($readyEvidence) {
            $iso = '20[0-9]{2}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12][0-9]|3[01])T(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](?:\.[0-9]{1,9})?Z'
            $projection = [regex]::Matches($message,
                'projection snapshot retrieved (?<time>' + $iso + ') UTC')
            $status = [regex]::Matches($message,
                'swap players status map retrieved (?<time>' + $iso + ') UTC')
            $readyEvidence = $projection.Count -eq 1 -and
                ((-not $hasChanges) -or $status.Count -eq 1)
            if ($readyEvidence) {
                $sourcesToCheck = @(@{ Timestamp = $projection[0].Groups['time'].Value; MaxMinutes = 120 })
                if ($hasChanges) {
                    $sourcesToCheck += @{ Timestamp = $status[0].Groups['time'].Value; MaxMinutes = 10 }
                }
                foreach ($item in $sourcesToCheck) {
                    # Keep the 9-digit original fetch evidence in HTML but
                    # parse whole seconds to validate real calendar dates.
                    $rounded = [regex]::Replace($item.Timestamp, '\.[0-9]{1,9}Z$', 'Z')
                    $stamp = [DateTimeOffset]::MinValue
                    if (-not [DateTimeOffset]::TryParseExact($rounded, "yyyy-MM-dd'T'HH:mm:ss'Z'",
                        [Globalization.CultureInfo]::InvariantCulture,
                        [Globalization.DateTimeStyles]::AssumeUniversal, [ref]$stamp)) {
                        $readyEvidence = $false
                        break
                    }
                    $age = [DateTimeOffset]::UtcNow - $stamp.ToUniversalTime()
                    if ($age -lt [TimeSpan]::FromSeconds(-90) -or
                        $age -gt [TimeSpan]::FromMinutes($item.MaxMinutes)) {
                        $readyEvidence = $false
                        break
                    }
                }
            }
        }
        if (-not $readyEvidence) {
            $result.Status = 'WARN'
            $result.Evidence = 'START/SIT SOURCE HOLD'
        }
    }

    # BF-1047: do not misreport a successfully rendered but stale Dashboard
    # as a fresh team/waiver decision. The old smoke gate only checked HTML
    # shape, so it could say PASS even with outdated or unverified evidence.
    if ($Route -ceq '/') {
        $html = [string]$Response.Body
        $state = Get-DecisionRefreshTechnicalField -Html $html -Label 'Decision state:'
        $bf629 = Get-DecisionRefreshTechnicalField -Html $html -Label 'BF-629:'
        $bf631 = Get-DecisionRefreshTechnicalField -Html $html -Label 'BF-631:'
        if (($state -ceq 'CURRENT_AND_ACTIONABLE' -and
                $bf629 -ceq 'LIVE_ACTIONABLE_VERIFIED' -and
                $bf631 -ceq 'LATEST_EVIDENCE_LINEAGE_VERIFIED') -or
            ($state -ceq 'NO_TRANSACTION_TO_ACT_ON' -and
                $bf629 -ceq 'NO_TRANSACTION_TO_REVALIDATE' -and
                $bf631 -ceq 'LATEST_EVIDENCE_LINEAGE_VERIFIED')) {
            $result.Evidence = 'AUDIT CURRENT'
        }
        elseif (@('STALE_DO_NOT_ACT', 'CURRENT_REFRESH_RECOMMENDED') -ccontains $state -or
                ($state -ceq 'NO_TRANSACTION_TO_ACT_ON' -and
                 $bf629 -ceq 'NO_TRANSACTION_TO_REVALIDATE' -and
                 @('MARKET_LINEAGE_SUPERSEDED', 'WAIVER_LINEAGE_SUPERSEDED',
                   'MARKET_AND_WAIVER_LINEAGE_SUPERSEDED') -ccontains $bf631)) {
            $result.Status = 'WARN'
            $result.Evidence = 'AUDIT STALE'
        }
        else {
            $result.Status = 'WARN'
            $result.Evidence = 'AUDIT UNVERIFIED'
        }
    }
    if ($Route -ceq '/autopilot') {
        # BF-1145: distinguish a proven bounded local read-contention hold
        # from an unknown weekly watch. The same exact source proof must
        # accompany the warning badge; floating text or duplicate attributes
        # cannot promote a misleading diagnostic. Neither category is ready.
        $busyReasonMarkers = [regex]::Matches($body, 'data-butler-lineup-reason=')
        $busySourceProofs = [regex]::Matches($body,
            '<div class="autopilot-source-proof" role="status" aria-label="Start/Sit source evidence" data-butler-lineup-proof="held" data-butler-lineup-reason="local-data-busy">')
        # BF-1084: a random "CURRENT SNAPSHOT" string elsewhere in the HTML
        # is not evidence that the actual Auto-Pilot watch badge is green.
        # Require the exact one rendered watch-header badge from Butler,
        # so explicit lineup holds and duplicated/conflicting badges warn.
        $watchHeads = [regex]::Matches($body,
            '(?is)<div\s+class="autopilot-watch-head">(?:(?!</div>\s*</div>).)*?<span\s+class="status\s+(?<tone>good|warn)">(?<label>[^<]+)</span>\s*</div>')
        if ($watchHeads.Count -ne 1 -or
            $watchHeads[0].Groups['tone'].Value -cne 'good' -or
            $watchHeads[0].Groups['label'].Value -cne 'CURRENT SNAPSHOT') {
            $result.Status = 'WARN'
            $result.Evidence = if ($watchHeads.Count -eq 1 -and
                $watchHeads[0].Groups['tone'].Value -ceq 'warn' -and
                $watchHeads[0].Groups['label'].Value -ceq 'LOCAL DATA BUSY' -and
                $busyReasonMarkers.Count -eq 1 -and $busySourceProofs.Count -eq 1) {
                'LOCAL READ BUSY'
            } else { 'WATCH INCOMPLETE' }
        }
        elseif ($busyReasonMarkers.Count -gt 0) {
            # A green badge alongside any busy-source marker contradicts
            # the read proof and must never pass the strict readiness gate.
            $result.Status = 'WARN'
            $result.Evidence = 'WATCH INCOMPLETE'
        }
        else {
            # BF-1093: a green weekly watch is not enough by itself.
            # Require the real prepared-review card to agree that this is
            # either a sourced review proposal or a verified no-action.
            # A missing, duplicate or held approval packet is not READY.
            $preparedHeads = [regex]::Matches($body,
                '(?is)<div class="autopilot-prepared-head">\s*<div>\s*<div class="eyebrow">PREPARED START/SIT REVIEW</div>\s*<h2>Recommendation packet</h2>\s*</div>\s*<span class="status (?<tone>good|warn)">(?<label>[^<]+)</span>\s*</div>')
            if ($preparedHeads.Count -ne 1 -or
                $preparedHeads[0].Groups['tone'].Value -cne 'good' -or
                @('READY FOR MANAGER REVIEW', 'NO ACTION REQUIRED') -cnotcontains
                    $preparedHeads[0].Groups['label'].Value) {
                $result.Status = 'WARN'
                $result.Evidence = 'WATCH INCOMPLETE'
            }
            else {
                $result.Evidence = 'WATCH CURRENT'
            }
        }
    }
    # BF-1051: a route can be stale but entitled to an automatic update.
    # A missing browser script in that exact situation must FAIL the smoke;
    # it must not silently be reported as NOT NEEDED/GATED.
    $autoRequired = $false
    if ($Route -ceq '/') {
        $auditId = Get-DecisionRefreshTechnicalField -Html $body -Label 'Audit ID:'
        if ($auditId -cmatch '^[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}$') {
            if ($state -ceq 'STALE_DO_NOT_ACT' -and
                $bf629 -ceq 'LIVE_ACTIONABLE_VERIFIED' -and
                @('MARKET_LINEAGE_SUPERSEDED', 'WAIVER_LINEAGE_SUPERSEDED',
                  'MARKET_AND_WAIVER_LINEAGE_SUPERSEDED') -ccontains $bf631) {
                $autoRequired = $true
            }
            elseif ($state -ceq 'CURRENT_REFRESH_RECOMMENDED' -and
                $bf629 -ceq 'LIVE_ACTIONABLE_VERIFIED' -and
                $bf631 -ceq 'LATEST_EVIDENCE_LINEAGE_VERIFIED' -and
                (Get-DecisionRefreshTechnicalField -Html $body -Label 'BF-636 plan state:') -ceq 'MANUAL_REFRESH_PLAN_READY' -and
                (Get-DecisionRefreshTechnicalField -Html $body -Label 'BF-636 plan policy:') -ceq 'sleeper-live-waiver-manual-refresh-plan-v1-bf635-explicit-operator-only-no-execution' -and
                (Get-DecisionRefreshTechnicalField -Html $body -Label 'Governed step count:') -ceq '9') {
                $autoRequired = $true
            }
        }
    }
    elseif ($Route -ceq '/waivers') {
        $waiverMarker = [regex]::Matches($body, 'data-butler-auto-waiver="(?<audit>[0-9a-fA-F-]{36})"')
        if ($waiverMarker.Count -eq 1 -and
            $waiverMarker[0].Groups['audit'].Value -cmatch '^[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}$') {
            $autoRequired = $true
        }
    }
    if (@('/', '/waivers', '/autopilot') -ccontains $Route) {
        if ([string]$Response.Body -match 'id="butler-auto-refresh-status"') {
            $nonces = [regex]::Matches([string]$Response.Body, '<script nonce="(?<nonce>[0-9a-f]{64})">')
            if ($nonces.Count -ne 1 -or
                [string]$Response.Csp -notlike ("*script-src 'nonce-" + $nonces[0].Groups['nonce'].Value + "'*")) {
                $result.Status = 'FAIL'
                $result.Evidence = 'AUTO CSP'
                $result.AutoCheck = 'BLOCKED'
            }
            else { $result.AutoCheck = 'ARMED' }
        }
        else { $result.AutoCheck = 'NOT NEEDED/GATED' }
    }
    if ($autoRequired -and $result.AutoCheck -cne 'ARMED' -and
        $result.AutoCheck -cne 'BLOCKED') {
        $result.Status = 'FAIL'
        $result.Evidence = 'AUTO MISSING'
        $result.AutoCheck = 'BLOCKED'
    }
    return [pscustomobject]$result
}

$ports = if ($Port -gt 0) { @($Port) } else { @(18080, 8080) }
$live = New-Object System.Collections.Generic.List[int]
foreach ($candidate in $ports) {
    try {
        $health = Invoke-ButlerLocalGet -SelectedPort $candidate -Route '/health' -Seconds 5
        if (Test-ButlerLocalHealth -Response $health) { $live.Add($candidate) }
    }
    catch { }
}
if ($live.Count -ne 1) {
    throw 'BF-1146 BLOCKED: expected exactly one current v0.4 manager worker on 18080/8080. A frozen release or earlier v0.4 draft lacks the checked bounded local-read capability. Use the updated draft v0.4 runtime or specify its exact -Port; this diagnostic does not restart any process. No outside host was contacted.'
}
$selectedPort = $live[0]
Write-Host ('BUTLER v0.4 LOCAL MANAGER PAGE CHECK | 127.0.0.1:' + $selectedPort)
Write-Host 'GET only. No diagnostic POST or Sleeper transaction.'
Write-Host 'PowerShell does not run browser scripts. ARMED means HTML/CSP is ready; browser execution still needs testing.'
Write-Host ''

$failed = 0
$warned = 0
$matchupResponse = $null
$localCheckStarted = [System.Diagnostics.Stopwatch]::StartNew()
$matchupCheck = $null
$startSitResponse = $null
$startSitCheck = $null
$dashboardResponse = $null
$dashboardCheck = $null
$autoPilotResponse = $null
$autoPilotCheck = $null
foreach ($route in @('/', '/team', '/waivers', '/matchup', '/matchup/autofill', '/league', '/autopilot')) {
    $routeTimer = [Diagnostics.Stopwatch]::StartNew()
    $routeResponseObtained = $false
    try {
        $response = Invoke-ButlerLocalGet -SelectedPort $selectedPort -Route $route -Seconds $TimeoutSeconds
        $routeResponseObtained = $true
        if ($route -ceq '/') { $dashboardResponse = $response }
        if ($route -ceq '/autopilot') { $autoPilotResponse = $response }
        if ($route -ceq '/matchup') { $matchupResponse = $response }
        if ($route -ceq '/matchup/autofill') { $startSitResponse = $response }
        $check = Test-ButlerLivePage -Route $route -Response $response -RequireStartSitProvenance:$RequireReady
        if ($route -ceq '/') { $dashboardCheck = $check }
        if ($route -ceq '/autopilot') { $autoPilotCheck = $check }
        if ($route -ceq '/matchup') { $matchupCheck = $check }
        if ($route -ceq '/matchup/autofill') { $startSitCheck = $check }
    }
    catch {
        # BF-1107: stable transport category only; never disclose an exception
        # message or response body in the shareable readiness report.
        $category = 'PAGE CHECK ERROR'
        if (-not $routeResponseObtained) {
            $category = Get-ButlerLocalGetFailureCategory -Failure $_
        }
        $check = [pscustomobject]@{
            Route = $route
            Status = 'FAIL'
            Evidence = $category
            AutoCheck = 'N/A'
        }
    }
    if ($check.Status -ceq 'FAIL') { $failed++ }
    if ($check.Status -ceq 'WARN') { $warned++ }
    $routeTimer.Stop()
    Write-Host ("{0,-19} {1,-5} evidence={2,-17} auto={3} get_ms={4}" -f $check.Route, $check.Status, $check.Evidence, $check.AutoCheck, [int][Math]::Round($routeTimer.Elapsed.TotalMilliseconds))
}
$weekPair = Test-ButlerCrossRouteWeek -MatchupHtml $(if ($null -ne $matchupResponse) { [string]$matchupResponse.Body } else { '' }) `
    -StartSitHtml $(if ($null -ne $startSitResponse) { [string]$startSitResponse.Body } else { '' }) `
    -MatchupCheck $matchupCheck -StartSitCheck $startSitCheck
if ($weekPair.Status -ceq 'FAIL') { $failed++ }
Write-Host ("{0,-19} {1,-5} evidence={2}" -f 'Cross-route week', $weekPair.Status, $weekPair.Evidence)
$watchWeekPair = Test-ButlerCrossRouteWatchWeek -MatchupHtml $(if ($null -ne $matchupResponse) { [string]$matchupResponse.Body } else { '' }) `
    -AutoPilotHtml $(if ($null -ne $autoPilotResponse) { [string]$autoPilotResponse.Body } else { '' }) `
    -MatchupCheck $matchupCheck -AutoPilotCheck $autoPilotCheck
if ($watchWeekPair.Status -ceq 'WARN') { $warned++ }
Write-Host ("{0,-19} {1,-5} evidence={2}" -f 'Auto-Pilot week', $watchWeekPair.Status, $watchWeekPair.Evidence)
$lineupPair = Test-ButlerCrossRouteLineup -DashboardHtml $(if ($null -ne $dashboardResponse) { [string]$dashboardResponse.Body } else { '' }) `
    -AutoPilotHtml $(if ($null -ne $autoPilotResponse) { [string]$autoPilotResponse.Body } else { '' }) `
    -DashboardCheck $dashboardCheck -AutoPilotCheck $autoPilotCheck
if ($lineupPair.Status -ceq 'WARN') { $warned++ }
Write-Host ("{0,-19} {1,-5} evidence={2}" -f 'Cross-route lineup', $lineupPair.Status, $lineupPair.Evidence)
$sourcePair = Test-ButlerCrossRouteStartSit -AutoPilotHtml $(if ($null -ne $autoPilotResponse) { [string]$autoPilotResponse.Body } else { '' }) `
    -StartSitHtml $(if ($null -ne $startSitResponse) { [string]$startSitResponse.Body } else { '' }) `
    -AutoPilotCheck $autoPilotCheck -StartSitCheck $startSitCheck -LineupPair $lineupPair
if ($sourcePair.Status -ceq 'WARN') { $warned++ }
Write-Host ("{0,-19} {1,-5} evidence={2}" -f 'Source lineup', $sourcePair.Status, $sourcePair.Evidence)
$autoPair = Test-ButlerCrossRouteOnOpen -DashboardHtml $(if ($null -ne $dashboardResponse) { [string]$dashboardResponse.Body } else { '' }) `
    -AutoPilotHtml $(if ($null -ne $autoPilotResponse) { [string]$autoPilotResponse.Body } else { '' }) `
    -DashboardCheck $dashboardCheck -AutoPilotCheck $autoPilotCheck
if ($autoPair.Status -ceq 'WARN') { $warned++ }
Write-Host ("{0,-19} {1,-5} evidence={2}" -f 'Auto-Pilot on-open', $autoPair.Status, $autoPair.Evidence)
# The optional source check is deliberately separate from default localhost
# smoke behavior. It never downloads player/league data or repairs evidence.
if ($CheckSleeperWeek) {
    Write-Host 'Public Sleeper NFL week check: one read-only GET to api.sleeper.app; no account or league identifiers sent.'
    $weekCheck = $null
    try {
        $nflState = Get-ButlerPublicNflState
        $html = if ($null -ne $matchupResponse) { [string]$matchupResponse.Body } else { '' }
        $weekCheck = Test-ButlerSleeperWeekMatch -MatchupHtml $html -PublicNflState $nflState
    }
    catch {
        $weekCheck = [pscustomobject]@{ Status = 'WARN'; Evidence = 'SLEEPER OFFLINE' }
    }
    if ($weekCheck.Status -ceq 'WARN') { $warned++ }
    Write-Host ("{0,-19} {1,-5} evidence={2}" -f 'Sleeper NFL week', $weekCheck.Status, $weekCheck.Evidence)
}
Write-Host ''
$localCheckStarted.Stop()
Write-Host ("RESULT: {0} page failure(s), {1} watch warning(s). Local GET checks + optional public-week lookup: {2} ms." -f $failed, $warned, [int][Math]::Round($localCheckStarted.Elapsed.TotalMilliseconds))
Write-Host 'A page may respond normally but have stale/unknown audited evidence. WARN never certifies current decisions.'
Write-Host 'Local audited labels do NOT prove source freshness. Optional public week matching cannot verify player injury/projection or roster synchronization.'
Write-Host 'This does not test actual browser refresh completion.'
if ($failed -gt 0) {
    Write-Host 'READINESS GATE: FAILED. One or more local page checks were unsafe or broken.'
    exit 1
}
if ($warned -gt 0) {
    Write-Host 'Review WARN statuses before treating Auto-Pilot data as current.'
    if ($RequireReady) {
        # BF-1078: a real-league readiness run must not return OS exit 0
        # merely because every page returned HTTP 200. Held injuries,
        # missing league proof, old dashboard lineage and offline public
        # week checks are not verified ready. Preserve legacy smoke behavior
        # without -RequireReady.
        Write-Host 'READINESS GATE: BLOCKED BY WARNINGS. No lineup/waiver advice certified.'
        exit 2
    }
}
if ($RequireReady) {
    if ($CheckSleeperWeek) {
        Write-Host 'READINESS GATE: LOCAL PAGE AND PUBLIC NFL WEEK CHECKS PASS. Browser refresh completion and current injuries are not certified.'
    }
    else {
        Write-Host 'READINESS GATE: LOCAL PAGE CHECKS PASS ONLY; PUBLIC NFL WEEK NOT REQUESTED. Browser refresh completion and current injuries are not certified.'
    }
}
Write-Host 'BF-1040 LIVE PAGE CHECK: COMPLETE'
