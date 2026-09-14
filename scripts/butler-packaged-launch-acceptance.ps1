param(
    [string]$SourceZip,
    [ValidateRange(30, 600)]
    [int]$StartupTimeoutSeconds = 240
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$sourceZipExplicit = $PSBoundParameters.ContainsKey('SourceZip')
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $scriptDir
$releaseOutput = Join-Path $repoRoot 'release-output'
$releaseBuilder = Join-Path $scriptDir 'butler-release-bundle.ps1'
$loopback = [System.Net.IPAddress]::Parse('127.0.0.1')
$powershell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$taskkill = Join-Path $env:SystemRoot 'System32\taskkill.exe'
$localAppData = [string]$env:LOCALAPPDATA
if ([string]::IsNullOrWhiteSpace($localAppData)) {
    $localAppData = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)
}
if ([string]::IsNullOrWhiteSpace($localAppData)) {
    throw 'BF-770 BLOCKED: LocalApplicationData is unavailable.'
}
$dataDir = Join-Path $localAppData 'Butler\data'
$databasePath = Join-Path $dataDir 'butler.db'
$configPath = Join-Path $localAppData 'Butler\app-league.txt'

if (-not (Test-Path -LiteralPath $powershell -PathType Leaf)) {
    throw 'BF-770 BLOCKED: Windows PowerShell 5.1 executable is unavailable.'
}
if (-not (Test-Path -LiteralPath $databasePath -PathType Leaf)) {
    throw "BF-770 BLOCKED: governed Butler database is missing at $databasePath. Run scripts\butler-migrate-runtime-data.ps1 first."
}
if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
    throw 'BF-770 BLOCKED: Butler league configuration is missing; packaged launch requires the existing configured app target.'
}

if (-not $sourceZipExplicit) {
    $gitCommand = Get-Command git.exe -ErrorAction SilentlyContinue
    if ($null -eq $gitCommand) { $gitCommand = Get-Command git -ErrorAction SilentlyContinue }
    if ($null -eq $gitCommand) { throw 'BF-770 BLOCKED: Git is required to resolve the current release artifact.' }
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
        throw 'BF-770 BLOCKED: unable to resolve exact current HEAD short SHA.'
    }
    $SourceZip = Join-Path $releaseOutput ("Butler-source-{0}.zip" -f $shortSha)
    if (-not (Test-Path -LiteralPath $SourceZip -PathType Leaf)) {
        if (-not (Test-Path -LiteralPath $releaseBuilder -PathType Leaf)) {
            throw "BF-772 BLOCKED: BF-769 release builder not found at $releaseBuilder"
        }
        Write-Host "BF-772: current HEAD release artifact is absent; building exact BF-769 code-only bundle for $shortSha."
        & $releaseBuilder -Force
    }
}
else {
    if ([string]::IsNullOrWhiteSpace($SourceZip)) {
        throw 'BF-772 BLOCKED: explicit -SourceZip must not be blank.'
    }
    if (-not [IO.Path]::IsPathRooted($SourceZip)) {
        $SourceZip = Join-Path $repoRoot $SourceZip
    }
}
$SourceZip = [IO.Path]::GetFullPath($SourceZip)
if (-not (Test-Path -LiteralPath $SourceZip -PathType Leaf)) {
    throw "BF-770 BLOCKED: BF-769 source release ZIP not found at $SourceZip"
}

$checksumPath = $SourceZip + '.sha256'
if (-not (Test-Path -LiteralPath $checksumPath -PathType Leaf)) {
    throw "BF-770 BLOCKED: BF-769 checksum sidecar not found at $checksumPath"
}
$checksumText = [IO.File]::ReadAllText($checksumPath, [Text.Encoding]::ASCII).Trim()
if ($checksumText -notmatch '^([0-9a-f]{64})\s{2}(.+)$') {
    throw 'BF-770 BLOCKED: BF-769 checksum sidecar format is invalid.'
}
$expectedHash = $Matches[1]
$expectedName = $Matches[2]
if ($expectedName -cne [IO.Path]::GetFileName($SourceZip)) {
    throw 'BF-770 BLOCKED: checksum sidecar artifact name does not match the selected ZIP.'
}
$actualHash = (Get-FileHash -LiteralPath $SourceZip -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actualHash -cne $expectedHash) {
    throw 'BF-770 BLOCKED: packaged source ZIP SHA-256 does not match its BF-769 checksum.'
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
            throw 'BF-770 BLOCKED: packaged Butler exited during startup.'
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
    throw "BF-770 BLOCKED: packaged Butler did not become healthy within $StartupTimeoutSeconds seconds."
}

$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ("Butler-bf770-{0}" -f [Guid]::NewGuid().ToString('N'))
$process = $null
try {
    [IO.Directory]::CreateDirectory($tempRoot) | Out-Null
    Expand-Archive -LiteralPath $SourceZip -DestinationPath $tempRoot -Force

    $packagedLauncher = Join-Path $tempRoot 'scripts\butler-app.ps1'
    $securityCheck = Join-Path $tempRoot 'scripts\butler-release-security-check.ps1'
    if (-not (Test-Path -LiteralPath $packagedLauncher -PathType Leaf) -or
        -not (Test-Path -LiteralPath $securityCheck -PathType Leaf)) {
        throw 'BF-770 BLOCKED: extracted source package is missing Butler launcher/security entrypoints.'
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
        throw 'BF-770 BLOCKED: unable to start Butler from the extracted source package.'
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
        throw "BF-770 BLOCKED: runtime database material leaked into the extracted package tree: $leaks"
    }

    Write-Host 'Butler packaged-source launch acceptance (BF-770)'
    Write-Host "Artifact: $SourceZip"
    Write-Host "SHA-256: $actualHash"
    Write-Host "Extracted package: $tempRoot"
    Write-Host "Runtime data: $dataDir"
    Write-Host 'Boundary: EXTRACTED_PACKAGE_CODE_ONLY; SQLITE_RUNTIME_DATA_EXTERNAL'
    Write-Host 'BF-770 PACKAGED LAUNCH: PASS'
}
finally {
    Stop-OwnedProcessTree -Process $process
    if (Test-Path -LiteralPath $tempRoot) {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}
