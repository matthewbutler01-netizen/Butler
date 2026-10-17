Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$tokens=$null; $errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'butler-app-shell.ps1'),[ref]$tokens,[ref]$errors)
if (@($errors).Count) { throw 'App shell parse failed.' }
foreach ($name in @('Start-AppCore','Wait-ForAppCore')) {
    $found=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq $name},$true))
    if ($found.Count -ne 1) { throw "Startup function not unique: $name" }
    . ([scriptblock]::Create($found[0].Extent.Text))
}
$scriptDir=$PSScriptRoot
$LeagueId='fixture-only'
$savedLocalAppData=$env:LOCALAPPDATA
$tempBase=[IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$root=Join-Path $tempBase ('butler-startup-stderr-'+[guid]::NewGuid().ToString('N'))
$coreShell=Join-Path $root 'fixture-core.ps1'
$process=$null
try {
    [void][IO.Directory]::CreateDirectory($root)
    $env:LOCALAPPDATA=$root
    $failure=@'
param($LeagueId,[int]$Port,[switch]$NoBrowser)
[Console]::Error.Write('START-OF-PRIVATE-FIXTURE')
for ($i=0;$i -lt 128;$i++) { [Console]::Error.Write(('x' * 16384)) }
[Console]::Error.Write('END-OF-PRIVATE-FIXTURE')
exit 37
'@
    [IO.File]::WriteAllText($coreShell,$failure,[Text.UTF8Encoding]::new($false))
    $process=Start-AppCore -InnerPort 18099
    if (-not $process.WaitForExit(15000)) { throw 'Large stderr burst deadlocked startup.' }
    $message=''
    try { Wait-ForAppCore -InnerPort 18099 -Process $process }
    catch { $message=$_.Exception.Message }
    $path=Join-Path $root 'Butler\diagnostics\app-core-startup-latest.txt'
    $detail=[IO.File]::ReadAllText($path)
    if ($detail.Length -gt 17000 -or -not $detail.Contains('Core exit code: 37') -or
        -not $detail.Contains('END-OF-PRIVATE-FIXTURE') -or $detail.Contains('START-OF-PRIVATE-FIXTURE')) {
        throw 'Startup stderr tail is missing, unbounded, or not the final output.'
    }
    if (-not $message.StartsWith('BF-670 BLOCKED:') -or -not $message.Contains($path) -or
        $message.Contains('END-OF-PRIVATE-FIXTURE')) { throw 'Public startup error leaked raw stderr or lost its label/path.' }
    $env:LOCALAPPDATA=$coreShell # An existing file cannot become a diagnostics directory.
    . (Join-Path $PSScriptRoot 'butler-startup-stderr.ps1')
    if ((Save-ButlerStartupFailure -Process $process) -cne ' Local startup details could not be saved.') {
        throw 'Diagnostic storage failure masked the original failure.'
    }
    $process.Dispose(); $process=$null
    $env:LOCALAPPDATA=$root
    [IO.File]::WriteAllText($coreShell,'param($LeagueId,[int]$Port,[switch]$NoBrowser); exit 0',[Text.UTF8Encoding]::new($false))
    $process=Start-AppCore -InnerPort 18099
    if (-not $process.WaitForExit(10000)) { throw 'Empty fixture did not exit.' }
    try { Wait-ForAppCore -InnerPort 18099 -Process $process } catch { }
    $empty=[IO.File]::ReadAllText($path)
    if (-not $empty.Contains('No child stderr was captured.') -or -not $empty.Contains('Core exit code: 0')) {
        throw 'Silent early exit was misreported as successful startup.'
    }
    $process.Dispose(); $process=$null
    Remove-Item -LiteralPath $path
    $healthy=@'
param($LeagueId,[int]$Port,[switch]$NoBrowser)
[Console]::Error.Write(('h' * 131072))
$listener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,$Port)
$listener.Start()
try {
    $client=$listener.AcceptTcpClient()
    try {
        $stream=$client.GetStream()
        $reader=[IO.StreamReader]::new($stream)
        while (-not [string]::IsNullOrEmpty($reader.ReadLine())) { }
        $response=[Text.Encoding]::ASCII.GetBytes("HTTP/1.1 200 OK`r`nContent-Length: 2`r`nConnection: close`r`n`r`n{}")
        $stream.Write($response,0,$response.Length); $stream.Flush()
    } finally { $client.Close() }
    Start-Sleep -Seconds 30
} finally { $listener.Stop() }
'@
    [IO.File]::WriteAllText($coreShell,$healthy,[Text.UTF8Encoding]::new($false))
    $probe=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0)
    $probe.Start(); $port=([Net.IPEndPoint]$probe.LocalEndpoint).Port; $probe.Stop()
    $process=Start-AppCore -InnerPort $port
    Wait-ForAppCore -InnerPort $port -Process $process
    if ($process.HasExited -or (Test-Path -LiteralPath $path) -or $process.ButlerStartupStderr.Snapshot(0).Length -gt 16384) {
        throw 'Healthy startup was blocked, persisted stderr, or exceeded the tail limit.'
    }
    . (Join-Path $PSScriptRoot 'butler-startup-stderr.ps1')
    [void](Save-ButlerStartupFailure -Process $process)
    if (-not ([IO.File]::ReadAllText($path)).Contains('Core exit code: unavailable (child still running)') -or $process.HasExited) {
        throw 'Timeout observation invented an exit code or stopped the child.'
    }
    Write-Host 'STARTUP STDERR ACCEPTANCE: PASS (large burst, bounded tail, exit code, private storage, storage failure, healthy startup)'
}
finally {
    if ($null -ne $process) {
        if (-not $process.HasExited) { $process.Kill(); [void]$process.WaitForExit(5000) }
        $process.Dispose()
    }
    $env:LOCALAPPDATA=$savedLocalAppData
    $resolved=[IO.Path]::GetFullPath($root)
    if (-not $resolved.StartsWith($tempBase,[StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($resolved) -notmatch '^butler-startup-stderr-[0-9a-f]{32}$') { throw 'Unsafe fixture cleanup path.' }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
