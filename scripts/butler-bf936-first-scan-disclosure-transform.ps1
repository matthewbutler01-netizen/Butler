param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath,
    [string]$CorePath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $DashboardPath -PathType Leaf)) {
    throw "BF-936 BLOCKED: staged Butler dashboard not found at $DashboardPath"
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
        throw "BF-936 BLOCKED: $Contract expected one match, found $count."
    }
    return $Text.Replace($Old, $New)
}

if (-not [string]::IsNullOrWhiteSpace($CorePath)) {
    if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
        throw "BF-936 BLOCKED: staged Butler core not found at $CorePath"
    }

    $core = [System.IO.File]::ReadAllText($CorePath)

    # Matchup: decision and workflow buttons remain first scan; secondary opponent evidence is collapsible.
    $matchupOld = @'
$autoFillHtml
$opponentHtml
<section class="panel boundary">
'@
    $matchupNew = @'
$autoFillHtml
<details class="manager-disclosure matchup-context-details"><summary>View opponent context</summary><div class="manager-disclosure-body">$opponentHtml</div></details>
<section class="panel boundary">
'@
    $core = Replace-ExactlyOnce -Text $core -Old $matchupOld.TrimEnd() -New $matchupNew.TrimEnd() -Contract 'Matchup opponent-context disclosure'

    # Player Detail: Player Hub remains visible; neutral evidence moves behind one disclosure.
    $playerReturnOld = '    return $Html.Replace($anchor, $playerHub.TrimEnd() + [Environment]::NewLine + $anchor)'
    $playerReturnNew = @'
    $managerHtml = $Html.Replace($anchor, $playerHub.TrimEnd() + [Environment]::NewLine + $anchor)
    $evidenceStart = $managerHtml.IndexOf($anchor, [System.StringComparison]::Ordinal)
    $boundaryStart = $managerHtml.IndexOf('<section class="panel boundary">', $evidenceStart, [System.StringComparison]::Ordinal)
    if ($evidenceStart -lt 0 -or $boundaryStart -le $evidenceStart) {
        throw 'BF-936 BLOCKED: Player Detail evidence disclosure boundary is missing.'
    }
    $evidenceHtml = $managerHtml.Substring($evidenceStart, $boundaryStart - $evidenceStart)
    return $managerHtml.Substring(0, $evidenceStart) +
        '<details class="manager-disclosure player-evidence-details"><summary>View player evidence</summary><div class="manager-disclosure-body">' +
        $evidenceHtml +
        '</div></details>' + [Environment]::NewLine +
        $managerHtml.Substring($boundaryStart)
'@
    $core = Replace-ExactlyOnce -Text $core -Old $playerReturnOld -New $playerReturnNew.TrimEnd() -Contract 'Player Detail evidence disclosure'

    $cssStart = $core.IndexOf('function Get-AppCss {', [System.StringComparison]::Ordinal)
    $cssEnd = $core.IndexOf('function Get-AppNav {', $cssStart, [System.StringComparison]::Ordinal)
    if ($cssStart -lt 0 -or $cssEnd -le $cssStart) {
        throw 'BF-936 BLOCKED: shared manager CSS boundary is missing.'
    }
    $cssBlock = $core.Substring($cssStart, $cssEnd - $cssStart)
    $cssTerminator = $cssBlock.LastIndexOf("'@", [System.StringComparison]::Ordinal)
    if ($cssTerminator -lt 0) {
        throw 'BF-936 BLOCKED: shared manager CSS terminator is missing.'
    }
    $bf936Css = @'
/* BF-936 first-scan progressive disclosure. */
.manager-disclosure{margin:0 0 18px;border:1px solid var(--line);border-radius:12px;background:var(--surface)}.manager-disclosure>summary{cursor:pointer;padding:13px 15px;font-weight:800;color:var(--ink)}.manager-disclosure[open]>summary{border-bottom:1px solid var(--line)}.manager-disclosure-body{padding:14px}.manager-disclosure-body>.panel{margin-bottom:12px}.manager-disclosure-body>.panel:last-child{margin-bottom:0}
'@
    $cssBlock = $cssBlock.Substring(0, $cssTerminator) + [Environment]::NewLine + $bf936Css.TrimEnd() + [Environment]::NewLine + $cssBlock.Substring($cssTerminator)
    $core = $core.Substring(0, $cssStart) + $cssBlock + $core.Substring($cssEnd)

    foreach ($required in @(
        'View opponent context',
        'View player evidence',
        'BF-936 first-scan progressive disclosure',
        'href="/franchise?id=$opponentHrefId">Scout opponent</a>',
        'href="/trade?opponent=$opponentHrefId">Trade with opponent</a>',
        'What do you want to decide?',
        'Compare this player',
        'Scout franchise',
        'Open Trade Analyzer',
        'Check Waiver Board'
    )) {
        if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-936 BLOCKED: required staged-core marker is missing: $required"
        }
    }

    [System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))

    $tokens = $null
    $parseErrors = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile($CorePath, [ref]$tokens, [ref]$parseErrors)
    if (@($parseErrors).Count -gt 0) {
        $parseSummary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
        throw "BF-936 BLOCKED: generated staged core failed PowerShell parse: $parseSummary"
    }
}

# Waiver Board: governed decision stays first scan; review pool remains fully available behind disclosure.
$dashboard = [System.IO.File]::ReadAllText($DashboardPath)
$waiverStart = $dashboard.IndexOf('function ConvertTo-WaiverHtml {', [System.StringComparison]::Ordinal)
$waiverEnd = $dashboard.IndexOf('function ConvertTo-WaiverCandidateDetailHtml {', $waiverStart, [System.StringComparison]::Ordinal)
if ($waiverStart -lt 0 -or $waiverEnd -le $waiverStart) {
    throw 'BF-936 BLOCKED: Waiver Board renderer boundary is missing.'
}
$waiverBlock = $dashboard.Substring($waiverStart, $waiverEnd - $waiverStart)
$reviewHead = $waiverBlock.IndexOf('<div class="waiver-board-head">', [System.StringComparison]::Ordinal)
if ($reviewHead -lt 0) {
    throw 'BF-936 BLOCKED: Waiver review-pool header is missing.'
}
$reviewStart = $waiverBlock.LastIndexOf('<section class="panel">', $reviewHead, [System.StringComparison]::Ordinal)
$boundaryStart = $waiverBlock.IndexOf('<section class="panel boundary">', $reviewHead, [System.StringComparison]::Ordinal)
if ($reviewStart -lt 0 -or $boundaryStart -le $reviewStart) {
    throw 'BF-936 BLOCKED: Waiver review-pool disclosure boundary is missing.'
}
$reviewHtml = $waiverBlock.Substring($reviewStart, $boundaryStart - $reviewStart).TrimEnd()
$reviewWrapped = '<details class="waiver-review-details"><summary>Review authorized players</summary><div class="waiver-review-body">' +
    [Environment]::NewLine + $reviewHtml + [Environment]::NewLine + '</div></details>' + [Environment]::NewLine
$waiverBlock = $waiverBlock.Substring(0, $reviewStart) + $reviewWrapped + $waiverBlock.Substring($boundaryStart)

$styleEnd = $waiverBlock.IndexOf('</style></head>', [System.StringComparison]::Ordinal)
if ($styleEnd -lt 0) {
    throw 'BF-936 BLOCKED: Waiver Board style terminator is missing.'
}
$waiverCss = '.waiver-review-details{margin:0 0 18px;border:1px solid var(--line);border-radius:12px;background:var(--surface)}.waiver-review-details>summary{cursor:pointer;padding:13px 15px;font-weight:800;color:var(--ink)}.waiver-review-details[open]>summary{border-bottom:1px solid var(--line)}.waiver-review-body>.panel{margin:0;border:0;box-shadow:none}'
$waiverBlock = $waiverBlock.Insert($styleEnd, $waiverCss)

$dashboard = $dashboard.Substring(0, $waiverStart) + $waiverBlock + $dashboard.Substring($waiverEnd)

foreach ($required in @(
    'Review authorized players',
    'Butler waiver decision',
    'What to do now',
    'Position focus',
    'Players Butler authorized for review',
    'NOT A RANKING.'
)) {
    if ($dashboard.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-936 BLOCKED: required Waiver Board marker is missing: $required"
    }
}

[System.IO.File]::WriteAllText($DashboardPath, $dashboard, [System.Text.UTF8Encoding]::new($false))

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($DashboardPath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $parseSummary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-936 BLOCKED: generated staged dashboard failed PowerShell parse: $parseSummary"
}

Write-Host 'BF-936 first-scan progressive disclosure batch applied.'
