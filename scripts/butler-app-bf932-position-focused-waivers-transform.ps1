param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-932 BLOCKED: staged Butler core not found at $CorePath"
}

$core = [System.IO.File]::ReadAllText($CorePath)

$teamStart = $core.IndexOf('function ConvertTo-TeamHtml {', [System.StringComparison]::Ordinal)
if ($teamStart -lt 0) {
    throw 'BF-932 BLOCKED: My Team renderer start is missing.'
}

$positionStart = $core.IndexOf('$positionHtml', $teamStart, [System.StringComparison]::Ordinal)
$seasonStart = $core.IndexOf('$seasonHtml', $positionStart, [System.StringComparison]::Ordinal)
if ($positionStart -lt 0 -or $seasonStart -le $positionStart) {
    throw 'BF-932 BLOCKED: final My Team position-card block is missing.'
}

$positionBlock = $core.Substring($positionStart, $seasonStart - $positionStart)
$actionOld = '<div class=`"button-row`" style=`"margin-top:12px`"><a class=`"btn btn-secondary`" href=`"/players?q=$positionHref`">Browse $(ConvertTo-HtmlText $position.Position) players</a></div></article>"'
$actionNew = '<div class=`"button-row`" style=`"margin-top:12px`"><a class=`"btn btn-secondary`" href=`"/players?q=$positionHref`">Browse $(ConvertTo-HtmlText $position.Position) players</a><a class=`"btn btn-secondary`" href=`"/waivers?position=$positionHref`">Check $(ConvertTo-HtmlText $position.Position) waivers</a></div></article>"'
$actionCount = [regex]::Matches($positionBlock, [regex]::Escape($actionOld)).Count
if ($actionCount -ne 2) {
    throw "BF-932 BLOCKED: My Team position action row expected two matches, found $actionCount."
}
$positionBlock = $positionBlock.Replace($actionOld, $actionNew)
$core = $core.Substring(0, $positionStart) + $positionBlock + $core.Substring($seasonStart)

$proxyOld = '                $proxied = Invoke-GovernedDashboardGet -InnerPort $innerPort -Path $path'
$proxyNew = @'
                $dashboardRequestTarget = if ($path -eq "/waivers") { $requestTarget } else { $path }
                $proxied = Invoke-GovernedDashboardGet -InnerPort $innerPort -Path $dashboardRequestTarget
'@
$proxyCount = [regex]::Matches($core, [regex]::Escape($proxyOld)).Count
if ($proxyCount -ne 1) {
    throw "BF-932 BLOCKED: governed dashboard proxy expected one match, found $proxyCount."
}
$core = $core.Replace($proxyOld, $proxyNew.TrimEnd())

foreach ($required in @(
    'href=`"/waivers?position=$positionHref`">Check $(ConvertTo-HtmlText $position.Position) waivers</a>',
    '$dashboardRequestTarget = if ($path -eq "/waivers") { $requestTarget } else { $path }',
    'Invoke-GovernedDashboardGet -InnerPort $innerPort -Path $dashboardRequestTarget'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-932 BLOCKED: required core marker is missing: $required"
    }
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($CorePath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $parseSummary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-932 BLOCKED: generated staged core failed PowerShell parse: $parseSummary"
}

Write-Host 'BF-932 positional waiver links and query preservation applied.'
