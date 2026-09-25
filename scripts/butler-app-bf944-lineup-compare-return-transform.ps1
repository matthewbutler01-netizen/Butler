param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-944 BLOCKED: staged Butler core not found at $CorePath"
}

function Replace-ExactlyOnce {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Old,
        [Parameter(Mandatory = $true)][string]$New,
        [Parameter(Mandatory = $true)][string]$Contract
    )

    $count = [regex]::Matches($Text, [regex]::Escape($Old)).Count
    if ($count -ne 1) {
        throw "BF-944 BLOCKED: $Contract expected one match, found $count."
    }
    return $Text.Replace($Old, $New)
}

$core = [System.IO.File]::ReadAllText($CorePath)

$lineupHrefOld = '    return "/compare?left=$leftHref&right=$rightHref"'
$lineupHrefNew = '    return "/compare?left=$leftHref&right=$rightHref&from=lineup"'
$core = Replace-ExactlyOnce -Text $core -Old $lineupHrefOld -New $lineupHrefNew -Contract 'Lineup swap compare context flag'

$requestStart = $core.IndexOf('function Get-PlayerCompareRequest {', [System.StringComparison]::Ordinal)
$requestEnd = $core.IndexOf('function ConvertTo-PlayerCompareSideView {', $requestStart, [System.StringComparison]::Ordinal)
if ($requestStart -lt 0 -or $requestEnd -le $requestStart) {
    throw 'BF-944 BLOCKED: Player Compare request parser boundary is missing.'
}
$request = $core.Substring($requestStart, $requestEnd - $requestStart)

$valuesOld = @'
        q = @()
        support = @()
'@
$valuesNew = @'
        q = @()
        support = @()
        from = @()
'@
$request = Replace-ExactlyOnce -Text $request -Old $valuesOld.TrimEnd() -New $valuesNew.TrimEnd() -Contract 'Player Compare from collection'

$unknownOld = "throw 'BF-906 BLOCKED: Player Compare accepts only left, right, q, and support query parameters.'"
$unknownNew = "throw 'BF-944 BLOCKED: Player Compare accepts only left, right, q, support, and from query parameters.'"
$request = Replace-ExactlyOnce -Text $request -Old $unknownOld -New $unknownNew -Contract 'Player Compare query whitelist'

$uniqueOld = "foreach (`$name in @('left','right','q','support')) {"
$uniqueNew = "foreach (`$name in @('left','right','q','support','from')) {"
$request = Replace-ExactlyOnce -Text $request -Old $uniqueOld -New $uniqueNew -Contract 'Player Compare from uniqueness'

$parseOld = @'
    $support = if (@($values.support).Count -eq 1) { [string]$values.support[0] } else { '' }
    if (@($values.support).Count -eq 1 -and $support -cne '1') {
        throw 'BF-906 BLOCKED: Player Compare support may only be requested as support=1.'
    }
'@
$parseNew = @'
    $support = if (@($values.support).Count -eq 1) { [string]$values.support[0] } else { '' }
    if (@($values.support).Count -eq 1 -and $support -cne '1') {
        throw 'BF-906 BLOCKED: Player Compare support may only be requested as support=1.'
    }

    $from = if (@($values.from).Count -eq 1) { [string]$values.from[0] } else { '' }
    $fromLineup = @($values.from).Count -eq 1
    if ($fromLineup -and $from -cne 'lineup') {
        throw 'BF-944 BLOCKED: Player Compare from context may only be from=lineup.'
    }
'@
$request = Replace-ExactlyOnce -Text $request -Old $parseOld.TrimEnd() -New $parseNew.TrimEnd() -Contract 'Player Compare lineup context validation'

$returnOld = @'
    return [pscustomobject]@{
        LeftPlayerId = $left
        RightPlayerId = $right
        Query = $query
        LoadSupportingEvidence = (@($values.support).Count -eq 1)
    }
'@
$returnNew = @'
    if ($fromLineup) {
        if ([string]::IsNullOrWhiteSpace($left) -or [string]::IsNullOrWhiteSpace($right)) {
            throw 'BF-944 BLOCKED: lineup compare context requires two exact players.'
        }
        if (-not [string]::IsNullOrWhiteSpace($query)) {
            throw 'BF-944 BLOCKED: lineup compare context cannot be used during player search.'
        }
    }

    return [pscustomobject]@{
        LeftPlayerId = $left
        RightPlayerId = $right
        Query = $query
        LoadSupportingEvidence = (@($values.support).Count -eq 1)
        FromLineup = $fromLineup
    }
'@
$request = Replace-ExactlyOnce -Text $request -Old $returnOld.TrimEnd() -New $returnNew.TrimEnd() -Contract 'Player Compare lineup context result'

$core = $core.Substring(0, $requestStart) + $request + $core.Substring($requestEnd)

$renderStart = $core.IndexOf('function ConvertTo-PlayerCompareHtml {', [System.StringComparison]::Ordinal)
$renderEnd = $core.IndexOf('function Add-PlayerCompareDetailAction {', $renderStart, [System.StringComparison]::Ordinal)
if ($renderStart -lt 0 -or $renderEnd -le $renderStart) {
    throw 'BF-944 BLOCKED: final Player Compare renderer boundary is missing.'
}
$render = $core.Substring($renderStart, $renderEnd - $renderStart)

$hrefOld = @'
        $leftHref = [System.Uri]::EscapeDataString([string]$Request.LeftPlayerId)
        $rightHref = [System.Uri]::EscapeDataString([string]$Request.RightPlayerId)
        $supportHref = "/compare?left=$leftHref&right=$rightHref&support=1#supporting-evidence"
        $swapSuffix = if ($Request.LoadSupportingEvidence) { '&support=1#supporting-evidence' } else { '' }
        $swapHref = "/compare?left=$rightHref&right=$leftHref$swapSuffix"
'@
$hrefNew = @'
        $leftHref = [System.Uri]::EscapeDataString([string]$Request.LeftPlayerId)
        $rightHref = [System.Uri]::EscapeDataString([string]$Request.RightPlayerId)
        $lineupContextSuffix = if ($Request.FromLineup) { '&from=lineup' } else { '' }
        $supportHref = "/compare?left=$leftHref&right=$rightHref$lineupContextSuffix&support=1#supporting-evidence"
        $swapSuffix = if ($Request.LoadSupportingEvidence) { '&support=1#supporting-evidence' } else { '' }
        $swapHref = "/compare?left=$rightHref&right=$leftHref$lineupContextSuffix$swapSuffix"
        $returnToLineupAction = if ($Request.FromLineup) {
            '<a class="btn btn-secondary" href="/team/autofill">Back to Lineup Review</a>'
        } else {
            ''
        }
'@
$render = Replace-ExactlyOnce -Text $render -Old $hrefOld.TrimEnd() -New $hrefNew.TrimEnd() -Contract 'Player Compare context-preserving hrefs'

$heroOld = '<div class="button-row"><a class="btn btn-primary" href="$swapHref">Swap sides</a><a class="btn btn-secondary" href="/players">Compare different players</a></div>'
$heroNew = '<div class="button-row"><a class="btn btn-primary" href="$swapHref">Swap sides</a>$returnToLineupAction<a class="btn btn-secondary" href="/players">Compare different players</a></div>'
$render = Replace-ExactlyOnce -Text $render -Old $heroOld -New $heroNew -Contract 'Player Compare lineup return action'

$core = $core.Substring(0, $renderStart) + $render + $core.Substring($renderEnd)

foreach ($required in @(
    '&from=lineup',
    "from context may only be from=lineup",
    'lineup compare context requires two exact players',
    'FromLineup = $fromLineup',
    '$lineupContextSuffix = if ($Request.FromLineup)',
    '$supportHref = "/compare?left=$leftHref&right=$rightHref$lineupContextSuffix&support=1#supporting-evidence"',
    '$swapHref = "/compare?left=$rightHref&right=$leftHref$lineupContextSuffix$swapSuffix"',
    'Back to Lineup Review',
    'href="/team/autofill"'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-944 BLOCKED: required lineup compare return marker is missing: $required"
    }
}

$installedStart = $core.IndexOf('function Get-PlayerCompareRequest {', [System.StringComparison]::Ordinal)
$installedEnd = $core.IndexOf('function Add-PlayerCompareDetailAction {', $installedStart, [System.StringComparison]::Ordinal)
$installed = $core.Substring($installedStart, $installedEnd - $installedStart)
foreach ($forbidden in @(
    'Invoke-RestMethod',
    'Invoke-WebRequest',
    'Method = "POST"',
    'returnUrl',
    'redirectUrl',
    'window.history',
    'javascript:',
    'submitTransaction',
    'setFaab'
)) {
    if ($installed.IndexOf($forbidden, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
        throw "BF-944 BLOCKED: lineup compare return introduced forbidden behavior: $forbidden"
    }
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($CorePath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $summary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-944 BLOCKED: generated staged core failed PowerShell parse: $summary"
}

Write-Host 'BF-944 Lineup Compare Return applied.'
