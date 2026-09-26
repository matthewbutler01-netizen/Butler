Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = Join-Path ([IO.Path]::GetTempPath()) ('butler-mvp-onboarding-fixtures-' + [Guid]::NewGuid().ToString('N'))
$package = Join-Path $root 'package'
$profile = Join-Path $root 'profile'
$javaHome = Join-Path $root 'java'
$zip = Join-Path $root 'runtime.zip'
$saved = @{}
foreach ($name in @('LOCALAPPDATA', 'BUTLER_APP_DATA_DIR', 'JAVA_HOME', 'PATH')) {
    $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

try {
    foreach ($dir in @(
        $package,
        (Join-Path $package 'scripts'),
        (Join-Path $package 'bet\bet-cli\build\install\bet-cli\lib'),
        $profile,
        (Join-Path $javaHome 'bin')
    )) {
        [IO.Directory]::CreateDirectory($dir) | Out-Null
    }

    foreach ($name in @(
        'butler-setup-new-league.ps1',
        'butler-setup-new-league.cmd',
        'butler-setup-check.ps1',
        'butler-setup-check.cmd',
        'butler-java-preflight.ps1'
    )) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination (Join-Path $package ('scripts\' + $name))
    }

    foreach ($name in @('butler-app.cmd', 'butler-app.ps1', 'butler-app-shell-core.ps1')) {
        [IO.File]::WriteAllText((Join-Path $package ('scripts\' + $name)), 'fixture - never executed', [Text.Encoding]::ASCII)
    }

    $launchStub = @'
param([string]$RuntimeZip, [switch]$VerifyOnly)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$dataDir = [string]$env:BUTLER_APP_DATA_DIR
if ([string]::IsNullOrWhiteSpace($dataDir)) { throw 'fixture launch missing BUTLER_APP_DATA_DIR' }
$db = Join-Path $dataDir 'butler.db'
$config = Join-Path $env:LOCALAPPDATA 'Butler\app-league.txt'
if (-not (Test-Path -LiteralPath $db -PathType Leaf)) { throw 'fixture launch missing database' }
if (-not (Test-Path -LiteralPath $config -PathType Leaf)) { throw 'fixture launch missing selection' }
Write-Output 'SETUP PAGE: PASS /'
Write-Output 'SETUP PAGE: PASS /team'
Write-Output 'SETUP PAGE: PASS /matchup'
Write-Output 'SETUP PAGE: PASS /waivers'
Write-Output 'SETUP PAGE: PASS /league'
Write-Output 'SETUP PAGE: PASS /trade?load=1'
Write-Output 'SETUP PAGE: PASS /history?load=1'
Write-Output 'BUTLER FIRST LAUNCH: PASS'
exit 0
'@
    [IO.File]::WriteAllText((Join-Path $package 'scripts\butler-setup-launch.ps1'), $launchStub, [Text.Encoding]::ASCII)

    [IO.File]::WriteAllText(
        (Join-Path $package 'bet\bet-cli\build\install\bet-cli\lib\bet-cli-fixture.jar'),
        'fixture - never loaded',
        [Text.Encoding]::ASCII
    )

    Add-Type -TypeDefinition @'
using System;
using System.IO;
public static class ButlerMvpFixtureJava {
    public static void Main(string[] args) {
        if (args.Length == 1 && args[0] == "-version") {
            Console.Error.WriteLine("openjdk version \"25.0.1\"");
            return;
        }
        if (args.Length < 4) {
            Console.Error.WriteLine("fixture missing Butler main class");
            Environment.Exit(2);
            return;
        }

        string main = args[3];
        if (main == "io.butler.bet.cli.ButlerMain") {
            byte[] header = new byte[] {
                83,81,76,105,116,101,32,102,111,114,109,97,116,32,51,0,
                102,105,120,116,117,114,101
            };
            File.WriteAllBytes(Path.Combine(Environment.CurrentDirectory, "butler.db"), header);
            Console.WriteLine("Full league sync completed.");
            Console.WriteLine("League ID: 11111111-2222-3333-4444-555555555555");
            return;
        }
        if (main == "io.butler.bet.cli.ButlerSleeperPersonalTargetDiscoveryCli") {
            Console.WriteLine("Discovery state: EXACT_USER_LEAGUE_ROSTER_DISCOVERED");
            return;
        }
        if (main == "io.butler.bet.cli.ButlerSleeperPersonalTargetBindCli") {
            Console.WriteLine("Binding state: BOUND_VERIFIED");
            return;
        }
        if (main == "io.butler.bet.cli.ButlerSleeperPersonalTargetVerificationDiagnosticCli") {
            Console.WriteLine("BF855_STATE BOUND_TARGET_LIVE_VERIFIED");
            return;
        }

        Console.WriteLine("fixture stage complete: " + main);
    }
}
'@ -OutputAssembly (Join-Path $javaHome 'bin\java.exe') -OutputType ConsoleApplication

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [IO.Compression.ZipFile]::CreateFromDirectory($package, $zip)
    $hash = (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash
    [IO.File]::WriteAllText($zip + '.sha256', "$hash  runtime.zip`r`n", [Text.Encoding]::ASCII)

    $env:LOCALAPPDATA = $profile
    $env:BUTLER_APP_DATA_DIR = ''
    $env:JAVA_HOME = $javaHome
    $env:PATH = Join-Path $env:SystemRoot 'System32'

    $entry = Join-Path $package 'scripts\butler-setup-new-league.cmd'
    $output = @(& $entry -RuntimeZip $zip -SleeperUsername 'fixture-user' -SleeperLeagueId '123456789012345678' -VerifyOnly 2>&1) -join "`n"
    $exitCode = $LASTEXITCODE
    Assert-True ($exitCode -eq 0) "expected successful onboarding, exit=$exitCode output=$output"
    Assert-True ($output -match 'BUTLER MVP ONBOARDING: PASS') "missing onboarding PASS marker: $output"
    Assert-True ($output -match 'BUTLER SETUP RUNTIME CHECK: PASS') "runtime-only preflight did not run: $output"

    $dataDir = Join-Path $profile 'Butler\data'
    $db = Join-Path $dataDir 'butler.db'
    $selection = Join-Path $profile 'Butler\app-league.txt'
    Assert-True (Test-Path -LiteralPath $db -PathType Leaf) 'onboarding did not commit butler.db'
    Assert-True (Test-Path -LiteralPath $selection -PathType Leaf) 'onboarding did not commit app-league.txt'
    Assert-True (([IO.File]::ReadAllText($selection).Trim()) -ceq '11111111-2222-3333-4444-555555555555') 'saved Butler league UUID mismatch'

    $beforeDb = (Get-FileHash -LiteralPath $db -Algorithm SHA256).Hash
    $beforeSelection = [IO.File]::ReadAllText($selection)
    $second = @(& $entry -RuntimeZip $zip -SleeperUsername 'fixture-user' -SleeperLeagueId '123456789012345678' -VerifyOnly 2>&1) -join "`n"
    $secondExit = $LASTEXITCODE
    Assert-True ($secondExit -eq 1) "existing install should block fresh setup, exit=$secondExit output=$second"
    Assert-True ($second -match 'Saved Butler league selection already exists') "existing-selection guard missing: $second"
    Assert-True ((Get-FileHash -LiteralPath $db -Algorithm SHA256).Hash -ceq $beforeDb) 'blocked retry changed existing database'
    Assert-True ([IO.File]::ReadAllText($selection) -ceq $beforeSelection) 'blocked retry changed saved league selection'

    $source = [IO.File]::ReadAllText((Join-Path $package 'scripts\butler-setup-new-league.ps1'))
    foreach ($requiredClass in @(
        'io.butler.bet.cli.ButlerMain',
        'io.butler.bet.cli.ButlerSleeperPersonalTargetDiscoveryCli',
        'io.butler.bet.cli.ButlerSleeperPersonalTargetBindCli',
        'io.butler.bet.cli.ButlerSleeperCurrentWeekMatchupSyncCli',
        'io.butler.bet.cli.ButlerSleeperLiveWaiverSnapshotSyncCli',
        'io.butler.bet.cli.ButlerSleeperLiveWaiverMarketAttentionSyncCli',
        'io.butler.bet.cli.ButlerSleeperLiveWaiverProductionHydrationCli',
        'io.butler.bet.cli.ButlerSleeperLiveWaiverAvailabilitySyncCli',
        'io.butler.bet.cli.ButlerSleeperLiveWaiverCurrentWeekStatSyncCli',
        'io.butler.bet.cli.ButlerSleeperLiveWaiverTargetRosterProductionHydrationCli',
        'io.butler.bet.cli.ButlerSleeperLiveWaiverComparisonBundleCli'
    )) {
        Assert-True ($source.Contains($requiredClass)) "onboarding contract lost required stage $requiredClass"
    }
    Assert-True ($source.Contains('It never submits a lineup, waiver, trade, FAAB change, or other Sleeper transaction.')) 'no-Sleeper-write boundary text missing'

    Write-Host 'BUTLER MVP ONBOARDING FIXTURES: PASS'
}
finally {
    foreach ($name in $saved.Keys) {
        [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process')
    }
    $resolved = [IO.Path]::GetFullPath($root)
    $prefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\butler-mvp-onboarding-fixtures-'
    if (-not $resolved.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe onboarding fixture cleanup path.' }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
$global:LASTEXITCODE = 0
