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

    # BF-766 preserves the historical worker source and its health fast path.
    # Only the parsed per-request execution copy is changed: UI modules are
    # parsed once per persistent runspace and then dot-sourced from cached
    # ScriptBlocks. BF-767 route-scopes that cache use: exact core proxied reads
    # need only History and DecisionRefresh, while every other non-health route
    # retains the historical five-module execution order.
    $moduleLoadPattern = '(?m)^    \. \$TradeHost\r?\n    \. \$TradeLab\r?\n    \. \$History\r?\n    \. \$Detail\r?\n    \. \$DecisionRefresh\r?$'
    $moduleLoadMatches = [regex]::Matches($implementation, $moduleLoadPattern)
    if ($moduleLoadMatches.Count -ne 1) {
        try { $Client.Close() } catch {}
        throw "BF-766 BLOCKED: public UI module load block count was $($moduleLoadMatches.Count), expected exactly 1."
    }

    $moduleLoadReplacement = @'
    $bf767CoreRead = $requestTarget -ceq '/' -or
        $requestTarget -ceq '/team' -or
        $requestTarget -ceq '/waivers' -or
        $requestTarget -ceq '/league'

    $bf766ModuleSpecs = if ($bf767CoreRead) {
        @(
            [pscustomobject]@{ Name = 'History'; Path = $History; CacheName = 'ButlerBf766HistoryScriptBlock' },
            [pscustomobject]@{ Name = 'DecisionRefresh'; Path = $DecisionRefresh; CacheName = 'ButlerBf766DecisionRefreshScriptBlock' }
        )
    }
    else {
        @(
            [pscustomobject]@{ Name = 'TradeHost'; Path = $TradeHost; CacheName = 'ButlerBf766TradeHostScriptBlock' },
            [pscustomobject]@{ Name = 'TradeLab'; Path = $TradeLab; CacheName = 'ButlerBf766TradeLabScriptBlock' },
            [pscustomobject]@{ Name = 'History'; Path = $History; CacheName = 'ButlerBf766HistoryScriptBlock' },
            [pscustomobject]@{ Name = 'Detail'; Path = $Detail; CacheName = 'ButlerBf766DetailScriptBlock' },
            [pscustomobject]@{ Name = 'DecisionRefresh'; Path = $DecisionRefresh; CacheName = 'ButlerBf766DecisionRefreshScriptBlock' }
        )
    }

    foreach ($bf766ModuleSpec in $bf766ModuleSpecs) {
        $bf766PathCacheName = $bf766ModuleSpec.CacheName + 'Path'
        $bf766Module = Get-Variable -Name $bf766ModuleSpec.CacheName -Scope Global -ValueOnly -ErrorAction SilentlyContinue
        $bf766CachedPath = Get-Variable -Name $bf766PathCacheName -Scope Global -ValueOnly -ErrorAction SilentlyContinue

        if ($null -eq $bf766Module -and $null -eq $bf766CachedPath) {
            if (-not (Test-Path -LiteralPath $bf766ModuleSpec.Path -PathType Leaf)) {
                throw "BF-766 BLOCKED: $($bf766ModuleSpec.Name) module is unavailable at $($bf766ModuleSpec.Path)"
            }
            $bf766Source = [System.IO.File]::ReadAllText($bf766ModuleSpec.Path, [System.Text.Encoding]::ASCII)
            if ([string]::IsNullOrWhiteSpace($bf766Source)) {
                throw "BF-766 BLOCKED: $($bf766ModuleSpec.Name) module is empty."
            }
            try {
                $bf766Module = [scriptblock]::Create($bf766Source)
            }
            catch {
                throw "BF-766 BLOCKED: $($bf766ModuleSpec.Name) module could not be parsed: $($_.Exception.Message)"
            }
            Set-Variable -Name $bf766ModuleSpec.CacheName -Scope Global -Value $bf766Module
            Set-Variable -Name $bf766PathCacheName -Scope Global -Value ([string]$bf766ModuleSpec.Path)
        }
        elseif ($null -eq $bf766Module -or
                $null -eq $bf766CachedPath -or
                $bf766Module -isnot [scriptblock] -or
                [string]$bf766CachedPath -cne [string]$bf766ModuleSpec.Path) {
            throw "BF-766 BLOCKED: cached $($bf766ModuleSpec.Name) module state is invalid or belongs to a different path."
        }

        . $bf766Module
    }
'@

    $moduleLoadMatch = $moduleLoadMatches[0]
    $implementation = $implementation.Substring(0, $moduleLoadMatch.Index) +
        $moduleLoadReplacement +
        $implementation.Substring($moduleLoadMatch.Index + $moduleLoadMatch.Length)

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
