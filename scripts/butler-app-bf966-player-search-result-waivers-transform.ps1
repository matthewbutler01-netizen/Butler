param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-966 BLOCKED: staged Butler core not found at $CorePath"
}

function Replace-ExactlyOnce {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Old,
        [Parameter(Mandatory = $true)][string]$New,
        [Parameter(Mandatory = $true)][string]$Contract
    )

    $matches = [regex]::Matches($Text, [regex]::Escape($Old)).Count
    if ($matches -ne 1) {
        throw "BF-966 BLOCKED: $Contract expected one match, found $matches."
    }
    return $Text.Replace($Old, $New)
}

$core = [System.IO.File]::ReadAllText($CorePath)

$tokens = $null
$parseErrors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseInput($core, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $summary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-966 BLOCKED: staged core failed pre-transform parse: $summary"
}

$functions = @($ast.FindAll({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
    $node.Name -eq 'ConvertTo-PlayerSearchHtml'
}, $true))
if ($functions.Count -ne 1) {
    throw "BF-966 BLOCKED: expected exactly one Player Search renderer, found $($functions.Count)."
}

$start = $functions[0].Extent.StartOffset
$end = $functions[0].Extent.EndOffset
$search = $core.Substring($start, $end - $start)

foreach ($required in @(
    '$hrefId = [System.Uri]::EscapeDataString([string]$player.PlayerId)',
    'href=`"/player?id=$hrefId&from=players`">View Player Detail</a>',
    'href=`"/compare?left=$hrefId`">Compare this player</a>',
    'Scout franchise'
)) {
    if ($search.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-966 BLOCKED: required Player Search result-card marker is missing: $required"
    }
}

$idOld = '                $hrefId = [System.Uri]::EscapeDataString([string]$player.PlayerId)'
$idNew = @'
                $hrefId = [System.Uri]::EscapeDataString([string]$player.PlayerId)
                $resultWaiverPosition = ([string]$player.Position).Trim().ToUpperInvariant()
                $resultWaiverAction = if (@("QB", "RB", "WR", "TE") -ccontains $resultWaiverPosition) {
                    $resultWaiverHref = [System.Uri]::EscapeDataString($resultWaiverPosition)
                    '<a class="btn btn-secondary" href="/waivers?position=' + (ConvertTo-HtmlText $resultWaiverHref) + '">Check ' + (ConvertTo-HtmlText $resultWaiverPosition) + ' waivers</a>'
                }
                else {
                    '<a class="btn btn-secondary" href="/waivers">Check Waiver Board</a>'
                }
'@
$search = Replace-ExactlyOnce -Text $search -Old $idOld -New $idNew.TrimEnd() -Contract 'Player Search result-card waiver derivation'

$cardOld = '<div class=`"button-row`" style=`"margin-top:12px`"><a class=`"btn btn-secondary`" href=`"/player?id=$hrefId&from=players`">View Player Detail</a><a class=`"btn btn-secondary`" href=`"/compare?left=$hrefId`">Compare this player</a><a class=`"btn btn-secondary`" href=`"/franchise?id=$([System.Uri]::EscapeDataString([string]$player.OwnerTeamId))`">Scout franchise</a></div></article>'
$cardNew = '<div class=`"button-row`" style=`"margin-top:12px`"><a class=`"btn btn-secondary`" href=`"/player?id=$hrefId&from=players`">View Player Detail</a><a class=`"btn btn-secondary`" href=`"/compare?left=$hrefId`">Compare this player</a><a class=`"btn btn-secondary`" href=`"/franchise?id=$([System.Uri]::EscapeDataString([string]$player.OwnerTeamId))`">Scout franchise</a>$resultWaiverAction</div></article>'
$search = Replace-ExactlyOnce -Text $search -Old $cardOld -New $cardNew -Contract 'Player Search result-card waiver action'

$core = $core.Substring(0, $start) + $search + $core.Substring($end)

$tokens = $null
$parseErrors = $null
$finalAst = [System.Management.Automation.Language.Parser]::ParseInput($core, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $summary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-966 BLOCKED: generated staged core failed PowerShell parse: $summary"
}

$installed = @($finalAst.FindAll({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
    $node.Name -eq 'ConvertTo-PlayerSearchHtml'
}, $true))
if ($installed.Count -ne 1) {
    throw "BF-966 BLOCKED: generated core must contain exactly one Player Search renderer; found $($installed.Count)."
}
$installedSearch = $installed[0].Extent.Text

foreach ($required in @(
    '$resultWaiverPosition = ([string]$player.Position).Trim().ToUpperInvariant()',
    '@("QB", "RB", "WR", "TE") -ccontains $resultWaiverPosition',
    '$resultWaiverHref = [System.Uri]::EscapeDataString($resultWaiverPosition)',
    'href="/waivers?position=',
    'Check '' + (ConvertTo-HtmlText $resultWaiverPosition) + '' waivers',
    'href="/waivers">Check Waiver Board</a>',
    '$resultWaiverAction</div></article>',
    '$searchWaiverAction</div></section>'
)) {
    if ($installedSearch.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-966 BLOCKED: installed Player Search result-card waiver marker is missing: $required"
    }
}

$bf966Surface = $idNew + $cardNew
if ($bf966Surface -match 'Invoke-RestMethod|Invoke-WebRequest|Invoke-ButlerReadOnly|Invoke-Bf742DashboardWorkerRead|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
    throw 'BF-966 BLOCKED: Player Search result-card waiver action introduced provider, backend-read, optimizer, FAAB, or write behavior.'
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))
Write-Host 'BF-966 Player Search result-card waivers applied.'

$bf975Transform = Join-Path $PSScriptRoot 'butler-app-bf975-player-search-return-context-transform.ps1'
if (-not (Test-Path -LiteralPath $bf975Transform -PathType Leaf)) {
    throw "BF-975 BLOCKED: Player Search return-context transform not found at $bf975Transform"
}
& $bf975Transform -CorePath $CorePath
