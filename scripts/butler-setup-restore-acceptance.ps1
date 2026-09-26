Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$root = Join-Path ([IO.Path]::GetTempPath()) ('butler-restore-fixtures-' + [Guid]::NewGuid().ToString('N'))
$originalLocal = $env:LOCALAPPDATA
$originalData = $env:BUTLER_APP_DATA_DIR
$league = '11111111-2222-3333-4444-555555555555'
$other = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'
$entry = Join-Path $PSScriptRoot 'butler-setup-restore.cmd'
$shell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$originalPath = $env:PATH

function Snapshot {
    (@(Get-ChildItem -LiteralPath $root -Recurse -Force | Sort-Object FullName | ForEach-Object {
        if ($_.PSIsContainer) { 'D:' + $_.FullName }
        else { $_.FullName + ':' + (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash }
    }) -join "`n")
}
function Make-Archive {
    param([string]$Name, [string]$Selection, [switch]$BadHeader)
    $stage = Join-Path $root ($Name + '-contents')
    [IO.Directory]::CreateDirectory($stage) | Out-Null
    $db = Join-Path $stage 'butler.db'
    if ($BadHeader) { [IO.File]::WriteAllText($db, 'invalid SQLite database header fixture') }
    else { Copy-Item -LiteralPath (Join-Path $root 'source.db') -Destination $db }
    $hash = (Get-FileHash -LiteralPath $db -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText((Join-Path $stage 'butler.db.sha256'), "$hash  butler.db`r`n")
    $state = 'ABSENT'
    if ($Selection) { $state = 'PRESENT'; [IO.File]::WriteAllText((Join-Path $stage 'app-league.txt'), $Selection) }
    $manifest = @('BUTLER_RUNTIME_DATA_BACKUP_V1','created_utc=2026-09-26T00:00:00Z','database=butler.db',"database_sha256=$hash","league_selection=$state",'boundary=PRIVATE_RUNTIME_DATA_BACKUP_CONTAINS_USER_DATA')
    [IO.File]::WriteAllText((Join-Path $stage 'backup.manifest.txt'), ($manifest -join "`r`n"))
    $zip = Join-Path $root ($Name + '.zip')
    [IO.Compression.ZipFile]::CreateFromDirectory($stage, $zip)
    $zipHash = (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash
    [IO.File]::WriteAllText($zip + '.sha256', "$zipHash  $Name.zip`r`n")
    return $zip
}
function New-Target {
    param([string]$Name)
    $env:LOCALAPPDATA = Join-Path $root $Name
    $env:BUTLER_APP_DATA_DIR = Join-Path $env:LOCALAPPDATA 'custom-data'
    [IO.Directory]::CreateDirectory((Join-Path $env:LOCALAPPDATA 'Butler')) | Out-Null
}
function Check-Blocked {
    param([string]$Name, [string[]]$Arguments, [string]$Expected)
    $before = Snapshot
    $output = @(& $entry @Arguments 2>&1) -join "`n"
    if ($LASTEXITCODE -eq 0 -or $output -notmatch [regex]::Escape($Expected)) { throw "${Name}: unexpected result`n$output" }
    if ((Snapshot) -cne $before) { throw "${Name}: failure changed fixture files/directories" }
    Write-Host "RESTORE FIXTURE PASS: $Name preserved files"
}
try {
    [IO.Directory]::CreateDirectory($root) | Out-Null
    $env:PATH = $env:PATH + ';' + (Split-Path -Parent $shell)
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $java = & (Join-Path $PSScriptRoot 'butler-java-preflight.ps1') -PassThru
    $source = Join-Path $root 'FixtureDatabase.java'
    [IO.File]::WriteAllText($source, @'
import java.sql.*;
class FixtureDatabase {
 public static void main(String[] args) throws Exception {
  try (var c=DriverManager.getConnection("jdbc:sqlite:"+args[0]); var s=c.createStatement()) {
   s.execute("CREATE TABLE leagues(id TEXT PRIMARY KEY)");
   s.execute("INSERT INTO leagues VALUES('11111111-2222-3333-4444-555555555555')");
  }
 }
}
'@)
    & $java.Executable '--enable-native-access=ALL-UNNAMED' '-cp' (Join-Path $repoRoot 'bet\bet-cli\build\install\bet-cli\lib\*') $source (Join-Path $root 'source.db')
    if ($LASTEXITCODE -ne 0) { throw 'Synthetic database creation failed.' }
    $good = Make-Archive 'good' $league
    $unselected = Make-Archive 'unselected' ''
    $wrong = Make-Archive 'wrong-league' $other
    $header = Make-Archive 'bad-header' $league -BadHeader
    New-Target 'no-selection'
    Check-Blocked 'explicit backup required' @() 'Select a private backup explicitly'
    Check-Blocked 'missing league requires explicit selection' @('-BackupZip', $unselected) 'backup has no saved league selection'
    Check-Blocked 'absent league rejected before install' @('-BackupZip', $wrong) 'Selected league is absent'
    Check-Blocked 'invalid SQLite header' @('-BackupZip', $header) 'expected SQLite header'
    Check-Blocked 'explicit selection conflicts with backup' @('-BackupZip', $good, '-LeagueId', $other) 'conflicts with the backup'

    $corrupt = Join-Path $root 'corrupt.zip'
    [IO.File]::WriteAllText($corrupt, 'not a ZIP')
    [IO.File]::WriteAllText($corrupt + '.sha256', (('0' * 64) + '  corrupt.zip'))
    Check-Blocked 'corrupt checksum' @('-BackupZip', $corrupt) 'does not match its checksum'
    $hash = (Get-FileHash -LiteralPath $corrupt -Algorithm SHA256).Hash
    [IO.File]::WriteAllText($corrupt + '.sha256', "$hash  corrupt.zip")
    Check-Blocked 'invalid ZIP with matching checksum' @('-BackupZip', $corrupt) 'BUTLER SETUP RESTORE: BLOCKED'

    New-Target 'conflict'
    [IO.File]::WriteAllText((Join-Path $env:LOCALAPPDATA 'Butler\app-league.txt'), $other)
    Check-Blocked 'conflicting saved selection' @('-BackupZip', $good) 'different Butler league selection'
    New-Target 'active'
    $self = Get-Process -Id $PID
    [IO.File]::WriteAllText((Join-Path $env:LOCALAPPDATA 'Butler\running-port-12345.txt'), "$PID|$($self.StartTime.ToUniversalTime().Ticks)|12345")
    Check-Blocked 'active runtime' @('-BackupZip', $good) 'Butler is running'

    foreach ($case in @(@('saved-selection', $good, ''), @('explicit-selection', $unselected, $league))) {
        New-Target $case[0]
        $restoreArgs = @('-BackupZip', $case[1])
        if ($case[2]) { $restoreArgs += @('-LeagueId', $case[2]) }
        $output = @(& $entry @restoreArgs 2>&1) -join "`n"
        if ($LASTEXITCODE -ne 0 -or $output -notmatch 'BUTLER SETUP RESTORE: PASS') { throw "Restore failed: $output" }
        $db = Join-Path $env:BUTLER_APP_DATA_DIR 'butler.db'
        if ((Get-FileHash -LiteralPath $db).Hash -cne (Get-FileHash -LiteralPath (Join-Path $root 'source.db')).Hash) { throw 'Restore altered database contents.' }
        if ([IO.File]::ReadAllText((Join-Path $env:LOCALAPPDATA 'Butler\app-league.txt')).Trim() -cne $league) { throw 'Restore did not save verified selection.' }
        Write-Host "RESTORE FIXTURE PASS: $($case[0]) custom external data and selection restored"
        Check-Blocked 'existing database never overwritten' @('-BackupZip', $good) 'already exists'
    }
    Write-Host 'BUTLER SETUP RESTORE FIXTURES: PASS'
}
finally {
    $env:LOCALAPPDATA = $originalLocal
    $env:BUTLER_APP_DATA_DIR = $originalData
    $env:PATH = $originalPath
    $resolved = [IO.Path]::GetFullPath($root)
    $prefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\butler-restore-fixtures-'
    if (-not $resolved.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe fixture cleanup path.' }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
$global:LASTEXITCODE = 0
