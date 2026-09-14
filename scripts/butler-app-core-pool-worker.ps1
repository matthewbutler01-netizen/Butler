param(
    [Parameter(Mandatory = $true)]
    [System.Net.Sockets.TcpClient]$Client,

    [Parameter(Mandatory = $true)]
    [int]$BackendPort
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$cacheName = 'ButlerBf764CorePoolWorkerScriptBlock'
$worker = Get-Variable -Name $cacheName -Scope Global -ValueOnly -ErrorAction SilentlyContinue
if ($null -eq $worker) {
    $implementationPath = Join-Path $PSScriptRoot 'butler-app-core-pool-worker-impl.ps1'
    if (-not (Test-Path -LiteralPath $implementationPath -PathType Leaf)) {
        try { $Client.Close() } catch {}
        throw "BF-764 BLOCKED: core-pool worker implementation is unavailable at $implementationPath"
    }

    $implementation = [System.IO.File]::ReadAllText($implementationPath, [System.Text.Encoding]::ASCII)
    if ([string]::IsNullOrWhiteSpace($implementation)) {
        try { $Client.Close() } catch {}
        throw 'BF-764 BLOCKED: core-pool worker implementation is empty.'
    }

    $worker = [scriptblock]::Create($implementation)
    Set-Variable -Name $cacheName -Scope Global -Value $worker
}
elseif ($worker -isnot [scriptblock]) {
    try { $Client.Close() } catch {}
    throw 'BF-764 BLOCKED: cached core-pool worker has an unexpected type.'
}

& $worker -Client $Client -BackendPort $BackendPort
