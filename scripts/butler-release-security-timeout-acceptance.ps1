Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = Join-Path ([IO.Path]::GetTempPath()) ('Butler-security-timeout-' + [guid]::NewGuid().ToString('N'))
$powershell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$fixturePid = 0
try {
    [IO.Directory]::CreateDirectory($root) | Out-Null
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'butler-release-security-acceptance.ps1') -Destination $root
    # Inert launcher: no Butler, listener, database or provider access.
    [IO.File]::WriteAllText((Join-Path $root 'butler-app.ps1'), @'
param([int]$Port, [switch]$NoBrowser)
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'owned-pid.txt'), [string]$PID)
[Console]::Out.WriteLine(('x' * 2000) + 'STDOUT_TIMEOUT_TAIL')
[Console]::Out.Flush()
[Console]::Error.WriteLine(('y' * 2000) + 'STDERR_TIMEOUT_TAIL')
[Console]::Error.Flush()
while ($true) { Start-Sleep -Milliseconds 100 }
'@)
    [IO.File]::WriteAllText((Join-Path $root 'butler-release-security-check.ps1'), @'
param([string]$BaseUrl)
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'security-ran.txt'), 'unexpected')
'@)
    $runner = Join-Path $root 'butler-release-security-acceptance.ps1'
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $ErrorActionPreference = 'Continue'
    try {
        $output = (& $powershell -NoLogo -NoProfile -ExecutionPolicy Bypass -File $runner -StartupTimeoutSeconds 3 2>&1 | Out-String)
        $code = $LASTEXITCODE
    }
    finally { $ErrorActionPreference = 'Stop' }
    $timer.Stop()
    if ($code -eq 0 -or $output -notmatch 'BF-768 RELEASE SECURITY ACCEPTANCE: FAIL' -or
        $output -notmatch 'did not become healthy within 3 seconds') { throw 'Timeout did not remain a failure.' }
    if ($output -match 'BF-768 RELEASE SECURITY ACCEPTANCE: PASS') { throw 'False security acceptance PASS.' }
    if ($output -notmatch 'stdout=\.\.\..*STDOUT_TIMEOUT_TAIL' -or
        $output -notmatch 'stderr=\.\.\..*STDERR_TIMEOUT_TAIL') { throw 'Missing bounded startup diagnostic tails.' }
    if ($output -match ('x' * 701) -or $output -match ('y' * 701)) { throw 'Unbounded startup diagnostic output.' }
    if (Test-Path -LiteralPath (Join-Path $root 'security-ran.txt')) { throw 'Security checker ran before readiness.' }
    $fixturePid = [int][IO.File]::ReadAllText((Join-Path $root 'owned-pid.txt'))
    if (Get-Process -Id $fixturePid -ErrorAction SilentlyContinue) { throw 'Owned timeout fixture survived cleanup.' }
    if ($timer.Elapsed.TotalSeconds -gt 30) { throw 'Timeout fixture exceeded bounded completion budget.' }
    Write-Host 'BF-768 TIMEOUT DIAGNOSTICS: PASS (failure preserved; both tails bounded; owned process stopped; security checker skipped)'
}
finally {
    # Only terminate a still-running fixture whose command line names this unique root.
    if ($fixturePid -eq 0 -and (Test-Path -LiteralPath (Join-Path $root 'owned-pid.txt'))) {
        $fixturePid = [int][IO.File]::ReadAllText((Join-Path $root 'owned-pid.txt'))
    }
    if ($fixturePid -gt 0) {
        $owned = Get-CimInstance Win32_Process -Filter "ProcessId=$fixturePid" -ErrorAction SilentlyContinue
        if ($null -ne $owned -and $owned.CommandLine.Contains($root)) { Stop-Process -Id $fixturePid -Force }
    }
    $resolvedRoot = [IO.Path]::GetFullPath($root)
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if ($resolvedRoot.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -and
        [IO.Path]::GetFileName($resolvedRoot) -match '^Butler-security-timeout-[0-9a-f]{32}$') {
        Remove-Item -LiteralPath $resolvedRoot -Recurse -Force
    }
}
# GitHub's PowerShell wrapper propagates the last native child exit code.
# The expected failing runner was asserted above; successful fixture completion
# must not inherit its deliberate nonzero status. Exceptions still fail the step.
$global:LASTEXITCODE = 0
