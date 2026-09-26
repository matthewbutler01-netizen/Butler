Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = Join-Path ([IO.Path]::GetTempPath()) ('butler-setup-fixtures-' + [Guid]::NewGuid().ToString('N'))
$package = Join-Path $root 'package'
$profile = Join-Path $root 'profile'
$javaHome = Join-Path $root 'java'
$zip = Join-Path $root 'runtime.zip'
$saved = @{}
foreach ($name in @('LOCALAPPDATA', 'BUTLER_APP_DATA_DIR', 'JAVA_HOME', 'PATH', 'BUTLER_FIXTURE_JAVA')) { $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$shell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'

function Snapshot {
    return (@(Get-ChildItem -LiteralPath $root -Recurse -Force | Sort-Object FullName | ForEach-Object {
        if ($_.PSIsContainer) { 'D:' + $_.FullName }
        else { $_.FullName + ':' + (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash }
    }) -join "`n")
}

function Check-Case {
    param([string]$Name, [int]$Exit, [string]$Expected, [switch]$UseCmd, [switch]$RuntimeOnly)
    $before = Snapshot
    $extra = @()
    if ($RuntimeOnly) { $extra += '-RuntimeOnly' }
    if ($UseCmd) {
        $output = @(& (Join-Path $package 'scripts\butler-setup-check.cmd') -RuntimeZip $zip @extra 2>&1) -join "`n"
    }
    else {
        $output = @(& $shell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $package 'scripts\butler-setup-check.ps1') -RuntimeZip $zip @extra 2>&1) -join "`n"
    }
    $actualExit = $LASTEXITCODE
    if ($actualExit -ne $Exit -or $output -notmatch [regex]::Escape($Expected)) { throw "${Name}: expected exit $Exit and '$Expected', got $actualExit`n$output" }
    if ((Snapshot) -cne $before) { throw "${Name}: read-only setup changed fixture files or directories." }
    Write-Host "SETUP FIXTURE PASS: $Name"
}

try {
    foreach ($dir in @('scripts', 'bet\bet-cli\build\install\bet-cli\lib')) { [IO.Directory]::CreateDirectory((Join-Path $package $dir)) | Out-Null }
    [IO.Directory]::CreateDirectory((Join-Path $profile 'Butler\data')) | Out-Null
    [IO.Directory]::CreateDirectory((Join-Path $javaHome 'bin')) | Out-Null
    foreach ($name in @('butler-setup-check.ps1', 'butler-setup-check.cmd', 'butler-java-preflight.ps1')) { Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination (Join-Path $package ('scripts\' + $name)) }
    foreach ($name in @('butler-app.cmd', 'butler-app.ps1', 'butler-app-shell-core.ps1')) { [IO.File]::WriteAllText((Join-Path $package ('scripts\' + $name)), 'fixture - never executed') }
    [IO.File]::WriteAllText((Join-Path $package 'bet\bet-cli\build\install\bet-cli\lib\bet-cli-fixture.jar'), 'fixture - never executed')
    Add-Type -TypeDefinition @'
using System;
public class ButlerSetupFixtureJava {
    public static void Main() {
        Console.Error.WriteLine("openjdk version \"" + Environment.GetEnvironmentVariable("BUTLER_FIXTURE_JAVA") + "\"");
    }
}
'@ -OutputAssembly (Join-Path $javaHome 'bin\java.exe') -OutputType ConsoleApplication
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [IO.Compression.ZipFile]::CreateFromDirectory($package, $zip)
    $hash = (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash
    [IO.File]::WriteAllText($zip + '.sha256', "$hash  runtime.zip`r`n")
    $db = Join-Path $profile 'Butler\data\butler.db'
    $selection = Join-Path $profile 'Butler\app-league.txt'
    [IO.File]::WriteAllText($db, "SQLite format 3`0fixture")
    [IO.File]::WriteAllText($selection, 'a75ccbfa-18b4-4e02-9d21-ccb0356568cf')
    $env:LOCALAPPDATA = $profile
    $env:BUTLER_APP_DATA_DIR = ''
    $env:JAVA_HOME = $javaHome
    $env:PATH = Join-Path $env:SystemRoot 'System32'
    $env:BUTLER_FIXTURE_JAVA = '25.0.1'
    Check-Case 'ready prerequisites without Git or Gradle' 0 'BUTLER SETUP CHECK: PASS'
    Remove-Item -LiteralPath $db -Force
    Remove-Item -LiteralPath $selection -Force
    Check-Case 'runtime-only preflight does not require data or selection' 0 'BUTLER SETUP RUNTIME CHECK: PASS' -RuntimeOnly
    [IO.File]::WriteAllText($db, "SQLite format 3`0fixture")
    [IO.File]::WriteAllText($selection, 'a75ccbfa-18b4-4e02-9d21-ccb0356568cf')
    $env:PATH = (Join-Path $env:SystemRoot 'System32') + ';' + (Split-Path -Parent $shell)
    Check-Case 'CMD wrapper ready' 0 'BUTLER SETUP CHECK: PASS' -UseCmd
    $env:JAVA_HOME = ''
    Check-Case 'CMD wrapper propagates missing Java failure' 1 'java.exe is unavailable' -UseCmd
    $env:JAVA_HOME = $javaHome
    $env:BUTLER_FIXTURE_JAVA = '17.0.1'
    Check-Case 'unsupported Java' 1 'resolved Java major version 17'
    $env:BUTLER_FIXTURE_JAVA = '25.0.1'
    $env:BUTLER_APP_DATA_DIR = Join-Path $root 'absent-data'
    Check-Case 'missing data is not created' 1 'Database missing:'
    $env:BUTLER_APP_DATA_DIR = Join-Path $package 'data'
    Check-Case 'package internal data' 1 'Runtime data must be outside'
    $env:BUTLER_APP_DATA_DIR = 'relative-data'
    Check-Case 'relative data path' 1 'must be an absolute path'
    $env:BUTLER_APP_DATA_DIR = ''
    [IO.File]::WriteAllText($selection, 'invalid')
    Check-Case 'malformed selection preserved' 1 'not a UUID'
    [IO.File]::WriteAllText($selection, 'a75ccbfa-18b4-4e02-9d21-ccb0356568cf')
    [IO.File]::WriteAllText($db, 'bad database header')
    Check-Case 'invalid database preserved' 1 'Invalid SQLite header'
    [IO.File]::WriteAllText($db, "SQLite format 3`0fixture")
    [IO.File]::WriteAllText($zip + '.sha256', ((('0' * 64) + "  runtime.zip`r`n")))
    Check-Case 'corrupt archive checksum' 1 'Runtime ZIP checksum mismatch'
    [IO.File]::WriteAllText($zip + '.sha256', "$hash  runtime.zip`r`n")
    [IO.File]::WriteAllText((Join-Path $package 'scripts\butler-app.cmd'), 'tampered')
    Check-Case 'modified extracted file' 1 'Extracted file differs from ZIP'
    [IO.File]::WriteAllText((Join-Path $package 'scripts\butler-app.cmd'), 'fixture - never executed')
    [IO.File]::WriteAllText((Join-Path $package 'bet\bet-cli\build\install\bet-cli\lib\extra.jar'), 'unexpected')
    Check-Case 'unexpected runtime library' 1 'Unexpected executable file'
    Write-Host 'BUTLER SETUP CHECK FIXTURES: PASS'
}
finally {
    foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
    $resolvedRoot = [IO.Path]::GetFullPath($root)
    $tempPrefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\butler-setup-fixtures-'
    if (-not $resolvedRoot.StartsWith($tempPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe fixture cleanup path.' }
    if (Test-Path -LiteralPath $resolvedRoot) { Remove-Item -LiteralPath $resolvedRoot -Recurse -Force }
}
# Expected child-process failures must not become the CI step's final exit code.
$global:LASTEXITCODE = 0
