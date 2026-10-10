param(
    [ValidateRange(0, 65535)][int]$Port = 0,
    [ValidateRange(5, 180)][int]$TimeoutSeconds = 120,
    [switch]$CheckSleeperWeek
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
        # The app's BF-840 exact weekly-matchup renderer only shows a week
        # after it proves the roster/league matchup. Never infer the week
        # from a sidebar or a fake "Week 5" label in generic page text.
        $matches = [regex]::Matches($MatchupHtml,
            '(?is)<div class="target" data-butler-matchup-season="(?<season>20[0-9]{2})">[^<]*\bWeek\s+(?<week>[1-9]|1[0-8])\s*</div>')
        if ($matches.Count -ne 1) { return [pscustomobject]$result }
        $localWeek = [int]$matches[0].Groups['week'].Value
        $localSeason = $matches[0].Groups['season'].Value
        if ($localWeek -eq [int]$weekText -and $localSeason -ceq $season) {
            $result.Status = 'PASS'
            $result.Evidence = 'WEEK MATCH'
        }
        else {
            $result.Evidence = 'WEEK MISMATCH'
        }
    }
    catch {
        # An incomplete or malformed provider response is not evidence of
        # the live week. Never guess from the current calendar date.
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
            $health.bind -ceq '127.0.0.1'
    }
    catch { return $false }
}

function Test-ButlerLivePage {
    param([string]$Route, $Response)
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
        '/league' { @('League intelligence', 'Governed guidance') }
        default { @() }
    }
    foreach ($required in $requiredContent) {
        if ($body.IndexOf($required, [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
            $result.Status = 'FAIL'
            $result.Evidence = 'PAGE CONTENT'
            return [pscustomobject]$result
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
        if ([string]$Response.Body -match 'WATCH DATA UNAVAILABLE|EVIDENCE NEEDS REFRESH' -or
            [string]$Response.Body -notmatch 'CURRENT SNAPSHOT') {
            $result.Status = 'WARN'
            $result.Evidence = 'WATCH INCOMPLETE'
        }
        else {
            $result.Evidence = 'WATCH CURRENT'
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
    throw 'BF-1048 BLOCKED: expected exactly one *v0.4 audited-freshness* Butler on 18080/8080. An older frozen v0.3 app cannot pass this gate. Launch the v0.4 test checkout, or specify its exact -Port. No outside host was contacted.'
}
$selectedPort = $live[0]
Write-Host ('BUTLER v0.4 LOCAL MANAGER PAGE CHECK | 127.0.0.1:' + $selectedPort)
Write-Host 'GET only. No diagnostic POST or Sleeper transaction.'
Write-Host 'PowerShell does not run browser scripts. ARMED means HTML/CSP is ready; browser execution still needs testing.'
Write-Host ''

$failed = 0
$warned = 0
$matchupResponse = $null
$matchupCheck = $null
$startSitResponse = $null
$startSitCheck = $null
foreach ($route in @('/', '/team', '/waivers', '/matchup', '/matchup/autofill', '/league', '/autopilot')) {
    try {
        $response = Invoke-ButlerLocalGet -SelectedPort $selectedPort -Route $route -Seconds $TimeoutSeconds
        if ($route -ceq '/matchup') { $matchupResponse = $response }
        if ($route -ceq '/matchup/autofill') { $startSitResponse = $response }
        $check = Test-ButlerLivePage -Route $route -Response $response
        if ($route -ceq '/matchup') { $matchupCheck = $check }
        if ($route -ceq '/matchup/autofill') { $startSitCheck = $check }
    }
    catch {
        $check = [pscustomobject]@{ Route = $route; Status = 'FAIL'; Evidence = 'GET ERROR'; AutoCheck = 'N/A' }
    }
    if ($check.Status -ceq 'FAIL') { $failed++ }
    if ($check.Status -ceq 'WARN') { $warned++ }
    Write-Host ("{0,-19} {1,-5} evidence={2,-17} auto={3}" -f $check.Route, $check.Status, $check.Evidence, $check.AutoCheck)
}
$weekPair = Test-ButlerCrossRouteWeek -MatchupHtml $(if ($null -ne $matchupResponse) { [string]$matchupResponse.Body } else { '' }) `
    -StartSitHtml $(if ($null -ne $startSitResponse) { [string]$startSitResponse.Body } else { '' }) `
    -MatchupCheck $matchupCheck -StartSitCheck $startSitCheck
if ($weekPair.Status -ceq 'FAIL') { $failed++ }
Write-Host ("{0,-19} {1,-5} evidence={2}" -f 'Cross-route week', $weekPair.Status, $weekPair.Evidence)
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
Write-Host ("RESULT: {0} page failure(s), {1} watch warning(s)." -f $failed, $warned)
Write-Host 'A page may respond normally but have stale/unknown audited evidence. WARN never certifies current decisions.'
Write-Host 'Local audited labels do NOT prove source freshness. Optional public week matching cannot verify player injury/projection or roster synchronization.'
Write-Host 'This does not test actual browser refresh completion.'
if ($failed -gt 0) { exit 1 }
if ($warned -gt 0) { Write-Host 'Review WARN statuses before treating Auto-Pilot data as current.' }
Write-Host 'BF-1040 LIVE PAGE CHECK: COMPLETE'
