param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$LeagueId
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# BF-1060: narrowly scoped Butler-local evidence recovery. The existing
# BF-1059 Java sync independently rechecks BOTH league and public NFL week
# before its first DB write. No Sleeper transactions, roster edits or FAAB.
$parsed = [guid]::Empty
if (-not [guid]::TryParse($LeagueId, [ref]$parsed) -or
    $parsed.ToString('D') -cne $LeagueId) {
    throw 'BF-1060 BLOCKED: exact configured Butler league UUID is required.'
}

$root = Split-Path -Parent $PSScriptRoot
$localData = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)
$config = Join-Path $localData 'Butler\app-league.txt'
if (-not (Test-Path -LiteralPath $config -PathType Leaf)) {
    throw 'BF-1060 BLOCKED: Butler league configuration is missing.'
}
$configured = [IO.File]::ReadAllText($config, [Text.Encoding]::ASCII).Trim()
if ($configured -cne $LeagueId) {
    throw 'BF-1060 BLOCKED: requested league does not match the configured Butler league.'
}

$rawData = [string]$env:BUTLER_APP_DATA_DIR
if ([string]::IsNullOrWhiteSpace($rawData) -or -not [IO.Path]::IsPathRooted($rawData)) {
    throw 'BF-1060 BLOCKED: governed runtime data directory must be explicit and absolute.'
}
$dataDir = [IO.Path]::GetFullPath($rawData)
$sourceRoot = [IO.Path]::GetFullPath($root).TrimEnd('\')
if ($dataDir.Equals($sourceRoot, [StringComparison]::OrdinalIgnoreCase) -or
    $dataDir.StartsWith($sourceRoot + '\', [StringComparison]::OrdinalIgnoreCase) -or
    -not (Test-Path -LiteralPath (Join-Path $dataDir 'butler.db') -PathType Leaf)) {
    throw 'BF-1060 BLOCKED: governed external Butler runtime database is unavailable.'
}

$java = ''
if (-not [string]::IsNullOrWhiteSpace([string]$env:JAVA_HOME)) {
    $candidate = Join-Path $env:JAVA_HOME 'bin\java.exe'
    if (Test-Path -LiteralPath $candidate -PathType Leaf) { $java = $candidate }
}
if ([string]::IsNullOrWhiteSpace($java)) {
    $java = [string](Get-Command java.exe -ErrorAction Stop).Source
}
$libs = Join-Path $root 'bet\bet-cli\build\install\bet-cli\lib'
if (-not (Test-Path -LiteralPath $libs -PathType Container) -or
    @(Get-ChildItem -LiteralPath $libs -Filter '*.jar' -File).Count -eq 0) {
    throw 'BF-1060 BLOCKED: installed Butler Java runtime is unavailable.'
}

$classpath = Join-Path $libs '*'
$previousPreference = $ErrorActionPreference
$exitCode = -1
$lines = @()
Push-Location $dataDir
try {
    try {
        $ErrorActionPreference = 'Continue'
        $lines = & $java '--enable-native-access=ALL-UNNAMED' '-cp' $classpath 'io.butler.bet.cli.ButlerSleeperCurrentWeekMatchupSyncCli' $LeagueId 2>&1
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }
}
finally {
    Pop-Location
}

$text = (($lines | ForEach-Object { "$_" }) -join "`n")
if ($exitCode -ne 0 -or
    $text.IndexOf('BF-840 current weekly matchup pairing synchronized.', [StringComparison]::Ordinal) -lt 0) {
    # Do not emit player, roster or league payloads to public HTTP.
    throw 'BF-1060 BLOCKED: exact current-week sync did not complete. Saved matchup advice stays held.'
}
Write-Host 'BF-1060 WEEK RECOVERY: COMPLETE'
