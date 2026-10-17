Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$tokens=$null; $errors=$null
$source=Join-Path $PSScriptRoot 'butler-app-shell-core.ps1'
$ast=[Management.Automation.Language.Parser]::ParseFile($source,[ref]$tokens,[ref]$errors)
if (@($errors).Count) { throw 'Core parser failed' }
$definitions=@($ast.FindAll({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq 'Initialize-PreservedCoreWorkers' },$true))
if ($definitions.Count -ne 1) { throw 'Missing unique pool initializer' }
. ([scriptblock]::Create($definitions[0].Extent.Text))
function Reset-Fixture {
    $script:maxCoreWorkers=6
    $script:backendPorts=New-Object 'System.Collections.Generic.List[int]'
    $script:backendProcesses=New-Object 'System.Collections.Generic.List[object]'
    $script:events=New-Object 'System.Collections.Generic.List[string]'
    $script:port=20000
    $script:startFailure=0
    $script:healthFailure=0
}
function Get-FreeLoopbackPort { $script:port++; return $script:port }
function Start-PreservedCore {
    param([int]$BackendPort)
    if ($BackendPort -eq $script:startFailure) { throw 'fixture start failure' }
    $script:events.Add("start:$BackendPort")
    return [pscustomobject]@{ Id=$BackendPort }
}
function Wait-PreservedCore {
    param([int]$BackendPort,$Process)
    if ($Process.Id -ne $BackendPort) { throw 'Wrong process health identity' }
    if ($script:backendProcesses.Count -ne 6) { throw 'Health waited before bounded pool launch completed' }
    $script:events.Add("health:$BackendPort")
    if ($BackendPort -eq $script:healthFailure) { throw 'fixture health failure' }
}
function Invoke-PreservedCoreWarmup {
    param([int]$BackendPort)
    if ($script:events[$script:events.Count-1] -cne "health:$BackendPort") { throw 'Warmup bypassed health' }
    $script:events.Add("warm:$BackendPort")
}
Reset-Fixture
Initialize-PreservedCoreWorkers
if ($backendProcesses.Count -ne 6 -or $backendPorts.Count -ne 6 -or $events.Count -ne 18) { throw 'Pool accounting mismatch' }
if (@($events | Select-Object -First 6 | Where-Object { $_ -notlike 'start:*' }).Count) { throw 'Launches did not overlap preparation' }
if (@($backendProcesses | Where-Object { $null -eq $_.StartupTimer }).Count) { throw 'Missing timing' }

Reset-Fixture
$script:healthFailure=20003
$failed=$false
try { Initialize-PreservedCoreWorkers } catch { if ($_.Exception.Message -cne 'fixture health failure') { throw }; $failed=$true }
if (-not $failed -or $backendProcesses.Count -ne 6) { throw 'Health failure lost child cleanup ownership' }
if ($events -contains 'warm:20003' -or $events -contains 'health:20004') { throw 'Startup continued after health failure' }

Reset-Fixture
$script:startFailure=20004
$failed=$false
try { Initialize-PreservedCoreWorkers } catch { if ($_.Exception.Message -cne 'fixture start failure') { throw }; $failed=$true }
if (-not $failed -or $backendProcesses.Count -ne 3 -or $backendPorts.Count -ne 3) { throw 'Partial launch lost child cleanup ownership' }
if (@($events | Where-Object { $_ -like 'health:*' -or $_ -like 'warm:*' }).Count) { throw 'Preparation continued after launch failure' }
Write-Host 'Bounded startup pool acceptance: PASS (normal, health failure, partial launch failure).'
