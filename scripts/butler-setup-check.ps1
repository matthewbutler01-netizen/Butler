param(
    [string]$RuntimeZip,
    [switch]$RuntimeOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$packageRoot = Split-Path -Parent $PSScriptRoot
$script:blockers = 0

function Get-SetupHash {
    param([string]$Path)
    $stream = [IO.File]::OpenRead($Path)
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-', '') }
    finally { $stream.Dispose(); $sha.Dispose() }
}

function Report-Check {
    param([string]$Name, [scriptblock]$Check, [string]$Next)
    try {
        $detail = & $Check
        Write-Output ("SETUP {0}: PASS - {1}" -f $Name, ($detail -join '; '))
    }
    catch {
        $script:blockers++
        Write-Output ("SETUP {0}: BLOCKED - {1}" -f $Name, $_.Exception.Message)
        Write-Output ("NEXT: {0}" -f $Next)
    }
}

Write-Output 'Butler read-only setup check'
Write-Output 'Boundary: no launch, database creation, restore, or saved-setting changes. No Git or Gradle required.'

Report-Check 'POWERSHELL' {
    $windowsPowerShell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    if (-not (Test-Path -LiteralPath $windowsPowerShell -PathType Leaf)) { throw 'Windows PowerShell is unavailable.' }
    $version = & $windowsPowerShell -NoProfile -Command '$PSVersionTable.PSVersion.ToString()'
    if ($LASTEXITCODE -ne 0 -or [version]$version -lt [version]'5.1') { throw 'Windows PowerShell 5.1 is required.' }
    "$version at $windowsPowerShell"
} 'Enable Windows PowerShell 5.1, then rerun scripts\butler-setup-check.cmd.'

Report-Check 'JAVA' {
    $java = & (Join-Path $PSScriptRoot 'butler-java-preflight.ps1') -PassThru
    "$($java.Version) at $($java.Executable)"
} 'Install Java 25 or newer and set JAVA_HOME to its installation directory, then rerun this check.'

Report-Check 'PACKAGE' {
    if ([string]::IsNullOrWhiteSpace($RuntimeZip)) { throw 'Supply -RuntimeZip with the downloaded Butler runtime ZIP path.' }
    $zipPath = [IO.Path]::GetFullPath($RuntimeZip)
    $checksum = [IO.File]::ReadAllText($zipPath + '.sha256').Trim()
    if ($checksum -notmatch '^([0-9a-fA-F]{64})\s{2}(.+)$') { throw 'Invalid runtime checksum sidecar.' }
    $expectedHash = $Matches[1]
    if ($Matches[2] -cne [IO.Path]::GetFileName($zipPath)) { throw 'Checksum names a different ZIP.' }
    if ((Get-SetupHash $zipPath) -ine $expectedHash) { throw 'Runtime ZIP checksum mismatch.' }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($zipPath)
    try {
        $names = @($archive.Entries | ForEach-Object { $_.FullName.Replace('\', '/') })
        foreach ($required in @('scripts/butler-app.cmd', 'scripts/butler-app.ps1', 'scripts/butler-app-shell-core.ps1', 'scripts/butler-java-preflight.ps1', 'scripts/butler-setup-check.ps1')) {
            if ($names -cnotcontains $required) { throw "Runtime ZIP is missing $required" }
        }
        if (@($names | Where-Object { $_ -match '^bet/bet-cli/build/install/bet-cli/lib/bet-cli[^/]*\.jar$' }).Count -eq 0) { throw 'Prebuilt Butler runtime JAR is missing.' }
        $seen = @{}
        foreach ($entry in $archive.Entries) {
            $name = $entry.FullName.Replace('\', '/')
            if ($name.EndsWith('/')) { continue }
            if ($name -match '(^/|:|(^|/)\.\.(/|$))' -or $seen.ContainsKey($name)) { throw "Unsafe or duplicate ZIP entry: $name" }
            $seen[$name] = $true
            $path = [IO.Path]::GetFullPath((Join-Path $packageRoot $name))
            if (-not $path.StartsWith($packageRoot.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) { throw "ZIP entry escapes package: $name" }
            if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Extracted file missing: $name" }
            $stream = $entry.Open()
            $sha = [Security.Cryptography.SHA256]::Create()
            try { $entryHash = [BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-', '') }
            finally { $stream.Dispose(); $sha.Dispose() }
            if ((Get-SetupHash $path) -cne $entryHash) { throw "Extracted file differs from ZIP: $name" }
        }
        # Extra executable content could alter class loading or launch behavior.
        foreach ($file in Get-ChildItem -LiteralPath $packageRoot -Recurse -File) {
            $relative = $file.FullName.Substring($packageRoot.Length + 1).Replace('\', '/')
            if ($file.Extension -match '^\.(jar|ps1|cmd|bat|exe|dll)$' -and -not $seen.ContainsKey($relative)) { throw "Unexpected executable file: $relative" }
        }
        "ZIP checksum and $($seen.Count) extracted files match (checksum is not a publisher signature)"
    }
    finally { $archive.Dispose() }
} 'Download the runtime ZIP and matching .sha256 from the same Butler release, extract to an empty folder, and pass -RuntimeZip with that ZIP path.'

if ($RuntimeOnly) {
    if ($script:blockers -gt 0) {
        Write-Output "BUTLER SETUP RUNTIME CHECK: BLOCKED ($script:blockers checks)"
        exit 1
    }
    Write-Output 'BUTLER SETUP RUNTIME CHECK: PASS'
    Write-Output 'NEXT: Runtime integrity and prerequisites are ready for a restore or new-league setup.'
    exit 0
}

$configDir = $null
$dataDir = $null
Report-Check 'DATA' {
    $localData = [string]$env:LOCALAPPDATA
    if ([string]::IsNullOrWhiteSpace($localData)) { $localData = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData) }
    if ([string]::IsNullOrWhiteSpace($localData)) { throw 'LocalApplicationData is unavailable.' }
    $script:configDir = Join-Path $localData 'Butler'
    $candidate = [string]$env:BUTLER_APP_DATA_DIR
    if ([string]::IsNullOrWhiteSpace($candidate)) { $candidate = Join-Path $script:configDir 'data' }
    if (-not [IO.Path]::IsPathRooted($candidate)) { throw 'BUTLER_APP_DATA_DIR must be an absolute path.' }
    $script:dataDir = [IO.Path]::GetFullPath($candidate)
    $root = [IO.Path]::GetFullPath($packageRoot).TrimEnd('\')
    if ($script:dataDir.Equals($root, [StringComparison]::OrdinalIgnoreCase) -or $script:dataDir.StartsWith($root + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Runtime data must be outside the source/package tree.' }
    $database = Join-Path $script:dataDir 'butler.db'
    if (-not (Test-Path -LiteralPath $database -PathType Leaf)) { throw "Database missing: $database" }
    $stream = [IO.File]::Open($database, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
    try {
        $header = New-Object byte[] 16
        if ($stream.Read($header, 0, 16) -ne 16 -or [Text.Encoding]::ASCII.GetString($header) -cne "SQLite format 3`0") { throw "Invalid SQLite header: $database" }
    }
    finally { $stream.Dispose() }
    "$database exists with a SQLite header; schema and league contents require launch verification"
} 'Use an external data directory. Restore an existing private Butler backup, or on a fresh profile run scripts\butler-setup-new-league.cmd -RuntimeZip <runtime.zip> to build Butler from your current Sleeper league.'

Report-Check 'LEAGUE' {
    if ([string]::IsNullOrWhiteSpace($script:configDir)) { throw 'Saved league location is unavailable.' }
    $selection = Join-Path $script:configDir 'app-league.txt'
    if (-not (Test-Path -LiteralPath $selection -PathType Leaf)) { throw "Saved league selection missing: $selection" }
    $raw = [IO.File]::ReadAllText($selection).Trim()
    $league = [Guid]::Empty
    if (-not [Guid]::TryParse($raw, [ref]$league)) { throw "Saved league selection is not a UUID: $selection" }
    "$league (format only; membership is checked during launch)"
} 'Restore the matching private backup and league selection, or use scripts\butler-setup-new-league.cmd on a fresh profile. This check never changes app-league.txt.'

if ($script:blockers -gt 0) {
    Write-Output "BUTLER SETUP CHECK: BLOCKED ($script:blockers checks)"
    exit 1
}
Write-Output 'BUTLER SETUP CHECK: PASS'
Write-Output 'NEXT: Run scripts\butler-app.cmd. These prerequisite checks do not verify database contents or manager-page readiness.'
