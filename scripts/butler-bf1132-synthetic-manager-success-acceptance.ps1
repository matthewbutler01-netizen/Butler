Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# BF-1132: exercise the real cached public worker's successful manager
# path with a single-use fake LOCAL core that serves fixed, data-free HTML.
# No live Butler core, database, Sleeper API or provider transaction is used.
$repoRoot = Split-Path -Parent $PSScriptRoot
$cachePath = Join-Path $PSScriptRoot 'butler-app-request-worker-cache.ps1'
$fixturePath = Join-Path $PSScriptRoot 'butler-bf1127-worker-first-open-503-acceptance.ps1'
$tokens = $null
$errors = $null
$fixtureAst = [System.Management.Automation.Language.Parser]::ParseFile(
    $fixturePath, [ref]$tokens, [ref]$errors)
if (@($errors).Count -ne 0) { throw 'BF-1132 BLOCKED: shared TCP fixture does not parse.' }
$fixtureHelper = $fixtureAst.Find({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
    $node.Name -ceq 'Invoke-Bf1127FirstOpenGet'
}, $true)
if ($null -eq $fixtureHelper) { throw 'BF-1132 BLOCKED: shared loopback GET helper missing.' }
. ([scriptblock]::Create($fixtureHelper.Extent.Text))

# C# is used only for the fake core's background TCP accept. No PowerShell
# runspace is created off-thread, and all traffic is bound to 127.0.0.1.
Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
public sealed class ButlerBf1132LocalCoreStub : IDisposable {
    private readonly TcpListener listener;
    private readonly Thread thread;
    public readonly int Port;
    public string RequestedRoute = "";
    public string Failure = "";
    public ButlerBf1132LocalCoreStub() {
        listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        Port = ((IPEndPoint)listener.LocalEndpoint).Port;
        thread = new Thread(Serve);
        thread.IsBackground = true;
        thread.Start();
    }
    private void Serve() {
        try {
            using (TcpClient client = listener.AcceptTcpClient()) {
                client.ReceiveTimeout = 5000;
                client.SendTimeout = 5000;
                NetworkStream stream = client.GetStream();
                string firstLine;
                using (StreamReader reader = new StreamReader(stream, Encoding.ASCII, false, 1024, true)) {
                    firstLine = reader.ReadLine();
                    for (int i = 0; i < 50; i++) {
                        string next = reader.ReadLine();
                        if (next == null || next.Length == 0) break;
                    }
                }
                if (firstLine == null || !firstLine.StartsWith("GET /", StringComparison.Ordinal))
                    throw new InvalidOperationException("Not a synthetic GET");
                RequestedRoute = firstLine.Split(' ')[1];
                string body = "<!doctype html><html><head><title>Butler synthetic</title></head>" +
                    "<body><main><h1>Butler synthetic healthy page</h1></main></body></html>";
                byte[] bytes = Encoding.UTF8.GetBytes(body);
                string headers = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: " + bytes.Length.ToString() + "\r\nCache-Control: no-store\r\n" +
                    "Connection: close\r\n\r\n";
                byte[] head = Encoding.ASCII.GetBytes(headers);
                stream.Write(head, 0, head.Length);
                stream.Write(bytes, 0, bytes.Length);
                stream.Flush();
            }
        }
        catch (Exception ex) { Failure = ex.GetType().Name; }
    }
    public void Dispose() {
        listener.Stop();
        if (!thread.Join(2000)) throw new InvalidOperationException("Synthetic core did not stop");
    }
}
'@

$privateMarker = 'BF1132_PRIVATE_LEAGUE_NEVER_RENDER'
$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ('butler-bf1132-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixtureRoot -Force | Out-Null
$validPath = Join-Path $fixtureRoot 'valid.ps1'
[IO.File]::WriteAllText($validPath, '$true | Out-Null', [Text.Encoding]::ASCII)

try {
    foreach ($target in @('/team', '/league', '/matchup')) {
        $core = [ButlerBf1132LocalCoreStub]::new()
        try {
            $wire = Invoke-Bf1127FirstOpenGet -Target $target `
                -HistoryPath $validPath -TradeHostPath $validPath -InnerPort $core.Port
            if (-not $wire.StartsWith('HTTP/1.1 200 OK', [StringComparison]::Ordinal) -or
                $wire.IndexOf('Butler synthetic healthy page', [StringComparison]::Ordinal) -lt 0 -or
                $wire.IndexOf('Content-Type: text/html', [StringComparison]::Ordinal) -lt 0 -or
                $wire.IndexOf('Cache-Control: no-store', [StringComparison]::Ordinal) -lt 0 -or
                $wire.IndexOf('Content-Length:', [StringComparison]::Ordinal) -lt 0 -or
                $wire.IndexOf('X-Butler-Failure-Layer:', [StringComparison]::Ordinal) -ge 0 -or
                $wire.IndexOf($privateMarker, [StringComparison]::Ordinal) -ge 0 -or
                $wire.IndexOf($fixtureRoot, [StringComparison]::OrdinalIgnoreCase) -ge 0 -or
                $wire.Length -gt 25000) {
                throw 'BF-1132 BLOCKED: successful core GET did not render a safe normal public page.'
            }
        }
        finally {
            $core.Dispose()
        }
        if ($core.Failure -ne '' -or $core.RequestedRoute -cne $target) {
            throw 'BF-1132 BLOCKED: public manager did not read the exact synthetic core route.'
        }
    }
}
finally {
    Remove-Item -LiteralPath $fixtureRoot -Recurse -Force -ErrorAction SilentlyContinue
}
Write-Host 'BF-1132 PASS: real public worker displays successful synthetic core manager reads.'
