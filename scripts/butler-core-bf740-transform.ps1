param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ([string]$env:BUTLER_APP_PERSISTENT_CORE_WORKER -ceq '0') {
    throw 'BF-741 BLOCKED: persistent core worker transform is explicitly disabled by BUTLER_APP_PERSISTENT_CORE_WORKER=0.'
}
if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-740 BLOCKED: staged app core not found at $CorePath"
}

$helperSource = Join-Path $PSScriptRoot 'butler-persistent-core-worker.ps1'
if (-not (Test-Path -LiteralPath $helperSource -PathType Leaf)) {
    throw "BF-740 BLOCKED: persistent core worker helper not found at $helperSource"
}
$runtimeScripts = Split-Path -Parent $CorePath
$helperDestination = Join-Path $runtimeScripts 'butler-persistent-core-worker.ps1'

$bootstrapOriginal = @'
$loopback = [System.Net.IPAddress]::Parse("127.0.0.1")

if (-not (Test-Path -LiteralPath $dashboard)) {
'@
$bootstrapReplacement = @'
$loopback = [System.Net.IPAddress]::Parse("127.0.0.1")
. (Join-Path $scriptDir 'butler-persistent-core-worker.ps1')
# BF-741: staging is default-on; the outer staging gate omits this transform only for explicit opt-out (=0).
$script:Bf740PersistentCoreWorkerCanary = $true

if (-not (Test-Path -LiteralPath $dashboard)) {
'@

$taskOriginal = @'
function Invoke-ButlerReadOnlyTask {
    param(
        [Parameter(Mandatory = $true)][string]$Task,
        [Parameter(Mandatory = $true)][string]$Arguments,
        [Parameter(Mandatory = $true)][string]$BoundaryName
    )
    $previousPreference = $ErrorActionPreference
'@
$taskReplacement = @'
function Invoke-ButlerReadOnlyTask {
    param(
        [Parameter(Mandatory = $true)][string]$Task,
        [Parameter(Mandatory = $true)][string]$Arguments,
        [Parameter(Mandatory = $true)][string]$BoundaryName
    )
    if ($script:Bf740PersistentCoreWorkerCanary -and
        $Task -ceq ":bet:bet-cli:sleeperLiveWaiverTargetRosterContextAudit" -and
        $Arguments -ceq "$LeagueId --team-bundle") {
        return Invoke-Bf740PersistentCoreWorker -Operation 'TEAM_BUNDLE' -BoundaryName $BoundaryName
    }
    $previousPreference = $ErrorActionPreference
'@

$leagueOriginal = @'
function Invoke-ButlerLeagueOverview {
    # Keep the exact BF-667 source contract visible for regression/audit.
    $previousPreference = $ErrorActionPreference
'@
$leagueReplacement = @'
function Invoke-ButlerLeagueOverview {
    # Keep the exact BF-667 source contract visible for regression/audit.
    if ($script:Bf740PersistentCoreWorkerCanary) {
        return Invoke-Bf740PersistentCoreWorker -Operation 'LEAGUE_OVERVIEW' -BoundaryName 'BF-667'
    }
    $previousPreference = $ErrorActionPreference
'@

$startupOriginal = @'
try {
    $dashboardProcess = Start-GovernedDashboard -InnerPort $innerPort
'@
$startupReplacement = @'
try {
    if ($script:Bf740PersistentCoreWorkerCanary) {
        [void](Start-Bf740PersistentCoreWorker)
    }
    $dashboardProcess = Start-GovernedDashboard -InnerPort $innerPort
'@

$shutdownOriginal = @'
    if ($null -ne $dashboardProcess -and -not $dashboardProcess.HasExited) {
        try { $dashboardProcess.Kill() } catch {}
        try { $dashboardProcess.WaitForExit(5000) | Out-Null } catch {}
    }
    Pop-Location
}
'@
$shutdownReplacement = @'
    if ($null -ne $dashboardProcess -and -not $dashboardProcess.HasExited) {
        try { $dashboardProcess.Kill() } catch {}
        try { $dashboardProcess.WaitForExit(5000) | Out-Null } catch {}
    }
    if ($script:Bf740PersistentCoreWorkerCanary) {
        try { Stop-Bf740PersistentCoreWorker } catch {}
    }
    Pop-Location
}
'@

$text = [System.IO.File]::ReadAllText($CorePath)
$contracts = @(
    [pscustomobject]@{ Name = 'bootstrap'; Original = $bootstrapOriginal; Replacement = $bootstrapReplacement },
    [pscustomobject]@{ Name = 'team task'; Original = $taskOriginal; Replacement = $taskReplacement },
    [pscustomobject]@{ Name = 'league overview'; Original = $leagueOriginal; Replacement = $leagueReplacement },
    [pscustomobject]@{ Name = 'startup'; Original = $startupOriginal; Replacement = $startupReplacement },
    [pscustomobject]@{ Name = 'shutdown'; Original = $shutdownOriginal; Replacement = $shutdownReplacement }
)

foreach ($contract in $contracts) {
    $matches = [regex]::Matches($text, [regex]::Escape([string]$contract.Original)).Count
    if ($matches -ne 1) {
        throw "BF-740 BLOCKED: expected exactly one $($contract.Name) staging contract, found $matches."
    }
    $text = $text.Replace([string]$contract.Original, [string]$contract.Replacement)
}

if ($text.Contains($taskOriginal) -or $text.Contains($leagueOriginal)) {
    throw 'BF-740 BLOCKED: staged core still contains an untransformed persistent-worker routing contract.'
}
if (-not $text.Contains("Invoke-Bf740PersistentCoreWorker -Operation 'TEAM_BUNDLE'")) {
    throw 'BF-740 BLOCKED: staged core is missing the exact My Team persistent-worker route.'
}
if (-not $text.Contains("Invoke-Bf740PersistentCoreWorker -Operation 'LEAGUE_OVERVIEW'")) {
    throw 'BF-740 BLOCKED: staged core is missing the exact League persistent-worker route.'
}

Copy-Item -LiteralPath $helperSource -Destination $helperDestination -Force
[System.IO.File]::WriteAllText($CorePath, $text, [System.Text.UTF8Encoding]::new($false))
