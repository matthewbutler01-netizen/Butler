param(
    [string]$RuntimeZip,
    [ValidateRange(30, 600)]
    [int]$StartupTimeoutSeconds = 240
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$runtimeZipExplicit = $PSBoundParameters.ContainsKey('RuntimeZip')
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $scriptDir
$releaseOutput = Join-Path $repoRoot 'release-output'
$runtimeBuilder = Join-Path $scriptDir 'butler-runtime-release-bundle.ps1'
$loopback = [System.Net.IPAddress]::Parse('127.0.0.1')
$powershell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$taskkill = Join-Path $env:SystemRoot 'System32\taskkill.exe'
$localAppData = [string]$env:LOCALAPPDATA
if ([string]::IsNullOrWhiteSpace($localAppData)) {
    $localAppData = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)
}
if ([string]::IsNullOrWhiteSpace($localAppData)) {
    throw 'BF-773 BLOCKED: LocalApplicationData is unavailable.'
}

$configuredDataDir = [string]$env:BUTLER_APP_DATA_DIR
if ([string]::IsNullOrWhiteSpace($configuredDataDir)) {
    $dataDir = Join-Path $localAppData 'Butler\data'
}
else {
    if (-not [IO.Path]::IsPathRooted($configuredDataDir)) {
        throw 'BF-773 BLOCKED: BUTLER_APP_DATA_DIR must be absolute when supplied.'
    }
    $dataDir = [IO.Path]::GetFullPath($configuredDataDir)
}
$databasePath = Join-Path $dataDir 'butler.db'
$configPath = Join-Path $localAppData 'Butler\app-league.txt'

if (-not (Test-Path -LiteralPath $powershell -PathType Leaf)) {
    throw 'BF-773 BLOCKED: Windows PowerShell 5.1 executable is unavailable.'
}
if (-not (Test-Path -LiteralPath $databasePath -PathType Leaf)) {
    throw "BF-773 BLOCKED: governed Butler database is missing at $databasePath. Run scripts\butler-migrate-runtime-data.ps1 first."
}
if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
    throw 'BF-773 BLOCKED: Butler league configuration is missing; packaged launch requires the existing configured app target.'
}

if (-not $runtimeZipExplicit) {
    $gitCommand = Get-Command git.exe -ErrorAction SilentlyContinue
    if ($null -eq $gitCommand) { $gitCommand = Get-Command git -ErrorAction SilentlyContinue }
    if ($null -eq $gitCommand) { throw 'BF-773 BLOCKED: Git is required to resolve the current runtime artifact.' }
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
        throw 'BF-773 BLOCKED: unable to resolve exact current HEAD short SHA.'
    }
    $RuntimeZip = Join-Path $releaseOutput ("Butler-runtime-{0}.zip" -f $shortSha)
    if (-not (Test-Path -LiteralPath $RuntimeZip -PathType Leaf)) {
        if (-not (Test-Path -LiteralPath $runtimeBuilder -PathType Leaf)) {
            throw "BF-773 BLOCKED: runtime release builder not found at $runtimeBuilder"
        }
        Write-Host "BF-773: current HEAD runtime artifact is absent; building exact prebuilt read-runtime bundle for $shortSha."
        & $runtimeBuilder -Force
    }
}
else {
    if ([string]::IsNullOrWhiteSpace($RuntimeZip)) {
        throw 'BF-773 BLOCKED: explicit -RuntimeZip must not be blank.'
    }
    if (-not [IO.Path]::IsPathRooted($RuntimeZip)) {
        $RuntimeZip = Join-Path $repoRoot $RuntimeZip
    }
}
$RuntimeZip = [IO.Path]::GetFullPath($RuntimeZip)
if (-not (Test-Path -LiteralPath $RuntimeZip -PathType Leaf)) {
    throw "BF-773 BLOCKED: runtime release ZIP not found at $RuntimeZip"
}

$checksumPath = $RuntimeZip + '.sha256'
if (-not (Test-Path -LiteralPath $checksumPath -PathType Leaf)) {
    throw "BF-773 BLOCKED: runtime release checksum sidecar not found at $checksumPath"
}
$checksumText = [IO.File]::ReadAllText($checksumPath, [Text.Encoding]::ASCII).Trim()
if ($checksumText -notmatch '^([0-9a-f]{64})\s{2}(.+)$') {
    throw 'BF-773 BLOCKED: runtime release checksum sidecar format is invalid.'
}
$expectedHash = $Matches[1]
$expectedName = $Matches[2]
if ($expectedName -cne [IO.Path]::GetFileName($RuntimeZip)) {
    throw 'BF-773 BLOCKED: checksum sidecar artifact name does not match the selected runtime ZIP.'
}
$actualHash = (Get-FileHash -LiteralPath $RuntimeZip -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actualHash -cne $expectedHash) {
    throw 'BF-773 BLOCKED: runtime release ZIP SHA-256 does not match its checksum.'
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
        [Parameter(Mandatory = $true)]$Process
    )
    $deadline = [DateTime]::UtcNow.AddSeconds($StartupTimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($Process.HasExited) {
            throw 'BF-773 BLOCKED: packaged Butler exited during no-Gradle startup.'
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
    throw "BF-773 BLOCKED: packaged Butler did not become healthy within $StartupTimeoutSeconds seconds."
}

$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ("Butler-bf773-runtime-{0}" -f [Guid]::NewGuid().ToString('N'))
$process = $null
try {
    [IO.Directory]::CreateDirectory($tempRoot) | Out-Null
    Expand-Archive -LiteralPath $RuntimeZip -DestinationPath $tempRoot -Force

    $packagedLauncher = Join-Path $tempRoot 'scripts\butler-app.ps1'
    $securityCheck = Join-Path $tempRoot 'scripts\butler-release-security-check.ps1'
    $runtimeLib = Join-Path $tempRoot 'runtime\lib'
    if (-not (Test-Path -LiteralPath $packagedLauncher -PathType Leaf) -or
        -not (Test-Path -LiteralPath $securityCheck -PathType Leaf) -or
        -not (Test-Path -LiteralPath $runtimeLib -PathType Container)) {
        throw 'BF-773 BLOCKED: extracted runtime package is missing launcher, security, or runtime/lib entrypoints.'
    }
    $runtimeJars = @(Get-ChildItem -LiteralPath $runtimeLib -Filter '*.jar' -File -ErrorAction Stop)
    $appJars = @($runtimeJars | Where-Object { $_.Name -like 'bet-cli*.jar' })
    if ($runtimeJars.Count -eq 0 -or $appJars.Count -ne 1) {
        throw 'BF-773 BLOCKED: extracted runtime package does not contain one valid bet-cli prebuilt runtime.'
    }

    $packagedGradle = Join-Path $tempRoot 'gradlew.bat'
    if (-not (Test-Path -LiteralPath $packagedGradle -PathType Leaf)) {
        throw 'BF-773 BLOCKED: extracted source package is missing the launcher sentinel target gradlew.bat.'
    }
    $sentinel = "@echo off`r`necho BF-773 BLOCKED: packaged read startup attempted Gradle. 1>&2`r`nexit /b 77`r`n"
    [IO.File]::WriteAllText($packagedGradle, $sentinel, [Text.Encoding]::ASCII)
    $wrapperDir = Join-Path $tempRoot 'gradle'
    if (Test-Path -LiteralPath $wrapperDir) {
        Remove-Item -LiteralPath $wrapperDir -Recurse -Force
    }
    $unixGradle = Join-Path $tempRoot 'gradlew'
    if (Test-Path -LiteralPath $unixGradle) {
        Remove-Item -LiteralPath $unixGradle -Force
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
        throw 'BF-773 BLOCKED: unable to start Butler from the extracted prebuilt runtime package.'
    }

    Wait-ButlerHealth -Port $port -Process $process
    & $securityCheck -BaseUrl ("http://127.0.0.1:{0}/" -f $port)

    $runtimeLeaks = @(Get-ChildItem -LiteralPath $tempRoot -Recurse -File -ErrorAction Stop | Where-Object {
        $name = $_.Name.ToLowerInvariant()
        $name -ceq 'butler.db' -or
        $name.EndsWith('.db-wal') -or
        $name.EndsWith('.db-shm') -or
        $name.EndsWith('.db-journal') -or
        $name.EndsWith('.db.init.lock')
    })
    if ($runtimeLeaks.Count -ne 0) {
        $leaks = ($runtimeLeaks | ForEach-Object { $_.FullName }) -join '; '
        throw "BF-773 BLOCKED: runtime database material leaked into the extracted runtime package tree: $leaks"
    }

    Write-Host 'Butler prebuilt runtime packaged launch acceptance (BF-773)'
    Write-Host "Artifact: $RuntimeZip"
    Write-Host "SHA-256: $actualHash"
    Write-Host "Extracted package: $tempRoot"
    Write-Host "Runtime JARs: $($runtimeJars.Count)"
    Write-Host "Runtime data: $dataDir"
    Write-Host 'Gradle proof: wrapper directory removed; gradlew.bat replaced by fail-closed sentinel; Butler became healthy without invoking it.'
    Write-Host 'Boundary: EXTRACTED_RUNTIME_PACKAGE; PREBUILT_READ_RUNTIME; SQLITE_RUNTIME_DATA_EXTERNAL; STARTUP_GRADLE_NOT_INVOKED'
    Write-Host 'BF-773 PREBUILT RUNTIME PACKAGED LAUNCH: PASS'
}
finally {
    Stop-OwnedProcessTree -Process $process
    if (Test-Path -LiteralPath $tempRoot) {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}
