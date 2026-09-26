Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'butler-setup-process.ps1')
$root = Join-Path ([IO.Path]::GetTempPath()) ('butler-launch-fixtures-' + [Guid]::NewGuid().ToString('N'))
$package = Join-Path $root 'package'
$profile = Join-Path $root 'profile'
$shell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$saved = @{}
foreach ($name in @('LOCALAPPDATA','JAVA_HOME','PATH','BUTLER_APP_DATA_DIR','BUTLER_LAUNCH_FIXTURE')) { $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$sentinel = $null
try {
    foreach ($dir in @('scripts','bet\bet-cli\build\install\bet-cli\lib')) { [IO.Directory]::CreateDirectory((Join-Path $package $dir)) | Out-Null }
    [IO.Directory]::CreateDirectory((Join-Path $profile 'Butler\data')) | Out-Null
    [IO.Directory]::CreateDirectory((Join-Path $root 'java\bin')) | Out-Null
    foreach ($name in @('butler-setup-launch.ps1','butler-setup-process.ps1','butler-setup-runner.ps1','butler-setup-check.ps1','butler-java-preflight.ps1','butler-setup-stop.ps1')) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination (Join-Path $package ('scripts\' + $name))
    }
    foreach ($name in @('butler-app.cmd','butler-app-shell-core.ps1')) { [IO.File]::WriteAllText((Join-Path $package ('scripts\' + $name)), 'fixture') }
    [IO.File]::WriteAllText((Join-Path $package 'bet\bet-cli\build\install\bet-cli\lib\bet-cli-fixture.jar'), 'fixture')
    [IO.File]::WriteAllText((Join-Path $package 'scripts\butler-app.ps1'), @'
param([int]$Port,[switch]$NoBrowser)
$ErrorActionPreference='Stop'
$self=Get-Process -Id $PID
[IO.File]::WriteAllText((Join-Path $env:LOCALAPPDATA "Butler\running-port-$Port.txt"), "$PID|$($self.StartTime.ToUniversalTime().Ticks)|11111111-2222-3333-4444-555555555555")
if($env:BUTLER_LAUNCH_FIXTURE -eq 'orphan') {
 $child=Start-Process -FilePath "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -ArgumentList @('-NoProfile','-Command','Start-Sleep -Seconds 120') -WindowStyle Hidden -PassThru
 [IO.File]::WriteAllText((Join-Path $env:LOCALAPPDATA 'child.pid'), [string]$child.Id)
 exit 1
}
if($env:BUTLER_LAUNCH_FIXTURE -eq 'timeout') { Write-Output ('diagnostic ' * 1000); Start-Sleep -Seconds 120; exit 1 }
$server=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,$Port)
$server.Start()
try {
 while($true) {
  $client=$server.AcceptTcpClient()
  try {
   $stream=$client.GetStream()
   $reader=[IO.StreamReader]::new($stream)
   $line=$reader.ReadLine()
   while($reader.ReadLine()) {}
   $status='200 OK'
   if($line -match ' /health ') {
    $service=if($env:BUTLER_LAUNCH_FIXTURE -eq 'identity'){'other-service'}else{'butler-app-shell'}
    $body='{"service":"'+$service+'","status":"ok","bind":"127.0.0.1"}'
   } else {
    $body='<html>Butler Your fantasy week in one view My Team Matchup Players Butler authorized for review League No new trade score is created here. Your waiver decision timeline</html>'
    if($env:BUTLER_LAUNCH_FIXTURE -eq 'route-failure'){$status='500 Failed'}
    if($env:BUTLER_LAUNCH_FIXTURE -eq 'loading'){$body='<html>Butler Loading</html>'}
   }
   $bytes=[Text.Encoding]::UTF8.GetBytes($body)
   $header=[Text.Encoding]::ASCII.GetBytes("HTTP/1.1 $status`r`nContent-Type: text/html`r`nContent-Length: $($bytes.Length)`r`nConnection: close`r`n`r`n")
   $stream.Write($header,0,$header.Length);$stream.Write($bytes,0,$bytes.Length);$stream.Flush()
  } finally { $client.Close() }
 }
} finally { $server.Stop() }
'@)
    Add-Type -TypeDefinition 'public class LaunchFixtureJava { public static void Main() { System.Console.Error.WriteLine("openjdk version \"25.0.1\""); } }' -OutputAssembly (Join-Path $root 'java\bin\java.exe') -OutputType ConsoleApplication
    $db = Join-Path $profile 'Butler\data\butler.db'
    $selection = Join-Path $profile 'Butler\app-league.txt'
    [IO.File]::WriteAllText($db, "SQLite format 3`0fixture")
    [IO.File]::WriteAllText($selection, '11111111-2222-3333-4444-555555555555')
    $beforeDb = (Get-FileHash $db).Hash
    $beforeSelection = (Get-FileHash $selection).Hash
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = Join-Path $root 'runtime.zip'
    [IO.Compression.ZipFile]::CreateFromDirectory($package,$zip)
    [IO.File]::WriteAllText($zip + '.sha256', ((Get-FileHash $zip).Hash + '  runtime.zip'))
    $env:LOCALAPPDATA=$profile
    $env:JAVA_HOME=Join-Path $root 'java'
    $env:PATH=Join-Path $env:SystemRoot 'System32'
    $env:BUTLER_APP_DATA_DIR=''
    $sentinel=Start-Process -FilePath $shell -ArgumentList @('-NoProfile','-Command','Start-Sleep -Seconds 180') -WindowStyle Hidden -PassThru
    foreach ($mode in @('ready','restart','timeout','identity','route-failure','loading','orphan','handoff')) {
        $env:BUTLER_LAUNCH_FIXTURE=$mode
        $stdout=Join-Path $root ($mode+'.stdout')
        $stderr=Join-Path $root ($mode+'.stderr')
        $outerJob=New-Object ButlerSetupJob
        try {
            $argsList=@('-NoProfile','-ExecutionPolicy','Bypass','-File',('"'+(Join-Path $package 'scripts\butler-setup-launch.ps1')+'"'),'-RuntimeZip',('"'+$zip+'"'),'-StartupTimeoutSeconds','3','-RequestTimeoutSeconds','2')
            if($mode -ne 'handoff'){$argsList+='-VerifyOnly'}
            $check=Start-Process -FilePath $shell -ArgumentList $argsList -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
            $outerJob.Assign($check)
            if(-not $check.WaitForExit(30000)){throw "Fixture $mode exceeded 30-second bound"}
            $output=[IO.File]::ReadAllText($stdout)
            $expected=if($mode -in @('ready','restart','handoff')){0}else{1}
            if($check.ExitCode -ne $expected){throw "Fixture $mode returned $($check.ExitCode): $output $([IO.File]::ReadAllText($stderr))"}
            if($expected -eq 0 -and $output -notmatch 'BUTLER FIRST LAUNCH: PASS'){throw 'Missing success marker'}
            if($mode -eq 'timeout' -and ($output -notmatch 'timed out' -or $output.Length -gt 7000)){throw 'Startup timeout diagnostics are not bounded'}
            if($mode -eq 'orphan'){
                $childId=[int][IO.File]::ReadAllText((Join-Path $profile 'child.pid'))
                if(Get-Process -Id $childId -ErrorAction SilentlyContinue){throw 'Orphan descendant survived failed launch'}
            }
            if($mode -eq 'handoff'){
                if($output -notmatch 'BUTLER DASHBOARD: (http://127\.0\.0\.1:[0-9]+/)'){throw 'Dashboard URL missing'}
                $response=Invoke-WebRequest ($Matches[1]+'health') -UseBasicParsing -TimeoutSec 3
                if($response.StatusCode -ne 200){throw 'Handed-off app did not survive setup exit'}
                $record=@(Get-ChildItem (Join-Path $profile 'Butler\setup-runs') -Recurse -Filter run.json)[0]
                $originalRecord=[IO.File]::ReadAllText($record.FullName)
                $stale=$originalRecord | ConvertFrom-Json
                $stale.pid=$sentinel.Id
                $stale.startTicks=1
                [IO.File]::WriteAllText($record.FullName,($stale|ConvertTo-Json))
                $stopOutput=@(& $shell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $package 'scripts\butler-setup-stop.ps1') -RunDirectory $record.DirectoryName) -join "`n"
                if($LASTEXITCODE -ne 1 -or $stopOutput -notmatch 'different process' -or $sentinel.HasExited){throw 'Stale stop record did not preserve unrelated process'}
                [IO.File]::WriteAllText($record.FullName,$originalRecord)
                $stopOutput=@(& $shell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $package 'scripts\butler-setup-stop.ps1') -RunDirectory $record.DirectoryName) -join "`n"
                if($LASTEXITCODE -ne 0 -or $stopOutput -notmatch 'BUTLER SETUP STOP: PASS'){throw "Owned stop failed: $stopOutput"}
            } elseif(@(Get-ChildItem (Join-Path $profile 'Butler') -Filter 'running-port-*.txt').Count){throw 'Owned marker was not cleaned'}
            if($sentinel.HasExited){throw 'Unrelated process was stopped'}
            if((Get-FileHash $db).Hash -cne $beforeDb -or (Get-FileHash $selection).Hash -cne $beforeSelection){throw 'External data/settings changed'}
            Write-Host "LAUNCH FIXTURE PASS: $mode"
        } finally {$outerJob.Dispose()}
    }
    Write-Host 'BUTLER FIRST LAUNCH FIXTURES: PASS'
} finally {
    if($null -ne $sentinel -and -not $sentinel.HasExited){$sentinel.Kill()}
    foreach($name in $saved.Keys){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}
    $resolved=[IO.Path]::GetFullPath($root)
    $prefix=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')+'\butler-launch-fixtures-'
    if(-not $resolved.StartsWith($prefix,[StringComparison]::OrdinalIgnoreCase)){throw 'Unsafe fixture cleanup path'}
    if(Test-Path -LiteralPath $resolved){Remove-Item -LiteralPath $resolved -Recurse -Force}
}
$global:LASTEXITCODE=0
