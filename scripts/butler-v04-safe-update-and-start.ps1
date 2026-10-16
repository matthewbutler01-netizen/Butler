param(
    [string]$CheckoutPath = 'C:\ButlerDev\pr1464',
    [ValidateRange(1024, 65535)][int]$TargetPort = 18080,
    [switch]$PreflightOnly,
    [switch]$UpdateOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# BF-M1: a pinned, explicitly scoped v0.4 *development* update. Never
# force-stop a process, modify Sleeper, touch the v0.3 release, reset Git,
# clean untracked files or switch to a moving remote branch.
$targetCommit = 'dd77d6b3221f4d78226d61330bc9ef5bed46976a'
$sourceBranch = 'refs/heads/bf1525-waiver-evidence-age'

try {
    Write-Host 'BUTLER v0.4 - PINNED LOCAL DEVELOPMENT UPDATE'
    Write-Host 'Scope: only the existing v0.4 source checkout and port 18080.'
    Write-Host 'No v0.3 release replacement; no Sleeper transaction.'
    Write-Host ('Approved beta candidate: ' + $targetCommit.Substring(0, 12))
    Write-Host ''

    if (-not (Test-Path -LiteralPath $CheckoutPath -PathType Container) -or
        -not (Test-Path -LiteralPath (Join-Path $CheckoutPath '.git'))) {
        throw 'DEVELOPMENT CHECKOUT NOT FOUND. Nothing was changed.'
    }
    $launcher = Join-Path $CheckoutPath 'scripts\butler-v04-start-18080.cmd'
    if (-not (Test-Path -LiteralPath $launcher -PathType Leaf)) {
        throw 'COMPLETE V0.4 CHECKOUT REQUIRED. Nothing was changed.'
    }
    if ($null -eq (Get-Command git.exe -ErrorAction SilentlyContinue)) {
        throw 'GIT NOT FOUND. Install Git for Windows before attempting this update.'
    }

    $origin = @(& git -C $CheckoutPath remote get-url origin)
    if ($LASTEXITCODE -ne 0 -or $origin.Count -ne 1 -or
        $origin[0] -notmatch '(?i)^(?:https://github\.com/|git@github\.com:|ssh://git@github\.com/)matthewbutler01-netizen/Butler(?:\.git)?/?$') {
        throw 'UNRECOGNIZED GIT ORIGIN. Refusing to fetch or switch the checkout.'
    }
    $changes = @(& git -C $CheckoutPath status --porcelain=v1 --untracked-files=normal)
    if ($LASTEXITCODE -ne 0) {
        throw 'GIT STATUS FAILED. Nothing was changed.'
    }
    if ($changes.Count -gt 0) {
        throw 'LOCAL CHANGES PRESENT. Nothing was overwritten; review the checkout first.'
    }

    # Check port before fetching: the running older worker must be stopped by
    # the user through its own window (Ctrl+C), never killed by this helper.
    $listener = [System.Net.Sockets.TcpListener]::new(
        [System.Net.IPAddress]::Parse('127.0.0.1'), $TargetPort)
    try {
        try {
            $listener.Start()
        }
        catch [System.Net.Sockets.SocketException] {
            throw ('PORT ' + $TargetPort + ' OCCUPIED. Close the existing Butler window with Ctrl+C, then retry. Nothing was changed.')
        }
    }
    finally {
        try { $listener.Stop() } catch {}
    }

    if ($PreflightOnly) {
        Write-Host 'PREFLIGHT ONLY: clean trusted checkout and free loopback port confirmed.'
        Write-Host 'No fetch, checkout, server launch or Sleeper request performed.'
        exit 0
    }

    Write-Host 'Fetching pinned, reviewed development build...'
    & git -C $CheckoutPath fetch --no-tags origin $sourceBranch
    if ($LASTEXITCODE -ne 0) { throw 'FETCH FAILED. Original checkout was not switched.' }

    # Insist that the approved immutable commit is reachable from the exact
    # expected development branch. A moving branch head is never executed.
    & git -C $CheckoutPath merge-base --is-ancestor $targetCommit FETCH_HEAD
    if ($LASTEXITCODE -ne 0) {
        throw 'PINNED COMMIT NOT REACHABLE FROM EXPECTED BRANCH. No checkout changed.'
    }
    & git -C $CheckoutPath switch --detach $targetCommit
    if ($LASTEXITCODE -ne 0) { throw 'PINNED CHECKOUT FAILED. Butler was not started.' }
    $head = @(& git -C $CheckoutPath rev-parse HEAD)
    if ($LASTEXITCODE -ne 0 -or $head.Count -ne 1 -or
        $head[0].Trim() -cne $targetCommit) {
        throw 'CHECKOUT HEAD DOES NOT MATCH THE APPROVED COMMIT. Butler was not started.'
    }

    $java = Join-Path $env:LOCALAPPDATA 'Programs\Eclipse Adoptium\jdk-25.0.4.101-hotspot'
    if (Test-Path -LiteralPath (Join-Path $java 'bin\java.exe') -PathType Leaf) {
        $env:JAVA_HOME = $java
    }

    Write-Host ('Pinned v0.4 commit verified: ' + $targetCommit.Substring(0, 8))
    if ($UpdateOnly) {
        Write-Host 'UPDATE COMPLETE. One-click beta launcher will start and check the app.'
        Write-Host 'No server launched or stopped by updater.'
        return
    }
    Write-Host 'Starting guarded development app on 127.0.0.1:18080.'
    Write-Host 'Leave the Butler window open while testing.'
    Write-Host ''
    & $launcher
    $result = $LASTEXITCODE
    if ($null -ne $result -and $result -ne 0) {
        throw ('BUTLER STARTER EXITED WITH STATUS ' + $result + '. Review its output above.')
    }
}
catch {
    # Operator-facing errors are fixed/helper-owned messages only. Never
    # report a token, roster, database content or raw source HTML.
    Write-Host ''
    Write-Host ('UPDATE HELD: ' + [string]$_.Exception.Message)
    exit 2
}
