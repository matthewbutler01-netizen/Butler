param(
    [string]$CheckoutPath = 'C:\ButlerDev\pr1464',
    [ValidateRange(1024,65535)][int]$TargetPort = 18080,
    [switch]$PreflightOnly,
    [ValidateRange(1,600)][int]$StartupTimeoutSeconds = 300
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

# Startup polling is bounded and never treats a source pin as service readiness.
function Wait-BetaStartup {
    param([int]$Port, [int]$TimeoutSeconds, [object]$StartedProcess)
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $nextNotice = 15
    while ($timer.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
        if (Test-BetaHealth -Port $Port) { return $true }
        if ($StartedProcess.HasExited) { return $false }
        if ($timer.Elapsed.TotalSeconds -ge $nextNotice) {
            Write-Host 'STARTUP PENDING. Runtime preparation may still be running; do not launch another copy.'
            $nextNotice += 15
        }
        Start-Sleep -Milliseconds 500
    }
    return $false
}
function Write-BetaStartupReport {
    param([ValidateSet('PENDING','HELD')][string]$Status)
    $directory = Join-Path $env:LOCALAPPDATA 'Butler\diagnostics'
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    $path = Join-Path $directory 'v04-beta-one-click-latest.txt'
    $text = 'BETA STARTUP: ' + $Status + [Environment]::NewLine +
        'Browser acceptance: NOT RUN. Service readiness is not verified.' + [Environment]::NewLine +
        'The app window may still be preparing the runtime. Inspect that known window; do not start another copy or kill unknown processes.'
    [IO.File]::WriteAllText($path, $text, [Text.Encoding]::UTF8)
    Write-Host ('Startup status report: ' + $path)
}

function Save-BetaPageCheckReport {
    param([string]$Path, [string]$CheckoutCommit, [string]$Content, [int]$CheckExit, [int]$Port)
    # A completed page sequence cannot prove that its app is still available.
    $healthy = Test-BetaHealth -Port $Port
    $status = if (-not $healthy) { 'HELD - SERVICE NO LONGER VERIFIED' }
        elseif ($CheckExit -ne 0 -or $Content -notmatch '(?m)^RESULT:\s*0 page failure\(s\),\s*[0-9]+ watch warning\(s\)\.' -or
            $Content -match '(?m)^RESULT:\s*[1-9][0-9]* page failure') { 'PAGE ERROR' }
        elseif ($Content -match '(?m)^RESULT:\s*0 page failure\(s\),\s*[1-9][0-9]* watch warning') { 'PAGES LOAD - EVIDENCE WARNINGS' }
        else { 'LOCAL PAGES RESPOND - LIVE INJURIES NOT CERTIFIED' }
    $header = 'BETA SOURCE CHECKOUT: VERIFIED ' + $CheckoutCommit + [Environment]::NewLine +
        'SOURCE PROOF: exact Git checkout, not a Sleeper week, injury, waiver availability or browser execution check.' +
        [Environment]::NewLine + [Environment]::NewLine
    $footer = [Environment]::NewLine + [Environment]::NewLine +
        'BETA FINAL STATUS: ' + $status + [Environment]::NewLine +
        'Final service check UTC: ' + [DateTimeOffset]::UtcNow.ToString('o') + [Environment]::NewLine +
        'Browser acceptance: NOT RUN by this launcher.'
    if (-not $healthy) {
        $footer += [Environment]::NewLine + 'The page results above preceded this failed service check. Keep the known Butler app window open; inspect it before restarting. Do not start another copy or stop unknown processes.'
    }
    [IO.File]::WriteAllText($Path, $header + $Content + $footer, [Text.Encoding]::UTF8)
    return [pscustomobject]@{ Healthy = $healthy; Status = $status }
}

# BF-1527: verify the checkout of the immutable candidate before we start it.
# This proves disk HEAD, not live player freshness or a health endpoint's SHA.
$approvedCandidateCommit = 'c9a0a7f946f32abdf6f6f13a15321b467acbe8e6'
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
    $startupReportWritten = $false
    # The source starter binds 18080; never inspect one port and launch another.
    if ($TargetPort -ne 18080 -and -not $PreflightOnly) {
        throw 'PORT MISMATCH. This development starter requires port 18080; no update or app launch attempted.'
    }
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
        Write-BetaStartupReport -Status 'PENDING'
        $startupReportWritten = $true
        $startedProcess = Start-Process -FilePath $cmd -ArgumentList $arguments -WorkingDirectory (Split-Path -Parent $launcher) -PassThru
        $ready = Wait-BetaStartup -Port $TargetPort -TimeoutSeconds $StartupTimeoutSeconds -StartedProcess $startedProcess
        if (-not $ready) {
            throw 'STARTUP NOT VERIFIED WITHIN THE BOUNDED WAIT OR STARTER EXITED. Inspect the known Butler app window and saved startup report; do not launch another copy.'
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
    # First-open exact roster recovery may outlive the former 30-second check.
    # Use the checker's existing finite default; never retry a failed page here.
    $log = @(& $pwsh -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $checker -Port $TargetPort -TimeoutSeconds 120 -CheckSleeperWeek 2>&1 | ForEach-Object { [string]$_ })
    $checkExit = $LASTEXITCODE
    $content = $log -join [Environment]::NewLine
    $finalCheck = Save-BetaPageCheckReport -Path $reportPath -CheckoutCommit $verifiedCheckout -Content $content -CheckExit $checkExit -Port $TargetPort
    # Preserve the completed report, including its failed final probe, in catch.
    $startupReportWritten = $false
    if (-not $finalCheck.Healthy) {
        Start-Process -FilePath 'notepad.exe' -ArgumentList ('"' + $reportPath + '"')
        throw 'SERVICE NO LONGER VERIFIED AFTER PAGE CHECKS. Browser was not opened. Inspect the saved report and known Butler app window.'
    }

    if ($finalCheck.Status -ceq 'PAGE ERROR') {
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
    if ($startupReportWritten) {
        try { Write-BetaStartupReport -Status 'HELD' } catch { Write-Host 'Startup status report could not be saved.' }
    }
    Write-Host ('BETA HELD: ' + [string]$_.Exception.Message)
    exit 2
}
