param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$RuntimeZip,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$SleeperLeagueId,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$SleeperUsername,

    [switch]$VerifyOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$packageRoot = Split-Path -Parent $scriptDir
$runtimeLibDir = Join-Path $packageRoot 'bet\bet-cli\build\install\bet-cli\lib'
$javaPreflight = Join-Path $scriptDir 'butler-java-preflight.ps1'
$setupLaunch = Join-Path $scriptDir 'butler-setup-launch.ps1'

function Get-LocalAppData {
    $value = [string]$env:LOCALAPPDATA
    if ([string]::IsNullOrWhiteSpace($value)) {
        $value = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)
    }
    if ([string]::IsNullOrWhiteSpace($value)) {
        throw 'MVP SETUP BLOCKED: LocalApplicationData is unavailable.'
    }
    return $value
}

function Resolve-TargetDataDir {
    param([Parameter(Mandatory = $true)][string]$ConfigDir)

    $configured = [string]$env:BUTLER_APP_DATA_DIR
    if ([string]::IsNullOrWhiteSpace($configured)) {
        $candidate = Join-Path $ConfigDir 'data'
    }
    else {
        if (-not [IO.Path]::IsPathRooted($configured)) {
            throw 'MVP SETUP BLOCKED: BUTLER_APP_DATA_DIR must be an absolute path.'
        }
        $candidate = $configured
    }

    $resolved = [IO.Path]::GetFullPath($candidate)
    $package = [IO.Path]::GetFullPath($packageRoot).TrimEnd('\')
    if ($resolved.Equals($package, [StringComparison]::OrdinalIgnoreCase) -or
        $resolved.StartsWith($package + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw 'MVP SETUP BLOCKED: Butler runtime data must remain outside the source/package tree.'
    }
    return $resolved
}

function Get-BoundedTail {
    param([AllowNull()][string]$Text, [int]$Limit = 2600)

    if ([string]::IsNullOrWhiteSpace($Text)) { return 'no task output' }
    $normalized = [regex]::Replace($Text, '\s+', ' ').Trim()
    if ($normalized.Length -le $Limit) { return $normalized }
    return '...' + $normalized.Substring($normalized.Length - $Limit)
}

function Invoke-ButlerJava {
    param(
        [Parameter(Mandatory = $true)][string]$MainClass,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$Label
    )

    Write-Host ("MVP SETUP: {0}..." -f $Label)
    $previousPreference = $ErrorActionPreference
    $lines = $null
    $exitCode = $null
    $javaArgs = @('--enable-native-access=ALL-UNNAMED', '-cp', $script:classPath, $MainClass) + @($Arguments)

    Push-Location $script:stagingDir
    try {
        try {
            $ErrorActionPreference = 'Continue'
            $lines = & $script:java $javaArgs 2>&1
            $exitCode = $LASTEXITCODE
        }
        finally {
            $ErrorActionPreference = $previousPreference
        }
    }
    finally {
        Pop-Location
    }

    $text = (($lines | ForEach-Object { "$_" }) -join [Environment]::NewLine)
    if ($exitCode -ne 0) {
        throw ("MVP SETUP BLOCKED: {0} failed with exit code {1}; output={2}" -f
            $Label, $exitCode, (Get-BoundedTail -Text $text))
    }
    Write-Host ("MVP SETUP: {0} complete." -f $Label)
    return $text
}

Write-Host 'Butler MVP fresh-league setup'
Write-Host 'Boundary: Butler-local data creation and read-only/provider evidence acquisition only.'
Write-Host 'Boundary: no Sleeper lineup, waiver, trade, FAAB, cancel, replace, or transaction write is performed.'

if ($SleeperLeagueId.Trim() -notmatch '^\d+$') {
    throw 'MVP SETUP BLOCKED: SleeperLeagueId must contain digits only.'
}
$SleeperLeagueId = $SleeperLeagueId.Trim()
$SleeperUsername = $SleeperUsername.Trim()
if ([string]::IsNullOrWhiteSpace($SleeperUsername)) {
    throw 'MVP SETUP BLOCKED: SleeperUsername must not be blank.'
}

$zipPath = [IO.Path]::GetFullPath($RuntimeZip)
if (-not (Test-Path -LiteralPath $zipPath -PathType Leaf)) {
    throw "MVP SETUP BLOCKED: runtime ZIP not found at $zipPath"
}
if (-not (Test-Path -LiteralPath ($zipPath + '.sha256') -PathType Leaf)) {
    throw "MVP SETUP BLOCKED: matching runtime checksum sidecar not found at $($zipPath + '.sha256')"
}

foreach ($required in @($javaPreflight, $setupLaunch, $runtimeLibDir)) {
    if (-not (Test-Path -LiteralPath $required)) {
        throw "MVP SETUP BLOCKED: required packaged component not found at $required"
    }
}
$runtimeJars = @(Get-ChildItem -LiteralPath $runtimeLibDir -Filter '*.jar' -File -ErrorAction Stop)
if ($runtimeJars.Count -eq 0 -or @($runtimeJars | Where-Object { $_.Name -like 'bet-cli*.jar' }).Count -ne 1) {
    throw 'MVP SETUP BLOCKED: packaged Butler runtime JAR set is incomplete or ambiguous.'
}

$javaInfo = & $javaPreflight -PassThru
$script:java = [string]$javaInfo.Executable
$script:classPath = Join-Path $runtimeLibDir '*'

$localAppData = Get-LocalAppData
$configDir = Join-Path $localAppData 'Butler'
$targetDataDir = Resolve-TargetDataDir -ConfigDir $configDir
$targetDatabase = Join-Path $targetDataDir 'butler.db'
$configPath = Join-Path $configDir 'app-league.txt'

if (Test-Path -LiteralPath $targetDatabase -PathType Leaf) {
    throw "MVP SETUP BLOCKED: an existing Butler database is already installed at $targetDatabase. Use normal launch or backup/restore instead of fresh-league setup."
}
if (Test-Path -LiteralPath $configPath -PathType Leaf) {
    throw "MVP SETUP BLOCKED: an existing Butler league selection is already saved at $configPath. Preserve it and use normal launch or backup/restore instead of fresh-league setup."
}

[IO.Directory]::CreateDirectory($configDir) | Out-Null
$script:stagingDir = Join-Path $configDir ('fresh-league-staging-' + [Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($script:stagingDir) | Out-Null
$installed = $false
$originalDataDir = [string]$env:BUTLER_APP_DATA_DIR

try {
    $env:BUTLER_APP_DATA_DIR = $script:stagingDir

    $syncText = Invoke-ButlerJava -MainClass 'io.butler.bet.cli.ButlerMain' -Arguments @('sleeper', 'sync-all', $SleeperLeagueId) -Label 'importing Sleeper league, rosters, draft picks, and DynastyProcess values'

    $leagueMatch = [regex]::Match($syncText, '(?m)^League ID:\s*(?<id>[0-9a-fA-F-]{36})\s*$')
    if (-not $leagueMatch.Success) {
        throw 'MVP SETUP BLOCKED: full league sync completed without one parseable Butler league UUID.'
    }
    $parsedLeague = [Guid]::Empty
    if (-not [Guid]::TryParse($leagueMatch.Groups['id'].Value, [ref]$parsedLeague)) {
        throw 'MVP SETUP BLOCKED: imported Butler league identifier is not a UUID.'
    }
    $butlerLeagueId = $parsedLeague.ToString('D').ToLowerInvariant()
    Write-Host "MVP SETUP: Butler league resolved automatically: $butlerLeagueId"

    [void](Invoke-ButlerJava -MainClass 'io.butler.bet.cli.ButlerSleeperPersonalTargetBindCli' -Arguments @($butlerLeagueId, $SleeperUsername, $SleeperLeagueId) -Label 'binding your Sleeper account to My Team')

    $stages = @(
        [pscustomobject]@{ MainClass = 'io.butler.bet.cli.ButlerSleeperCurrentWeekMatchupSyncCli'; Label = 'synchronizing the current weekly matchup' },
        [pscustomobject]@{ MainClass = 'io.butler.bet.cli.ButlerSleeperLiveWaiverSnapshotSyncCli'; Label = 'building the current waiver candidate frame' },
        [pscustomobject]@{ MainClass = 'io.butler.bet.cli.ButlerSleeperLiveWaiverMarketAttentionSyncCli'; Label = 'capturing waiver market attention' },
        [pscustomobject]@{ MainClass = 'io.butler.bet.cli.ButlerSleeperLiveWaiverProductionHydrationCli'; Label = 'hydrating governed prior-season production' },
        [pscustomobject]@{ MainClass = 'io.butler.bet.cli.ButlerSleeperLiveWaiverAvailabilitySyncCli'; Label = 'capturing current availability evidence' },
        [pscustomobject]@{ MainClass = 'io.butler.bet.cli.ButlerSleeperLiveWaiverCurrentWeekStatSyncCli'; Label = 'capturing current-week stat evidence' },
        [pscustomobject]@{ MainClass = 'io.butler.bet.cli.ButlerSleeperLiveWaiverTargetRosterProductionHydrationCli'; Label = 'hydrating My Team production evidence' },
        [pscustomobject]@{ MainClass = 'io.butler.bet.cli.ButlerSleeperLiveWaiverFinalRecommendationBundleCli'; Label = 'building the first governed waiver decision' },
        [pscustomobject]@{ MainClass = 'io.butler.bet.cli.ButlerSleeperLiveWaiverRecommendationAuditCaptureCli'; Label = 'capturing the first decision history record' },
        [pscustomobject]@{ MainClass = 'io.butler.bet.cli.ButlerSleeperLiveWaiverLatestGovernedDecisionSummaryCli'; Label = 'verifying the first governed decision summary' }
    )

    foreach ($stage in $stages) {
        [void](Invoke-ButlerJava -MainClass $stage.MainClass -Arguments @($butlerLeagueId) -Label $stage.Label)
    }

    $stagedDatabase = Join-Path $script:stagingDir 'butler.db'
    if (-not (Test-Path -LiteralPath $stagedDatabase -PathType Leaf)) {
        throw 'MVP SETUP BLOCKED: staged setup completed without a Butler database.'
    }
    foreach ($suffix in @('-wal', '-shm', '-journal')) {
        if (Test-Path -LiteralPath ($stagedDatabase + $suffix)) {
            throw "MVP SETUP BLOCKED: staged database still has live SQLite sidecar $suffix; installation was not attempted."
        }
    }

    [IO.Directory]::CreateDirectory($targetDataDir) | Out-Null
    if (Test-Path -LiteralPath $targetDatabase) {
        throw "MVP SETUP BLOCKED: target database appeared during setup at $targetDatabase; refusing to overwrite it."
    }

    Move-Item -LiteralPath $stagedDatabase -Destination $targetDatabase
    try {
        [IO.File]::WriteAllText($configPath, ($butlerLeagueId + [Environment]::NewLine), [Text.Encoding]::ASCII)
    }
    catch {
        Move-Item -LiteralPath $targetDatabase -Destination $stagedDatabase -Force
        throw
    }
    $installed = $true

    Write-Host 'MVP SETUP: governed Butler data installed.'
    Write-Host "MVP SETUP: Data: $targetDataDir"
    Write-Host "MVP SETUP: League: $butlerLeagueId"
}
finally {
    if ([string]::IsNullOrWhiteSpace($originalDataDir)) {
        Remove-Item Env:BUTLER_APP_DATA_DIR -ErrorAction SilentlyContinue
    }
    else {
        $env:BUTLER_APP_DATA_DIR = $originalDataDir
    }
    if (-not $installed -and (Test-Path -LiteralPath $script:stagingDir)) {
        Remove-Item -LiteralPath $script:stagingDir -Recurse -Force -ErrorAction SilentlyContinue
    }
}

if (Test-Path -LiteralPath $script:stagingDir) {
    Remove-Item -LiteralPath $script:stagingDir -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host 'MVP SETUP: running existing package/setup and seven-page launch verification.'
$launchArgs = @{ RuntimeZip = $zipPath }
if ($VerifyOnly) { $launchArgs.VerifyOnly = $true }
& $setupLaunch @launchArgs
if ($LASTEXITCODE -ne 0) {
    throw 'MVP SETUP INSTALLED BUT LAUNCH VERIFICATION BLOCKED: Butler data is installed. Fix the reported launch blocker, then run scripts\butler-setup-launch.cmd with the same -RuntimeZip; do not rerun fresh-league setup.'
}

Write-Host 'BUTLER MVP FRESH LEAGUE SETUP: PASS'
Write-Host 'Boundary verified: Butler-local evidence only; no Sleeper transaction write was submitted.'
