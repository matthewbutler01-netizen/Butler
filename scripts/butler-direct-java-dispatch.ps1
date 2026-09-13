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
elif ($normalizedArguments.StartsWith('--args=', [System.StringComparison]::Ordinal)) {
    $normalizedArguments = $normalizedArguments.Substring(7)
    if (-not [string]::IsNullOrWhiteSpace($remainder)) {
        $normalizedArguments = ($normalizedArguments.TrimEnd() + ' ' + $remainder).Trim()
    }
}
elif (-not [string]::IsNullOrWhiteSpace($remainder)) {
    $normalizedArguments = ($normalizedArguments + ' ' + $remainder).Trim()
}

$mainArguments = @()
if (-not [string]::IsNullOrWhiteSpace($normalizedArguments)) {
    $mainArguments = @($normalizedArguments.Trim() -split '\s+')
}

$classPath = Join-Path $runtimeLib '*'
$previousPreference = $ErrorActionPreference
$exitCode = $null
Push-Location $workingDir
try {
    try {
        $ErrorActionPreference = 'Continue'
        & $java '--enable-native-access=ALL-UNNAMED' '-cp' $classPath $mainClass @mainArguments
        $exitCode = $LASTEXITCODE
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
