param(
    [string]$RuntimeZip,
    [string]$SleeperUsername,
    [string]$SleeperLeagueId,
    [string]$DataDir,
    [switch]$VerifyOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$packageRoot = Split-Path -Parent $PSScriptRoot
$shell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$setupCheck = Join-Path $PSScriptRoot 'butler-setup-check.ps1'
$setupLaunch = Join-Path $PSScriptRoot 'butler-setup-launch.ps1'
$javaPreflight = Join-Path $PSScriptRoot 'butler-java-preflight.ps1'
$runtimeLibDir = Join-Path $packageRoot 'bet\bet-cli\build\install\bet-cli\lib'
$stagingRoot = $null
$stagedDatabase = $null
$finalDatabase = $null
$tempDatabase = $null
$committed = $false
$databaseInstalled = $false
$selectionInstalled = $false
$originalDataDir = [string]$env:BUTLER_APP_DATA_DIR

function Get-BoundedTail {
    param([AllowNull()][object[]]$Lines, [int]$Limit = 2400)
    if ($null -eq $Lines -or @($Lines).Count -eq 0) { return 'no captured output' }
    $text = ((@($Lines) | ForEach-Object { "$_" }) -join ' ')
    $text = [regex]::Replace($text, '\s+', ' ').Trim()
    if ($text.Length -le $Limit) { return $text }
    return '...' + $text.Substring($text.Length - $Limit)
}

function Require-Text {
    param([AllowNull()][string]$Value, [string]$Label)
    if ([string]::IsNullOrWhiteSpace($Value)) { throw "$Label is required." }
    return $Value.Trim()
}

function Invoke-ButlerRuntime {
    param(
        [Parameter(Mandatory = $true)][string]$Label,
        [Parameter(Mandatory = $true)][string]$MainClass,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    Write-Host ("SETUP START: {0}" -f $Label)
    $javaArgs = @('--enable-native-access=ALL-UNNAMED', '-cp', $script:classPath, $MainClass) + @($Arguments)
    $previousPreference = $ErrorActionPreference
    $lines = @()
    $exitCode = -1
    Push-Location $script:stagingRoot
    try {
        try {
            $ErrorActionPreference = 'Continue'
            $lines = @(& $script:java @javaArgs 2>&1)
            $exitCode = $LASTEXITCODE
        }
        finally {
            $ErrorActionPreference = $previousPreference
        }
    }
    finally {
        Pop-Location
    }

    if ($exitCode -ne 0) {
        throw ("{0} failed with runtime exit code {1}. {2}" -f $Label, $exitCode, (Get-BoundedTail -Lines $lines))
    }
    Write-Host ("SETUP PASS: {0}" -f $Label)
    return (($lines | ForEach-Object { "$_" }) -join "`n")
}

function Get-ExactField {
    param([string]$Text, [string]$Pattern, [string]$Label)
    $matches = [regex]::Matches($Text, $Pattern)
    if ($matches.Count -ne 1) { throw "Expected exactly one $Label in setup output." }
    return $matches[0].Groups['value'].Value.Trim()
}

try {
    Write-Output 'Butler MVP new-league setup'
    Write-Output 'Boundary: creates Butler-local data and evidence only. It never submits a lineup, waiver, trade, FAAB change, or other Sleeper transaction.'

    if (-not (Test-Path -LiteralPath $shell -PathType Leaf)) { throw 'Windows PowerShell 5.1 is unavailable.' }
    foreach ($required in @($setupCheck, $setupLaunch, $javaPreflight)) {
        if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Required setup component missing: $required" }
    }

    $RuntimeZip = Require-Text -Value $RuntimeZip -Label 'RuntimeZip'
    if ([string]::IsNullOrWhiteSpace($SleeperUsername)) {
        $SleeperUsername = Read-Host 'Sleeper username'
    }
    if ([string]::IsNullOrWhiteSpace($SleeperLeagueId)) {
        $SleeperLeagueId = Read-Host 'Sleeper league ID'
    }
    $SleeperUsername = Require-Text -Value $SleeperUsername -Label 'Sleeper username'
    $SleeperLeagueId = Require-Text -Value $SleeperLeagueId -Label 'Sleeper league ID'
    if ($SleeperLeagueId -notmatch '^\d+$') { throw 'Sleeper league ID must contain digits only.' }

    & $shell -NoLogo -NoProfile -ExecutionPolicy Bypass -File $setupCheck -RuntimeZip $RuntimeZip -RuntimeOnly
    if ($LASTEXITCODE -ne 0) { throw 'Runtime integrity/prerequisite check failed.' }

    $localData = [string]$env:LOCALAPPDATA
    if ([string]::IsNullOrWhiteSpace($localData)) {
        $localData = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)
    }
    if ([string]::IsNullOrWhiteSpace($localData)) { throw 'LocalApplicationData is unavailable.' }

    $configDir = Join-Path $localData 'Butler'
    $selectionPath = Join-Path $configDir 'app-league.txt'
    if (Test-Path -LiteralPath $selectionPath -PathType Leaf) {
        throw "Saved Butler league selection already exists at $selectionPath. New-league setup is fresh-profile only and will not overwrite it."
    }

    $candidate = $DataDir
    if ([string]::IsNullOrWhiteSpace($candidate)) { $candidate = $originalDataDir }
    if ([string]::IsNullOrWhiteSpace($candidate)) { $candidate = Join-Path $configDir 'data' }
    if (-not [IO.Path]::IsPathRooted($candidate)) { throw 'DataDir/BUTLER_APP_DATA_DIR must be an absolute path.' }
    $resolvedDataDir = [IO.Path]::GetFullPath($candidate)
    $resolvedPackageRoot = [IO.Path]::GetFullPath($packageRoot).TrimEnd('\')
    if ($resolvedDataDir.Equals($resolvedPackageRoot, [StringComparison]::OrdinalIgnoreCase) -or
        $resolvedDataDir.StartsWith($resolvedPackageRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Butler runtime data must be outside the extracted package.'
    }

    $finalDatabase = Join-Path $resolvedDataDir 'butler.db'
    if (Test-Path -LiteralPath $finalDatabase) {
        throw "Butler database already exists at $finalDatabase. New-league setup never overwrites an existing database."
    }
    if (Test-Path -LiteralPath $resolvedDataDir -PathType Container) {
        $existing = @(Get-ChildItem -LiteralPath $resolvedDataDir -Force -ErrorAction Stop)
        if ($existing.Count -gt 0) {
            throw "Data directory is not empty: $resolvedDataDir. Use an empty fresh directory or the existing restore/launch workflow."
        }
    }

    $javaInfo = & $javaPreflight -PassThru
    $java = [string]$javaInfo.Executable
    if ([string]::IsNullOrWhiteSpace($java) -or -not (Test-Path -LiteralPath $java -PathType Leaf)) {
        throw 'Java preflight did not return a usable executable.'
    }
    if (-not (Test-Path -LiteralPath $runtimeLibDir -PathType Container)) {
        throw "Prebuilt Butler runtime library is missing at $runtimeLibDir"
    }
    $runtimeJars = @(Get-ChildItem -LiteralPath $runtimeLibDir -Filter '*.jar' -File -ErrorAction Stop)
    $appJars = @($runtimeJars | Where-Object { $_.Name -like 'bet-cli*.jar' })
    if ($runtimeJars.Count -eq 0 -or $appJars.Count -ne 1) {
        throw 'Prebuilt runtime must contain exactly one bet-cli application JAR and its dependencies.'
    }
    $classPath = Join-Path $runtimeLibDir '*'

    $stagingParent = Join-Path $configDir 'setup-new-league-staging'
    [IO.Directory]::CreateDirectory($stagingParent) | Out-Null
    $stagingRoot = Join-Path $stagingParent ([Guid]::NewGuid().ToString('N'))
    [IO.Directory]::CreateDirectory($stagingRoot) | Out-Null
    $stagedDatabase = Join-Path $stagingRoot 'butler.db'
    $env:BUTLER_APP_DATA_DIR = $stagingRoot

    $sync = Invoke-ButlerRuntime -Label 'Sleeper league import + dynasty values' -MainClass 'io.butler.bet.cli.ButlerMain' -Arguments @('sleeper', 'sync-all', $SleeperLeagueId)
    $butlerLeagueId = Get-ExactField -Text $sync -Pattern '(?m)^League ID:\s+(?<value>[0-9a-fA-F-]{36})\s*$' -Label 'Butler league ID'
    $parsedLeague = [Guid]::Empty
    if (-not [Guid]::TryParse($butlerLeagueId, [ref]$parsedLeague)) { throw 'Imported Butler league ID is not a UUID.' }
    $butlerLeagueId = $parsedLeague.ToString('D').ToLowerInvariant()

    $discovery = Invoke-ButlerRuntime -Label 'Exact Sleeper manager/roster discovery' -MainClass 'io.butler.bet.cli.ButlerSleeperPersonalTargetDiscoveryCli' -Arguments @($SleeperUsername, $SleeperLeagueId)
    if ($discovery -notmatch '(?m)^Discovery state:\s+EXACT_USER_LEAGUE_ROSTER_DISCOVERED\s*$') {
        throw 'Sleeper manager discovery did not prove exactly one current roster.'
    }

    $binding = Invoke-ButlerRuntime -Label 'Exact manager/league/roster binding' -MainClass 'io.butler.bet.cli.ButlerSleeperPersonalTargetBindCli' -Arguments @($butlerLeagueId, $SleeperUsername, $SleeperLeagueId)
    if ($binding -notmatch '(?m)^Binding state:\s+BOUND_VERIFIED\s*$') {
        throw 'Fresh setup did not create the exact personalized Sleeper target binding.'
    }

    $verified = Invoke-ButlerRuntime -Label 'Live personalized-target verification' -MainClass 'io.butler.bet.cli.ButlerSleeperPersonalTargetVerificationDiagnosticCli' -Arguments @($butlerLeagueId)
    if ($verified -notmatch '(?m)^BF855_STATE\s+BOUND_TARGET_LIVE_VERIFIED\s*$') {
        throw 'Bound Sleeper target did not pass live verification.'
    }

    $stages = @(
        @{ Label = 'Current weekly matchup'; Main = 'io.butler.bet.cli.ButlerSleeperCurrentWeekMatchupSyncCli' },
        @{ Label = 'Waiver identity snapshot'; Main = 'io.butler.bet.cli.ButlerSleeperLiveWaiverSnapshotSyncCli' },
        @{ Label = 'Waiver market attention'; Main = 'io.butler.bet.cli.ButlerSleeperLiveWaiverMarketAttentionSyncCli' },
        @{ Label = 'Waiver production hydration'; Main = 'io.butler.bet.cli.ButlerSleeperLiveWaiverProductionHydrationCli' },
        @{ Label = 'Waiver availability'; Main = 'io.butler.bet.cli.ButlerSleeperLiveWaiverAvailabilitySyncCli' },
        @{ Label = 'Current-week waiver stats'; Main = 'io.butler.bet.cli.ButlerSleeperLiveWaiverCurrentWeekStatSyncCli' },
        @{ Label = 'My Team production hydration'; Main = 'io.butler.bet.cli.ButlerSleeperLiveWaiverTargetRosterProductionHydrationCli' },
        @{ Label = 'Waiver comparison readiness'; Main = 'io.butler.bet.cli.ButlerSleeperLiveWaiverComparisonBundleCli' }
    )
    foreach ($stage in $stages) {
        [void](Invoke-ButlerRuntime -Label $stage.Label -MainClass $stage.Main -Arguments @($butlerLeagueId))
    }

    if (-not (Test-Path -LiteralPath $stagedDatabase -PathType Leaf)) {
        throw 'Setup completed its provider stages without producing butler.db.'
    }
    $stream = [IO.File]::Open($stagedDatabase, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
    try {
        $header = New-Object byte[] 16
        if ($stream.Read($header, 0, 16) -ne 16 -or [Text.Encoding]::ASCII.GetString($header) -cne "SQLite format 3`0") {
            throw 'Staged Butler database does not have a valid SQLite header.'
        }
    }
    finally { $stream.Dispose() }

    [IO.Directory]::CreateDirectory($resolvedDataDir) | Out-Null
    $tempDatabase = Join-Path $resolvedDataDir ('butler.db.setup-' + [Guid]::NewGuid().ToString('N'))
    [IO.File]::Copy($stagedDatabase, $tempDatabase, $false)
    $sourceHash = (Get-FileHash -LiteralPath $stagedDatabase -Algorithm SHA256).Hash
    $copyHash = (Get-FileHash -LiteralPath $tempDatabase -Algorithm SHA256).Hash
    if ($sourceHash -ine $copyHash) { throw 'Staged database copy verification failed.' }
    [IO.File]::Move($tempDatabase, $finalDatabase)
    $databaseInstalled = $true
    $tempDatabase = $null

    [IO.Directory]::CreateDirectory($configDir) | Out-Null
    $selectionTemp = Join-Path $configDir ('app-league.txt.setup-' + [Guid]::NewGuid().ToString('N'))
    [IO.File]::WriteAllText($selectionTemp, ($butlerLeagueId + "`r`n"), [Text.Encoding]::ASCII)
    if (Test-Path -LiteralPath $selectionPath) {
        Remove-Item -LiteralPath $selectionTemp -Force
        throw 'Saved league selection appeared during setup; Butler refused to overwrite it.'
    }
    [IO.File]::Move($selectionTemp, $selectionPath)
    $selectionInstalled = $true
    $committed = $true

    $env:BUTLER_APP_DATA_DIR = $resolvedDataDir
    Write-Output "BUTLER NEW LEAGUE: INITIALIZED $butlerLeagueId"
    Write-Output "Data: $resolvedDataDir"
    Write-Output 'Running seven-page launch verification...'

    $launchArgs = @('-NoLogo','-NoProfile','-ExecutionPolicy','Bypass','-File',$setupLaunch,'-RuntimeZip',$RuntimeZip)
    if ($VerifyOnly) { $launchArgs += '-VerifyOnly' }
    & $shell @launchArgs
    if ($LASTEXITCODE -ne 0) {
        throw 'League initialization succeeded, but seven-page launch verification was blocked. Keep the initialized data and use the reported launch diagnostics.'
    }

    Write-Output 'BUTLER MVP ONBOARDING: PASS'
    if ($VerifyOnly) {
        Write-Output 'Verified onboarding runtime was stopped.'
    }
    else {
        Write-Output 'Butler is ready in the Dashboard window opened by setup launch.'
    }
}
catch {
    Write-Output ('BUTLER MVP ONBOARDING: BLOCKED - ' + $_.Exception.Message)
    if ($committed) {
        Write-Output 'Butler data was already initialized before the final launch check. It was preserved; do not rerun fresh setup over it.'
        Write-Output 'NEXT: Resolve the reported launch blocker, then run scripts\butler-setup-launch.cmd with the same RuntimeZip.'
    }
    else {
        Write-Output 'No existing Butler database or saved league selection was overwritten.'
    }
    exit 1
}
finally {
    if (-not $committed) {
        if ($selectionInstalled -and (Test-Path -LiteralPath $selectionPath -PathType Leaf)) {
            Remove-Item -LiteralPath $selectionPath -Force -ErrorAction SilentlyContinue
        }
        if ($databaseInstalled -and $null -ne $finalDatabase -and (Test-Path -LiteralPath $finalDatabase -PathType Leaf)) {
            Remove-Item -LiteralPath $finalDatabase -Force -ErrorAction SilentlyContinue
        }
    }

    if ($null -ne $tempDatabase -and (Test-Path -LiteralPath $tempDatabase)) {
        Remove-Item -LiteralPath $tempDatabase -Force -ErrorAction SilentlyContinue
    }
    if (-not $committed -and $null -ne $stagingRoot -and (Test-Path -LiteralPath $stagingRoot)) {
        Remove-Item -LiteralPath $stagingRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
    elseif ($null -ne $stagingRoot -and (Test-Path -LiteralPath $stagingRoot)) {
        Remove-Item -LiteralPath $stagingRoot -Recurse -Force -ErrorAction SilentlyContinue
    }

    if ([string]::IsNullOrWhiteSpace($originalDataDir)) {
        Remove-Item Env:BUTLER_APP_DATA_DIR -ErrorAction SilentlyContinue
    }
    else {
        $env:BUTLER_APP_DATA_DIR = $originalDataDir
    }
}
