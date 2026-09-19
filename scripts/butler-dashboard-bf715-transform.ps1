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

# BF-805: local visual acceptance found the dashboard header lagging the BF-803 manager shell
# and the normal explanation still carrying implementation-heavy persisted wording.
$bf805Transform = Join-Path $PSScriptRoot 'butler-dashboard-bf805-command-center-polish-transform.ps1'
if (-not (Test-Path -LiteralPath $bf805Transform -PathType Leaf)) {
    throw "BF-805 BLOCKED: Command Center polish transform not found at $bf805Transform"
}
& $bf805Transform -DashboardPath $DashboardPath

# BF-806: turn the single waiver-centric priority surface into a read-only manager queue
# spanning waiver, lineup, and trade signals without adding new provider calls or decision semantics.
$bf806Transform = Join-Path $PSScriptRoot 'butler-dashboard-bf806-multi-signal-priorities-transform.ps1'
if (-not (Test-Path -LiteralPath $bf806Transform -PathType Leaf)) {
    throw "BF-806 BLOCKED: multi-signal priorities transform not found at $bf806Transform"
}
& $bf806Transform -DashboardPath $DashboardPath

# BF-807: order the three already-derived signals by explicit attention state so priority 01
# reflects what needs attention first without adding a hidden score or new evidence read.
$bf807Transform = Join-Path $PSScriptRoot 'butler-dashboard-bf807-priority-ordering-transform.ps1'
if (-not (Test-Path -LiteralPath $bf807Transform -PathType Leaf)) {
    throw "BF-807 BLOCKED: priority ordering transform not found at $bf807Transform"
}
& $bf807Transform -DashboardPath $DashboardPath

# BF-808: preserve the result of the explicit read-only AutoFill request and let the
# Command Center reuse that local snapshot without re-contacting FantasyPros on Dashboard load.
if (Test-Path -LiteralPath $stagedCore -PathType Leaf) {
    $bf808Transform = Join-Path $PSScriptRoot 'butler-bf808-autofill-command-center-transform.ps1'
    if (-not (Test-Path -LiteralPath $bf808Transform -PathType Leaf)) {
        throw "BF-808 BLOCKED: AutoFill Command Center transform not found at $bf808Transform"
    }
    & $bf808Transform -CorePath $stagedCore -DashboardPath $DashboardPath

    # BF-809: live acceptance proved the writer succeeds while the dashboard can miss the saved file.
    # Harden discovery and identity matching after BF-808 has installed its snapshot reader.
    $bf809Transform = Join-Path $PSScriptRoot 'butler-dashboard-bf809-autofill-snapshot-reader-transform.ps1'
    if (-not (Test-Path -LiteralPath $bf809Transform -PathType Leaf)) {
        throw "BF-809 BLOCKED: AutoFill snapshot reader transform not found at $bf809Transform"
    }
    & $bf809Transform -DashboardPath $DashboardPath

    # BF-810/BF-811: explanation and next-action guidance must follow the already-ordered priority 01 signal
    # rather than always explaining the saved waiver decision.
    $bf810Transform = Join-Path $PSScriptRoot 'butler-dashboard-bf810-priority-explanation-transform.ps1'
    if (-not (Test-Path -LiteralPath $bf810Transform -PathType Leaf)) {
        throw "BF-810 BLOCKED: priority explanation transform not found at $bf810Transform"
    }
    & $bf810Transform -DashboardPath $DashboardPath

    # BF-813: evidence status follows the same priority 01 signal using only evidence already loaded
    # by the Dashboard. Lineup uses the persisted AutoFill frame; waiver keeps the governed waiver cards.
    $bf813Transform = Join-Path $PSScriptRoot 'butler-dashboard-bf813-priority-evidence-transform.ps1'
    if (-not (Test-Path -LiteralPath $bf813Transform -PathType Leaf)) {
        throw "BF-813 BLOCKED: priority evidence transform not found at $bf813Transform"
    }
    & $bf813Transform -DashboardPath $DashboardPath
}

# BF-834: Waiver Board becomes a manager-first decision surface only after every existing
# dashboard/core staging pass has returned. This pass reuses the already-governed waiver state
# and exact audited pair; it does not change recommendation semantics or introduce a write path.
$bf834Transform = Join-Path $PSScriptRoot 'butler-dashboard-bf834-waiver-decision-surface-transform.ps1'
if (-not (Test-Path -LiteralPath $bf834Transform -PathType Leaf)) {
    throw "BF-834 BLOCKED: Waiver decision surface transform not found at $bf834Transform"
}
& $bf834Transform -DashboardPath $DashboardPath

# BF-837: live visual acceptance proved manager surfaces were still split across multiple
# design systems. Run the visual alignment after BF-834 and after the chained BF-815..BF-835
# staging work so nothing later can restore the legacy blue/Teko presentation.
if (Test-Path -LiteralPath $stagedCore -PathType Leaf) {
    $bf837CoreTransform = Join-Path $PSScriptRoot 'butler-app-bf837-manager-page-visual-transform.ps1'
    if (-not (Test-Path -LiteralPath $bf837CoreTransform -PathType Leaf)) {
        throw "BF-837 BLOCKED: final staged-core visual transform not found at $bf837CoreTransform"
    }
    & $bf837CoreTransform -CorePath $stagedCore

    # BF-840: add exact weekly matchup only after the accepted manager visual system is final.
    $bf840Transform = Join-Path $PSScriptRoot 'butler-app-bf840-weekly-matchup-transform.ps1'
    if (-not (Test-Path -LiteralPath $bf840Transform -PathType Leaf)) {
        throw "BF-840 BLOCKED: weekly matchup transform not found at $bf840Transform"
    }
    & $bf840Transform -CorePath $stagedCore

    # BF-852: passive Weekly Matchup reuses the existing authenticated persistent
    # read-only JVM worker. Explicit /matchup/autofill stays on its opt-in direct task.
    # Preserve BF-742's emergency opt-out by leaving the original direct Matchup path intact.
    if ([string]$env:BUTLER_APP_PERSISTENT_CORE_WORKER -cne '0') {
        $bf852Transform = Join-Path $PSScriptRoot 'butler-app-bf852-matchup-persistent-worker-transform.ps1'
        if (-not (Test-Path -LiteralPath $bf852Transform -PathType Leaf)) {
            throw "BF-852 BLOCKED: passive Matchup persistent-worker transform not found at $bf852Transform"
        }
        & $bf852Transform -CorePath $stagedCore -DashboardPath $DashboardPath
    }

    # BF-872: final My Team presentation polish runs after Weekly Matchup routing
    # and persistent-worker staging so no later staged-core transform restores
    # the dense roster-intelligence cards or changes the manager action routes.
    $bf872CoreTransform = Join-Path $PSScriptRoot 'butler-app-bf872-my-team-at-a-glance-transform.ps1'
    if (-not (Test-Path -LiteralPath $bf872CoreTransform -PathType Leaf)) {
        throw "BF-872 BLOCKED: My Team at-a-glance transform not found at $bf872CoreTransform"
    }
    & $bf872CoreTransform -CorePath $stagedCore
}

$bf837DashboardTransform = Join-Path $PSScriptRoot 'butler-dashboard-bf837-manager-page-visual-transform.ps1'
if (-not (Test-Path -LiteralPath $bf837DashboardTransform -PathType Leaf)) {
    throw "BF-837 BLOCKED: final dashboard-hosted visual transform not found at $bf837DashboardTransform"
}
& $bf837DashboardTransform -DashboardPath $DashboardPath

# BF-843: the Matchup route overlay targets the full app's BF-819 manager queue,
# which only exists when the sibling staged core is present. Standalone BF-715 waivers
# staging intentionally remains valid without the manager queue.
if (Test-Path -LiteralPath $stagedCore -PathType Leaf) {
    $bf843DashboardTransform = Join-Path $PSScriptRoot 'butler-dashboard-bf843-matchup-routing-transform.ps1'
    if (-not (Test-Path -LiteralPath $bf843DashboardTransform -PathType Leaf)) {
        throw "BF-843 BLOCKED: Dashboard Matchup routing transform not found at $bf843DashboardTransform"
    }
    & $bf843DashboardTransform -DashboardPath $DashboardPath

    # BF-871: final Dashboard presentation polish binds the hero to the exact
    # priority-01 card after BF-843 has finalized any Matchup-aware action route.
    $bf871DashboardTransform = Join-Path $PSScriptRoot 'butler-dashboard-bf871-decision-first-polish-transform.ps1'
    if (-not (Test-Path -LiteralPath $bf871DashboardTransform -PathType Leaf)) {
        throw "BF-871 BLOCKED: Dashboard decision-first polish transform not found at $bf871DashboardTransform"
    }
    & $bf871DashboardTransform -DashboardPath $DashboardPath
}

# BF-873: final Waiver Board presentation polish runs after the BF-834 decision
# surface, BF-837 visual alignment, and any full-app Dashboard route overlays.
$bf873DashboardTransform = Join-Path $PSScriptRoot 'butler-dashboard-bf873-waiver-decision-first-transform.ps1'
if (-not (Test-Path -LiteralPath $bf873DashboardTransform -PathType Leaf)) {
    throw "BF-873 BLOCKED: Waiver Board decision-first transform not found at $bf873DashboardTransform"
}
& $bf873DashboardTransform -DashboardPath $DashboardPath

# BF-857: diagnostic-only inner-core timing runs last so it observes the exact final
# staged Dashboard/core code without changing normal runtime when the flag is absent.
if ([string]$env:BUTLER_APP_BF857_CORE_TIMING -ceq '1') {
    if (-not (Test-Path -LiteralPath $stagedCore -PathType Leaf)) {
        throw "BF-857 BLOCKED: staged core is required for inner-core timing."
    }
    $bf857Transform = Join-Path $PSScriptRoot 'butler-bf857-inner-core-timing-transform.ps1'
    if (-not (Test-Path -LiteralPath $bf857Transform -PathType Leaf)) {
        throw "BF-857 BLOCKED: inner-core timing transform not found at $bf857Transform"
    }
    & $bf857Transform -CorePath $stagedCore -DashboardPath $DashboardPath
}
