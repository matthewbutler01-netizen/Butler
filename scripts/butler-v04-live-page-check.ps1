param(
    [ValidateRange(0, 65535)][int]$Port = 0,
    [ValidateRange(5, 180)][int]$TimeoutSeconds = 120
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

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
        return $health.status -ceq 'ok' -and
            $health.service -ceq 'butler-app-shell' -and
            $health.bind -ceq '127.0.0.1'
    }
    catch { return $false }
}

function Test-ButlerLivePage {
    param([string]$Route, $Response)
    $result = [ordered]@{ Route = $Route; Status = 'PASS'; Evidence = 'READY'; AutoCheck = 'N/A' }
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
    if ($Route -ceq '/autopilot' -and [string]$Response.Body -match 'WATCH DATA UNAVAILABLE') {
        $result.Status = 'WARN'
        $result.Evidence = 'WATCH INCOMPLETE'
    }
    if (@('/', '/waivers') -ccontains $Route) {
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
    throw 'BF-1040 BLOCKED: expected exactly one local Butler on 18080/8080. Launch the v0.4 app or specify -Port. No outside host was contacted.'
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
Write-Host 'This checks local route responses, NOT browser refresh completion or live provider freshness.'
if ($failed -gt 0) { exit 1 }
if ($warned -gt 0) { Write-Host 'Review WARN statuses before treating Auto-Pilot data as current.' }
Write-Host 'BF-1040 LIVE PAGE CHECK: COMPLETE'
