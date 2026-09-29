Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = Join-Path ([IO.Path]::GetTempPath()) ('Butler-onboard-smoke-' + [Guid]::NewGuid().ToString('N'))
$originalLocalData = [string]$env:LOCALAPPDATA
$originalDataDir = [string]$env:BUTLER_APP_DATA_DIR
$process = $null
try {
    [IO.Directory]::CreateDirectory($root) | Out-Null
    $env:LOCALAPPDATA = Join-Path $root 'profile'
    $env:BUTLER_APP_DATA_DIR = ''
    [IO.Directory]::CreateDirectory($env:LOCALAPPDATA) | Out-Null
    $zip = Join-Path $root 'fixture-runtime.zip'
    [IO.File]::WriteAllText($zip, 'fixture; browser smoke does not import')
    [IO.File]::WriteAllText($zip + '.sha256', 'fixture')
    $fixtureScripts = Join-Path $root 'package\scripts'
    $fixtureLibs = Join-Path $root 'package\bet\bet-cli\build\install\bet-cli\lib'
    [IO.Directory]::CreateDirectory($fixtureScripts) | Out-Null
    [IO.Directory]::CreateDirectory($fixtureLibs) | Out-Null
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'butler-onboard.ps1') -Destination $fixtureScripts
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'butler-java-preflight.ps1') -Destination $fixtureScripts
    [IO.File]::WriteAllText((Join-Path $fixtureScripts 'butler-setup-check.ps1'), 'param([string]$RuntimeZip,[switch]$RuntimeOnly); exit 0')
    [IO.File]::WriteAllText((Join-Path $fixtureScripts 'butler-setup-new-league.ps1'), 'throw "fixture must not import"')
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    try { $listener.Start(); $port = ([Net.IPEndPoint]$listener.LocalEndpoint).Port } finally { $listener.Stop() }
    $script = Join-Path $fixtureScripts 'butler-onboard.ps1'
    $shell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    $stdout = Join-Path $root 'out.log'
    $stderr = Join-Path $root 'err.log'
    $launchArgs = '-NoLogo -NoProfile -ExecutionPolicy Bypass -File "' + $script + '" -RuntimeZip "' + $zip + '" -Port ' + $port + ' -NoBrowser'
    $process = Start-Process -FilePath $shell -ArgumentList $launchArgs -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru -WindowStyle Hidden
    $url = "http://127.0.0.1:$port/"
    $ready = $false
    for ($attempt = 0; $attempt -lt 80; $attempt++) {
        if ($process.HasExited) { throw ('Onboarding server exited: ' + [IO.File]::ReadAllText($stderr)) }
        try {
            $page = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 2
            $ready = $true
            break
        }
        catch { Start-Sleep -Milliseconds 100 }
    }
    if (-not $ready) { throw 'Onboarding page did not start.' }
    if ($page.StatusCode -ne 200 -or $page.Content -notmatch 'Connect your Sleeper profile' -or $page.Content -notmatch 'not Sleeper account sign-in') {
        throw 'Fresh-profile connection page is missing its primary flow or public-lookup boundary.'
    }
    if ($page.Content -notmatch "name='token' value='([a-f0-9]+)'") { throw 'Fresh-profile form token is missing.' }
    $formToken = $Matches[1]
    foreach ($testOrigin in @($url.TrimEnd('/'), 'null', 'http://example.invalid')) {
        $lookup = [Net.HttpWebRequest]::Create($url + 'lookup')
        $lookup.Method = 'POST'
        $lookup.ContentType = 'application/x-www-form-urlencoded'
        $lookup.Headers['Origin'] = $testOrigin
        $lookupBody = [Text.Encoding]::UTF8.GetBytes('token=' + $formToken + '&username=invalid!')
        $lookup.ContentLength = $lookupBody.Length
        $lookupStream = $lookup.GetRequestStream()
        try { $lookupStream.Write($lookupBody, 0, $lookupBody.Length) } finally { $lookupStream.Dispose() }
        $expectedStatus = if ($testOrigin -ceq 'http://example.invalid') { 403 } else { 400 }
        try {
            $lookupResponse = $lookup.GetResponse()
            try { throw "Origin guard accepted invalid username with HTTP $([int]$lookupResponse.StatusCode)." } finally { $lookupResponse.Close() }
        }
        catch [Net.WebException] {
            $lookupResponse = $_.Exception.Response
            if ($null -eq $lookupResponse) { throw }
            try { if ([int]$lookupResponse.StatusCode -ne $expectedStatus) { throw "Expected HTTP $expectedStatus for origin $testOrigin, got $([int]$lookupResponse.StatusCode)." } }
            finally { $lookupResponse.Close() }
        }
    }
    $request = [Net.HttpWebRequest]::Create($url + 'import')
    $request.Method = 'POST'
    $request.ContentType = 'application/x-www-form-urlencoded'
    $request.Headers['Origin'] = 'null'
    $body = [Text.Encoding]::UTF8.GetBytes('token=invalid&league=123')
    $request.ContentLength = $body.Length
    $stream = $request.GetRequestStream()
    try { $stream.Write($body, 0, $body.Length) } finally { $stream.Dispose() }
    try {
        $response = $request.GetResponse()
        try { throw "Invalid setup token was accepted: $([int]$response.StatusCode)" } finally { $response.Close() }
    }
    catch [Net.WebException] {
        $response = $_.Exception.Response
        if ($null -eq $response) { throw }
        try { if ([int]$response.StatusCode -ne 403) { throw "Expected 403 for invalid token, got $([int]$response.StatusCode)." } }
        finally { $response.Close() }
    }
    if (Test-Path -LiteralPath (Join-Path $env:LOCALAPPDATA 'Butler\data\butler.db')) { throw 'GET/invalid POST created a Butler database.' }
    Write-Host 'BUTLER BROWSER ONBOARDING SMOKE: PASS'
}
finally {
    if ($null -ne $process -and -not $process.HasExited) { $process.Kill(); $process.WaitForExit() }
    $env:LOCALAPPDATA = $originalLocalData
    $env:BUTLER_APP_DATA_DIR = $originalDataDir
    if (Test-Path -LiteralPath $root) { Remove-Item -LiteralPath $root -Recurse -Force }
}
