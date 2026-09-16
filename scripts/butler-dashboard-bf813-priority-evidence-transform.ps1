param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $DashboardPath -PathType Leaf)) {
    throw "BF-813 BLOCKED: staged Butler dashboard not found at $DashboardPath"
}

function Replace-ExactlyOnce {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Old,
        [Parameter(Mandatory = $true)][string]$New,
        [Parameter(Mandatory = $true)][string]$Contract
    )

    $first = $Text.IndexOf($Old, [System.StringComparison]::Ordinal)
    if ($first -lt 0) {
        throw "BF-813 BLOCKED: $Contract anchor is missing."
    }
    $second = $Text.IndexOf($Old, $first + $Old.Length, [System.StringComparison]::Ordinal)
    if ($second -ge 0) {
        throw "BF-813 BLOCKED: $Contract anchor is ambiguous."
    }
    return $Text.Substring(0, $first) + $New + $Text.Substring($first + $Old.Length)
}

$text = [System.IO.File]::ReadAllText($DashboardPath)
$dashboardStart = $text.IndexOf('function ConvertTo-DashboardHtml {', [System.StringComparison]::Ordinal)
$dashboardEnd = $text.IndexOf('function ConvertTo-TeamHtml {', $dashboardStart, [System.StringComparison]::Ordinal)
if ($dashboardStart -lt 0 -or $dashboardEnd -le $dashboardStart) {
    throw 'BF-813 BLOCKED: dashboard renderer function boundary is missing.'
}

$dashboardBlock = $text.Substring($dashboardStart, $dashboardEnd - $dashboardStart)

$evidenceDerivationAnchor = @'
            default {
                $primaryExplanationTitle = "Why this is priority 01"
                $primaryExplanationCopy = [string]$priorityOne.Copy
                $primaryNextActionCopy = "Review the priority signal above before making a roster decision."
                $primaryNextActionHref = "/"
                $primaryNextActionLabel = "Return to Dashboard"
            }
        }
    }
'@

$evidenceDerivation = @'
            default {
                $primaryExplanationTitle = "Why this is priority 01"
                $primaryExplanationCopy = [string]$priorityOne.Copy
                $primaryNextActionCopy = "Review the priority signal above before making a roster decision."
                $primaryNextActionHref = "/"
                $primaryNextActionLabel = "Return to Dashboard"
            }
        }
    }

    # BF-813 runs before the older BF-804 manager prelude assigns $rosterStatusText and
    # $lineageStatusText. Derive the two display values here from verification directly so
    # StrictMode never observes an uninitialized variable while interpolating evidence HTML.
    $evidenceRosterStatusText = if ($verification.RosterOk) { "Verified" } else { "Needs attention" }
    $evidenceLineageStatusText = if ($verification.LineageOk) { "Verified" } else { "Needs refresh" }

    $primaryEvidenceHtml = @"
<div class="evidence-grid">
  <div class="evidence-card"><strong>Roster check</strong><div class="evidence-value">$(ConvertTo-HtmlText $evidenceRosterStatusText)</div><div class="evidence-note">$(ConvertTo-HtmlText $verification.Roster)</div></div>
  <div class="evidence-card"><strong>Recommendation data</strong><div class="evidence-value">$(ConvertTo-HtmlText $evidenceLineageStatusText)</div><div class="evidence-note">$(ConvertTo-HtmlText $verification.Lineage)</div></div>
  <div class="evidence-card"><strong>Waiver market</strong><div class="evidence-value">$(ConvertTo-HtmlText $market.Human)</div><div class="evidence-note">Evidence age</div></div>
  <div class="evidence-card"><strong>Roster / waiver</strong><div class="evidence-value">$(ConvertTo-HtmlText $waiver.Human)</div><div class="evidence-note">Evidence age</div></div>
</div>
"@

    if ($null -ne $priorityOne) {
        switch ([string]$priorityOne.Kind) {
            "Lineup" {
                $snapshotValue = "Not requested"
                $snapshotNote = "No saved AutoFill result is available for this lineup frame."
                $frameValue = "No saved frame"
                $frameNote = "Open Lineup Advisor when you want an explicit read-only weekly check."
                $coverageValue = "Not evaluated"
                $coverageNote = "No current AutoFill projection result is available."

                if ($null -ne $lineupSnapshot) {
                    $snapshotValue = if ($snapshotFresh -and $snapshotTargetMatches -and $verification.RosterOk) { "Current" } else { "Needs refresh" }
                    if ([double]::IsPositiveInfinity([double]$snapshotAgeHours)) {
                        $snapshotNote = "Saved AutoFill age is unavailable."
                    }
                    elseif ([double]$snapshotAgeHours -lt 1.0) {
                        $snapshotMinutes = [math]::Max(0, [math]::Round(([double]$snapshotAgeHours * 60.0)))
                        $snapshotNote = "$snapshotMinutes min old; saved locally from the explicit AutoFill request."
                    }
                    else {
                        $snapshotHours = [math]::Round([double]$snapshotAgeHours, 1)
                        $snapshotNote = "$snapshotHours h old; saved locally from the explicit AutoFill request."
                    }

                    $weekText = [string]$lineupSnapshot.Week
                    if ([string]::IsNullOrWhiteSpace($weekText)) { $weekText = "Unknown week" } else { $weekText = "Week $weekText" }
                    $scoringText = [string]$lineupSnapshot.Scoring
                    if ([string]::IsNullOrWhiteSpace($scoringText)) { $scoringText = "Scoring not recorded" }
                    $frameValue = "$weekText | $scoringText"

                    $sourceText = [string]$lineupSnapshot.Source
                    if ([string]::IsNullOrWhiteSpace($sourceText)) { $sourceText = "Projection source not recorded" }
                    $seasonText = [string]$lineupSnapshot.Season
                    if ([string]::IsNullOrWhiteSpace($seasonText)) {
                        $frameNote = $sourceText
                    }
                    else {
                        $frameNote = "Season $seasonText; $sourceText"
                    }

                    switch ([string]$lineupSignalStatus) {
                        "EVIDENCE GAP" {
                            $coverageValue = "Incomplete"
                            $coverageNote = [string]$lineupSignalCopy
                        }
                        "REFRESH AUTOFILL" {
                            $coverageValue = "Needs refresh"
                            $coverageNote = "The saved projection frame is no longer current for this roster or target."
                        }
                        "AUTOFILL READY" {
                            $coverageValue = "Complete"
                            $coverageNote = "The saved AutoFill result contains the weekly projection evidence required for its lineup recommendation."
                        }
                        "NO CHANGES" {
                            $coverageValue = "Complete"
                            $coverageNote = "The saved AutoFill result contains the weekly projection evidence required to keep the current starters."
                        }
                        default {
                            $coverageValue = "Review"
                            $coverageNote = [string]$lineupSignalCopy
                        }
                    }
                }

                $primaryEvidenceHtml = @"
<div class="evidence-grid">
  <div class="evidence-card"><strong>Roster check</strong><div class="evidence-value">$(ConvertTo-HtmlText $evidenceRosterStatusText)</div><div class="evidence-note">$(ConvertTo-HtmlText $verification.Roster)</div></div>
  <div class="evidence-card"><strong>AutoFill snapshot</strong><div class="evidence-value">$(ConvertTo-HtmlText $snapshotValue)</div><div class="evidence-note">$(ConvertTo-HtmlText $snapshotNote)</div></div>
  <div class="evidence-card"><strong>Weekly frame</strong><div class="evidence-value">$(ConvertTo-HtmlText $frameValue)</div><div class="evidence-note">$(ConvertTo-HtmlText $frameNote)</div></div>
  <div class="evidence-card"><strong>Projection coverage</strong><div class="evidence-value">$(ConvertTo-HtmlText $coverageValue)</div><div class="evidence-note">$(ConvertTo-HtmlText $coverageNote)</div></div>
</div>
"@
            }
            "Trade" {
                $primaryEvidenceHtml = @"
<div class="evidence-grid">
  <div class="evidence-card"><strong>Roster check</strong><div class="evidence-value">$(ConvertTo-HtmlText $evidenceRosterStatusText)</div><div class="evidence-note">$(ConvertTo-HtmlText $verification.Roster)</div></div>
  <div class="evidence-card"><strong>Trade review</strong><div class="evidence-value">On demand</div><div class="evidence-note">No specific trade is being evaluated from the Dashboard.</div></div>
  <div class="evidence-card"><strong>Trade evidence</strong><div class="evidence-value">Not loaded</div><div class="evidence-note">Open Trade Lab with a specific deal or target before relying on trade evidence.</div></div>
  <div class="evidence-card"><strong>Decision scope</strong><div class="evidence-value">Specific deal required</div><div class="evidence-note">Butler will not invent a trade trust frame without an evaluated proposal.</div></div>
</div>
"@
            }
        }
    }
'@

$dashboardBlock = Replace-ExactlyOnce -Text $dashboardBlock -Old $evidenceDerivationAnchor.TrimEnd() -New $evidenceDerivation.TrimEnd() -Contract 'priority-aware evidence derivation'

$evidencePanelMarker = '<section class="panel"><div class="section-head"><div><div class="eyebrow">Evidence status</div><h2>Can I trust this decision frame?</h2></div></div>'
$evidencePanelStart = $dashboardBlock.IndexOf($evidencePanelMarker, [System.StringComparison]::Ordinal)
if ($evidencePanelStart -lt 0) {
    throw 'BF-813 BLOCKED: Command Center evidence panel start is missing.'
}
if ($dashboardBlock.IndexOf($evidencePanelMarker, $evidencePanelStart + $evidencePanelMarker.Length, [System.StringComparison]::Ordinal) -ge 0) {
    throw 'BF-813 BLOCKED: Command Center evidence panel start is ambiguous.'
}
$evidencePanelEnd = $dashboardBlock.IndexOf('<section class="panel">', $evidencePanelStart + $evidencePanelMarker.Length, [System.StringComparison]::Ordinal)
if ($evidencePanelEnd -le $evidencePanelStart) {
    throw 'BF-813 BLOCKED: Command Center evidence panel end is missing.'
}
$evidencePanelNew = '<section class="panel"><div class="section-head"><div><div class="eyebrow">Evidence status</div><h2>Can I trust this decision frame?</h2></div></div>$primaryEvidenceHtml</section>'
$dashboardBlock = $dashboardBlock.Substring(0, $evidencePanelStart) + $evidencePanelNew + $dashboardBlock.Substring($evidencePanelEnd)

$text = $text.Substring(0, $dashboardStart) + $dashboardBlock + $text.Substring($dashboardEnd)

if ($text -notmatch '<strong>AutoFill snapshot</strong>') {
    throw 'BF-813 BLOCKED: lineup AutoFill evidence card was not installed.'
}
if ($text -notmatch '<strong>Projection coverage</strong>') {
    throw 'BF-813 BLOCKED: lineup projection coverage card was not installed.'
}
if ($text -notmatch 'No specific trade is being evaluated from the Dashboard') {
    throw 'BF-813 BLOCKED: trade on-demand evidence contract is missing.'
}
if ($text -notmatch '\$primaryEvidenceHtml</section>') {
    throw 'BF-813 BLOCKED: priority-aware evidence panel binding was not installed.'
}
if ($text -match 'FantasyProsApiClient|Invoke-RestMethod|BUTLER_FANTASYPROS_API_KEY') {
    throw 'BF-813 BLOCKED: evidence presentation introduced provider access or credentials.'
}

[System.IO.File]::WriteAllText($DashboardPath, $text, [System.Text.UTF8Encoding]::new($false))
