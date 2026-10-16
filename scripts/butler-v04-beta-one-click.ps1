param(
    [string]$CheckoutPath = 'C:\ButlerDev\pr1464',
    [ValidateRange(1024,65535)][int]$TargetPort = 18080,
    [switch]$PreflightOnly
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# BF-BETA: one double-click updates, starts, checks and opens v0.4.
# Does not touch v0.3, force-stop apps or submit Sleeper transactions.
# Normal GETs may invoke Butler's existing governed local evidence recovery.
function Test-BetaHealth {
    param([int]$Port)
    $req = [Net.HttpWebRequest]::Create(('http://127.0.0.1:'+$Port+'/health'))
    $req.Method = 'GET'
    $req.Proxy = $null
    $req.AllowAutoRedirect = $false
    $req.Timeout = 1400
    $req.ReadWriteTimeout = 1400
    $response = $null
    try {
        $response = $req.GetResponse()
        if ([int]$response.StatusCode -ne 200 -or
            $response.ContentType -notmatch '^application/json') {
            return $false
        }
        $reader = [IO.StreamReader]::new($response.GetResponseStream())
        try { $body = $reader.ReadToEnd() } finally { $reader.Dispose() }
        $json = ConvertFrom-Json -InputObject $body
        foreach ($field in @('status','service','bind','localReadContract')) {
            if ($null -eq $json.PSObject.Properties[$field]) { return $false }
        }
        return $json.status -ceq 'ok' -and
            $json.service -ceq 'butler-app-shell' -and
            $json.bind -ceq '127.0.0.1' -and
            $json.localReadContract -ceq 'v04-bounded-local-read-bf1146'
    }
    catch { return $false }
    finally { if ($null -ne $response) { $response.Dispose() } }
}
function Test-BetaPortBusy {
    param([int]$Port)
    $listener = [Net.Sockets.TcpListener]::new(
        [Net.IPAddress]::Parse('127.0.0.1'), $Port)
    try { $listener.Start(); return $false }
    catch [Net.Sockets.SocketException] { return $true }
    finally { try { $listener.Stop() } catch {} }
}

# BF-1527: verify the checkout of the immutable candidate before we start it.
# This proves disk HEAD, not live player freshness or a health endpoint's SHA.
$approvedCandidateCommit = 'dd77d6b3221f4d78226d61330bc9ef5bed46976a'
function Assert-BetaCandidateCheckout {
    param([Parameter(Mandatory = $true)][string]$Path)
    if ($null -eq (Get-Command git.exe -ErrorAction SilentlyContinue)) {
        throw 'GIT NOT AVAILABLE. Candidate source identity cannot be checked.'
    }
    if (-not (Test-Path -LiteralPath $Path -PathType Container)) {
        throw 'BETA CHECKOUT MISMATCH. The required local source checkout directory is missing; no app was started.'
    }
    $gitMarker = Join-Path $Path '.git'
    if (-not (Test-Path -LiteralPath $gitMarker)) {
        throw 'BETA CHECKOUT MISMATCH. The source directory has no Git checkout; no app was started.'
    }
    $head = @(& git -C $Path rev-parse HEAD 2>$null)
    if ($LASTEXITCODE -ne 0 -or $head.Count -ne 1 -or
        $head[0].Trim() -cne $approvedCandidateCommit) {
        throw 'BETA CHECKOUT MISMATCH. The checked-out source is not the approved candidate; no app was started.'
    }
    return $approvedCandidateCommit
}

try {
    Write-Host 'BUTLER v0.4 - OPEN BETA (READ ONLY)'
    Write-Host 'One action: update, start, check, open.'
    Write-Host 'Never replaces v0.3 or submits a Sleeper transaction.'
    Write-Host ''
    $updater = Join-Path $PSScriptRoot 'butler-v04-safe-update-and-start.ps1'
    $launcher = Join-Path $CheckoutPath 'scripts\butler-v04-start-18080.cmd'
    $checker = Join-Path $CheckoutPath 'scripts\butler-v04-live-page-check.ps1'
    $refresh = Join-Path $CheckoutPath 'scripts\butler-decision-refresh.ps1'
    if (-not (Test-Path -LiteralPath $updater -PathType Leaf)) {
        throw 'EXTRACT ALL REQUIRED. The update helper is missing.'
    }
    if (-not (Test-Path -LiteralPath $launcher -PathType Leaf) -or
        -not (Test-Path -LiteralPath $checker -PathType Leaf) -or
        -not (Test-Path -LiteralPath $refresh -PathType Leaf)) {
        throw 'V0.4 CHECKOUT INCOMPLETE. No process was started.'
    }
    $pwsh = Join-Path $PSHOME 'powershell.exe'
    if (-not (Test-Path -LiteralPath $pwsh -PathType Leaf)) {
        throw 'WINDOWS POWERSHELL UNAVAILABLE.'
    }
    if ($PreflightOnly) {
        & $pwsh -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $updater -CheckoutPath $CheckoutPath -TargetPort $TargetPort -PreflightOnly
        if ($LASTEXITCODE -ne 0) { exit 2 }
        Write-Host 'ONE-CLICK PREFLIGHT: PASS. No updates, app launches or page checks.'
        exit 0
    }

    $ready = Test-BetaHealth -Port $TargetPort
    if (-not $ready) {
        if (Test-BetaPortBusy -Port $TargetPort) {
            throw 'PORT OCCUPIED. Close only your known v0.4 window using Ctrl+C; then double-click again.'
        }
        & $pwsh -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $updater -CheckoutPath $CheckoutPath -TargetPort $TargetPort -UpdateOnly
        if ($LASTEXITCODE -ne 0) {
            throw 'SAFE UPDATE HELD. No app started. See message above.'
        }
        $verifiedCheckout = Assert-BetaCandidateCheckout -Path $CheckoutPath
        Write-Host ('BETA CHECKOUT: VERIFIED ' + $verifiedCheckout.Substring(0, 12))
        Write-Host 'Starting v0.4 in a separate app window...'
        $cmd = Join-Path $env:SystemRoot 'System32\cmd.exe'
        $arguments = '/c "' + $launcher + '"'
        [void](Start-Process -FilePath $cmd -ArgumentList $arguments -WorkingDirectory (Split-Path -Parent $launcher) -PassThru)
        $timer = [Diagnostics.Stopwatch]::StartNew()
        while ($timer.Elapsed.TotalSeconds -lt 120) {
            if (Test-BetaHealth -Port $TargetPort) { $ready = $true; break }
            Start-Sleep -Seconds 2
        }
        if (-not $ready) {
            throw 'STARTUP NOT VERIFIED AFTER 120 SECONDS. Inspect the Butler app window.'
        }
        Write-Host 'BUTLER: STARTED.'
    }
    else {
        # Health confirms only the service contract, not the checked-out Git SHA.
        # Never mislabel an older running worker as this exact candidate.
        throw 'EXISTING v0.4 WORKER. The tested beta update was NOT applied; source commit cannot be verified from health. Close your known Butler app window with Ctrl+C, then double-click again.'
    }

    $reportDir = Join-Path $env:LOCALAPPDATA 'Butler\diagnostics'
    New-Item -ItemType Directory -Path $reportDir -Force | Out-Null
    $reportPath = Join-Path $reportDir 'v04-beta-one-click-latest.txt'
    $log = @(& $pwsh -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $checker -Port $TargetPort -TimeoutSeconds 30 -CheckSleeperWeek 2>&1 | ForEach-Object { [string]$_ })
    $checkExit = $LASTEXITCODE
    $content = $log -join [Environment]::NewLine
    # The report proves which source checkout the updater selected. It does not
    # certify the health endpoint's process identity or live Sleeper evidence.
    $header = 'BETA SOURCE CHECKOUT: VERIFIED ' + $verifiedCheckout + [Environment]::NewLine +
        'SOURCE PROOF: exact Git checkout, not a Sleeper week, injury, waiver availability or browser execution check.' +
        [Environment]::NewLine + [Environment]::NewLine
    [IO.File]::WriteAllText($reportPath, $header + $content, [Text.Encoding]::UTF8)

    if ($checkExit -ne 0 -or $content -match '(?m)^RESULT:\s*[1-9][0-9]* page failure') {
        Write-Host 'BETA STATUS: PAGE ERROR. The app is open for read-only inspection.'
    }
    elseif ($content -match '(?m)^RESULT:\s*0 page failure\(s\),\s*[1-9][0-9]* watch warning') {
        Write-Host 'BETA STATUS: PAGES LOAD. Some recommendations are held or unverified.'
    }
    else {
        Write-Host 'BETA STATUS: LOCAL PAGES RESPOND; live injuries are not certified.'
    }
    Write-Host ('One status report: ' + $reportPath)
    Start-Process -FilePath ('http://127.0.0.1:'+$TargetPort+'/')
    Start-Process -FilePath 'notepad.exe' -ArgumentList ('"' + $reportPath + '"')
    Write-Host 'Keep the separate Butler app window running; this launcher may close.'
}
catch {
    Write-Host ('BETA HELD: ' + [string]$_.Exception.Message)
    exit 2
}
