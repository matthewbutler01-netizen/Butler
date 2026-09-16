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

# BF-742 default: app-shell staging consolidates one worker into the sibling dashboard unless explicitly opted out with =0.
$stagedCore = Join-Path (Split-Path -Parent $DashboardPath) 'butler-app-shell-core-single.ps1'
if ([string]$env:BUTLER_APP_PERSISTENT_CORE_WORKER -cne '0' -and (Test-Path -LiteralPath $stagedCore -PathType Leaf)) {
    $bf742Transform = Join-Path $PSScriptRoot 'butler-core-bf742-transform.ps1'
    if (-not (Test-Path -LiteralPath $bf742Transform -PathType Leaf)) {
        throw "BF-742 BLOCKED: shared-worker staging transform not found at $bf742Transform"
    }
    & $bf742Transform -CorePath $stagedCore -DashboardPath $DashboardPath
}
elseif ([string]$env:BUTLER_APP_PERSISTENT_CORE_WORKER -ceq '1') {
    throw "BF-742 BLOCKED: explicit persistent worker staging requested but staged core was not found at $stagedCore"
}

# BF-800: when app-shell staging supplies the sibling core, add the read-only My Team AutoFill preview
# only after prior staged-core transforms complete. Standalone BF-715 dashboard transforms remain valid.
if (Test-Path -LiteralPath $stagedCore -PathType Leaf) {
    $bf800Transform = Join-Path $PSScriptRoot 'butler-app-bf800-autofill-transform.ps1'
    if (-not (Test-Path -LiteralPath $bf800Transform -PathType Leaf)) {
        throw "BF-800 BLOCKED: AutoFill staging transform not found at $bf800Transform"
    }
    & $bf800Transform -CorePath $stagedCore
}

# BF-803: presentation-only manager modernization runs after BF-800 so it can
# restyle My Team and AutoFill without changing provider, optimizer, or route semantics.
if (Test-Path -LiteralPath $stagedCore -PathType Leaf) {
    $bf803Transform = Join-Path $PSScriptRoot 'butler-app-bf803-manager-ui-transform.ps1'
    if (-not (Test-Path -LiteralPath $bf803Transform -PathType Leaf)) {
        throw "BF-803 BLOCKED: manager UI transform not found at $bf803Transform"
    }
    & $bf803Transform -CorePath $stagedCore
}

# BF-804: Command Center is a dashboard-only presentation pass. Run it after the
# worker/core staging chain so no later transform can accidentally restore operator-first markup.
$bf804Transform = Join-Path $PSScriptRoot 'butler-dashboard-bf804-command-center-transform.ps1'
if (-not (Test-Path -LiteralPath $bf804Transform -PathType Leaf)) {
    throw "BF-804 BLOCKED: Command Center transform not found at $bf804Transform"
}
& $bf804Transform -DashboardPath $DashboardPath
