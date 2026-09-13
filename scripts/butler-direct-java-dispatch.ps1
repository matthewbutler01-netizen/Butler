param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$Task,

    [AllowEmptyString()]
    [string]$ArgumentText = '',

    [AllowEmptyString()]
    [string]$ArgumentRemainder = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-ButlerDashboardAncestorPid {
    $currentPid = $PID
    for ($depth = 0; $depth -lt 6; $depth++) {
        try {
            $process = Get-CimInstance Win32_Process -Filter ("ProcessId = {0}" -f $currentPid) -ErrorAction Stop
        }
        catch {
            return $null
        }
        if ($null -eq $process) { return $null }
        $commandLine = [string]$process.CommandLine
        if ($commandLine -like '*butler-dashboard.ps1*') {
            return [int]$process.ProcessId
        }
        $currentPid = [int]$process.ParentProcessId
        if ($currentPid -le 0) { break }
    }
    return $null
}

function Get-Bf713RosterCachePath {
    param(
        [Parameter(Mandatory = $true)][int]$DashboardPid,
        [Parameter(Mandatory = $true)][string]$LeagueId
    )
    $runtimeRoot = Split-Path -Parent $PSScriptRoot
    $cacheRoot = Join-Path $runtimeRoot '.bf713-waiver-context-cache'
    $safeLeague = $LeagueId -replace '[^A-Za-z0-9_.-]', '_'
    return Join-Path $cacheRoot ("{0}-{1}.roster.txt" -f $DashboardPid, $safeLeague)
}

function Write-Bf713CacheText {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Text
    )
    $directory = Split-Path -Parent $Path
    if (-not (Test-Path -LiteralPath $directory -PathType Container)) {
        New-Item -ItemType Directory -Path $directory -Force | Out-Null
    }
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $Text, $utf8)
}

function Read-Bf713FreshCacheText {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $null }
    $item = Get-Item -LiteralPath $Path -ErrorAction Stop
    $ageSeconds = ([DateTime]::UtcNow - $item.LastWriteTimeUtc).TotalSeconds
    if ($ageSeconds -gt 30) {
        Remove-Item -LiteralPath $Path -Force -ErrorAction SilentlyContinue
        return $null
    }
    $text = [System.IO.File]::ReadAllText($Path)
    Remove-Item -LiteralPath $Path -Force -ErrorAction SilentlyContinue
    return $text
}

function Get-Bf712BundleSection {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Name
    )
    $begin = "===BUTLER_WAIVER_BUNDLE:${Name}:BEGIN==="
    $end = "===BUTLER_WAIVER_BUNDLE:${Name}:END==="
    $start = $Text.IndexOf($begin, [System.StringComparison]::Ordinal)
    if ($start -lt 0) { throw "BF-712 BLOCKED: waiver evidence bundle is missing $Name begin marker." }
    $bodyStart = $start + $begin.Length
    $finish = $Text.IndexOf($end, $bodyStart, [System.StringComparison]::Ordinal)
    if ($finish -lt 0) { throw "BF-712 BLOCKED: waiver evidence bundle is missing $Name end marker." }
    $body = $Text.Substring($bodyStart, $finish - $bodyStart).Trim()
    if ([string]::IsNullOrWhiteSpace($body)) { throw "BF-712 BLOCKED: waiver evidence bundle section $Name is empty." }
    return $body
}

$runtimeLib = [string]$env:BUTLER_APP_RUNTIME_LIB
if ([string]::IsNullOrWhiteSpace($runtimeLib) -or -not (Test-Path -LiteralPath $runtimeLib -PathType Container)) {
    [Console]::Error.WriteLine('BF-704 BLOCKED: prepared Butler runtime library directory is unavailable.')
    exit 2
}
$repoRoot = [string]$env:BUTLER_APP_REPO_ROOT
if ([string]::IsNullOrWhiteSpace($repoRoot) -or -not (Test-Path -LiteralPath $repoRoot -PathType Container)) {
    [Console]::Error.WriteLine('BF-704 BLOCKED: Butler repository root is unavailable.')
    exit 2
}
$workingDir = Join-Path $repoRoot 'bet\bet-cli'
if (-not (Test-Path -LiteralPath $workingDir -PathType Container)) {
    [Console]::Error.WriteLine('BF-705 BLOCKED: Butler bet-cli working directory is unavailable.')
    exit 2
}

$java = $null
if (-not [string]::IsNullOrWhiteSpace([string]$env:JAVA_HOME)) {
    $candidate = Join-Path $env:JAVA_HOME 'bin\java.exe'
    if (Test-Path -LiteralPath $candidate -PathType Leaf) {
        $java = $candidate
    }
}
if ([string]::IsNullOrWhiteSpace([string]$java)) {
    try {
        $javaCommand = Get-Command java.exe -ErrorAction Stop
        $java = $javaCommand.Source
    }
    catch {
        [Console]::Error.WriteLine('BF-704 BLOCKED: Java executable is unavailable.')
        exit 2
    }
}

$mainClass = switch ($Task) {
    ':bet:bet-cli:run' { 'io.butler.bet.cli.ButlerCommandRouter'; break }
    ':bet:bet-cli:sleeperLiveWaiverTargetRosterContextAudit' { 'io.butler.bet.cli.ButlerSleeperLiveWaiverTargetRosterContextAuditCli'; break }
    ':bet:bet-cli:sleeperLiveWaiverLatestGovernedDecisionSummary' { 'io.butler.bet.cli.ButlerSleeperLiveWaiverLatestGovernedDecisionSummaryCli'; break }
    ':bet:bet-cli:sleeperLiveWaiverComparisonBundle' { 'io.butler.bet.cli.ButlerSleeperLiveWaiverComparisonBundleCli'; break }
    ':bet:bet-cli:sleeperLiveWaiverGovernedExplanationLookup' { 'io.butler.bet.cli.ButlerSleeperLiveWaiverGovernedExplanationLookupCli'; break }
    default { $null }
}

if ([string]::IsNullOrWhiteSpace([string]$mainClass)) {
    [Console]::Error.WriteLine(("BF-704 BLOCKED: interactive Gradle task is not authorized for direct Java execution: {0}" -f $Task))
    exit 2
}

$normalizedArguments = ([string]$ArgumentText).Trim()
$remainder = ([string]$ArgumentRemainder).Trim()
if ($normalizedArguments -ceq '--args') {
    if ([string]::IsNullOrWhiteSpace($remainder)) {
        [Console]::Error.WriteLine('BF-710 BLOCKED: Gradle-compatible --args token was split but no argument value followed it.')
        exit 2
    }
    $normalizedArguments = $remainder
}
elseif ($normalizedArguments.StartsWith('--args=', [System.StringComparison]::Ordinal)) {
    $normalizedArguments = $normalizedArguments.Substring(7)
    if (-not [string]::IsNullOrWhiteSpace($remainder)) {
        $normalizedArguments = ($normalizedArguments.TrimEnd() + ' ' + $remainder).Trim()
    }
}
elseif (-not [string]::IsNullOrWhiteSpace($remainder)) {
    $normalizedArguments = ($normalizedArguments + ' ' + $remainder).Trim()
}

$mainArguments = @()
if (-not [string]::IsNullOrWhiteSpace($normalizedArguments)) {
    $mainArguments = @($normalizedArguments.Trim() -split '\s+')
}

$rosterCachePath = $null
if ($mainArguments.Count -eq 1 -and -not [string]::IsNullOrWhiteSpace([string]$mainArguments[0])) {
    $dashboardPid = Get-ButlerDashboardAncestorPid
    if ($null -ne $dashboardPid) {
        $rosterCachePath = Get-Bf713RosterCachePath -DashboardPid $dashboardPid -LeagueId ([string]$mainArguments[0])
    }
}

if ($null -ne $rosterCachePath -and $Task -eq ':bet:bet-cli:sleeperLiveWaiverTargetRosterContextAudit') {
    $cachedRoster = Read-Bf713FreshCacheText -Path $rosterCachePath
    if ($null -ne $cachedRoster) {
        [Console]::Out.WriteLine($cachedRoster)
        exit 0
    }
}

$classPath = Join-Path $runtimeLib '*'
$previousPreference = $ErrorActionPreference
$exitCode = $null
Push-Location $workingDir
try {
    try {
        $ErrorActionPreference = 'Continue'
        if ($null -ne $rosterCachePath -and $Task -eq ':bet:bet-cli:sleeperLiveWaiverComparisonBundle') {
            $bundleLines = & $java '--enable-native-access=ALL-UNNAMED' '-cp' $classPath `
                'io.butler.bet.cli.ButlerSleeperLiveWaiverTargetRosterContextAuditCli' `
                ([string]$mainArguments[0]) '--waiver-board-context-bundle' 2>&1
            $exitCode = $LASTEXITCODE
            $bundleText = ($bundleLines | ForEach-Object { "$_" }) -join "`n"
            if ($exitCode -eq 0) {
                $comparison = Get-Bf712BundleSection -Text $bundleText -Name 'WAIVER_BOARD'
                $roster = Get-Bf712BundleSection -Text $bundleText -Name 'ROSTER_CONTEXT'
                Write-Bf713CacheText -Path $rosterCachePath -Text $roster
                [Console]::Out.WriteLine($comparison)
            }
            else {
                [Console]::Error.WriteLine($bundleText)
            }
        }
        else {
            & $java '--enable-native-access=ALL-UNNAMED' '-cp' $classPath $mainClass @mainArguments
            $exitCode = $LASTEXITCODE
        }
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }
}
finally {
    Pop-Location
}

if ($null -eq $exitCode) {
    exit 2
}
exit $exitCode
