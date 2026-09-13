param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $DashboardPath -PathType Leaf)) {
    throw "BF-715 BLOCKED: staged dashboard not found at $DashboardPath"
}

$helperOriginal = @'
function Invoke-ButlerReadOnlyWaiverBoard {
    return Invoke-ButlerReadOnlyTask -Task ":bet:bet-cli:sleeperLiveWaiverComparisonBundle" -BoundaryName "BF-646"
}
'@

$helperReplacement = @'
function Invoke-ButlerReadOnlyWaiverBoard {
    return Invoke-ButlerReadOnlyTask -Task ":bet:bet-cli:sleeperLiveWaiverComparisonBundle" -BoundaryName "BF-646"
}

function Get-Bf715WaiverBundleSection {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Name
    )

    $begin = "===BUTLER_WAIVER_BUNDLE:${Name}:BEGIN==="
    $end = "===BUTLER_WAIVER_BUNDLE:${Name}:END==="
    $start = $Text.IndexOf($begin, [System.StringComparison]::Ordinal)
    if ($start -lt 0) { throw "BF-715 BLOCKED: waiver evidence bundle is missing $Name begin marker." }
    $bodyStart = $start + $begin.Length
    $finish = $Text.IndexOf($end, $bodyStart, [System.StringComparison]::Ordinal)
    if ($finish -lt 0) { throw "BF-715 BLOCKED: waiver evidence bundle is missing $Name end marker." }
    $body = $Text.Substring($bodyStart, $finish - $bodyStart).Trim()
    if ([string]::IsNullOrWhiteSpace($body)) { throw "BF-715 BLOCKED: waiver evidence bundle section $Name is empty." }
    return $body
}

function Invoke-ButlerReadOnlyWaiverEvidenceBundle {
    $previousPreference = $ErrorActionPreference
    $lines = $null
    $exitCode = $null
    try {
        $ErrorActionPreference = "Continue"
        $lines = & $gradle ":bet:bet-cli:sleeperLiveWaiverTargetRosterContextAudit" "--args=$LeagueId --waiver-dashboard-bundle" 2>&1
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }

    $text = ($lines | ForEach-Object { "$_" }) -join "`n"
    if ($exitCode -ne 0) {
        throw "BF-715 BLOCKED: governed waiver evidence bundle failed with Gradle exit code $exitCode.`n$text"
    }

    return [pscustomobject]@{
        Summary = Get-Bf715WaiverBundleSection -Text $text -Name "SUMMARY"
        WaiverBoard = Get-Bf715WaiverBundleSection -Text $text -Name "WAIVER_BOARD"
        RosterContext = Get-Bf715WaiverBundleSection -Text $text -Name "ROSTER_CONTEXT"
    }
}
'@

$routeOriginal = @'
                elseif ($path -eq "/waivers") {
                    $summary = Invoke-ButlerReadOnlySummary
                    $waiverBundle = Invoke-ButlerReadOnlyWaiverBoard
                    $rosterContext = Invoke-ButlerReadOnlyRosterContext
                    $html = ConvertTo-WaiverHtml -Bundle $waiverBundle -Summary $summary -RosterContext $rosterContext
                }
'@

$routeReplacement = @'
                elseif ($path -eq "/waivers") {
                    $waiverEvidence = Invoke-ButlerReadOnlyWaiverEvidenceBundle
                    $summary = $waiverEvidence.Summary
                    $waiverBundle = $waiverEvidence.WaiverBoard
                    $rosterContext = $waiverEvidence.RosterContext
                    $html = ConvertTo-WaiverHtml -Bundle $waiverBundle -Summary $summary -RosterContext $rosterContext
                }
'@

$text = [System.IO.File]::ReadAllText($DashboardPath)
$helperMatches = [regex]::Matches($text, [regex]::Escape($helperOriginal)).Count
$routeMatches = [regex]::Matches($text, [regex]::Escape($routeOriginal)).Count

if ($helperMatches -ne 1) {
    throw "BF-715 BLOCKED: expected exactly one waiver-board helper contract, found $helperMatches."
}
if ($routeMatches -ne 1) {
    throw "BF-715 BLOCKED: expected exactly one /waivers route contract, found $routeMatches."
}

$text = $text.Replace($helperOriginal, $helperReplacement)
$text = $text.Replace($routeOriginal, $routeReplacement)

if ($text.Contains($routeOriginal)) {
    throw 'BF-715 BLOCKED: staged /waivers route still contains sequential governed reads.'
}
if (-not $text.Contains('Invoke-ButlerReadOnlyWaiverEvidenceBundle')) {
    throw 'BF-715 BLOCKED: staged dashboard is missing the exact waiver evidence bundle helper.'
}

[System.IO.File]::WriteAllText($DashboardPath, $text, [System.Text.UTF8Encoding]::new($false))
