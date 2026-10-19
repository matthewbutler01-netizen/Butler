Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Execute the real startup prefixes, with the first heavyweight operation
# replaced by a contention/readiness assertion. No worker, JVM or data is used.
function Assert-ReservedStartupEndpoint {
    $competitor = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $script:fixturePort)
    $competitor.ExclusiveAddressUse = $true
    $rejected = $false
    try {
        try { $competitor.Start() }
        catch [Net.Sockets.SocketException] { $rejected = $true }
    }
    finally { $competitor.Stop() }
    if (-not $rejected) { throw 'Endpoint was available to a competing bind during preparation.' }
    $request = [Net.HttpWebRequest]::Create("http://127.0.0.1:$script:fixturePort/health")
    $request.Timeout = 250
    $request.Proxy = $null
    try {
        $response = $request.GetResponse()
        try { throw 'Startup listener incorrectly returned readiness before workers.' }
        finally { $response.Close() }
    }
    catch [Net.WebException] {
        if ($_.Exception.Status -ne [Net.WebExceptionStatus]::Timeout) { throw }
    }
    $script:reservationObserved = $true
}
function Enable-ButlerGradleNoDaemon {}
function Initialize-ReadOnlyCliRuntime { Assert-ReservedStartupEndpoint; throw 'FIXTURE_STARTUP_STOP' }
function Get-FreeLoopbackPort { Assert-ReservedStartupEndpoint; return 12345 }
function Start-AppCore { param([int]$InnerPort); Assert-ReservedStartupEndpoint; throw 'FIXTURE_STARTUP_STOP' }

foreach ($case in @(
    @{ File='butler-app-shell-core.ps1'; Stop='Initialize-ReadOnlyCliRuntime' },
    @{ File='butler-app-shell.ps1'; Stop='$coreProcess = Start-AppCore -InnerPort $innerPort' }
)) {
    $tokens = $null; $errors = $null
    $ast = [Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $case.File), [ref]$tokens, [ref]$errors)
    if ($errors.Count) { throw 'Startup source did not parse.' }
    $startup = @($ast.EndBlock.Statements | Where-Object {
        $_ -is [Management.Automation.Language.TryStatementAst] -and $_.Body.Extent.Text.Contains($case.Stop)
    })
    if ($startup.Count -ne 1) { throw 'Expected one production startup block.' }
    $stop = @($startup[0].Body.Statements | Where-Object { $_.Extent.Text -ceq $case.Stop })
    if ($stop.Count -ne 1) { throw 'Expected one heavyweight startup boundary.' }
    $body = $startup[0].Body.Extent
    $prefix = $body.Text.Substring(1, $stop[0].Extent.EndOffset - $body.StartOffset - 1)
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    # Obtain a concrete endpoint, then release it to reproduce delayed-binding
    # behavior if the production prefix does not reserve it itself.
    $listener.Start()
    $script:fixturePort = ([Net.IPEndPoint]$listener.LocalEndpoint).Port
    $listener.Stop()
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $script:fixturePort)
    $innerPort = 12345
    $script:reservationObserved = $false
    try {
        try { & ([scriptblock]::Create($prefix)); throw 'Fixture did not stop at its boundary.' }
        catch { if ($_.Exception.Message -cne 'FIXTURE_STARTUP_STOP') { throw } }
        if (-not $script:reservationObserved) { throw 'No real contention assertion ran.' }
    }
    finally { $listener.Stop() }
    $probe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $script:fixturePort)
    try { $probe.Start() } finally { $probe.Stop() }
    Write-Host ($case.File + ': PASS (reserved during preparation; no premature health; endpoint released)')
}
Write-Host 'STARTUP PORT RESERVATION: PASS'
