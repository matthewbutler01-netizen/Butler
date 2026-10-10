param(
    [ValidateRange(0, 65535)][int]$Port = 0,
    [ValidateRange(5, 180)][int]$TimeoutSeconds = 120
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
        '/team' { @('Current roster', 'Roster players') }
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
        $body.IndexOf('roster-card', [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
        $result.Status = 'WARN'
        $result.Evidence = 'ROSTER NOT SHOWN'
    }
    if ($Route -ceq '/matchup' -and
        $body -match '(?i)>\s*Opponent not confirmed\s*<') {
        $result.Status = 'WARN'
        $result.Evidence = 'OPPONENT UNKNOWN'
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
foreach ($route in @('/', '/team', '/waivers', '/matchup', '/matchup/autofill', '/league', '/autopilot')) {
    try {
        $response = Invoke-ButlerLocalGet -SelectedPort $selectedPort -Route $route -Seconds $TimeoutSeconds
        $check = Test-ButlerLivePage -Route $route -Response $response
    }
    catch {
        $check = [pscustomobject]@{ Route = $route; Status = 'FAIL'; Evidence = 'GET ERROR'; AutoCheck = 'N/A' }
    }
    if ($check.Status -ceq 'FAIL') { $failed++ }
    if ($check.Status -ceq 'WARN') { $warned++ }
    Write-Host ("{0,-19} {1,-5} evidence={2,-17} auto={3}" -f $check.Route, $check.Status, $check.Evidence, $check.AutoCheck)
}
Write-Host ''
Write-Host ("RESULT: {0} page failure(s), {1} watch warning(s)." -f $failed, $warned)
Write-Host 'A page may respond normally but have stale/unknown audited evidence. WARN never certifies current decisions.'
Write-Host 'This checks local route responses and audited labels, NOT live Sleeper provider freshness or browser refresh completion.'
if ($failed -gt 0) { exit 1 }
if ($warned -gt 0) { Write-Host 'Review WARN statuses before treating Auto-Pilot data as current.' }
Write-Host 'BF-1040 LIVE PAGE CHECK: COMPLETE'
