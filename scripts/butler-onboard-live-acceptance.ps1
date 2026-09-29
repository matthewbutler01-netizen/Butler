param(
    [string]$SleeperUsername,
    [string]$SleeperLeagueId,
    [ValidateRange(1, 900)][int]$TimeoutSeconds = 600
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$originalLocalData = [string]$env:LOCALAPPDATA
$originalDataDir = [string]$env:BUTLER_APP_DATA_DIR
$root = $null
$server = $null
$passed = $false

function Get-Page([string]$Url) {
    $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 15
    if ($response.StatusCode -ne 200) { throw "Expected HTTP 200 at $Url." }
    return [string]$response.Content
}

function Post-Form([string]$Url, [string]$Body) {
    $request = [Net.HttpWebRequest]::Create($Url)
    $request.Method = 'POST'
    $request.ContentType = 'application/x-www-form-urlencoded'
    $request.Timeout = 30000
    $bytes = [Text.Encoding]::UTF8.GetBytes($Body)
    $request.ContentLength = $bytes.Length
    $stream = $request.GetRequestStream()
    try { $stream.Write($bytes, 0, $bytes.Length) } finally { $stream.Dispose() }
    $response = $request.GetResponse()
    try {
        $reader = [IO.StreamReader]::new($response.GetResponseStream())
        try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
    }
    finally { $response.Close() }
}

try {
    if ([string]::IsNullOrWhiteSpace($SleeperUsername)) { $SleeperUsername = Read-Host 'Sleeper username' }
    if ([string]::IsNullOrWhiteSpace($SleeperLeagueId)) { $SleeperLeagueId = Read-Host 'Sleeper league ID' }
    if ($SleeperUsername -cnotmatch '^[A-Za-z0-9_-]{1,32}$' -or $SleeperLeagueId -notmatch '^\d+$') { throw 'Supply an exact Sleeper username and numeric league ID.' }
    $head = (& git -C $repoRoot rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0 -or $head -notmatch '^[0-9a-fA-F]{40}$') { throw 'Current Git HEAD is unavailable.' }
    $zip = Join-Path $repoRoot ('release-output\Butler-runtime-' + $head.Substring(0, 8) + '.zip')
    if (-not (Test-Path -LiteralPath $zip -PathType Leaf) -or -not (Test-Path -LiteralPath ($zip + '.sha256') -PathType Leaf)) {
        throw 'Exact-HEAD runtime is missing. Run scripts\butler-release-acceptance.cmd first.'
    }
    $root = Join-Path ([IO.Path]::GetTempPath()) ('Butler-browser-live-' + [Guid]::NewGuid().ToString('N'))
    $package = Join-Path $root 'package'
    $profile = Join-Path $root 'profile'
    [IO.Directory]::CreateDirectory($package) | Out-Null
    [IO.Directory]::CreateDirectory($profile) | Out-Null
    Expand-Archive -LiteralPath $zip -DestinationPath $package -Force
    $env:LOCALAPPDATA = $profile
    $env:BUTLER_APP_DATA_DIR = ''
    $probe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    try { $probe.Start(); $port = ([Net.IPEndPoint]$probe.LocalEndpoint).Port } finally { $probe.Stop() }
    $url = "http://127.0.0.1:$port/"
    $shell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    $entry = Join-Path $package 'scripts\butler-onboard.ps1'
    $stdout = Join-Path $root 'server.out.log'
    $stderr = Join-Path $root 'server.err.log'
    $launchArgs = '-NoLogo -NoProfile -ExecutionPolicy Bypass -File "' + $entry + '" -RuntimeZip "' + $zip + '" -Port ' + $port + ' -NoBrowser -VerifyOnly'
    $server = Start-Process -FilePath $shell -ArgumentList $launchArgs -RedirectStandardOutput $stdout -RedirectStandardError $stderr -WindowStyle Hidden -PassThru
    $welcome = $null
    for ($attempt = 0; $attempt -lt 100; $attempt++) {
        if ($server.HasExited) { throw ('Browser entry point stopped: ' + [IO.File]::ReadAllText($stderr)) }
        try { $welcome = Get-Page $url; break } catch { Start-Sleep -Milliseconds 200 }
    }
    if ($null -eq $welcome) { throw 'Browser entry point did not start.' }
    $tokenMatch = [regex]::Match($welcome, "name='token' value='(?<token>[0-9a-f]{64})'")
    if (-not $tokenMatch.Success) { throw 'Landing page did not include a setup token.' }
    $token = $tokenMatch.Groups['token'].Value
    $lookup = Post-Form ($url + 'lookup') ('token=' + $token + '&username=' + [Uri]::EscapeDataString($SleeperUsername))
    if ($lookup -notmatch [regex]::Escape($SleeperLeagueId) -or $lookup -notmatch 'Choose your team') { throw 'Live league lookup did not show the exact requested league.' }
    $progress = Post-Form ($url + 'import') ('token=' + $token + '&league=' + $SleeperLeagueId)
    if ($progress -notmatch 'Preparing your team') { throw 'Selected-league import did not start.' }
    Write-Host "Selected Sleeper league: $SleeperLeagueId"
    Write-Host 'Waiting for isolated read-only onboarding and seven-page launch verification...'
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($server.HasExited) { throw ('Browser entry point exited: ' + [IO.File]::ReadAllText($stderr)) }
        $status = Get-Page ($url + 'status')
        if ($status -match 'Verification passed') { break }
        if ($status -match 'Setup needs attention') { throw ('Packaged setup blocked: ' + $status) }
        Start-Sleep -Seconds 2
    }
    if ($status -notmatch 'Verification passed') { throw 'Browser onboarding timed out before verification completed.' }
    $runParent = Join-Path $profile 'Butler\onboard-runs'
    $runs = @(Get-ChildItem -LiteralPath $runParent -Directory)
    if ($runs.Count -ne 1) { throw 'Expected one isolated onboarding run.' }
    $log = [IO.File]::ReadAllText((Join-Path $runs[0].FullName 'setup.out.log'))
    foreach ($route in @('/', '/team', '/matchup', '/waivers', '/league', '/trade?load=1', '/history?load=1')) {
        if ($log -notmatch ('(?m)^SETUP PAGE: PASS\s+' + [regex]::Escape($route) + '\s*$')) { throw "Manager page $route was not verified." }
    }
    if ($log -notmatch '(?m)^BUTLER MVP ONBOARDING: PASS\s*$') { throw 'Packaged onboarding did not report PASS.' }
    $passed = $true
    Write-Host 'BUTLER BROWSER LIVE ONBOARDING ACCEPTANCE: PASS'
    Write-Host "Exact commit: $head"
    Write-Host 'Isolated profile and owned verification runtime are stopped and removed.'
}
finally {
    if ($null -ne $server -and -not $server.HasExited) { $server.Kill(); $server.WaitForExit() }
    if ($null -ne $root -and (Test-Path -LiteralPath $root)) {
        $pidFiles = @(Get-ChildItem -LiteralPath $root -Filter 'setup.pid' -Recurse -File -ErrorAction SilentlyContinue)
        foreach ($pidFile in $pidFiles) {
            $identity = [IO.File]::ReadAllText($pidFile.FullName).Split('|')
            $ownedPid = 0
            $startedTicks = 0L
            if ($identity.Length -eq 2 -and [int]::TryParse($identity[0], [ref]$ownedPid) -and [long]::TryParse($identity[1], [ref]$startedTicks)) {
                $child = Get-Process -Id $ownedPid -ErrorAction SilentlyContinue
                if ($null -ne $child -and -not $child.HasExited -and $child.StartTime.ToUniversalTime().Ticks -eq $startedTicks) { $child.Kill(); $child.WaitForExit() }
            }
        }
    }
    $env:LOCALAPPDATA = $originalLocalData
    $env:BUTLER_APP_DATA_DIR = $originalDataDir
    if ($passed -and $null -ne $root -and (Test-Path -LiteralPath $root)) { Remove-Item -LiteralPath $root -Recurse -Force }
    elseif ($null -ne $root) { Write-Host "Isolated diagnostic data retained: $root" }
}
