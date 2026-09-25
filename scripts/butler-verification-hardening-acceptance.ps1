Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Offline fixtures: no Butler database, provider calls, or actual Gradle build.
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('butler-verification-test-' + [Guid]::NewGuid().ToString('N'))
$scripts = Join-Path $fixture 'scripts'
$savedJava = $env:JAVA_HOME
$savedPath = $env:Path
$savedOpts = $env:GRADLE_OPTS
try {
    [void][IO.Directory]::CreateDirectory($scripts)
    Copy-Item (Join-Path $PSScriptRoot 'butler-acceptance-preflight.ps1') $scripts
    # Substitute only environment setup, so this fixture does not need a JDK.
    Set-Content (Join-Path $scripts 'butler-verification-environment.ps1') '# fixture environment' -Encoding Ascii
    $lib = Join-Path $fixture 'bet\bet-cli\build\install\bet-cli\lib'
    [void][IO.Directory]::CreateDirectory($lib)
    Set-Content (Join-Path $lib 'fixture.jar') 'fixture' -Encoding Ascii
    $gradle = Join-Path $fixture 'gradlew.bat'
    Set-Content $gradle "@echo off`r`necho FIRST_COMPILER_ERROR`r`necho Cannot snapshot generated output: not a regular file`r`nexit /b 7" -Encoding Ascii
    $before = @(Get-ChildItem (Join-Path ([IO.Path]::GetTempPath()) 'Butler\diagnostics') -Filter 'preflight-*.log' -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
    $env:GRADLE_OPTS = '-Dfixture=preserved'
    $failed = $false
    try { & (Join-Path $scripts 'butler-acceptance-preflight.ps1') }
    catch {
        $failed = $true
        if ($_ -notmatch 'Gradle exit code 7') { throw }
    }
    if (-not $failed) { throw 'Preflight incorrectly accepted a failed build.' }
    if ($env:GRADLE_OPTS -ne '-Dfixture=preserved') { throw 'Preflight did not restore Gradle options.' }
    $logs = @(Get-ChildItem (Join-Path ([IO.Path]::GetTempPath()) 'Butler\diagnostics') -Filter 'preflight-*.log' | Where-Object { $_.FullName -notin $before })
    if ($logs.Count -ne 1 -or (Get-Content $logs[0].FullName -Raw) -notmatch 'FIRST_COMPILER_ERROR') {
        throw 'Full failure diagnostic was not retained.'
    }
    Set-Content $gradle "@echo off`r`nexit /b 0" -Encoding Ascii
    & (Join-Path $scripts 'butler-acceptance-preflight.ps1')
    Remove-Item -LiteralPath (Join-Path $lib 'fixture.jar')
    $failed = $false
    try { & (Join-Path $scripts 'butler-acceptance-preflight.ps1') }
    catch {
        $failed = $true
        if ($_ -notmatch 'contains no jars') { throw }
    }
    if (-not $failed) { throw 'Preflight accepted a missing runtime.' }
    $failed = $false
    try { & (Join-Path $PSScriptRoot 'butler-verification-environment.ps1') -JavaHome 'relative-jdk' }
    catch {
        $failed = $true
        if ($_ -notmatch 'absolute path') { throw }
    }
    if (-not $failed) { throw 'Environment accepted a relative JDK path.' }

    # Exercise real Git/worktree checks with fake build and recovery commands.
    # Native stderr is diagnostic data; assert native exit codes explicitly below.
    $ErrorActionPreference = 'Continue'
    $origin = Join-Path $fixture 'origin.git'
    $source = Join-Path $fixture 'source'
    $lane = Join-Path $fixture 'lane'
    $trace = Join-Path $fixture 'trace.txt'
    function Invoke-FixtureGit {
        param([string[]]$GitArgs)
        $result = & git.exe @GitArgs 2>&1
        if ($LASTEXITCODE -ne 0) { throw ($result -join "`n") }
    }
    Invoke-FixtureGit @('init', '--bare', $origin)
    Invoke-FixtureGit @('init', $source)
    $sourceScripts = Join-Path $source 'scripts'
    [void][IO.Directory]::CreateDirectory($sourceScripts)
    Copy-Item (Join-Path $PSScriptRoot 'butler-fastlane-verify.ps1') $sourceScripts
    Set-Content (Join-Path $sourceScripts 'butler-verification-environment.ps1') 'param([string]$JavaHome)' -Encoding Ascii
    Set-Content (Join-Path $source 'gradlew.bat') "@echo off`r`necho build>>`"$trace`"`r`nexit /b 0" -Encoding Ascii
    Set-Content (Join-Path $sourceScripts 'butler-recover-roster-drift.cmd') "@echo off`r`necho recovery>>`"$trace`"`r`nexit /b 9" -Encoding Ascii
    Set-Content (Join-Path $sourceScripts 'butler-manager-journey-acceptance.cmd') "@echo off`r`necho journey>>`"$trace`"`r`nexit /b 0" -Encoding Ascii
    Invoke-FixtureGit @('-C', $source, 'add', '.')
    Invoke-FixtureGit @('-C', $source, '-c', 'user.name=Fixture', '-c', 'user.email=fixture@localhost', 'commit', '-qm', 'fixture')
    Invoke-FixtureGit @('-C', $source, 'branch', '-M', 'main')
    Invoke-FixtureGit @('-C', $source, 'remote', 'add', 'origin', $origin)
    Invoke-FixtureGit @('-C', $source, 'push', '-u', 'origin', 'main')
    $verifier = Join-Path $sourceScripts 'butler-fastlane-verify.ps1'
    $output = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $verifier main -FastLanePath $lane 2>&1
    if ($LASTEXITCODE -ne 0 -or ($output -join "`n") -notmatch 'BUTLER FASTLANE: PASS') { throw ($output -join "`n") }
    if ((Get-Content $trace -Raw).Trim() -ne "build`r`njourney") { throw 'Default verification invoked recovery or reordered stages.' }
    $sourceHead = & git.exe -C $source rev-parse HEAD
    $laneHead = & git.exe -C $lane rev-parse HEAD
    if ($sourceHead -cne $laneHead) { throw 'Fast Lane did not verify the remote head.' }
    Remove-Item -LiteralPath $trace
    $output = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $verifier main -FastLanePath $lane -RecoverRoster 2>&1
    if ($LASTEXITCODE -eq 0 -or ($output -join "`n") -match 'BUTLER FASTLANE: PASS') { throw 'Failed recovery incorrectly passed verification.' }
    if ((Get-Content $trace -Raw).Trim() -ne "build`r`nrecovery") { throw 'Journey ran after recovery failure.' }
    Remove-Item -LiteralPath $trace
    Set-Content (Join-Path $lane 'user-work.txt') 'preserve me' -Encoding Ascii
    $output = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $verifier main -FastLanePath $lane 2>&1
    if ($LASTEXITCODE -eq 0 -or (Test-Path $trace)) { throw 'Dirty worktree was reused.' }
    if ((Get-Content (Join-Path $lane 'user-work.txt')) -ne 'preserve me') { throw 'User work was changed.' }
    Write-Host 'Butler verification hardening fixtures: PASS'
}
finally {
    $env:JAVA_HOME = $savedJava
    $env:Path = $savedPath
    $env:GRADLE_OPTS = $savedOpts
    # Delete only this unique fixture directory beneath the process temp root.
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    $target = [IO.Path]::GetFullPath($fixture)
    if (-not $target.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Fixture cleanup escaped the temp directory.'
    }
    Remove-Item -LiteralPath $target -Recurse -Force
}
# GitHub's PowerShell runner propagates the last native exit code. The fixture
# deliberately ended with a rejected dirty-worktree command; all assertions passed.
exit 0

