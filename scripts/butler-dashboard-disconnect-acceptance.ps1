param([ValidateSet('butler-dashboard.ps1','butler-app-shell-core-single.ps1','butler-app-shell.ps1','butler-app-request-worker.ps1')][string]$ResponseSource = 'butler-dashboard.ps1')
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$tokens = $null; $errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $ResponseSource), [ref]$tokens, [ref]$errors)
if (@($errors).Count) { throw 'Dashboard parse failed.' }
$functions = @($ast.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq 'Send-HttpResponse'}, $true))
if ($functions.Count -ne 1) { throw 'Dashboard response function is not unique.' }
. ([scriptblock]::Create($functions[0].Extent.Text))
$bf856RouteTimingEnabled = $false
$bf857CoreTimingEnabled = $false

if (-not ('ButlerDisconnectTestStream' -as [type])) {
Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Net.Sockets;
public sealed class ButlerDisconnectTestStream : NetworkStream {
    private int writes;
    private readonly int stage;
    private readonly bool socketError;
    public ButlerDisconnectTestStream(Socket socket, int stage, bool socketError) : base(socket, false) {
        this.stage = stage; this.socketError = socketError;
    }
    private void Fail() {
        if (socketError) throw new SocketException(10054);
        throw new IOException("Fixture client disconnected");
    }
    public override void Write(byte[] buffer, int offset, int count) {
        writes++;
        if (writes == stage) Fail();
        base.Write(buffer, offset, count);
    }
    public override void Flush() {
        if (stage == 3) Fail();
        base.Flush();
    }
}
public sealed class ButlerUnexpectedWriteStream : MemoryStream {
    public override void Write(byte[] buffer, int offset, int count) {
        throw new IOException("Unexpected non-network I/O failure");
    }
}
'@
}

foreach ($socketError in @($false, $true)) {
    foreach ($stage in @(1, 2, 3)) {
        $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
        $client = $null; $accepted = $null; $stream = $null
        try {
            $listener.Start()
            $client = [Net.Sockets.TcpClient]::new()
            $client.Connect([Net.IPAddress]::Loopback, ([Net.IPEndPoint]$listener.LocalEndpoint).Port)
            $accepted = $listener.AcceptTcpClient()
            $stream = [ButlerDisconnectTestStream]::new($accepted.Client, $stage, $socketError)
            Send-HttpResponse -Stream $stream -StatusCode 200 -StatusText 'OK' -ContentType 'text/plain' -Body 'Evidence remains unchanged'
        }
        finally {
            if ($null -ne $stream) { $stream.Dispose() }
            if ($null -ne $accepted) { $accepted.Dispose() }
            if ($null -ne $client) { $client.Dispose() }
            $listener.Stop()
        }
    }
}

# Transport containment must not suppress unrelated I/O errors.
$unexpected = [ButlerUnexpectedWriteStream]::new()
$propagated = $false
try {
    try { Send-HttpResponse -Stream $unexpected -StatusCode 200 -StatusText 'OK' -ContentType 'text/plain' -Body 'test' }
    catch { $propagated = $true }
}
finally { $unexpected.Dispose() }
if (-not $propagated) { throw 'Unexpected non-network error was swallowed.' }

# The next response must still preserve framing, UTF-8 and security headers.
$healthy = [IO.MemoryStream]::new()
try {
    $body = 'Evidence & ' + [char]0x00E9
    Send-HttpResponse -Stream $healthy -StatusCode 200 -StatusText 'OK' -ContentType 'text/plain; charset=utf-8' -Body $body
    $response = [Text.Encoding]::UTF8.GetString($healthy.ToArray())
    foreach ($required in @('HTTP/1.1 200 OK', 'Cache-Control: no-store', 'X-Content-Type-Options: nosniff', "Content-Length: $([Text.Encoding]::UTF8.GetByteCount($body))", $body)) {
        if (-not $response.Contains($required)) { throw "Healthy response lost $required" }
    }
}
finally { $healthy.Dispose() }
Write-Host 'DASHBOARD DISCONNECT ACCEPTANCE: PASS (header/body/flush, I/O/socket errors, unexpected-error propagation, next healthy response)'
