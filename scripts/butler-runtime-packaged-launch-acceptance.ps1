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
$releaseBuilder = Join-Path $scriptDir 'butler-runtime-release-bundle.ps1'
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
$dataDir = Join-Path $localAppData 'Butler\data'
$databasePath = Join-Path $dataDir 'butler.db'
$configPath = Join-Path $localAppData 'Butler\app-league.txt'

if (-not (Test-Path -LiteralPath $powershell -PathType Leaf)) {
    throw 'BF-773 BLOCKED: Windows PowerShell 5.1 executable is unavailable.'
}
if (-not (Get-Command java.exe -ErrorAction SilentlyContinue)) {
    throw 'BF-773 BLOCKED: Java executable is unavailable; Java remains a host prerequisite.'
}
if (-not (Test-Path -LiteralPath $databasePath -PathType Leaf)) {
    throw "BF-773 BLOCKED: governed Butler database is missing at $databasePath."
}
if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
    throw 'BF-773 BLOCKED: Butler league configuration is missing.'
}

if (-not $runtimeZipExplicit) {
    $gitCommand = Get-Command git.exe -ErrorAction SilentlyContinue
    if ($null -eq $gitCommand) { $gitCommand = Get-Command git -ErrorAction SilentlyContinue }
    if ($null -eq $gitCommand) { throw 'BF-773 BLOCKED: Git is required to resolve current HEAD runtime artifact.' }
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
        Write-Host "BF-773: current HEAD runtime artifact is absent; building exact prebuilt runtime bundle for $shortSha."
        & $releaseBuilder -Force
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
    throw "BF-773 BLOCKED: runtime checksum sidecar not found at $checksumPath"
}
$checksumText = [IO.File]::ReadAllText($checksumPath, [Text.Encoding]::ASCII).Trim()
if ($checksumText -notmatch '^([0-9a-f]{64})\s{2}(.+)$') {
    throw 'BF-773 BLOCKED: runtime checksum sidecar format is invalid.'
}
$expectedHash = $Matches[1]
$expectedName = $Matches[2]
if ($expectedName -cne [IO.Path]::GetFileName($RuntimeZip)) {
    throw 'BF-773 BLOCKED: checksum sidecar artifact name does not match selected runtime ZIP.'
}
$actualHash = (Get-FileHash -LiteralPath $RuntimeZip -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actualHash -cne $expectedHash) {
    throw 'BF-773 BLOCKED: runtime ZIP SHA-256 does not match its checksum.'
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
        catch {}
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
            throw 'BF-773 BLOCKED: packaged Butler exited during startup.'
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
        catch {}
        finally {
            if ($null -ne $response) { $response.Close() }
        }
        Start-Sleep -Milliseconds 250
    }
    throw "BF-773 BLOCKED: packaged Butler did not become healthy within $StartupTimeoutSeconds seconds."
}

$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ("Butler-bf773-accept-{0}" -f [Guid]::NewGuid().ToString('N'))
$process = $null
try {
    [IO.Directory]::CreateDirectory($tempRoot) | Out-Null
    Expand-Archive -LiteralPath $RuntimeZip -DestinationPath $tempRoot -Force

    if (Test-Path -LiteralPath (Join-Path $tempRoot 'gradle')) {
        throw 'BF-773 BLOCKED: extracted runtime package contains Gradle wrapper/toolchain directory.'
    }
    if (Test-Path -LiteralPath (Join-Path $tempRoot 'gradlew')) {
        throw 'BF-773 BLOCKED: extracted runtime package contains Unix Gradle launcher.'
    }

    $shim = Join-Path $tempRoot 'gradlew.bat'
    if (-not (Test-Path -LiteralPath $shim -PathType Leaf)) {
        throw 'BF-773 BLOCKED: governed runtime gradlew.bat shim is missing.'
    }
    $shimText = [IO.File]::ReadAllText($shim, [Text.Encoding]::ASCII)
    if (-not $shimText.Contains('BF-773 governed runtime release shim') -or
        -not $shimText.Contains(':bet:bet-cli:installDist') -or
        -not $shimText.Contains('exit /b 23')) {
        throw 'BF-773 BLOCKED: gradlew.bat is not the governed fail-closed BF-773 shim.'
    }

    Push-Location $tempRoot
    try {
        & $shim '--no-daemon' ':bet:bet-cli:installDist' | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'BF-773 BLOCKED: governed startup probe was rejected.' }
        & $shim '--no-daemon' 'tasks' | Out-Null
        if ($LASTEXITCODE -eq 0) { throw 'BF-773 BLOCKED: unsupported packaged Gradle task did not fail closed.' }
    }
    finally {
        Pop-Location
    }

    $packagedLib = Join-Path $tempRoot 'bet\bet-cli\build\install\bet-cli\lib'
    if (-not (Test-Path -LiteralPath $packagedLib -PathType Container)) {
        throw 'BF-773 BLOCKED: packaged installDist JAR library is missing.'
    }
    $jars = @(Get-ChildItem -LiteralPath $packagedLib -File -Filter '*.jar' -ErrorAction Stop)
    $applicationJars = @($jars | Where-Object { $_.Name -like 'bet-cli-*.jar' })
    if ($applicationJars.Count -ne 1) {
        throw "BF-773 BLOCKED: expected exactly one bet-cli application JAR, found $($applicationJars.Count)."
    }
    $nonJars = @(Get-ChildItem -LiteralPath $packagedLib -File -ErrorAction Stop | Where-Object { $_.Extension -cne '.jar' })
    if ($nonJars.Count -ne 0) {
        throw 'BF-773 BLOCKED: packaged installDist library contains non-JAR files.'
    }

    $packagedLauncher = Join-Path $tempRoot 'scripts\butler-app.ps1'
    $securityCheck = Join-Path $tempRoot 'scripts\butler-release-security-check.ps1'
    if (-not (Test-Path -LiteralPath $packagedLauncher -PathType Leaf) -or
        -not (Test-Path -LiteralPath $securityCheck -PathType Leaf)) {
        throw 'BF-773 BLOCKED: extracted runtime package is missing Butler launcher/security entrypoints.'
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
        throw 'BF-773 BLOCKED: unable to start Butler from extracted runtime package.'
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
        throw "BF-773 BLOCKED: runtime database material leaked into extracted package tree: $leaks"
    }

    Write-Host 'Butler runtime packaged launch acceptance (BF-773)'
    Write-Host "Artifact: $RuntimeZip"
    Write-Host "SHA-256: $actualHash"
    Write-Host "Extracted package: $tempRoot"
    Write-Host "Runtime data: $dataDir"
    Write-Host 'Boundary: CODE_PLUS_PREBUILT_READ_RUNTIME_NO_RUNTIME_DATA; GRADLE_TOOLCHAIN_ABSENT; SQLITE_RUNTIME_DATA_EXTERNAL'
    Write-Host 'BF-773 RUNTIME PACKAGED LAUNCH: PASS'
}
finally {
    Stop-OwnedProcessTree -Process $process
    if (Test-Path -LiteralPath $tempRoot) {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}
