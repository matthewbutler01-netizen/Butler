param(
    [ValidateRange(30, 600)]
    [int]$StartupTimeoutSeconds = 240,

    [ValidateRange(30, 600)]
    [int]$RouteTimeoutSeconds = 180
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $scriptDir
$releaseOutput = Join-Path $repoRoot 'release-output'
$loopback = [System.Net.IPAddress]::Parse('127.0.0.1')
$powershell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$taskkill = Join-Path $env:SystemRoot 'System32\taskkill.exe'
$localAppData = [string]$env:LOCALAPPDATA
if ([string]::IsNullOrWhiteSpace($localAppData)) {
    $localAppData = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)
}
if ([string]::IsNullOrWhiteSpace($localAppData)) {
    throw 'BF-789 BLOCKED: LocalApplicationData is unavailable.'
}

$configuredDataDir = [string]$env:BUTLER_APP_DATA_DIR
if ([string]::IsNullOrWhiteSpace($configuredDataDir)) {
    $dataDir = Join-Path $localAppData 'Butler\data'
}
else {
    if (-not [IO.Path]::IsPathRooted($configuredDataDir)) {
        throw 'BF-789 BLOCKED: BUTLER_APP_DATA_DIR must be absolute when supplied.'
    }
    $dataDir = [IO.Path]::GetFullPath($configuredDataDir)
}
$databasePath = Join-Path $dataDir 'butler.db'
$configPath = Join-Path $localAppData 'Butler\app-league.txt'

if (-not (Test-Path -LiteralPath $powershell -PathType Leaf)) {
    throw 'BF-789 BLOCKED: Windows PowerShell 5.1 executable is unavailable.'
}
if (-not (Test-Path -LiteralPath $databasePath -PathType Leaf)) {
    throw "BF-789 BLOCKED: governed Butler database is missing at $databasePath."
}
if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
    throw 'BF-789 BLOCKED: Butler league configuration is missing.'
}

$gitCommand = Get-Command git.exe -ErrorAction SilentlyContinue
if ($null -eq $gitCommand) { $gitCommand = Get-Command git -ErrorAction SilentlyContinue }
if ($null -eq $gitCommand) { throw 'BF-789 BLOCKED: Git is required to resolve the current runtime artifact.' }
Push-Location $repoRoot
try {
    $shortSha = ((& $gitCommand.Source 'rev-parse' '--short=8' 'HEAD' 2>&1) | ForEach-Object { "$_" }) -join ''
    $gitExit = $LASTEXITCODE
}
finally {
    Pop-Location
}
$shortSha = $shortSha.Trim().ToLowerInvariant()
if ($gitExit -ne 0 -or $shortSha -notmatch '^[0-9a-f]{8}$') {
    throw 'BF-789 BLOCKED: unable to resolve exact current HEAD short SHA.'
}

$runtimeZip = Join-Path $releaseOutput ("Butler-runtime-{0}.zip" -f $shortSha)
$checksumPath = $runtimeZip + '.sha256'
if (-not (Test-Path -LiteralPath $runtimeZip -PathType Leaf) -or
    -not (Test-Path -LiteralPath $checksumPath -PathType Leaf)) {
    throw 'BF-789 BLOCKED: exact current-HEAD runtime ZIP/checksum is unavailable; BF-773 packaged acceptance must run first.'
}

$checksumText = [IO.File]::ReadAllText($checksumPath, [Text.Encoding]::ASCII).Trim()
if ($checksumText -notmatch '^([0-9a-f]{64})\s{2}(.+)$') {
    throw 'BF-789 BLOCKED: runtime checksum sidecar format is invalid.'
}
$expectedHash = $Matches[1]
$expectedName = $Matches[2]
if ($expectedName -cne [IO.Path]::GetFileName($runtimeZip)) {
    throw 'BF-789 BLOCKED: runtime checksum sidecar artifact name does not match the runtime ZIP.'
}
$actualHash = (Get-FileHash -LiteralPath $runtimeZip -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actualHash -cne $expectedHash) {
    throw 'BF-789 BLOCKED: runtime ZIP SHA-256 does not match its checksum.'
}

function Get-FreeLoopbackPort {
    $probe = [System.Net.Sockets.TcpListener]::new($loopback, 0)
    try {
        $probe.Start()
        return ([System.Net.IPEndPoint]$probe.LocalEndpoint).Port
    }
    finally {
        $probe.Stop()
    }
}

function Stop-OwnedProcessTree {
    param([AllowNull()]$Process)
    if ($null -eq $Process) { return }
    try { if ($Process.HasExited) { return } } catch { return }
    if (Test-Path -LiteralPath $taskkill -PathType Leaf) {
        try {
            & $taskkill /PID $Process.Id /T /F 2>$null | Out-Null
            if ($LASTEXITCODE -eq 0) { return }
        }
        catch {
        }
    }
    try { $Process.Kill() } catch {}
}

function Wait-ButlerHealth {
    param(
        [Parameter(Mandatory = $true)][int]$Port,
        [Parameter(Mandatory = $true)]$Process,
        [Parameter(Mandatory = $true)][string]$BoundaryName
    )

    $deadline = [DateTime]::UtcNow.AddSeconds($StartupTimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($Process.HasExited) {
            throw "BF-789 BLOCKED: packaged Butler exited during $BoundaryName."
        }
        $response = $null
        try {
            $request = [System.Net.HttpWebRequest]::Create("http://127.0.0.1:$Port/health")
            $request.Method = 'GET'
            $request.Timeout = 800
            $request.Proxy = $null
            $request.KeepAlive = $false
            $response = $request.GetResponse()
            $reader = [IO.StreamReader]::new($response.GetResponseStream(), [Text.Encoding]::UTF8)
            try { $body = $reader.ReadToEnd() } finally { $reader.Dispose() }
            if ([int]$response.StatusCode -eq 200) {
                $health = $body | ConvertFrom-Json
                if ([string]$health.service -ceq 'butler-app-shell' -and [string]$health.bind -ceq '127.0.0.1') {
                    return
                }
            }
        }
        catch {
        }
        finally {
            if ($null -ne $response) { $response.Close() }
        }
        Start-Sleep -Milliseconds 250
    }
    throw "BF-789 BLOCKED: packaged Butler did not remain healthy during $BoundaryName."
}

function Invoke-PackagedReadRoute {
    param(
        [Parameter(Mandatory = $true)][int]$Port,
        [Parameter(Mandatory = $true)]$Process,
        [Parameter(Mandatory = $true)][string]$RequestTarget,
        [Parameter(Mandatory = $true)][string]$ExpectedMarker,
        [Parameter(Mandatory = $true)][string]$BoundaryName
    )

    if ($Process.HasExited) {
        throw "BF-789 BLOCKED: packaged Butler exited before $BoundaryName."
    }

    $request = [System.Net.HttpWebRequest]::Create("http://127.0.0.1:$Port$RequestTarget")
    $request.Method = 'GET'
    $request.Timeout = $RouteTimeoutSeconds * 1000
    $request.ReadWriteTimeout = $RouteTimeoutSeconds * 1000
    $request.Proxy = $null
    $request.KeepAlive = $false
    $response = $null
    try {
        try {
            $response = $request.GetResponse()
        }
        catch [System.Net.WebException] {
            if ($null -eq $_.Exception.Response) {
                throw "BF-789 BLOCKED: $BoundaryName failed without an HTTP response: $($_.Exception.Message)"
            }
            $response = $_.Exception.Response
        }

        $reader = [IO.StreamReader]::new($response.GetResponseStream(), [Text.Encoding]::UTF8)
        try { $body = $reader.ReadToEnd() } finally { $reader.Dispose() }
        $status = [int]$response.StatusCode
        if ($status -ne 200) {
            throw "BF-789 BLOCKED: $BoundaryName returned HTTP $status.`n$body"
        }
        if ($body.IndexOf($ExpectedMarker, [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
            throw "BF-789 BLOCKED: $BoundaryName returned HTTP 200 without marker '$ExpectedMarker'."
        }
    }
    finally {
        if ($null -ne $response) { $response.Close() }
    }

    Wait-ButlerHealth -Port $Port -Process $Process -BoundaryName ("post-{0} health" -f $BoundaryName)
}

$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ("Butler-bf789-runtime-{0}" -f [Guid]::NewGuid().ToString('N'))
$process = $null
try {
    [IO.Directory]::CreateDirectory($tempRoot) | Out-Null
    Expand-Archive -LiteralPath $runtimeZip -DestinationPath $tempRoot -Force

    $packagedLauncher = Join-Path $tempRoot 'scripts\butler-app.ps1'
    $companionProxy = Join-Path $tempRoot 'scripts\butler-companion-read-proxy.cmd'
    $dispatcher = Join-Path $tempRoot 'scripts\butler-direct-java-dispatch.ps1'
    $runtimeLib = Join-Path $tempRoot 'bet\bet-cli\build\install\bet-cli\lib'
    $packagedGradle = Join-Path $tempRoot 'gradlew.bat'
    if (-not (Test-Path -LiteralPath $packagedLauncher -PathType Leaf) -or
        -not (Test-Path -LiteralPath $companionProxy -PathType Leaf) -or
        -not (Test-Path -LiteralPath $dispatcher -PathType Leaf) -or
        -not (Test-Path -LiteralPath $runtimeLib -PathType Container) -or
        -not (Test-Path -LiteralPath $packagedGradle -PathType Leaf)) {
        throw 'BF-789 BLOCKED: extracted package is missing launcher, companion proxy, direct-Java dispatcher, prebuilt runtime, or Gradle shim.'
    }

    $shimText = [IO.File]::ReadAllText($packagedGradle, [Text.Encoding]::ASCII)
    if ($shimText.IndexOf('runtime package Gradle shim only authorizes the prebuilt installDist startup probe', [System.StringComparison]::Ordinal) -lt 0 -or
        $shimText.IndexOf('exit /b 77', [System.StringComparison]::Ordinal) -lt 0) {
        throw 'BF-789 BLOCKED: release Gradle shim is no longer fail-closed.'
    }

    $port = Get-FreeLoopbackPort
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $powershell
    $start.Arguments = "-NoLogo -NoProfile -ExecutionPolicy Bypass -File `"$packagedLauncher`" -Port $port -NoBrowser"
    $start.WorkingDirectory = $tempRoot
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.EnvironmentVariables['BUTLER_APP_DATA_DIR'] = $dataDir
    $process = [Diagnostics.Process]::Start($start)
    if ($null -eq $process) {
        throw 'BF-789 BLOCKED: unable to start Butler from the extracted runtime package.'
    }

    Wait-ButlerHealth -Port $port -Process $process -BoundaryName 'startup'

    Invoke-PackagedReadRoute `
        -Port $port `
        -Process $process `
        -RequestTarget '/trade?load=1' `
        -ExpectedMarker 'Evaluate a trade' `
        -BoundaryName 'fully loaded Trade Lab'

    Invoke-PackagedReadRoute `
        -Port $port `
        -Process $process `
        -RequestTarget '/history?load=1' `
        -ExpectedMarker 'Immutable governed waiver audits' `
        -BoundaryName 'fully loaded Decision History'

    Write-Host 'Butler packaged companion-route acceptance (BF-789)'
    Write-Host "Artifact: $runtimeZip"
    Write-Host "SHA-256: $actualHash"
    Write-Host 'Routes: GET /trade?load=1; GET /history?load=1; health rechecked after each route'
    Write-Host 'Boundary: EXTRACTED_RUNTIME_PACKAGE; PREBUILT_READ_RUNTIME; GET_ONLY; /refresh EXCLUDED; NO_BUTLER_OR_SLEEPER_TRANSACTION_WRITE'
    Write-Host 'BF-789 PACKAGED COMPANION ROUTES: PASS'
}
finally {
    Stop-OwnedProcessTree -Process $process
    if (Test-Path -LiteralPath $tempRoot) {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}
