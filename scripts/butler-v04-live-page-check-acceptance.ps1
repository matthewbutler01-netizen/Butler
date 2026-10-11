Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'butler-decision-refresh.ps1')

$path = Join-Path $PSScriptRoot 'butler-v04-live-page-check.ps1'
$launcher = Join-Path $PSScriptRoot 'butler-v04-live-page-check.cmd'
$doubleClick = Join-Path $PSScriptRoot 'butler-v04-live-page-check-open.cmd'
$publicWeekCheck = Join-Path $PSScriptRoot 'butler-v04-week-check-open.cmd'
$realLeagueCheck = Join-Path $PSScriptRoot 'butler-v04-real-league-readiness-open.cmd'
foreach ($requiredPath in @($path, $launcher, $doubleClick, $publicWeekCheck, $realLeagueCheck)) {
    if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) { throw "BF-1040 BLOCKED: missing $requiredPath" }
}
$source = [IO.File]::ReadAllText($path)
$wrapper = [IO.File]::ReadAllText($launcher)
$doubleClickWrapper = [IO.File]::ReadAllText($doubleClick)
$publicWeekWrapper = [IO.File]::ReadAllText($publicWeekCheck)
$realLeagueWrapper = [IO.File]::ReadAllText($realLeagueCheck)
# BF-1077: no user PowerShell required, a single private local diagnostics
# text report is captured without storing player/roster HTML, tokens or data.
foreach ($required in @(
    'call "%~dp0butler-v04-live-page-check.cmd" -CheckSleeperWeek -RequireReady -TimeoutSeconds 30',
    'v04-real-league-readiness-latest.txt',
    'set "butlerReportDir=%LOCALAPPDATA%\Butler\diagnostics"',
    '> "%butlerReport%" 2>&1',
    'type "%butlerReport%"',
    'start "" notepad.exe "%butlerReport%"',
    'exit /b %butlerExit%',
    'may invoke Butler'
)) {
    if ($realLeagueWrapper.IndexOf($required, [StringComparison]::OrdinalIgnoreCase) -lt 0) {
        throw "BF-1077 BLOCKED: one-click real league report contract missing: $required"
    }
}
foreach ($forbidden in @(
    'taskkill', 'Stop-Process', 'git reset', 'git checkout',
    'Invoke-RestMethod', 'POST /refresh', 'submitTransaction',
    '.db', '.sqlite', 'app-league.txt', 'start butler-app'
)) {
    if ($realLeagueWrapper.IndexOf($forbidden, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
        throw "BF-1077 BLOCKED: one-click report may modify local runtime or reveal private evidence: $forbidden"
    }
}

foreach ($required in @('butler-v04-live-page-check.cmd', '-CheckSleeperWeek', 'pause >nul')) {
    if ($publicWeekWrapper.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-1053 BLOCKED: optional public NFL week launcher is missing: $required"
    }
}
foreach ($required in @('call "%~dp0butler-v04-live-page-check.cmd"', 'pause >nul', 'exit /b %butlerExit%')) {
    if ($doubleClickWrapper.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-1049 BLOCKED: the double-click tester lost its safe visible-result behavior: $required"
    }
}
foreach ($forbidden in @('taskkill', 'Stop-Process', 'git reset', 'git checkout', 'submitTransaction')) {
    if ($doubleClickWrapper.IndexOf($forbidden, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
        throw 'BF-1049 BLOCKED: the double-click tester may modify a frozen Butler instance.'
    }
}
$tokens = $null
$errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($path, [ref]$tokens, [ref]$errors)
if (@($errors).Count -ne 0) { throw 'BF-1040 BLOCKED: diagnostic does not parse on Windows PowerShell 5.1.' }
foreach ($name in @('Invoke-ButlerLocalGet', 'Get-ButlerLocalGetFailureCategory', 'Get-ButlerPublicNflState', 'Test-ButlerSleeperWeekMatch', 'Test-ButlerLocalHealth', 'Test-ButlerLivePage', 'Test-ButlerCrossRouteWeek', 'Test-ButlerCrossRouteWatchWeek', 'Test-ButlerCrossRouteLineup', 'Test-ButlerCrossRouteStartSit', 'Test-ButlerCrossRouteOnOpen')) {
    $node = $ast.Find({
        param($item)
        $item -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $item.Name -ceq $name
    }, $true)
    if ($null -eq $node) { throw "BF-1040 BLOCKED: missing smoke function $name" }
    . ([scriptblock]::Create($node.Extent.Text))
}

# BF-1107: a failed live route must report only the failure class, not
# private HTTP error body, exception text, URL, roster, or audit material.
# These tests exercise the same function imported from the production AST.
$bf1107Private = 'secret roster/player/league diagnostic material'
foreach ($probe in @(
    @{ Failure = [System.Net.WebException]::new($bf1107Private, [System.Net.WebExceptionStatus]::Timeout); Expected = 'GET TIMEOUT' },
    @{ Failure = [System.Net.WebException]::new($bf1107Private, [System.Net.WebExceptionStatus]::ConnectFailure); Expected = 'CONNECT FAILED' },
    @{ Failure = [System.Net.WebException]::new($bf1107Private, [System.Net.WebExceptionStatus]::ReceiveFailure); Expected = 'RECEIVE FAILED' },
    @{ Failure = [System.Net.WebException]::new($bf1107Private, [System.Net.WebExceptionStatus]::ProtocolError); Expected = 'HTTP ERROR' },
    @{ Failure = [System.Exception]::new($bf1107Private); Expected = 'GET ERROR' },
    @{ Failure = [System.Exception]::new('wrapper', [System.Net.WebException]::new($bf1107Private, [System.Net.WebExceptionStatus]::Timeout)); Expected = 'GET TIMEOUT' }
)) {
    $actual = Get-ButlerLocalGetFailureCategory -Failure $probe.Failure
    if ($actual -cne $probe.Expected -or $actual.Contains($bf1107Private)) {
        throw 'BF-1107 BLOCKED: transport classification was incorrect or revealed private diagnostic material.'
    }
}
if ((Get-ButlerLocalGetFailureCategory -Failure $null) -cne 'GET ERROR') {
    throw 'BF-1107 BLOCKED: missing transport error was not bounded.'
}
foreach ($required in @(
    '$routeResponseObtained = $true',
    '$category = Get-ButlerLocalGetFailureCategory -Failure $_',
    '$request.AllowAutoRedirect = $false'
)) {
    if ($source.IndexOf($required, [StringComparison]::Ordinal) -lt 0) {
        throw 'BF-1107 BLOCKED: GET status and page-parse errors are not safely separated.'
    }
}

# BF-1112: exercise the real public worker response writer with an in-memory
# stream. Neither the read-only readiness report nor its failure-layer header
# may echo body/player/league data. No network or provider is contacted.
$workerSourcePath = Join-Path $PSScriptRoot 'butler-app-request-worker.ps1'
$workerSource = [IO.File]::ReadAllText($workerSourcePath)
$workerTokens = $null
$workerErrors = $null
$workerAst = [System.Management.Automation.Language.Parser]::ParseFile(
    $workerSourcePath, [ref]$workerTokens, [ref]$workerErrors)
if (@($workerErrors).Count -ne 0) { throw 'BF-1112 BLOCKED: worker source failed parse.' }
$sendFunction = $workerAst.Find({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
    $node.Name -ceq 'Send-HttpResponse'
}, $true)
if ($null -eq $sendFunction) { throw 'BF-1112 BLOCKED: public response function missing.' }
. ([scriptblock]::Create($sendFunction.Extent.Text))
$script:bf856RouteTimingEnabled = $false
$script:bf857CoreTimingEnabled = $false
foreach ($required in @(
    "-FailureLayer 'CORE_HTTP'",
    '-FailureLayer $failurePhase',
    "$" + "failurePhase = 'CORE_READ'",
    "$" + "failurePhase = 'PUBLIC_WORKER'"
)) {
    if ($workerSource.IndexOf($required, [StringComparison]::Ordinal) -lt 0) {
        throw 'BF-1112 BLOCKED: public manager route does not identify failure origin.'
    }
}
foreach ($case in @(
    @{ Code = 500; Layer = 'CORE_HTTP'; Header = 'CORE_HTTP' },
    @{ Code = 503; Layer = 'CORE_READ'; Header = 'CORE_READ' },
    @{ Code = 500; Layer = 'PUBLIC_WORKER'; Header = 'PUBLIC_WORKER' },
    @{ Code = 200; Layer = 'CORE_HTTP'; Header = '' },
    @{ Code = 500; Layer = ''; Header = '' }
)) {
    $memory = [System.IO.MemoryStream]::new()
    try {
        Send-HttpResponse -Stream $memory -StatusCode $case.Code -StatusText 'Local response' -ContentType 'text/html; charset=utf-8' -Body 'PRIVATE_ROSTER_LEAGUE_PLAYER_BODY' -FailureLayer $case.Layer
        $bytes = [Text.Encoding]::ASCII.GetString($memory.ToArray())
        $boundary = $bytes.IndexOf("`r`n`r`n", [StringComparison]::Ordinal)
        if ($boundary -lt 0) { throw 'BF-1112 BLOCKED: worker response missing HTTP header boundary.' }
        $headersOnly = $bytes.Substring(0, $boundary)
        $line = 'X-Butler-Failure-Layer: '
        $expected = if ($case.Header -ne '') { $line + $case.Header } else { '' }
        $actualHeaderPresent = $headersOnly.Contains($line)
        if ($headersOnly.Contains('PRIVATE_ROSTER_LEAGUE_PLAYER_BODY') -or
            ($expected -eq '' -and $actualHeaderPresent) -or
            ($expected -ne '' -and (-not $headersOnly.Contains($expected)))) {
            throw 'BF-1112 BLOCKED: worker failure header omitted, untrusted or leaked private body.'
        }
    }
    finally {
        $memory.Dispose()
    }
}
$invalidMemory = [System.IO.MemoryStream]::new()
try {
    $invalidRejected = $false
    try {
        Send-HttpResponse -Stream $invalidMemory -StatusCode 500 -StatusText 'Error' -ContentType 'text/plain' -Body 'private' -FailureLayer 'PRIVATE_LEAGUE_IDENTIFIER'
    }
    catch {
        $invalidRejected = $_.Exception.Message -match 'BF-1112 BLOCKED: invalid local response failure layer'
    }
    if (-not $invalidRejected -or $invalidMemory.Length -ne 0) {
        throw 'BF-1112 BLOCKED: invalid failure layer reached the HTTP wire.'
    }
}
finally {
    $invalidMemory.Dispose()
}

# BF-1113: the failing public page is still navigable, but must never
# reflect a raw exception, local Windows path, player name, or token.
# Parse the actual worker helper, not a duplicated test implementation.
$recoveryFunction = $workerAst.Find({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
    $node.Name -ceq 'Get-ButlerManagerFailureSpec'
}, $true)
if ($null -eq $recoveryFunction) {
    throw 'BF-1113 BLOCKED: route-specific manager failure helper missing.'
}
. ([scriptblock]::Create($recoveryFunction.Extent.Text))
$bf1113Private = 'PRIVATE_LEAGUE_PLAYER_TOKEN C:\\Secret\\Butler\\database.db'
foreach ($expected in @(
    @{ Route = '/'; Href = '/'; Title = 'Dashboard unavailable' },
    @{ Route = '/team'; Href = '/team'; Title = 'My Team unavailable' },
    @{ Route = '/waivers'; Href = '/waivers'; Title = 'Waiver Board unavailable' },
    @{ Route = '/matchup'; Href = '/matchup'; Title = 'Matchup unavailable' },
    @{ Route = '/matchup/autofill'; Href = '/matchup/autofill'; Title = 'Start/Sit unavailable' },
    @{ Route = '/league'; Href = '/league'; Title = 'League unavailable' },
    @{ Route = '/team?account=secret'; Href = '/'; Title = 'Butler page unavailable' },
    @{ Route = $bf1113Private; Href = '/'; Title = 'Butler page unavailable' }
)) {
    foreach ($phase in @('CORE_READ', 'PUBLIC_WORKER')) {
        $result = Get-ButlerManagerFailureSpec -RequestTarget $expected.Route -FailurePhase $phase
        if ($result.Title -cne $expected.Title -or
            $result.PrimaryHref -cne $expected.Href -or
            @('/', '/team', '/waivers', '/matchup', '/matchup/autofill', '/league') -cnotcontains $result.PrimaryHref -or
            $result.Message.IndexOf($bf1113Private, [StringComparison]::Ordinal) -ge 0 -or
            $result.Message.IndexOf('secret', [StringComparison]::OrdinalIgnoreCase) -ge 0) {
            throw 'BF-1113 BLOCKED: manager recovery failed route or privacy contract.'
        }
        if ($phase -ceq 'CORE_READ' -and
            $result.Message.IndexOf('local Butler engine', [StringComparison]::Ordinal) -lt 0) {
            throw 'BF-1113 BLOCKED: missing concrete local engine failure message.'
        }
    }
}
foreach ($phase in @('CORE_READ', 'PUBLIC_WORKER')) {
    $unknown = Get-ButlerManagerFailureSpec -RequestTarget '/team' -FailurePhase $phase
    $errorMarkup = $workerSource.Substring($workerSource.IndexOf('# BF-1113: raw exception messages'))
    if ($errorMarkup.IndexOf('Get-ButlerManagerFailureSpec -RequestTarget $requestTarget -FailurePhase $failurePhase',
            [StringComparison]::Ordinal) -lt 0 -or
        $errorMarkup.IndexOf('-Message $_.Exception.Message',
            [StringComparison]::Ordinal) -ge 0) {
        throw 'BF-1113 BLOCKED: public manager catch still reflects exception messages.'
    }
}

# BF-1102: exact independently sourced Matchup week must align with
# the week printed on Auto-Pilot. Missing, stale or duplicated proofs fail.
$bf1102Matchup = '<section class="panel butler-live-week-status" role="status" data-butler-week-state="MATCH" data-butler-week-season="2026" data-butler-week-number="5"></section>'
$bf1102Badge = '<span class="autopilot-watch-week" role="status" data-butler-watch-week-state="MATCH">NFL 2026 season - Week 5 verified against the public Sleeper week.</span>'
$bf1102MatchupCheck = [pscustomobject]@{ Status = 'PASS'; Evidence = 'PAIRING VERIFIED' }
$bf1102WatchCheck = [pscustomobject]@{ Status = 'PASS'; Evidence = 'WATCH CURRENT' }
function Assert-Bf1102WatchWeek {
    param([string]$Matchup,[string]$Watch,[string]$Expected,[string]$Evidence,[switch]$NotCurrent)
    $check = if ($NotCurrent) {
        [pscustomobject]@{ Status = 'WARN'; Evidence = 'WATCH INCOMPLETE' }
    } else { $bf1102WatchCheck }
    $result = Test-ButlerCrossRouteWatchWeek -MatchupHtml $Matchup -AutoPilotHtml $Watch -MatchupCheck $bf1102MatchupCheck -AutoPilotCheck $check
    if ($result.Status -cne $Expected -or $result.Evidence -cne $Evidence) {
        throw "BF-1102 BLOCKED: wanted $Expected/$Evidence; got $($result.Status)/$($result.Evidence)."
    }
}
Assert-Bf1102WatchWeek -Matchup $bf1102Matchup -Watch $bf1102Badge -Expected 'PASS' -Evidence 'WATCH WEEK ALIGNED'
Assert-Bf1102WatchWeek -Matchup $bf1102Matchup -Watch '' -Expected 'WARN' -Evidence 'WATCH WEEK MISSING'
Assert-Bf1102WatchWeek -Matchup $bf1102Matchup -Watch ($bf1102Badge + $bf1102Badge) -Expected 'WARN' -Evidence 'WATCH WEEK MISSING'
Assert-Bf1102WatchWeek -Matchup $bf1102Matchup -Watch ($bf1102Badge.Replace('Week 5 verified', 'Week 4 verified')) -Expected 'WARN' -Evidence 'WATCH WEEK DRIFT'
Assert-Bf1102WatchWeek -Matchup $bf1102Matchup -Watch ($bf1102Badge.Replace('NFL 2026 season', 'NFL 2025 season')) -Expected 'WARN' -Evidence 'WATCH WEEK DRIFT'
Assert-Bf1102WatchWeek -Matchup ($bf1102Matchup.Replace('data-butler-week-state="MATCH"', 'data-butler-week-state="UNVERIFIED"')) -Watch $bf1102Badge -Expected 'WARN' -Evidence 'WEEK SOURCE UNKNOWN'
Assert-Bf1102WatchWeek -Matchup $bf1102Matchup -Watch $bf1102Badge -Expected 'SKIP' -Evidence 'PAGE NOT VERIFIED' -NotCurrent

# BF-1094: compare the two verified GETs without printing team or player
# names. Healthy aligned signals pass; drift or duplication is a warning.
$verifiedDash = [pscustomobject]@{ Status = 'PASS'; Evidence = 'AUDIT CURRENT' }
$verifiedPilot = [pscustomobject]@{ Status = 'PASS'; Evidence = 'WATCH CURRENT' }
function Test-Bf1094Lineup {
    param([string]$DashSignal, [string]$PilotSignal, [string]$Expected,
          [string]$ExpectedEvidence, [switch]$Duplicate,
          [switch]$UnknownWatch)
    $dashHtml = '<div class="dashboard-summary-card"><span>Start/Sit</span><strong>' +
        $DashSignal + '</strong></div>'
    $pilotCard = '<div class="autopilot-watch-card"><span>Start/Sit</span><strong>' +
        $PilotSignal + '</strong></div>'
    $pilotHtml = if ($Duplicate) { $pilotCard + $pilotCard } else { $pilotCard }
    $watch = if ($UnknownWatch) { [pscustomobject]@{ Status = 'WARN'; Evidence = 'WATCH INCOMPLETE' } }
        else { $verifiedPilot }
    $result = Test-ButlerCrossRouteLineup -DashboardHtml $dashHtml -AutoPilotHtml $pilotHtml -DashboardCheck $verifiedDash -AutoPilotCheck $watch
    if ($result.Status -cne $Expected -or $result.Evidence -cne $ExpectedEvidence) {
        throw "BF-1094 BLOCKED: cross-route lineup expected $Expected/$ExpectedEvidence, got $($result.Status)/$($result.Evidence)."
    }
}
Test-Bf1094Lineup -DashSignal 'REVIEW' -PilotSignal 'REVIEW' -Expected 'PASS' -ExpectedEvidence 'LINEUP ALIGNED'
Test-Bf1094Lineup -DashSignal 'NO CHANGE' -PilotSignal 'NO CHANGE VERIFIED' -Expected 'PASS' -ExpectedEvidence 'LINEUP ALIGNED'
Test-Bf1094Lineup -DashSignal 'START 1 / SIT 1' -PilotSignal 'START 1 / SIT 1' -Expected 'PASS' -ExpectedEvidence 'LINEUP ALIGNED'
Test-Bf1094Lineup -DashSignal 'REVIEW' -PilotSignal 'NO CHANGE VERIFIED' -Expected 'WARN' -ExpectedEvidence 'LINEUP SIGNAL DRIFT'
Test-Bf1094Lineup -DashSignal 'NO CHANGE' -PilotSignal 'REVIEW' -Expected 'WARN' -ExpectedEvidence 'LINEUP SIGNAL DRIFT'
Test-Bf1094Lineup -DashSignal 'REVIEW' -PilotSignal 'REVIEW' -Expected 'WARN' -ExpectedEvidence 'LINEUP CARD MISSING' -Duplicate
Test-Bf1094Lineup -DashSignal 'REVIEW' -PilotSignal 'NO CHANGE VERIFIED' -Expected 'SKIP' -ExpectedEvidence 'PAGE NOT VERIFIED' -UnknownWatch

# BF-1095: independently green manager pages must agree with the actual
# Start/Sit source decision, not just their summary card values.
$sourcePositive = '<p class="meta butler-startsit-source-proof" role="status">1 proposed lineup changes have exact player-status checks from Sleeper at the recorded fetch time. Full scoreable projection coverage; 0 player holds. no Sleeper move was submitted. Sources: projection snapshot retrieved 2026-10-11T04:58:00Z UTC; swap players status map retrieved 2026-10-11T04:59:00Z UTC.</p>'
$sourceKeep = '<p class="meta butler-startsit-source-proof" role="status">No lineup change is ready. Full scoreable projection coverage; 0 player holds. Recheck before kickoff. Sources: projection snapshot retrieved 2026-10-11T04:58:00Z UTC.</p>'
$sourcePass = [pscustomobject]@{ Status = 'PASS'; Evidence = 'PAIRING VERIFIED' }
$lineupPass = [pscustomobject]@{ Status = 'PASS'; Evidence = 'LINEUP ALIGNED' }
$pilotPass = [pscustomobject]@{ Status = 'PASS'; Evidence = 'WATCH CURRENT' }
function Assert-Bf1095Decision {
    param([string]$PilotSignal,[string]$Proof,[string]$Status,[string]$Evidence,
          [switch]$Duplicate,[switch]$OtherUnverified)
    $card = '<div class="autopilot-watch-card"><span>Start/Sit</span><strong>' +
        $PilotSignal + '</strong></div>'
    $pilotHtml = if ($Duplicate) { $card + $card } else { $card }
    $start = if ($Proof -ceq 'positive') { $sourcePositive }
        elseif ($Proof -ceq 'keep') { $sourceKeep }
        elseif ($Proof -ceq 'none') { '' }
        else { $Proof }
    $sourceResult = if ($OtherUnverified) {
        [pscustomobject]@{ Status = 'WARN'; Evidence = 'START/SIT SOURCE HOLD' }
    } else { $sourcePass }
    $actual = Test-ButlerCrossRouteStartSit -AutoPilotHtml $pilotHtml -StartSitHtml $start -AutoPilotCheck $pilotPass -StartSitCheck $sourceResult -LineupPair $lineupPass
    if ($actual.Status -cne $Status -or $actual.Evidence -cne $Evidence) {
        throw "BF-1095 BLOCKED: expected $Status/$Evidence, got $($actual.Status)/$($actual.Evidence)."
    }
}
Assert-Bf1095Decision -PilotSignal 'REVIEW' -Proof 'positive' -Status 'PASS' -Evidence 'START/SIT ALIGNED'
Assert-Bf1095Decision -PilotSignal 'START 1 / SIT 1' -Proof 'positive' -Status 'PASS' -Evidence 'START/SIT ALIGNED'
Assert-Bf1095Decision -PilotSignal 'NO CHANGE VERIFIED' -Proof 'keep' -Status 'PASS' -Evidence 'START/SIT ALIGNED'
Assert-Bf1095Decision -PilotSignal 'REVIEW' -Proof 'keep' -Status 'WARN' -Evidence 'START/SIT DECISION DRIFT'
Assert-Bf1095Decision -PilotSignal 'NO CHANGE VERIFIED' -Proof 'positive' -Status 'WARN' -Evidence 'START/SIT DECISION DRIFT'
Assert-Bf1095Decision -PilotSignal 'REVIEW' -Proof 'none' -Status 'WARN' -Evidence 'START/SIT SOURCE UNKNOWN'
Assert-Bf1095Decision -PilotSignal 'REVIEW' -Proof 'positive' -Duplicate -Status 'WARN' -Evidence 'START/SIT SOURCE UNKNOWN'
Assert-Bf1095Decision -PilotSignal 'REVIEW' -Proof 'positive' -OtherUnverified -Status 'SKIP' -Evidence 'PAGE NOT VERIFIED'
Assert-Bf1095Decision -PilotSignal 'REVIEW' -Proof $sourcePositive.Replace('1 proposed lineup changes','0 proposed lineup changes') -Status 'WARN' -Evidence 'START/SIT SOURCE UNKNOWN'

# BF-1097: on-open eligibility must be independently supported by
# audited Dashboard HTML. The diagnostic never executes a browser refresh.
$bf1097Audit = '11111111-1111-1111-1111-111111111111'
$bf1097Dash = '<html><body><nav class="nav" aria-label="Butler sections"></nav>' +
    '<div>Decision state: STALE_DO_NOT_ACT</div>' +
    '<div>BF-629: LIVE_ACTIONABLE_VERIFIED</div>' +
    '<div>BF-631: MARKET_LINEAGE_SUPERSEDED</div>' +
    '<div>Audit ID: ' + $bf1097Audit + '</div></body></html>'
$bf1097Pilot = '<html><body>CURRENT WEEKLY WATCH</body></html>'
$bf1097Token = 'a' * 64
$bf1097Armed = Add-AutomaticAutoPilotRefresh -Html $bf1097Pilot -DashboardHtml $bf1097Dash -RequestTarget '/autopilot' -Token $bf1097Token
if ([string]::IsNullOrWhiteSpace([string]$bf1097Armed.Nonce)) {
    throw 'BF-1097 BLOCKED: valid stale Dashboard did not authorize synthetic on-open fixture.'
}
$bf1097DashCheck = [pscustomobject]@{ Status = 'WARN'; Evidence = 'AUDIT STALE' }
$bf1097ArmedCheck = [pscustomobject]@{ Status = 'WARN'; Evidence = 'WATCH INCOMPLETE'; AutoCheck = 'ARMED' }
$bf1097UnarmedCheck = [pscustomobject]@{ Status = 'WARN'; Evidence = 'WATCH INCOMPLETE'; AutoCheck = 'NOT NEEDED/GATED' }
$bf1097CurrentDash = $bf1097Dash.Replace('STALE_DO_NOT_ACT', 'CURRENT_AND_ACTIONABLE').
    Replace('MARKET_LINEAGE_SUPERSEDED', 'LATEST_EVIDENCE_LINEAGE_VERIFIED')
function Assert-Bf1097OnOpen {
    param([string]$Dashboard,[string]$Pilot,$PilotCheck,[string]$Expected,[string]$Evidence)
    $actual = Test-ButlerCrossRouteOnOpen -DashboardHtml $Dashboard -AutoPilotHtml $Pilot -DashboardCheck $bf1097DashCheck -AutoPilotCheck $PilotCheck
    if ($actual.Status -cne $Expected -or $actual.Evidence -cne $Evidence) {
        throw "BF-1097 BLOCKED: expected $Expected/$Evidence, got $($actual.Status)/$($actual.Evidence)."
    }
}
Assert-Bf1097OnOpen -Dashboard $bf1097Dash -Pilot $bf1097Armed.Html -PilotCheck $bf1097ArmedCheck -Expected 'PASS' -Evidence 'ON-OPEN ARMED'
Assert-Bf1097OnOpen -Dashboard $bf1097Dash -Pilot $bf1097Pilot -PilotCheck $bf1097UnarmedCheck -Expected 'WARN' -Evidence 'AUTO-PILOT AUTO MISSING'
Assert-Bf1097OnOpen -Dashboard $bf1097CurrentDash -Pilot $bf1097Pilot -PilotCheck $bf1097UnarmedCheck -Expected 'PASS' -Evidence 'ON-OPEN GATED'
Assert-Bf1097OnOpen -Dashboard $bf1097CurrentDash -Pilot $bf1097Armed.Html -PilotCheck $bf1097ArmedCheck -Expected 'WARN' -Evidence 'AUTO-PILOT AUTO UNEXPECTED'
Assert-Bf1097OnOpen -Dashboard '<html><body>no nav</body></html>' -Pilot $bf1097Armed.Html -PilotCheck $bf1097ArmedCheck -Expected 'WARN' -Evidence 'AUTO-PILOT SOURCE UNKNOWN'
Assert-Bf1097OnOpen -Dashboard '<html><body>no nav</body></html>' -Pilot $bf1097Pilot -PilotCheck $bf1097UnarmedCheck -Expected 'SKIP' -Evidence 'PROOF UNAVAILABLE'

$health = [pscustomobject]@{
    Status = 200; Type = 'application/json; charset=utf-8'
    Body = '{"status":"ok","service":"butler-app-shell","featureSet":"v04-audited-onopen-freshness-bf1048","readinessContract":"v04-sourced-onopen-bf1098","liveCheckContract":"v04-current-page-proof-bf1110","bind":"127.0.0.1"}'
}
if (-not (Test-ButlerLocalHealth -Response $health)) {
    throw 'BF-1040 BLOCKED: exact Butler loopback health was rejected.'
}
# BF-1110: the original BF-1098 health contract was shared by many
# v0.4 draft workers; an earlier running cached worker must NOT be mistaken
# for a worker containing the current page-proof capabilities.
$health.Body = '{"status":"ok","service":"butler-app-shell","featureSet":"v04-audited-onopen-freshness-bf1048","readinessContract":"v04-sourced-onopen-bf1098","bind":"127.0.0.1"}'
if (Test-ButlerLocalHealth -Response $health) {
    throw 'BF-1110 BLOCKED: previous v0.4 worker passed the current runtime gate.'
}
$health.Body = '{"status":"ok","service":"butler-app-shell","featureSet":"v04-audited-onopen-freshness-bf1048","readinessContract":"v04-sourced-onopen-bf1098","liveCheckContract":"wrong-worker","bind":"127.0.0.1"}'
if (Test-ButlerLocalHealth -Response $health) {
    throw 'BF-1110 BLOCKED: unrelated live worker marker passed.'
}
$health.Body = '{"status":"ok","service":"butler-app-shell","featureSet":"v04-audited-onopen-freshness-bf1048","readinessContract":"v04-sourced-onopen-bf1098","liveCheckContract":"v04-current-page-proof-bf1110","bind":"127.0.0.1"}'
if (-not (Test-ButlerLocalHealth -Response $health)) {
    throw 'BF-1110 BLOCKED: current worker not accepted after old-worker rejections.'
}
$workerSource = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'butler-app-request-worker.ps1'))
if ($workerSource.IndexOf('"liveCheckContract":"v04-current-page-proof-bf1110"', [StringComparison]::Ordinal) -lt 0) {
    throw 'BF-1110 BLOCKED: production worker health does not advertise the current runtime capability.'
}

# BF-1098: older v0.4 still advertises the original featureSet,
# but lacks sourced cross-page and on-open safeguards. Never certify it
# using today's strict real-league readiness diagnostic.
$health.Body = '{"status":"ok","service":"butler-app-shell","featureSet":"v04-audited-onopen-freshness-bf1048","bind":"127.0.0.1"}'
if (Test-ButlerLocalHealth -Response $health) {
    throw 'BF-1098 BLOCKED: pre-BF-1098 v0.4 runtime passed the current strict readiness identity.'
}
$health.Body = '{"status":"ok","service":"butler-app-shell","featureSet":"v04-audited-onopen-freshness-bf1048","readinessContract":"other-build","bind":"127.0.0.1"}'
if (Test-ButlerLocalHealth -Response $health) {
    throw 'BF-1098 BLOCKED: invalid readiness contract passed the current runtime gate.'
}
$health.Body = '{"status":"ok","service":"different-app","bind":"127.0.0.1"}'
if (Test-ButlerLocalHealth -Response $health) {
    throw 'BF-1040 BLOCKED: unrelated loopback service accepted.'
}
# Old frozen Butler health must not pass as a v0.4 app even when
# hosted on the expected port and bound to loopback.
$health.Body = '{"status":"ok","service":"butler-app-shell","bind":"127.0.0.1"}'
if (Test-ButlerLocalHealth -Response $health) {
    throw 'BF-1048 BLOCKED: unversioned/old Butler instance passed as v0.4.'
}
$health.Body = '{"status":"ok","service":"butler-app-shell","featureSet":"unknown","bind":"127.0.0.1"}'
if (Test-ButlerLocalHealth -Response $health) {
    throw 'BF-1048 BLOCKED: incompatible development build passed v0.4 identity gate.'
}
$health.Body = '{"status":"ok","service":"butler-app-shell","featureSet":"v04-audited-onopen-freshness-bf1048","bind":"0.0.0.0"}'
if (Test-ButlerLocalHealth -Response $health) {
    throw 'BF-1040 BLOCKED: public bind accepted.'
}

$page = [pscustomobject]@{
    Status = 200
    Type = 'text/html; charset=utf-8'
    Cache = 'no-store'
    Csp = "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'"
    Body = '<html><body><div class="dashboard-summary-row"></div><div>Decision state: CURRENT_AND_ACTIONABLE</div><div>BF-629: LIVE_ACTIONABLE_VERIFIED</div><div>BF-631: LATEST_EVIDENCE_LINEAGE_VERIFIED</div></body></html>'
}
$check = Test-ButlerLivePage -Route '/' -Response $page
if ($check.Status -cne 'PASS' -or $check.Evidence -cne 'AUDIT CURRENT' -or $check.AutoCheck -cne 'NOT NEEDED/GATED') {
    throw 'BF-1040 BLOCKED: eligible Dashboard baseline was rejected.'
}
$page.Body = $page.Body.Replace('</body>', '<div id="butler-auto-refresh-status"></div><script nonce="' + ('a' * 64) + '">safe diagnostic</script></body>')
$page.Csp += "; script-src 'nonce-" + ('a' * 64) + "'; connect-src 'self'"
$check = Test-ButlerLivePage -Route '/' -Response $page
if ($check.Status -cne 'PASS' -or $check.AutoCheck -cne 'ARMED') {
    throw 'BF-1040 BLOCKED: nonce-gated automatic refresh HTML/CSP rejected.'
}
$page.Csp = "default-src 'none'; frame-ancestors 'none'"
$check = Test-ButlerLivePage -Route '/' -Response $page
if ($check.Status -cne 'FAIL' -or $check.Evidence -cne 'AUTO CSP') {
    throw 'BF-1040 BLOCKED: missing nonce CSP incorrectly accepted.'
}
# BF-1047: a perfectly rendered Dashboard can still carry an outdated,
# unknown, or contradictory audited decision. Never report it as PASS/current.
$page.Csp += "; script-src 'nonce-" + ('a' * 64) + "'; connect-src 'self'"
$validDashboardMarkup = $page.Body
foreach ($unsafeAudit in @(
    ($validDashboardMarkup.Replace('CURRENT_AND_ACTIONABLE', 'STALE_DO_NOT_ACT')),
    ($validDashboardMarkup.Replace('CURRENT_AND_ACTIONABLE', 'CURRENT_REFRESH_RECOMMENDED')),
    ($validDashboardMarkup.Replace('BF-629: LIVE_ACTIONABLE_VERIFIED', 'BF-629: BLOCKED')),
    ($validDashboardMarkup.Replace('BF-631: LATEST_EVIDENCE_LINEAGE_VERIFIED', 'BF-631: MARKET_LINEAGE_SUPERSEDED')),
    ($validDashboardMarkup.Replace('<div>BF-629: LIVE_ACTIONABLE_VERIFIED</div>', '')),
    ($validDashboardMarkup.Replace('<div>BF-631: LATEST_EVIDENCE_LINEAGE_VERIFIED</div>', '')),
    ($validDashboardMarkup.Replace('BF-629: LIVE_ACTIONABLE_VERIFIED</div>', 'BF-629: LIVE_ACTIONABLE_VERIFIED</div><div>BF-629: LIVE_ACTIONABLE_VERIFIED</div>'))
)) {
    $page.Body = $unsafeAudit
    $check = Test-ButlerLivePage -Route '/' -Response $page
    if ($check.Status -cne 'WARN' -or
        @('AUDIT STALE', 'AUDIT UNVERIFIED') -cnotcontains $check.Evidence -or
        $check.AutoCheck -cne 'ARMED') {
        throw 'BF-1047 BLOCKED: a stale or ambiguous Dashboard was reported current.'
    }
}
$page.Body = '<html><body><div class="dashboard-summary-row"></div><div>Decision state: NO_TRANSACTION_TO_ACT_ON</div><div>BF-629: NO_TRANSACTION_TO_REVALIDATE</div><div>BF-631: LATEST_EVIDENCE_LINEAGE_VERIFIED</div></body></html>'
$page.Csp = "default-src 'none'; frame-ancestors 'none'"
$check = Test-ButlerLivePage -Route '/' -Response $page
if ($check.Status -cne 'PASS' -or $check.Evidence -cne 'AUDIT CURRENT') {
    throw 'BF-1047 BLOCKED: current audited no-transaction Dashboard was rejected.'
}
foreach ($lineage in @('MARKET_LINEAGE_SUPERSEDED', 'WAIVER_LINEAGE_SUPERSEDED', 'MARKET_AND_WAIVER_LINEAGE_SUPERSEDED')) {
    $page.Body = $page.Body.Replace('LATEST_EVIDENCE_LINEAGE_VERIFIED', $lineage)
    $check = Test-ButlerLivePage -Route '/' -Response $page
    if ($check.Status -cne 'WARN' -or $check.Evidence -cne 'AUDIT STALE') {
        throw 'BF-1047 BLOCKED: superseded no-transaction lineage was mislabeled current.'
    }
    $page.Body = $page.Body.Replace($lineage, 'LATEST_EVIDENCE_LINEAGE_VERIFIED')
}
$page.Body = '<html><body><div class="dashboard-summary-row"></div></body></html>'
$check = Test-ButlerLivePage -Route '/' -Response $page
if ($check.Status -cne 'WARN' -or $check.Evidence -cne 'AUDIT UNVERIFIED') {
    throw 'BF-1047 BLOCKED: Dashboard with no audited evidence was reported current.'
}

# BF-1051: exact eligible stale decisions may not quietly lose their
# on-open browser script while retaining otherwise healthy page markup.
$auditId = '11111111-1111-1111-1111-111111111111'
$baseCsp = "default-src 'none'; frame-ancestors 'none'"
$eligibleDashboard = '<html><body><div class="dashboard-summary-row"></div>' +
    '<div>Decision state: STALE_DO_NOT_ACT</div>' +
    '<div>BF-629: LIVE_ACTIONABLE_VERIFIED</div>' +
    '<div>BF-631: MARKET_LINEAGE_SUPERSEDED</div>' +
    '<div>Audit ID: ' + $auditId + '</div></body></html>'
$page.Body = $eligibleDashboard
$page.Csp = $baseCsp
$check = Test-ButlerLivePage -Route '/' -Response $page
if ($check.Status -cne 'FAIL' -or $check.Evidence -cne 'AUTO MISSING') {
    throw 'BF-1051 BLOCKED: eligible stale Dashboard silently omitted automatic update.'
}
$page.Body = $eligibleDashboard.Replace('</body>', '<div id="butler-auto-refresh-status"></div><script nonce="' + ('a' * 64) + '">synthetic inert script</script></body>')
$page.Csp = $baseCsp + "; script-src 'nonce-" + ('a' * 64) + "'; connect-src 'self'"
$check = Test-ButlerLivePage -Route '/' -Response $page
if ($check.Status -cne 'WARN' -or $check.Evidence -cne 'AUDIT STALE' -or
    $check.AutoCheck -cne 'ARMED') {
    throw 'BF-1051 BLOCKED: eligible stale Dashboard with correct auto script was rejected.'
}
$recommended = $eligibleDashboard.Replace('STALE_DO_NOT_ACT', 'CURRENT_REFRESH_RECOMMENDED').
    Replace('MARKET_LINEAGE_SUPERSEDED', 'LATEST_EVIDENCE_LINEAGE_VERIFIED').
    Replace('</body>', '<div>BF-636 plan state: MANUAL_REFRESH_PLAN_READY</div><div>BF-636 plan policy: sleeper-live-waiver-manual-refresh-plan-v1-bf635-explicit-operator-only-no-execution</div><div>Governed step count: 9</div></body>')
$page.Body = $recommended
$page.Csp = $baseCsp
$check = Test-ButlerLivePage -Route '/' -Response $page
if ($check.Status -cne 'FAIL' -or $check.Evidence -cne 'AUTO MISSING') {
    throw 'BF-1051 BLOCKED: governed nine-stage refresh recommendation lost automatic update.'
}
$page.Body = $recommended.Replace('Governed step count: 9', 'Governed step count: 10')
$check = Test-ButlerLivePage -Route '/' -Response $page
if ($check.Status -cne 'WARN' -or $check.Evidence -cne 'AUDIT STALE' -or
    $check.AutoCheck -cne 'NOT NEEDED/GATED') {
    throw 'BF-1051 BLOCKED: invalid nine-stage plan incorrectly demanded an automatic update.'
}
$waiverBase = '<html><body>Waiver Board <section class="waiver-decision-hero">Butler waiver decision</section><span hidden data-butler-auto-waiver="' + $auditId + '"></span></body></html>'
$page.Body = $waiverBase
$check = Test-ButlerLivePage -Route '/waivers' -Response $page
if ($check.Status -cne 'FAIL' -or $check.Evidence -cne 'AUTO MISSING') {
    throw 'BF-1051 BLOCKED: eligible Waiver Board silently omitted automatic update.'
}
$page.Body = $waiverBase.Replace('</body>', '<div id="butler-auto-refresh-status"></div><script nonce="' + ('a' * 64) + '">synthetic inert script</script></body>')
$page.Csp = $baseCsp + "; script-src 'nonce-" + ('a' * 64) + "'; connect-src 'self'"
$check = Test-ButlerLivePage -Route '/waivers' -Response $page
if ($check.Status -cne 'PASS' -or $check.AutoCheck -cne 'ARMED') {
    throw 'BF-1051 BLOCKED: eligible Waiver Board with correct auto script was rejected.'
}
$page.Csp = $baseCsp
$page.Body = '<html><body>broken manager page</body></html>'
$check = Test-ButlerLivePage -Route '/' -Response $page
if ($check.Status -cne 'FAIL' -or $check.Evidence -cne 'PAGE CONTRACT') {
    throw 'BF-1040 BLOCKED: missing Dashboard markers accepted.'
}
$page.Body = '<html><body>CURRENT WEEKLY WATCH - WATCH DATA UNAVAILABLE</body></html>'
$page.Csp = "default-src 'none'; frame-ancestors 'none'"
$check = Test-ButlerLivePage -Route '/autopilot' -Response $page
if ($check.Status -cne 'WARN' -or $check.Evidence -cne 'WATCH INCOMPLETE') {
    throw 'BF-1040 BLOCKED: incomplete Auto-Pilot watch treated as current.'
}
$page.Body = '<html><body>CURRENT WEEKLY WATCH - EVIDENCE NEEDS REFRESH</body></html>'
$check = Test-ButlerLivePage -Route '/autopilot' -Response $page
if ($check.Status -cne 'WARN' -or $check.Evidence -cne 'WATCH INCOMPLETE') {
    throw 'BF-1042 BLOCKED: stale Auto-Pilot watch was mislabeled current.'
}
$page.Body = '<html><body><div class="autopilot-watch-head"><div><div class="eyebrow">CURRENT WEEKLY WATCH</div><h2>What Butler sees right now</h2></div><span class="status good">CURRENT SNAPSHOT</span></div><div class="autopilot-prepared-head"><div><div class="eyebrow">PREPARED START/SIT REVIEW</div><h2>Recommendation packet</h2></div><span class="status good">READY FOR MANAGER REVIEW</span></div><div id="butler-auto-refresh-status"></div><script nonce="' + ('a' * 64) + '">safe diagnostic</script></body></html>'
$page.Csp = "default-src 'none'; frame-ancestors 'none'; script-src 'nonce-" + ('a' * 64) + "'; connect-src 'self'"
$check = Test-ButlerLivePage -Route '/autopilot' -Response $page
if ($check.Status -cne 'PASS' -or $check.AutoCheck -cne 'ARMED') {
    throw 'BF-1040 BLOCKED: eligible Auto-Pilot nonce or healthy watch was rejected.'
}

# BF-1084: do not accept a green keyword floating in a footer when
# the actual watch badge is a held/warning state, and reject duplicate
# contradictory watch headers even if one of them says current.
$goodAutoPilot = $page.Body
$bf1084Case = 0
foreach ($bad in @(
    ($goodAutoPilot.Replace(
        '<span class="status good">CURRENT SNAPSHOT</span>',
        '<span class="status warn">LINEUP ACTION HELD</span><p>CURRENT SNAPSHOT</p>')),
    ($goodAutoPilot.Replace('<div id="butler-auto-refresh-status"></div>', '<div class="autopilot-watch-head"><div><div class="eyebrow">CURRENT WEEKLY WATCH</div><h2>Duplicate</h2></div><span class="status good">CURRENT SNAPSHOT</span></div><div id="butler-auto-refresh-status"></div>')),
    ($goodAutoPilot.Replace('class="status good"','class="status warn"')),
    ($goodAutoPilot.Replace('class="autopilot-watch-head"','class="other-banner"'))
)) {
    $bf1084Case++
    $page.Body = $bad
    $result = Test-ButlerLivePage -Route '/autopilot' -Response $page
    if ($result.Status -cne 'WARN' -or $result.Evidence -cne 'WATCH INCOMPLETE') {
        throw "BF-1084 BLOCKED: case $bf1084Case got $($result.Status)/$($result.Evidence), expected WARN/WATCH INCOMPLETE."
    }
}
$page.Body = $goodAutoPilot

# BF-1093: positive strict readiness must have a real, unique and
# consistent prepared-review status, not just a green watch title.
$preparedCard = '<div class="autopilot-prepared-head"><div><div class="eyebrow">PREPARED START/SIT REVIEW</div><h2>Recommendation packet</h2></div><span class="status good">READY FOR MANAGER REVIEW</span></div>'
foreach ($bad in @(
    $goodAutoPilot.Replace($preparedCard, ''),
    $goodAutoPilot.Replace('READY FOR MANAGER REVIEW</span>', 'BLOCKED</span>'),
    $goodAutoPilot.Replace('class="status good">READY FOR MANAGER REVIEW', 'class="status warn">BLOCKED'),
    $goodAutoPilot.Replace($preparedCard, $preparedCard + $preparedCard),
    $goodAutoPilot.Replace('class="autopilot-prepared-head"', 'class="footer-comment"')
)) {
    $page.Body = $bad
    $test = Test-ButlerLivePage -Route '/autopilot' -Response $page
    if ($test.Status -cne 'WARN' -or $test.Evidence -cne 'WATCH INCOMPLETE') {
        throw 'BF-1093 BLOCKED: conflicting, missing, duplicate or decorative Auto-Pilot packet passed.'
    }
}
$page.Body = $goodAutoPilot

# BF-1108: diagnostic must follow the actual staged BF-909 manager League
# renderer, not pre-BF-909 League intelligence / Governed guidance headings.
$leagueRenderer = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'butler-app-bf909-league-hub-transform.ps1'))
foreach ($marker in @('class="league-franchise-board"', 'class="league-governance"',
                     'View governed league actions', 'League hub',
                     'class="league-franchise-card"', 'class="empty"')) {
    if ($leagueRenderer.IndexOf($marker, [StringComparison]::Ordinal) -lt 0) {
        throw 'BF-1108 BLOCKED: current League renderer and local smoke disagree.'
    }
}

# BF-1049: menu labels can appear on a normal-looking shell even when the
# actual roster/matchup/waiver evidence did not render. Require page-specific
# content, and warn on an empty roster or unconfirmed opponent.
$page.Csp = "default-src 'none'; frame-ancestors 'none'"
foreach ($fixture in @(
    @{ Route = '/team'; Body = '<html><body>My Team Roster hub Lineup and depth at a glance <section id="roster-starters"><div class="player-row">safe</div></section></body></html>'; Expected = 'PASS'; Evidence = 'PAGE RESPONSE' },
    @{ Route = '/team'; Body = '<html><body>My Team Roster hub Lineup and depth at a glance <section id="roster-starters"></section></body></html>'; Expected = 'WARN'; Evidence = 'ROSTER NOT SHOWN' },
    @{ Route = '/team'; Body = '<html><body>My Team navigation only</body></html>'; Expected = 'FAIL'; Evidence = 'PAGE CONTENT' },
    @{ Route = '/waivers'; Body = '<html><body>Waiver Board waiver-decision-hero Butler waiver decision</body></html>'; Expected = 'PASS'; Evidence = 'PAGE RESPONSE' },
    @{ Route = '/waivers'; Body = '<html><body>Waiver Board navigation only</body></html>'; Expected = 'FAIL'; Evidence = 'PAGE CONTENT' },
    @{ Route = '/matchup'; Body = '<html><body>Matchup Weekly matchup hero-panel <section class="panel butler-live-week-status" role="status" data-butler-week-state="MATCH" data-butler-week-season="2026" data-butler-week-number="5"></section><div class="target" data-butler-matchup-season="2026">League &middot; Week 5</div><h1>Team A vs Team B</h1></body></html>'; Expected = 'PASS'; Evidence = 'PAIRING VERIFIED' },
    @{ Route = '/matchup'; Body = '<html><body>Matchup Weekly matchup hero-panel <section data-butler-week-state="MATCH"></section><h1>Week 5</h1></body></html>'; Expected = 'WARN'; Evidence = 'PAIRING UNVERIFIED' },
    @{ Route = '/matchup'; Body = '<html><body>Matchup Weekly matchup hero-panel <section class="panel butler-live-week-status" role="status" data-butler-week-state="MATCH" data-butler-week-season="2026" data-butler-week-number="5"></section><div class="target" data-butler-matchup-season="2026">League &middot; Week 4</div></body></html>'; Expected = 'FAIL'; Evidence = 'PAIRING CONFLICT' },
    @{ Route = '/matchup'; Body = '<html><body>Matchup Weekly matchup hero-panel <section class="panel butler-live-week-status" role="status" data-butler-week-state="MATCH" data-butler-week-season="2026" data-butler-week-number="5"></section><div class="target" data-butler-matchup-season="2026">League &middot; Week 5</div><div class="target" data-butler-matchup-season="2026">Fake duplicate &middot; Week 5</div></body></html>'; Expected = 'FAIL'; Evidence = 'PAIRING PROOF' },
    @{ Route = '/matchup'; Body = '<html><body>Matchup Weekly matchup hero-panel <section data-butler-week-state="MATCH"></section><h1>Opponent not confirmed</h1></body></html>'; Expected = 'WARN'; Evidence = 'OPPONENT UNKNOWN' },
    @{ Route = '/matchup'; Body = '<html><body>Matchup Weekly matchup hero-panel <section data-butler-week-state="MISMATCH"></section><h1>Saved matchup not usable</h1></body></html>'; Expected = 'WARN'; Evidence = 'WEEK MISMATCH' },
    @{ Route = '/matchup'; Body = '<html><body>Matchup Weekly matchup hero-panel <section data-butler-week-state="UNVERIFIED"></section><h1>Week not verified</h1></body></html>'; Expected = 'WARN'; Evidence = 'WEEK UNVERIFIED' },
    @{ Route = '/matchup'; Body = '<html><body>Matchup Weekly matchup hero-panel <h1>Week 5</h1></body></html>'; Expected = 'FAIL'; Evidence = 'WEEK PROOF' },
    @{ Route = '/matchup'; Body = '<html><body>Matchup Weekly matchup hero-panel <section data-butler-week-state="MATCH"></section><section data-butler-week-state="MATCH"></section></body></html>'; Expected = 'FAIL'; Evidence = 'WEEK PROOF' },
    @{ Route = '/matchup'; Body = '<html><body>Matchup Weekly matchup hero-panel <section data-butler-week-state="MISMATCH"></section><p>Promote to lineup</p></body></html>'; Expected = 'FAIL'; Evidence = 'HELD ADVICE' },
    @{ Route = '/matchup'; Body = '<html><body>Matchup navigation only</body></html>'; Expected = 'FAIL'; Evidence = 'PAGE CONTENT' },
    @{ Route = '/matchup/autofill'; Body = '<html><body>Start/Sit Assistant <section class="panel butler-live-week-status" role="status" data-butler-week-state="MATCH" data-butler-week-season="2026" data-butler-week-number="5"></section><div class="target" data-butler-matchup-season="2026">League &middot; Week 5</div><section class="panel recommendation-panel start-sit-assistant">safe</section></body></html>'; Expected = 'PASS'; Evidence = 'PAIRING VERIFIED' },
    @{ Route = '/matchup/autofill'; Body = '<html><body>Start/Sit Assistant <section class="panel butler-live-week-status" role="status" data-butler-week-state="MATCH" data-butler-week-season="2026" data-butler-week-number="5"></section><div class="target" data-butler-matchup-season="2026">League &middot; Week 5</div><section class="panel recommendation-panel start-sit-assistant"><div class="callout callout-danger start-sit-blocker"><strong>Blocking evidence:</strong> BF-1066 BLOCKED: player status missing</div></section></body></html>'; Expected = 'WARN'; Evidence = 'START/SIT BLOCKED' },
    @{ Route = '/matchup/autofill'; Body = '<html><body>Start/Sit Assistant <section class="panel butler-live-week-status" role="status" data-butler-week-state="MATCH" data-butler-week-season="2026" data-butler-week-number="5"></section><div class="target" data-butler-matchup-season="2026">League &middot; Week 5</div><section class="panel recommendation-panel start-sit-assistant"><p class="lede">Butler could not prove a complete weekly lineup recommendation.</p></section></body></html>'; Expected = 'WARN'; Evidence = 'START/SIT BLOCKED' },
    @{ Route = '/matchup/autofill'; Body = '<html><body>Start/Sit Assistant <section data-butler-week-state="MATCH"></section><section class="panel recommendation-panel start-sit-assistant">safe</section></body></html>'; Expected = 'WARN'; Evidence = 'PAIRING UNVERIFIED' },
    @{ Route = '/matchup/autofill'; Body = '<html><body>Start/Sit Assistant <section data-butler-week-state="UNVERIFIED"></section><section class="panel recommendation-panel start-sit-assistant">held</section></body></html>'; Expected = 'WARN'; Evidence = 'WEEK UNVERIFIED' },
    @{ Route = '/matchup/autofill'; Body = '<html><body>Start/Sit Assistant <section data-butler-week-state="MISMATCH"></section><section class="panel recommendation-panel start-sit-assistant">Recommended starter</section></body></html>'; Expected = 'FAIL'; Evidence = 'HELD ADVICE' },
    @{ Route = '/matchup/autofill'; Body = '<html><body>Start/Sit Assistant <section class="panel recommendation-panel start-sit-assistant">safe</section></body></html>'; Expected = 'FAIL'; Evidence = 'WEEK PROOF' },
    @{ Route = '/matchup/autofill'; Body = '<html><body>Start/Sit Assistant navigation only</body></html>'; Expected = 'FAIL'; Evidence = 'PAGE CONTENT' },
    @{ Route = '/league'; Body = '<html><body>League <section class="panel"><div class="eyebrow">League hub</div><div class="league-franchise-board"><article class="league-franchise-card">Current franchise</article></div><details class="league-governance"><summary>View governed league actions</summary></details></section></body></html>'; Expected = 'PASS'; Evidence = 'PAGE RESPONSE' },
    @{ Route = '/league'; Body = '<html><body>League <section class="panel"><div class="eyebrow">League hub</div><div class="league-franchise-board"><div class="empty">Rankings not current</div></div><details class="league-governance"><summary>View governed league actions</summary></details></section></body></html>'; Expected = 'WARN'; Evidence = 'LEAGUE RANKINGS HOLD' },
    @{ Route = '/league'; Body = '<html><body>League <section class="panel"><div class="eyebrow">League hub</div><div class="league-franchise-board"></div><details class="league-governance"><summary>View governed league actions</summary></details></section></body></html>'; Expected = 'FAIL'; Evidence = 'FRANCHISE CONTENT' },
    @{ Route = '/league'; Body = '<html><body>League League intelligence Governed guidance</body></html>'; Expected = 'FAIL'; Evidence = 'PAGE CONTENT' },
    @{ Route = '/league'; Body = '<html><body>League <div class="eyebrow">League hub</div><div class="league-franchise-board"></div><summary>View governed league actions</summary></body></html>'; Expected = 'FAIL'; Evidence = 'PAGE CONTENT' },
    @{ Route = '/league'; Body = '<html><body>League <div class="eyebrow">League hub</div><details class="league-governance"><summary>View governed league actions</summary></details></body></html>'; Expected = 'FAIL'; Evidence = 'PAGE CONTENT' },
    @{ Route = '/league'; Body = '<html><body>League navigation only</body></html>'; Expected = 'FAIL'; Evidence = 'PAGE CONTENT' }
)) {
    $page.Body = $fixture.Body
    $check = Test-ButlerLivePage -Route $fixture.Route -Response $page
    if ($check.Status -cne $fixture.Expected -or $check.Evidence -cne $fixture.Evidence) {
        throw "BF-1049 BLOCKED: real page vs navigation-only $($fixture.Route) expected $($fixture.Expected)/$($fixture.Evidence), got $($check.Status)/$($check.Evidence)."
    }
}

# BF-1079: opt-in actual readiness must not mistake the Start/Sit
# decision shell for sourced, recent or otherwise usable player guidance.
# Keep ordinary non-strict page-content tests backwards compatible.
$utc = [DateTimeOffset]::UtcNow.ToString("yyyy-MM-dd'T'HH:mm:ss'Z'", [Globalization.CultureInfo]::InvariantCulture)
$startSitFrame = '<html><body>Start/Sit Assistant <section class="panel butler-live-week-status" role="status" data-butler-week-state="MATCH" data-butler-week-season="2026" data-butler-week-number="5"></section><div class="target" data-butler-matchup-season="2026">League &middot; Week 5</div><section class="panel recommendation-panel start-sit-assistant">'
$readyProof = '<p class="meta butler-startsit-source-proof" role="status">1 proposed lineup changes have exact player-status checks from Sleeper at the recorded fetch time. Full scoreable projection coverage; 0 player holds. no Sleeper move was submitted. Sources: projection snapshot retrieved ' + $utc + ' UTC; swap players status map retrieved ' + $utc + ' UTC.</p>'
$page.Body = $startSitFrame + $readyProof + '</section></body></html>'
$strict = Test-ButlerLivePage -Route '/matchup/autofill' -Response $page -RequireStartSitProvenance
if ($strict.Status -cne 'PASS' -or $strict.Evidence -cne 'PAIRING VERIFIED') {
    throw 'BF-1079 BLOCKED: current, sourced exact-player preview did not pass strict readiness.'
}
$noChangeProof = '<p class="meta butler-startsit-source-proof" role="status">No lineup change is ready. Full scoreable projection coverage; 0 player holds. If availability cannot be verified, Butler holds the move. Sources: projection snapshot retrieved ' + $utc + ' UTC; exact swap status fetch time not verified.</p>'
$page.Body = $startSitFrame + $noChangeProof + '</section></body></html>'
$noChange = Test-ButlerLivePage -Route '/matchup/autofill' -Response $page -RequireStartSitProvenance
if ($noChange.Status -cne 'PASS') {
    throw 'BF-1079 BLOCKED: current zero-change lineup with no holds was treated as a fake pending transaction.'
}
# BF-1096: an apparently valid no-change preview must not override
# contradictory holds, coverage or a quietly embedded proposed swap.
foreach ($badKeep in @(
    $noChangeProof.Replace('0 player holds.', '0 player holds. 10 player holds.'),
    $noChangeProof.Replace('Full scoreable projection coverage;', 'Full scoreable projection coverage; Full scoreable projection coverage;'),
    $noChangeProof.Replace('No lineup change is ready.', '0 proposed lineup changes have exact player-status checks from Sleeper at the recorded fetch time.'),
    $noChangeProof.Replace('Sources:', '1 proposed lineup changes. Sources:'),
    $noChangeProof.Replace('No lineup change is ready.', 'Perhaps No lineup change is ready.')
)) {
    $page.Body = $startSitFrame + $badKeep + '</section></body></html>'
    $heldKeep = Test-ButlerLivePage -Route '/matchup/autofill' -Response $page -RequireStartSitProvenance
    if ($heldKeep.Status -cne 'WARN' -or $heldKeep.Evidence -cne 'START/SIT SOURCE HOLD') {
        throw 'BF-1096 BLOCKED: contradictory no-change source was certified.'
    }
}
foreach ($bad in @(
    '',
    ($readyProof + $readyProof),
    ($readyProof.Replace('Full scoreable projection coverage', 'Partial projection coverage; missing projections are not zeros')),
    ($readyProof.Replace('0 player holds', '2 player holds')),
    ($readyProof.Replace('0 player holds', '10 player holds')),
    ($readyProof.Replace('0 player holds', '100 player holds')),
    ($readyProof.Replace('recorded fetch time', 'unknown time')),
    ($readyProof.Replace('swap players status map retrieved ' + $utc + ' UTC', 'exact swap status fetch time not verified')),
    ($readyProof.Replace('swap players status map retrieved ' + $utc + ' UTC', 'swap players status map retrieved 2024-01-01T10:00:00Z UTC')),
    ($readyProof.Replace('projection snapshot retrieved ' + $utc + ' UTC', 'projection snapshot retrieved 2024-01-01T10:00:00Z UTC')),
    ($readyProof.Replace('projection snapshot retrieved ' + $utc + ' UTC', 'projection snapshot retrieved 2026-02-30T10:00:00Z UTC')),
    # BF-1096: the strict gate must reject contradictory or duplicative
    # counts even when it also contains one superficially valid phrase.
    ($readyProof.Replace('0 player holds.', '0 player holds. 10 player holds.')),
    ($readyProof.Replace('0 player holds.', '0 player holds. 1 player hold.')),
    ($readyProof.Replace('Full scoreable projection coverage;', 'Full scoreable projection coverage; Full scoreable projection coverage;')),
    ($readyProof.Replace('1 proposed lineup changes', '1 proposed lineup changes. 2 proposed lineup changes')),
    ($readyProof.Replace('1 proposed lineup changes', '0 proposed lineup changes')),
    ($readyProof.Replace('1 proposed lineup changes', '01 proposed lineup changes')),
    ($readyProof.Replace('1 proposed lineup changes', 'changed 1 proposed lineup changes')),
    ($readyProof.Replace('no Sleeper move was submitted', 'Sleeper move submitted'))
)) {
    $page.Body = $startSitFrame + $bad + '</section></body></html>'
    $held = Test-ButlerLivePage -Route '/matchup/autofill' -Response $page -RequireStartSitProvenance
    if ($held.Status -cne 'WARN' -or $held.Evidence -cne 'START/SIT SOURCE HOLD') {
        throw 'BF-1079 BLOCKED: unsourced, stale, incomplete or duplicate lineup proof was certified.'
    }
}
$page.Body = $startSitFrame + 'safe no proof</section></body></html>'
$legacy = Test-ButlerLivePage -Route '/matchup/autofill' -Response $page
if ($legacy.Status -cne 'PASS' -or $legacy.Evidence -cne 'PAIRING VERIFIED') {
    throw 'BF-1079 BLOCKED: new opt-in strict mode unexpectedly changed the legacy page diagnostic.'
}
if ($source.IndexOf('Test-ButlerLivePage -Route $route -Response $response -RequireStartSitProvenance:$RequireReady',
    [StringComparison]::Ordinal) -lt 0) {
    throw 'BF-1079 BLOCKED: real one-click readiness did not enable the source-provenance check.'
}

# BF-1072: independently valid route pages cannot describe different
# leagues/weeks while the all-pages smoke claims everything agrees.
$verified = [pscustomobject]@{ Status = 'PASS'; Evidence = 'PAIRING VERIFIED' }
$held = [pscustomobject]@{ Status = 'WARN'; Evidence = 'WEEK UNVERIFIED' }
$matchupPage = '<div class="target" data-butler-matchup-season="2026">Test league &middot; Week 5</div>'
$startSitPage = '<div class="target" data-butler-matchup-season="2026">Test league &middot; Week 5</div>'
$aligned = Test-ButlerCrossRouteWeek -MatchupHtml $matchupPage -StartSitHtml $startSitPage -MatchupCheck $verified -StartSitCheck $verified
if ($aligned.Status -cne 'PASS' -or $aligned.Evidence -cne 'WEEK/LEAGUE ALIGNED') {
    throw 'BF-1072 BLOCKED: two equal, independently verified league/week pages were not aligned.'
}
foreach ($bad in @(
    ($startSitPage.Replace('Week 5', 'Week 4')),
    ($startSitPage.Replace('2026', '2025')),
    ($startSitPage.Replace('Test league', 'Other league')),
    ($startSitPage + $startSitPage),
    ($startSitPage.Replace('data-butler-matchup-season="2026"', '')),
    ($startSitPage.Replace('Test league', ''))
)) {
    $difference = Test-ButlerCrossRouteWeek -MatchupHtml $matchupPage -StartSitHtml $bad -MatchupCheck $verified -StartSitCheck $verified
    if ($difference.Status -cne 'FAIL') {
        throw 'BF-1072 BLOCKED: conflicting, duplicate or incomplete pairing appeared cross-route consistent.'
    }
}
$notBoth = Test-ButlerCrossRouteWeek -MatchupHtml $matchupPage -StartSitHtml $startSitPage -MatchupCheck $verified -StartSitCheck $held
if ($notBoth.Status -cne 'SKIP' -or $notBoth.Evidence -cne 'PAGE NOT VERIFIED') {
    throw 'BF-1072 BLOCKED: a held Start/Sit page was certified aligned.'
}
if ($source.IndexOf('Test-ButlerCrossRouteWeek -MatchupHtml', [StringComparison]::Ordinal) -lt 0 -or
    $source.IndexOf('$weekPair.Status -ceq', [StringComparison]::Ordinal) -lt 0) {
    throw 'BF-1072 BLOCKED: cross-route frame check is not enforced by the real loopback diagnostic.'
}

# BF-1053: compare the actual BF-840 rendered matchup header with only
# Sleeper's small public NFL state payload. No real Internet is used here.
$matchingMatchup = '<html><body><section class="panel butler-live-week-status" role="status" data-butler-week-state="MATCH" data-butler-week-season="2026" data-butler-week-number="5"></section><div class="target" data-butler-matchup-season="2026">Synthetic league &middot; Week 5</div><h1>Team A vs Team B</h1></body></html>'
$liveWeekFixture = '{"season":"2026","season_type":"regular","week":5,"leg":5,"display_week":5}'
$matchingWeek = Test-ButlerSleeperWeekMatch -MatchupHtml $matchingMatchup -PublicNflState $liveWeekFixture
if ($matchingWeek.Status -cne 'PASS' -or $matchingWeek.Evidence -cne 'WEEK MATCH') {
    throw 'BF-1053 BLOCKED: matching external NFL week and exact matchup header were rejected.'
}
foreach ($badComparison in @(
    @{ Html = $matchingMatchup.Replace('Week 5', 'Week 4'); Json = $liveWeekFixture; Expected = 'WEEK UNVERIFIED' },
    @{ Html = $matchingMatchup.Replace('data-butler-matchup-season="2026"', 'data-butler-matchup-season="2025"'); Json = $liveWeekFixture; Expected = 'WEEK UNVERIFIED' },
    @{ Html = $matchingMatchup.Replace('data-butler-week-number="5"', 'data-butler-week-number="4"'); Json = $liveWeekFixture; Expected = 'WEEK UNVERIFIED' },
    @{ Html = $matchingMatchup.Replace('data-butler-week-state="MATCH"', 'data-butler-week-state="UNVERIFIED"'); Json = $liveWeekFixture; Expected = 'WEEK UNVERIFIED' },
    @{ Html = $matchingMatchup.Replace('data-butler-week-season="2026"', 'data-butler-week-season="2025"'); Json = $liveWeekFixture; Expected = 'WEEK UNVERIFIED' },
    @{ Html = $matchingMatchup.Replace(' data-butler-week-number="5"', ''); Json = $liveWeekFixture; Expected = 'WEEK UNVERIFIED' },
    @{ Html = $matchingMatchup.Replace(' data-butler-week-season="2026"', ''); Json = $liveWeekFixture; Expected = 'WEEK UNVERIFIED' },
    @{ Html = $matchingMatchup.Replace(' data-butler-week-state="MATCH"', ''); Json = $liveWeekFixture; Expected = 'WEEK UNVERIFIED' },
    @{ Html = ($matchingMatchup + '<div data-butler-week-number="5"></div>'); Json = $liveWeekFixture; Expected = 'WEEK UNVERIFIED' },
    @{ Html = $matchingMatchup; Json = '{"season":"2026","season_type":"regular","week":6}'; Expected = 'WEEK MISMATCH' },
    @{ Html = $matchingMatchup; Json = '{"season":"2025","season_type":"regular","week":5}'; Expected = 'WEEK MISMATCH' },
    @{ Html = $matchingMatchup.Replace(' data-butler-matchup-season="2026"', ''); Json = $liveWeekFixture; Expected = 'WEEK UNVERIFIED' },
    @{ Html = '<html><body>Sidebar: Week 5</body></html>'; Json = $liveWeekFixture; Expected = 'WEEK UNVERIFIED' },
    @{ Html = '<html><body><div class="target" data-butler-matchup-season="2026">Synthetic league &middot; Week 5</div></body></html>'; Json = $liveWeekFixture; Expected = 'WEEK UNVERIFIED' },
    @{ Html = ($matchingMatchup + $matchingMatchup); Json = $liveWeekFixture; Expected = 'WEEK UNVERIFIED' },
    @{ Html = $matchingMatchup; Json = '{"season":"2026","season_type":"post","week":5}'; Expected = 'WEEK UNVERIFIED' },
    @{ Html = $matchingMatchup; Json = '{"season":"2026","season_type":"regular","week":25}'; Expected = 'WEEK UNVERIFIED' },
    @{ Html = $matchingMatchup; Json = '{"season":"2026","season_type":"regular","week":"5x"}'; Expected = 'WEEK UNVERIFIED' },
    @{ Html = $matchingMatchup; Json = '{"season":"2026","season_type":"regular","week":5'; Expected = 'WEEK UNVERIFIED' },
    @{ Html = $matchingMatchup; Json = '{"season":"2026","season_type":"regular"}'; Expected = 'WEEK UNVERIFIED' }
)) {
    $check = Test-ButlerSleeperWeekMatch -MatchupHtml $badComparison.Html -PublicNflState $badComparison.Json
    if ($check.Status -cne 'WARN' -or $check.Evidence -cne $badComparison.Expected) {
        throw "BF-1053 BLOCKED: invalid/old NFL matchup fixture was incorrectly marked current ($($badComparison.Expected))."
    }
}
foreach ($required in @(
    "'https://api.sleeper.app/v1/state/nfl'",
    '$request.Method = ''GET''',
    '$request.AllowAutoRedirect = $false',
    '[switch]$CheckSleeperWeek',
    'Test-ButlerSleeperWeekMatch -MatchupHtml $html -PublicNflState $nflState'
)) {
    if ($source.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-1053 BLOCKED: opt-in bounded public NFL week contract missing: $required"
    }
}

# BF-1078: default synthetic diagnostics still distinguish FAIL from
# WARN without changing their exit behavior; real-league readiness is
# stricter and signals WARN as OS exit 2, not a false 0/SUCCESS.
foreach ($required in @(
    '[switch]$RequireReady',
    'if ($RequireReady)',
    "Write-Host 'READINESS GATE: BLOCKED BY WARNINGS. No lineup/waiver advice certified.'",
    'exit 2',
    'if ($failed -gt 0)'
)) {
    if ($source.IndexOf($required, [StringComparison]::Ordinal) -lt 0) {
        throw "BF-1078 BLOCKED: optional fail-closed readiness exit contract missing: $required"
    }
}
if ($realLeagueWrapper.IndexOf('-CheckSleeperWeek -RequireReady -TimeoutSeconds 30',
    [StringComparison]::Ordinal) -lt 0) {
    throw 'BF-1078 BLOCKED: real-league one-click diagnostic did not opt into strict readiness.'
}

# BF-1077: diagnostic must measure local GET time without writing response
# bodies, player names, account IDs or any Sleeper payload into the report.
foreach ($required in @(
    '$routeTimer = [Diagnostics.Stopwatch]::StartNew()',
    '$routeTimer.Stop()',
    'get_ms={4}',
    '$localCheckStarted.Elapsed.TotalMilliseconds',
    'data-butler-week-season=',
    'data-butler-matchup-season='
)) {
    if ($source.IndexOf($required, [StringComparison]::Ordinal) -lt 0) {
        throw "BF-1077 BLOCKED: one-click GET timing/source proof contract missing: $required"
    }
}

# Validate input is rejected before opening an external or invalid connection.
foreach ($probe in @(
    @{ SelectedPort = 443; Route = '/team' },
    @{ SelectedPort = 18080; Route = '/refresh' },
    @{ SelectedPort = 18080; Route = 'http://example.com/' }
)) {
    $rejected = $false
    try {
        [void](Invoke-ButlerLocalGet -SelectedPort $probe.SelectedPort -Route $probe.Route -Seconds 5)
    }
    catch { $rejected = $true }
    if (-not $rejected) { throw 'BF-1040 BLOCKED: invalid local probe was accepted.' }
}
foreach ($required in @(
    "http://127.0.0.1:",
    '$request.Method = ''GET''',
    '$request.Proxy = $null',
    '$request.AllowAutoRedirect = $false'
)) {
    if ($source.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-1040 BLOCKED: missing safe GET contract $required"
    }
}
foreach ($forbidden in @('''POST''', 'Invoke-RestMethod', 'submitTransaction', 'setFaab', 'create_transaction')) {
    if ($source.IndexOf($forbidden, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
        throw "BF-1040 BLOCKED: live diagnostic introduced forbidden provider or transaction action $forbidden"
    }
}
if ($wrapper.IndexOf('butler-v04-live-page-check.ps1', [System.StringComparison]::Ordinal) -lt 0 -or
    $wrapper.IndexOf('-ExecutionPolicy Bypass', [System.StringComparison]::Ordinal) -lt 0) {
    throw 'BF-1040 BLOCKED: one-command Windows launcher missing.'
}
Write-Host 'BF-1040 V0.4 LIVE PAGE SMOKE FIXTURES: PASS'
Write-Host 'Coverage: exact loopback health, page response/security, current/stale/unknown Dashboard audits, stale no-transaction lineage, auto-refresh CSP, Auto-Pilot watch, GET-only command.'
