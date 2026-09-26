param(
    [string]$SleeperUsername,
    [string]$SleeperLeagueId
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $scriptDir
$runtimeBuilder = Join-Path $scriptDir 'butler-runtime-release-bundle.ps1'
$gitCommand = Get-Command git.exe -ErrorAction SilentlyContinue
if ($null -eq $gitCommand) { $gitCommand = Get-Command git -ErrorAction SilentlyContinue }
if ($null -eq $gitCommand) { throw 'MVP ACCEPTANCE BLOCKED: Git is unavailable.' }
$git = [string]$gitCommand.Source

$isolatedRoot = $null
$originalLocalAppData = [string]$env:LOCALAPPDATA
$originalDataDir = [string]$env:BUTLER_APP_DATA_DIR
$passed = $false

function Invoke-GitText {
    param([string[]]$Arguments)
    Push-Location $repoRoot
    try {
        $lines = @(& $git @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
    }
    finally { Pop-Location }
    if ($exitCode -ne 0) {
        throw ("MVP ACCEPTANCE BLOCKED: git {0} failed: {1}" -f ($Arguments -join ' '), (($lines | ForEach-Object { "$_" }) -join ' '))
    }
    return (($lines | ForEach-Object { "$_" }) -join "`n").Trim()
}

function Require-Text {
    param([AllowNull()][string]$Value, [string]$Label)
    if ([string]::IsNullOrWhiteSpace($Value)) { throw "MVP ACCEPTANCE BLOCKED: $Label is required." }
    return $Value.Trim()
}

try {
    Write-Host 'Butler MVP completion acceptance'
    Write-Host 'Boundary: exact clean HEAD package; isolated temporary Windows profile; real Butler profile is not used.'
    Write-Host 'Boundary: Butler-local setup/evidence only; no lineup, waiver, trade, FAAB, or other Sleeper transaction write.'

    if (-not (Test-Path -LiteralPath $runtimeBuilder -PathType Leaf)) {
        throw "MVP ACCEPTANCE BLOCKED: runtime builder missing at $runtimeBuilder"
    }

    if ([string]::IsNullOrWhiteSpace($SleeperUsername)) {
        $SleeperUsername = Read-Host 'Sleeper username'
    }
    if ([string]::IsNullOrWhiteSpace($SleeperLeagueId)) {
        $SleeperLeagueId = Read-Host 'Sleeper league ID'
    }
    $SleeperUsername = Require-Text -Value $SleeperUsername -Label 'Sleeper username'
    $SleeperLeagueId = Require-Text -Value $SleeperLeagueId -Label 'Sleeper league ID'
    if ($SleeperLeagueId -notmatch '^\d+$') {
        throw 'MVP ACCEPTANCE BLOCKED: Sleeper league ID must contain digits only.'
    }

    $status = Invoke-GitText -Arguments @('status', '--porcelain', '--untracked-files=all')
    if (-not [string]::IsNullOrWhiteSpace($status)) {
        throw "MVP ACCEPTANCE BLOCKED: worktree must be clean before exact-HEAD package creation.`n$status"
    }
    $head = Invoke-GitText -Arguments @('rev-parse', '--verify', 'HEAD^{commit}')
    if ($head -notmatch '^[0-9a-fA-F]{40}$') {
        throw 'MVP ACCEPTANCE BLOCKED: HEAD did not resolve to one exact commit.'
    }
    $head = $head.ToLowerInvariant()
    $short = $head.Substring(0, 8)

    Push-Location $repoRoot
    try {
        & $runtimeBuilder -Force
        if ($LASTEXITCODE -ne 0) { throw "MVP ACCEPTANCE BLOCKED: runtime bundle exited with code $LASTEXITCODE." }
    }
    finally { Pop-Location }

    $runtimeZip = Join-Path $repoRoot ("release-output\Butler-runtime-{0}.zip" -f $short)
    $runtimeChecksum = $runtimeZip + '.sha256'
    foreach ($required in @($runtimeZip, $runtimeChecksum)) {
        if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
            throw "MVP ACCEPTANCE BLOCKED: exact-HEAD runtime artifact missing at $required"
        }
    }

    $isolatedRoot = Join-Path ([IO.Path]::GetTempPath()) ('Butler-mvp-real-' + [Guid]::NewGuid().ToString('N'))
    $packageDir = Join-Path $isolatedRoot 'package'
    $profileDir = Join-Path $isolatedRoot 'profile'
    [IO.Directory]::CreateDirectory($packageDir) | Out-Null
    [IO.Directory]::CreateDirectory($profileDir) | Out-Null

    Expand-Archive -LiteralPath $runtimeZip -DestinationPath $packageDir -Force
    $entry = Join-Path $packageDir 'scripts\butler-setup-new-league.cmd'
    if (-not (Test-Path -LiteralPath $entry -PathType Leaf)) {
        throw 'MVP ACCEPTANCE BLOCKED: packaged runtime is missing butler-setup-new-league.cmd.'
    }

    $env:LOCALAPPDATA = $profileDir
    $env:BUTLER_APP_DATA_DIR = ''

    Write-Host ''
    Write-Host "Exact commit: $head"
    Write-Host "Isolated profile: $profileDir"
    Write-Host 'Running packaged zero-to-Dashboard onboarding with seven-page verification...'

    $lines = @(& $entry -RuntimeZip $runtimeZip -SleeperUsername $SleeperUsername -SleeperLeagueId $SleeperLeagueId -VerifyOnly 2>&1)
    $exitCode = $LASTEXITCODE
    $text = ($lines | ForEach-Object { "$_" }) -join "`n"
    $lines | ForEach-Object { Write-Host "$_" }

    if ($exitCode -ne 0) {
        throw "MVP ACCEPTANCE BLOCKED: packaged onboarding exited with code $exitCode."
    }
    if ($text -notmatch '(?m)^BUTLER MVP ONBOARDING: PASS\s*$') {
        throw 'MVP ACCEPTANCE BLOCKED: packaged onboarding did not emit its PASS marker.'
    }
    foreach ($route in @('/', '/team', '/matchup', '/waivers', '/league', '/trade?load=1', '/history?load=1')) {
        if ($text -notmatch ('(?m)^SETUP PAGE: PASS\s+' + [regex]::Escape($route) + '\s*$')) {
            throw "MVP ACCEPTANCE BLOCKED: seven-page verification did not prove $route."
        }
    }

    $db = Join-Path $profileDir 'Butler\data\butler.db'
    $selection = Join-Path $profileDir 'Butler\app-league.txt'
    if (-not (Test-Path -LiteralPath $db -PathType Leaf) -or -not (Test-Path -LiteralPath $selection -PathType Leaf)) {
        throw 'MVP ACCEPTANCE BLOCKED: isolated onboarding did not create both governed data and saved league selection.'
    }

    $passed = $true
    Write-Host ''
    Write-Host 'BUTLER MVP COMPLETION ACCEPTANCE: PASS'
    Write-Host "Exact commit: $head"
    Write-Host 'Fresh-profile onboarding reached all seven manager pages from the packaged runtime.'
    Write-Host 'The isolated verification runtime stopped and no Sleeper transaction write was requested.'
}
finally {
    if ([string]::IsNullOrWhiteSpace($originalLocalAppData)) {
        Remove-Item Env:LOCALAPPDATA -ErrorAction SilentlyContinue
    }
    else { $env:LOCALAPPDATA = $originalLocalAppData }

    if ([string]::IsNullOrWhiteSpace($originalDataDir)) {
        Remove-Item Env:BUTLER_APP_DATA_DIR -ErrorAction SilentlyContinue
    }
    else { $env:BUTLER_APP_DATA_DIR = $originalDataDir }

    if ($null -ne $isolatedRoot -and (Test-Path -LiteralPath $isolatedRoot)) {
        $resolved = [IO.Path]::GetFullPath($isolatedRoot)
        $prefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\Butler-mvp-real-'
        if (-not $resolved.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
            throw "MVP ACCEPTANCE BLOCKED: unsafe isolated cleanup path $resolved"
        }
        Remove-Item -LiteralPath $resolved -Recurse -Force -ErrorAction SilentlyContinue
    }

    if (-not $passed) {
        Write-Host 'MVP acceptance did not pass. Real Butler profile/data were not selected by this test.'
    }
}
