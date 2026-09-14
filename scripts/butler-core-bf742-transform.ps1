param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ([string]$env:BUTLER_APP_PERSISTENT_CORE_WORKER -ceq '0') {
    throw 'BF-742 BLOCKED: shared persistent worker staging is explicitly disabled by BUTLER_APP_PERSISTENT_CORE_WORKER=0.'
}
if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-742 BLOCKED: staged app core not found at $CorePath"
}
if (-not (Test-Path -LiteralPath $DashboardPath -PathType Leaf)) {
    throw "BF-742 BLOCKED: staged dashboard not found at $DashboardPath"
}

$helperSource = Join-Path $PSScriptRoot 'butler-persistent-core-worker.ps1'
if (-not (Test-Path -LiteralPath $helperSource -PathType Leaf)) {
    throw "BF-742 BLOCKED: persistent worker helper not found at $helperSource"
}
$runtimeScripts = Split-Path -Parent $CorePath
$helperDestination = Join-Path $runtimeScripts 'butler-persistent-core-worker.ps1'

$coreBootstrapOriginal = @'
$loopback = [System.Net.IPAddress]::Parse("127.0.0.1")

if (-not (Test-Path -LiteralPath $dashboard)) {
'@
$coreBootstrapReplacement = @'
$loopback = [System.Net.IPAddress]::Parse("127.0.0.1")
$script:Bf742DashboardToken = $null
$script:Bf742InnerDashboardPort = $null

function New-Bf742DashboardToken {
    $bytes = New-Object byte[] 32
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($bytes)
    }
    finally {
        $rng.Dispose()
    }
    return [Convert]::ToBase64String($bytes)
}

if (-not (Test-Path -LiteralPath $dashboard)) {
'@

$coreLaunchOriginal = @'
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $process = [System.Diagnostics.Process]::Start($start)
'@
$coreLaunchReplacement = @'
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    if ([string]::IsNullOrWhiteSpace([string]$script:Bf742DashboardToken)) {
        throw "BF-742 BLOCKED: internal dashboard token was not initialized."
    }
    $start.EnvironmentVariables["BUTLER_APP_INTERNAL_DASHBOARD_TOKEN"] = $script:Bf742DashboardToken
    $process = [System.Diagnostics.Process]::Start($start)
'@

$coreReadOriginal = @'
function Invoke-ButlerReadOnly {
'@
$coreReadReplacement = @'
function Invoke-Bf742DashboardWorkerRead {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$BoundaryName
    )

    if ($null -eq $script:Bf742InnerDashboardPort -or [int]$script:Bf742InnerDashboardPort -le 0) {
        throw "$BoundaryName BLOCKED: BF-742 inner dashboard port is unavailable."
    }
    if ([string]::IsNullOrWhiteSpace([string]$script:Bf742DashboardToken)) {
        throw "$BoundaryName BLOCKED: BF-742 internal dashboard token is unavailable."
    }
    if ($Path -cne "/__butler/internal/team-bundle" -and $Path -cne "/__butler/internal/league-overview") {
        throw "$BoundaryName BLOCKED: BF-742 internal dashboard path is not authorized."
    }

    $request = [System.Net.HttpWebRequest]::Create("http://127.0.0.1:$($script:Bf742InnerDashboardPort)$Path")
    $request.Method = "GET"
    $request.Timeout = 180000
    $request.Headers["X-Butler-Internal-Token"] = $script:Bf742DashboardToken
    $response = $null
    try {
        $response = $request.GetResponse()
        if ([int]$response.StatusCode -ne 200) {
            throw "$BoundaryName BLOCKED: BF-742 internal dashboard worker returned HTTP $([int]$response.StatusCode)."
        }
        $reader = [System.IO.StreamReader]::new($response.GetResponseStream(), [System.Text.Encoding]::UTF8)
        try {
            return $reader.ReadToEnd()
        }
        finally {
            $reader.Dispose()
        }
    }
    catch {
        throw "$BoundaryName BLOCKED: BF-742 authenticated inner-dashboard read failed. $($_.Exception.Message)"
    }
    finally {
        if ($null -ne $response) { $response.Close() }
    }
}

function Invoke-ButlerReadOnly {
'@

$coreTaskOriginal = @'
function Invoke-ButlerReadOnlyTask {
    param(
        [Parameter(Mandatory = $true)][string]$Task,
        [Parameter(Mandatory = $true)][string]$Arguments,
        [Parameter(Mandatory = $true)][string]$BoundaryName
    )
    $previousPreference = $ErrorActionPreference
'@
$coreTaskReplacement = @'
function Invoke-ButlerReadOnlyTask {
    param(
        [Parameter(Mandatory = $true)][string]$Task,
        [Parameter(Mandatory = $true)][string]$Arguments,
        [Parameter(Mandatory = $true)][string]$BoundaryName
    )
    if ($Task -ceq ":bet:bet-cli:sleeperLiveWaiverTargetRosterContextAudit" -and
        $Arguments -ceq "$LeagueId --team-bundle") {
        return Invoke-Bf742DashboardWorkerRead -Path "/__butler/internal/team-bundle" -BoundaryName $BoundaryName
    }
    $previousPreference = $ErrorActionPreference
'@

$coreLeagueOriginal = @'
function Invoke-ButlerLeagueOverview {
    # Keep the exact BF-667 source contract visible for regression/audit.
    $previousPreference = $ErrorActionPreference
'@
$coreLeagueReplacement = @'
function Invoke-ButlerLeagueOverview {
    # Keep the exact BF-667 source contract visible for regression/audit.
    return Invoke-Bf742DashboardWorkerRead -Path "/__butler/internal/league-overview" -BoundaryName "BF-667"
    $previousPreference = $ErrorActionPreference
'@

$coreRuntimeOriginal = @'
$innerPort = Get-FreeLoopbackPort
$dashboardProcess = $null
$listener = [System.Net.Sockets.TcpListener]::new($loopback, $Port)
'@
$coreRuntimeReplacement = @'
$innerPort = Get-FreeLoopbackPort
$script:Bf742InnerDashboardPort = $innerPort
$script:Bf742DashboardToken = New-Bf742DashboardToken
$dashboardProcess = $null
$listener = [System.Net.Sockets.TcpListener]::new($loopback, $Port)
'@

$coreText = [System.IO.File]::ReadAllText($CorePath)
$coreContracts = @(
    [pscustomobject]@{ Name = 'core bootstrap'; Original = $coreBootstrapOriginal; Replacement = $coreBootstrapReplacement },
    [pscustomobject]@{ Name = 'dashboard launch token'; Original = $coreLaunchOriginal; Replacement = $coreLaunchReplacement },
    [pscustomobject]@{ Name = 'authenticated inner read helper'; Original = $coreReadOriginal; Replacement = $coreReadReplacement },
    [pscustomobject]@{ Name = 'team bundle route'; Original = $coreTaskOriginal; Replacement = $coreTaskReplacement },
    [pscustomobject]@{ Name = 'league overview route'; Original = $coreLeagueOriginal; Replacement = $coreLeagueReplacement },
    [pscustomobject]@{ Name = 'runtime token initialization'; Original = $coreRuntimeOriginal; Replacement = $coreRuntimeReplacement }
)
foreach ($contract in $coreContracts) {
    $matches = [regex]::Matches($coreText, [regex]::Escape([string]$contract.Original)).Count
    if ($matches -ne 1) {
        throw "BF-742 BLOCKED: expected exactly one $($contract.Name) staging contract, found $matches."
    }
    $coreText = $coreText.Replace([string]$contract.Original, [string]$contract.Replacement)
}

$dashboardBootstrapOriginal = @'
$loopback = [System.Net.IPAddress]::Parse("127.0.0.1")

if (-not (Test-Path -LiteralPath $gradle)) {
'@
$dashboardBootstrapReplacement = @'
$loopback = [System.Net.IPAddress]::Parse("127.0.0.1")
. (Join-Path $scriptDir 'butler-persistent-core-worker.ps1')
$script:Bf740PersistentCoreWorkerCanary = $true
$script:Bf742DashboardToken = [string]$env:BUTLER_APP_INTERNAL_DASHBOARD_TOKEN
if ([string]::IsNullOrWhiteSpace($script:Bf742DashboardToken) -or $script:Bf742DashboardToken -notmatch '^[A-Za-z0-9+/]{43}=$') {
    throw "BF-742 BLOCKED: authenticated parent-core dashboard token is missing or malformed."
}

if (-not (Test-Path -LiteralPath $gradle)) {
'@

$dashboardSummaryOriginal = @'
function Invoke-ButlerReadOnlySummary {
    return Invoke-ButlerReadOnlyTask -Task ":bet:bet-cli:sleeperLiveWaiverLatestGovernedDecisionSummary" -BoundaryName "BF-643"
}
'@
$dashboardSummaryReplacement = @'
function Invoke-ButlerReadOnlySummary {
    return Invoke-Bf740PersistentCoreWorker -Operation 'LATEST_SUMMARY' -BoundaryName "BF-643"
}
'@

$dashboardExplanationOriginal = @'
function Invoke-ButlerReadOnlyExplanationLookup {
    param([Parameter(Mandatory = $true)][string]$AuditId)
    if ([string]::IsNullOrWhiteSpace($AuditId)) {
        throw "BF-654 BLOCKED: current BF-627 audit id is missing"
    }
    $previousPreference = $ErrorActionPreference
'@
$dashboardExplanationReplacement = @'
function Invoke-ButlerReadOnlyExplanationLookup {
    param([Parameter(Mandatory = $true)][string]$AuditId)
    if ([string]::IsNullOrWhiteSpace($AuditId)) {
        throw "BF-654 BLOCKED: current BF-627 audit id is missing"
    }
    return Invoke-Bf740PersistentCoreWorker -Operation 'EXPLANATION_LOOKUP' -BoundaryName "BF-654" -AuditId $AuditId
    $previousPreference = $ErrorActionPreference
'@

$dashboardWaiverOriginal = @'
function Invoke-ButlerReadOnlyWaiverEvidenceBundle {
    $previousPreference = $ErrorActionPreference
'@
$dashboardWaiverReplacement = @'
function Invoke-ButlerReadOnlyWaiverEvidenceBundle {
    $text = Invoke-Bf740PersistentCoreWorker -Operation 'WAIVER_DASHBOARD_BUNDLE' -BoundaryName "BF-715"
    return [pscustomobject]@{
        Summary = Get-Bf715WaiverBundleSection -Text $text -Name "SUMMARY"
        WaiverBoard = Get-Bf715WaiverBundleSection -Text $text -Name "WAIVER_BOARD"
        RosterContext = Get-Bf715WaiverBundleSection -Text $text -Name "ROSTER_CONTEXT"
    }
    $previousPreference = $ErrorActionPreference
'@

$dashboardHeaderOriginal = @'
            while ($true) {
                $headerLine = $reader.ReadLine()
                if ($null -eq $headerLine -or $headerLine.Length -eq 0) { break }
            }
'@
$dashboardHeaderReplacement = @'
            $bf742InternalTokenHeader = $null
            while ($true) {
                $headerLine = $reader.ReadLine()
                if ($null -eq $headerLine -or $headerLine.Length -eq 0) { break }
                $tokenMatch = [regex]::Match($headerLine, '^X-Butler-Internal-Token:\s*(?<token>\S+)\s*$', [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
                if ($tokenMatch.Success) {
                    $bf742InternalTokenHeader = $tokenMatch.Groups['token'].Value
                }
            }
'@

$dashboardInternalOriginal = @'
            $candidateMatch = [regex]::Match($path, '^/waivers/candidate/(?<id>[0-9]+)$')
'@
$dashboardInternalReplacement = @'
            $bf742InternalOperation = $null
            $bf742InternalBoundary = $null
            if ($path -ceq "/__butler/internal/team-bundle") {
                $bf742InternalOperation = 'TEAM_BUNDLE'
                $bf742InternalBoundary = 'BF-692'
            }
            elseif ($path -ceq "/__butler/internal/league-overview") {
                $bf742InternalOperation = 'LEAGUE_OVERVIEW'
                $bf742InternalBoundary = 'BF-667'
            }
            if ($null -ne $bf742InternalOperation) {
                if ([string]::IsNullOrWhiteSpace($bf742InternalTokenHeader) -or $bf742InternalTokenHeader -cne $script:Bf742DashboardToken) {
                    Send-HttpResponse -Stream $stream -StatusCode 403 -StatusText "Forbidden" -ContentType "text/plain; charset=utf-8" -Body "Forbidden"
                    continue
                }
                try {
                    $internalBody = Invoke-Bf740PersistentCoreWorker -Operation $bf742InternalOperation -BoundaryName $bf742InternalBoundary
                    Send-HttpResponse -Stream $stream -StatusCode 200 -StatusText "OK" -ContentType "text/plain; charset=utf-8" -Body $internalBody
                }
                catch {
                    Send-HttpResponse -Stream $stream -StatusCode 500 -StatusText "Internal Server Error" -ContentType "text/plain; charset=utf-8" -Body ("BF-742 BLOCKED: " + $_.Exception.Message)
                }
                continue
            }

            $candidateMatch = [regex]::Match($path, '^/waivers/candidate/(?<id>[0-9]+)$')
'@

$dashboardStartupOriginal = @'
$listener = [System.Net.Sockets.TcpListener]::new($loopback, $Port)
Push-Location $repoRoot
try {
    $listener.Start()
'@
$dashboardStartupReplacement = @'
$listener = [System.Net.Sockets.TcpListener]::new($loopback, $Port)
Push-Location $repoRoot
try {
    [void](Start-Bf740PersistentCoreWorker)
    $listener.Start()
'@

$dashboardShutdownOriginal = @'
finally {
    $listener.Stop()
    Pop-Location
}
'@
$dashboardShutdownReplacement = @'
finally {
    try { $listener.Stop() } catch {}
    try { Stop-Bf740PersistentCoreWorker } catch {}
    Pop-Location
}
'@

$dashboardText = [System.IO.File]::ReadAllText($DashboardPath)
$dashboardContracts = @(
    [pscustomobject]@{ Name = 'dashboard worker bootstrap'; Original = $dashboardBootstrapOriginal; Replacement = $dashboardBootstrapReplacement },
    [pscustomobject]@{ Name = 'home summary worker read'; Original = $dashboardSummaryOriginal; Replacement = $dashboardSummaryReplacement },
    [pscustomobject]@{ Name = 'explanation worker read'; Original = $dashboardExplanationOriginal; Replacement = $dashboardExplanationReplacement },
    [pscustomobject]@{ Name = 'waiver bundle worker read'; Original = $dashboardWaiverOriginal; Replacement = $dashboardWaiverReplacement },
    [pscustomobject]@{ Name = 'internal token header'; Original = $dashboardHeaderOriginal; Replacement = $dashboardHeaderReplacement },
    [pscustomobject]@{ Name = 'private worker endpoints'; Original = $dashboardInternalOriginal; Replacement = $dashboardInternalReplacement },
    [pscustomobject]@{ Name = 'dashboard worker startup'; Original = $dashboardStartupOriginal; Replacement = $dashboardStartupReplacement },
    [pscustomobject]@{ Name = 'dashboard worker shutdown'; Original = $dashboardShutdownOriginal; Replacement = $dashboardShutdownReplacement }
)
foreach ($contract in $dashboardContracts) {
    $matches = [regex]::Matches($dashboardText, [regex]::Escape([string]$contract.Original)).Count
    if ($matches -ne 1) {
        throw "BF-742 BLOCKED: expected exactly one $($contract.Name) staging contract, found $matches."
    }
    $dashboardText = $dashboardText.Replace([string]$contract.Original, [string]$contract.Replacement)
}

if (-not $coreText.Contains('/__butler/internal/team-bundle') -or
    -not $coreText.Contains('/__butler/internal/league-overview') -or
    -not $coreText.Contains('X-Butler-Internal-Token')) {
    throw 'BF-742 BLOCKED: staged core is missing authenticated inner-dashboard routing.'
}
if ($coreText.Contains('Start-Bf740PersistentCoreWorker')) {
    throw 'BF-742 BLOCKED: staged core still owns a JVM worker; BF-742 requires dashboard ownership only.'
}
foreach ($required in @(
    "Invoke-Bf740PersistentCoreWorker -Operation 'LATEST_SUMMARY'",
    "Invoke-Bf740PersistentCoreWorker -Operation 'WAIVER_DASHBOARD_BUNDLE'",
    "Invoke-Bf740PersistentCoreWorker -Operation 'EXPLANATION_LOOKUP'",
    "Start-Bf740PersistentCoreWorker",
    'X-Butler-Internal-Token',
    '/__butler/internal/team-bundle',
    '/__butler/internal/league-overview'
)) {
    if (-not $dashboardText.Contains($required)) {
        throw "BF-742 BLOCKED: staged dashboard is missing required shared-worker contract: $required"
    }
}
if ($dashboardText.Contains('/refresh')) {
    throw 'BF-742 BLOCKED: staged dashboard shared-worker transform introduced /refresh unexpectedly.'
}

Copy-Item -LiteralPath $helperSource -Destination $helperDestination -Force
[System.IO.File]::WriteAllText($CorePath, $coreText, [System.Text.UTF8Encoding]::new($false))
[System.IO.File]::WriteAllText($DashboardPath, $dashboardText, [System.Text.UTF8Encoding]::new($false))
