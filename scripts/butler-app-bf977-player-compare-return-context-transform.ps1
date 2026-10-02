param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-977 BLOCKED: staged Butler core not found at $CorePath"
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
        throw "BF-977 BLOCKED: $Contract expected one match, found $matches."
    }
    return $Text.Replace($Old, $New)
}

$core = [System.IO.File]::ReadAllText($CorePath)

$tokens = $null
$parseErrors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseInput($core, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $summary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-977 BLOCKED: staged core failed pre-transform parse: $summary"
}

$compareFunctions = @($ast.FindAll({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
    $node.Name -eq 'ConvertTo-PlayerCompareHtml'
}, $true))
if ($compareFunctions.Count -ne 1) {
    throw "BF-977 BLOCKED: expected exactly one Player Compare renderer, found $($compareFunctions.Count)."
}

$compareStart = $compareFunctions[0].Extent.StartOffset
$compareEnd = $compareFunctions[0].Extent.EndOffset
$compare = $core.Substring($compareStart, $compareEnd - $compareStart)

$pairOld = @'
        $leftHtml = ConvertTo-PlayerCompareCardHtml -Player $View.Left -SupportingEvidenceState $View.SupportingEvidenceState -SupportingEvidenceHref $supportHref
        $rightHtml = ConvertTo-PlayerCompareCardHtml -Player $View.Right -SupportingEvidenceState $View.SupportingEvidenceState -SupportingEvidenceHref $supportHref
'@
$pairNew = @'
        $leftHtml = ConvertTo-PlayerCompareCardHtml -Player $View.Left -SupportingEvidenceState $View.SupportingEvidenceState -SupportingEvidenceHref $supportHref
        $rightHtml = ConvertTo-PlayerCompareCardHtml -Player $View.Right -SupportingEvidenceState $View.SupportingEvidenceState -SupportingEvidenceHref $supportHref
        $compareDetailSuffix = "&from=compare&left=$leftHref&right=$rightHref"
        $leftDetailOld = 'href="/player?id=' + $leftHref + '"'
        $leftDetailNew = 'href="/player?id=' + $leftHref + $compareDetailSuffix + '"'
        $rightDetailOld = 'href="/player?id=' + $rightHref + '"'
        $rightDetailNew = 'href="/player?id=' + $rightHref + $compareDetailSuffix + '"'
        if ([regex]::Matches($leftHtml, [regex]::Escape($leftDetailOld)).Count -ne 1 -or
            [regex]::Matches($rightHtml, [regex]::Escape($rightDetailOld)).Count -ne 1) {
            throw 'BF-977 BLOCKED: completed Player Compare detail links must resolve exactly once per side.'
        }
        $leftHtml = $leftHtml.Replace($leftDetailOld, $leftDetailNew)
        $rightHtml = $rightHtml.Replace($rightDetailOld, $rightDetailNew)
'@
$compare = Replace-ExactlyOnce -Text $compare -Old $pairOld.TrimEnd() -New $pairNew.TrimEnd() -Contract 'completed Player Compare detail context'
$core = $core.Substring(0, $compareStart) + $compare + $core.Substring($compareEnd)

$helperMarker = 'function Get-PlayerDetailRequestId {'
$helperIndex = $core.IndexOf($helperMarker, [System.StringComparison]::Ordinal)
if ($helperIndex -lt 0) {
    throw 'BF-977 BLOCKED: Player Detail helper insertion marker is missing.'
}

$helpers = @'
function Get-PlayerDetailCompareContext {
    param([Parameter(Mandatory = $true)][string]$RequestTarget)

    $question = $RequestTarget.IndexOf('?')
    if ($question -lt 0 -or $question + 1 -ge $RequestTarget.Length) {
        return [pscustomobject]@{ FromCompare = $false; Left = ''; Right = '' }
    }

    $fromValues = @()
    $leftValues = @()
    $rightValues = @()
    foreach ($part in @($RequestTarget.Substring($question + 1) -split '&')) {
        if ([string]::IsNullOrWhiteSpace($part)) { continue }

        $separator = $part.IndexOf('=')
        if ($separator -lt 0) {
            $rawName = $part
            $rawValue = ''
        }
        else {
            $rawName = $part.Substring(0, $separator)
            $rawValue = $part.Substring($separator + 1)
        }

        $name = [System.Uri]::UnescapeDataString($rawName.Replace('+', ' '))
        $value = [System.Uri]::UnescapeDataString($rawValue.Replace('+', ' ')).Trim()
        switch -CaseSensitive ($name) {
            'from' { $fromValues += $value }
            'left' { $leftValues += $value }
            'right' { $rightValues += $value }
        }
    }

    if ($fromValues.Count -eq 0 -or -not ($fromValues -ccontains 'compare')) {
        return [pscustomobject]@{ FromCompare = $false; Left = ''; Right = '' }
    }
    if ($fromValues.Count -ne 1 -or $leftValues.Count -ne 1 -or $rightValues.Count -ne 1) {
        throw 'BF-977 BLOCKED: Player Detail compare context requires exactly one from=compare, left, and right value.'
    }

    $left = [string]$leftValues[0]
    $right = [string]$rightValues[0]
    foreach ($id in @($left, $right)) {
        if ([string]::IsNullOrWhiteSpace($id) -or $id -notmatch '^[A-Za-z0-9._:-]+$') {
            throw 'BF-977 BLOCKED: Player Detail compare context contains an invalid player id.'
        }
    }

    return [pscustomobject]@{
        FromCompare = $true
        Left = $left
        Right = $right
    }
}

function Add-PlayerDetailCompareReturn {
    param(
        [Parameter(Mandatory = $true)][string]$Html,
        [Parameter(Mandatory = $true)]$CompareContext
    )

    if (-not [bool]$CompareContext.FromCompare) { return $Html }

    $leftHref = [System.Uri]::EscapeDataString([string]$CompareContext.Left)
    $rightHref = [System.Uri]::EscapeDataString([string]$CompareContext.Right)
    $old = '<div class="button-row"><a class="btn btn-secondary" href="/team">Back to My Team</a>'
    $new = '<div class="button-row"><a class="btn btn-secondary" href="/compare?left=' + $leftHref + '&right=' + $rightHref + '">Back to Player Compare</a><a class="btn btn-secondary" href="/team">My Team</a>'

    $matches = [regex]::Matches($Html, [regex]::Escape($old)).Count
    if ($matches -ne 1) {
        throw "BF-977 BLOCKED: Player Detail compare return expected one My Team action-row anchor, found $matches."
    }
    return $Html.Replace($old, $new)
}

'@

$core = $core.Insert($helperIndex, $helpers)

$routeOld = @'
                    $html = ConvertTo-PlayerDetailHtml -View $playerDetail
                    $fromPlayers = [regex]::IsMatch($parts[1], '(?:?|&)from=players(?:&|$)')
                    $searchReturnQuery = Get-PlayerDetailSearchQueryContext -RequestTarget $parts[1]
                    $html = Add-PlayerDetailContextNavigation -Html $html -FromPlayers $fromPlayers
                    $html = Add-PlayerCompareDetailAction -Html $html -PlayerId $playerDetail.PlayerId
                    $html = Add-PlayerDetailSearchReturnQuery -Html $html -FromPlayers $fromPlayers -SearchQuery $searchReturnQuery
'@
$routeNew = @'
                    $html = ConvertTo-PlayerDetailHtml -View $playerDetail
                    $fromPlayers = [regex]::IsMatch($parts[1], '(?:?|&)from=players(?:&|$)')
                    $searchReturnQuery = Get-PlayerDetailSearchQueryContext -RequestTarget $parts[1]
                    $compareReturnContext = Get-PlayerDetailCompareContext -RequestTarget $parts[1]
                    $html = Add-PlayerDetailContextNavigation -Html $html -FromPlayers $fromPlayers
                    $html = Add-PlayerCompareDetailAction -Html $html -PlayerId $playerDetail.PlayerId
                    $html = Add-PlayerDetailSearchReturnQuery -Html $html -FromPlayers $fromPlayers -SearchQuery $searchReturnQuery
                    $html = Add-PlayerDetailCompareReturn -Html $html -CompareContext $compareReturnContext
'@
$core = Replace-ExactlyOnce -Text $core -Old $routeOld.TrimEnd() -New $routeNew.TrimEnd() -Contract 'Player Detail compare-return route'

$tokens = $null
$parseErrors = $null
$finalAst = [System.Management.Automation.Language.Parser]::ParseInput($core, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $summary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-977 BLOCKED: generated staged core failed PowerShell parse: $summary"
}

foreach ($functionName in @('Get-PlayerDetailCompareContext', 'Add-PlayerDetailCompareReturn')) {
    $matches = @($finalAst.FindAll({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq $functionName
    }, $true))
    if ($matches.Count -ne 1) {
        throw "BF-977 BLOCKED: expected exactly one installed $functionName function, found $($matches.Count)."
    }
}

foreach ($required in @(
    '$compareDetailSuffix = "&from=compare&left=$leftHref&right=$rightHref"',
    'Back to Player Compare',
    'Get-PlayerDetailCompareContext -RequestTarget $parts[1]',
    'Add-PlayerDetailCompareReturn -Html $html -CompareContext $compareReturnContext'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-977 BLOCKED: installed Player Compare return-context marker is missing: $required"
    }
}

$bf977Surface = $helpers + $pairNew
if ($bf977Surface -match 'Invoke-RestMethod|Invoke-WebRequest|Invoke-ButlerReadOnly|Invoke-Bf742DashboardWorkerRead|https://api.sleeper.app|Method = "POST"|submitTransaction|setFaab|returnUrl|redirectUrl|javascript:') {
    throw 'BF-977 BLOCKED: Player Compare return context introduced provider, backend-read, write, or open-redirect behavior.'
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))
Write-Host 'BF-977 Player Compare exact return context applied.'
