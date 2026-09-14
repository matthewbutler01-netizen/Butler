param(
    [Parameter(Mandatory = $true)]
    [System.Net.Sockets.TcpClient]$Client,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$LeagueId,

    [Parameter(Mandatory = $true)]
    [int]$InnerPort,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$TradeHost,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$TradeLab,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$History,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$Detail,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DecisionRefresh,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DecisionRefreshRunner,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$RepoRoot,

    [Parameter(Mandatory = $true)]
    [hashtable]$RefreshState
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$cacheName = 'ButlerBf765PublicRequestWorkerScriptBlock'
$worker = Get-Variable -Name $cacheName -Scope Global -ValueOnly -ErrorAction SilentlyContinue
if ($null -eq $worker) {
    $implementationPath = Join-Path $PSScriptRoot 'butler-app-request-worker.ps1'
    if (-not (Test-Path -LiteralPath $implementationPath -PathType Leaf)) {
        try { $Client.Close() } catch {}
        throw "BF-765 BLOCKED: public request-worker implementation is unavailable at $implementationPath"
    }

    $implementation = [System.IO.File]::ReadAllText($implementationPath, [System.Text.Encoding]::ASCII)
    if ([string]::IsNullOrWhiteSpace($implementation)) {
        try { $Client.Close() } catch {}
        throw 'BF-765 BLOCKED: public request-worker implementation is empty.'
    }

    $worker = [scriptblock]::Create($implementation)
    Set-Variable -Name $cacheName -Scope Global -Value $worker
}
elseif ($worker -isnot [scriptblock]) {
    try { $Client.Close() } catch {}
    throw 'BF-765 BLOCKED: cached public request worker has an unexpected type.'
}

& $worker `
    -Client $Client `
    -LeagueId $LeagueId `
    -InnerPort $InnerPort `
    -TradeHost $TradeHost `
    -TradeLab $TradeLab `
    -History $History `
    -Detail $Detail `
    -DecisionRefresh $DecisionRefresh `
    -DecisionRefreshRunner $DecisionRefreshRunner `
    -RepoRoot $RepoRoot `
    -RefreshState $RefreshState
