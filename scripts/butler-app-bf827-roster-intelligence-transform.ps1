param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-827 BLOCKED: staged Butler core not found at $CorePath"
}

$core = [System.IO.File]::ReadAllText($CorePath)

$oldSummary = @'
    $rankText = if ($Context.Rank -eq "-") { "Unavailable" } else { "#$($Context.Rank)" }
    $strengthTier = if ($Strength.Available) { $Strength.Tier } else { "Unavailable" }
    $postureText = if ($Posture.Available) { $Posture.Posture } else { "Unavailable" }
    $capitalTier = if ($Capital.Available) { $Capital.Tier } else { "Unavailable" }
'@

$newSummary = @'
    $rankText = if ($Context.Rank -eq "-") { "Rank pending" } else { "#$($Context.Rank)" }
    $rankEvidence = if ($Context.Rank -eq "-") {
        "League comparison needs complete governed franchise-value coverage before Butler will rank the roster."
    }
    else {
        "Compared with $($Roster.LeagueName) franchises by Butler governed total franchise value."
    }

    $strengthTier = if ($Strength.Available) { $Strength.Tier } else { "Coverage needed" }
    $positionTierParts = @($Pressure | Where-Object { $_.Available } | ForEach-Object { "$($_.Position) $($_.Tier)" })
    $positionTierText = if ($positionTierParts.Count -gt 0) { $positionTierParts -join ', ' } else { "position tiers pending" }
    if ($Strength.Available) {
        $strengthEvidence = "Starter value $($Strength.StarterValue); roster player value $($Strength.TotalPlayerValue); value coverage $($Strength.Coverage)%. Position tiers: $positionTierText."
    }
    else {
        $strengthReason = if ($null -ne $Strength.PSObject.Properties['Reason'] -and -not [string]::IsNullOrWhiteSpace([string]$Strength.Reason)) { [string]$Strength.Reason } else { "Complete current value coverage is required." }
        $strengthEvidence = "$strengthReason Position tiers: $positionTierText."
    }

    $postureText = if ($Posture.Available) { $Posture.Posture } else { "Direction pending" }
    $postureEvidence = if ($Posture.Available) {
        "Competitive $($Posture.Competitive); roster $($Posture.Roster). Team direction is the governed posture from those two dimensions."
    }
    else {
        "Complete governed competitive and roster posture evidence is required; Butler will not infer a contend, hold, or build direction."
    }

    $capitalTier = if ($Capital.Available) { $Capital.Tier } else { "Capital pending" }
    $capitalEvidence = if ($Capital.Available) {
        "Future-pick value $($Capital.Value); value coverage $($Capital.Coverage)%."
    }
    else {
        $capitalReason = if ($null -ne $Capital.PSObject.Properties['Reason'] -and -not [string]::IsNullOrWhiteSpace([string]$Capital.Reason)) { [string]$Capital.Reason } else { "Future-pick evidence is incomplete." }
        $capitalReason
    }
'@

$summaryCount = [regex]::Matches($core, [regex]::Escape($oldSummary.Trim())).Count
if ($summaryCount -ne 1) {
    throw "BF-827 BLOCKED: My Team summary contract expected one match, found $summaryCount."
}
$core = $core.Replace($oldSummary.Trim(), $newSummary.Trim())

$oldMetrics = '<div class="manager-metrics"><div class="metric-card"><span class="metric-label">Franchise rank</span><span class="metric-value">$(ConvertTo-HtmlText $rankText)</span></div><div class="metric-card"><span class="metric-label">Roster strength</span><span class="metric-value">$(ConvertTo-HtmlText $strengthTier)</span></div><div class="metric-card"><span class="metric-label">Team direction</span><span class="metric-value">$(ConvertTo-HtmlText $postureText)</span></div><div class="metric-card"><span class="metric-label">Draft capital</span><span class="metric-value">$(ConvertTo-HtmlText $capitalTier)</span></div></div>'
$newMetrics = '<div class="manager-metrics"><div class="metric-card"><span class="metric-label">Franchise rank</span><span class="metric-value">$(ConvertTo-HtmlText $rankText)</span><div class="meta">$(ConvertTo-HtmlText $rankEvidence)</div></div><div class="metric-card"><span class="metric-label">Roster strength</span><span class="metric-value">$(ConvertTo-HtmlText $strengthTier)</span><div class="meta">$(ConvertTo-HtmlText $strengthEvidence)</div></div><div class="metric-card"><span class="metric-label">Team direction</span><span class="metric-value">$(ConvertTo-HtmlText $postureText)</span><div class="meta">$(ConvertTo-HtmlText $postureEvidence)</div></div><div class="metric-card"><span class="metric-label">Draft capital</span><span class="metric-value">$(ConvertTo-HtmlText $capitalTier)</span><div class="meta">$(ConvertTo-HtmlText $capitalEvidence)</div></div></div><details><summary>Roster intelligence evidence</summary><div class="technical">Franchise value $(ConvertTo-HtmlText $Context.Total) &middot; player value $(ConvertTo-HtmlText $Context.Players) &middot; pick value $(ConvertTo-HtmlText $Context.Picks) &middot; franchise coverage $(ConvertTo-HtmlText $Context.Coverage)% &middot; movement $(ConvertTo-HtmlText $Context.Movement) &middot; movement coverage $(ConvertTo-HtmlText $Context.MovementCoverage)%</div></details>'

$metricsCount = [regex]::Matches($core, [regex]::Escape($oldMetrics)).Count
if ($metricsCount -ne 1) {
    throw "BF-827 BLOCKED: My Team manager-metrics contract expected one match, found $metricsCount."
}
$core = $core.Replace($oldMetrics, $newMetrics)

foreach ($required in @(
    'Compared with $($Roster.LeagueName) franchises by Butler governed total franchise value.',
    'Starter value $($Strength.StarterValue); roster player value $($Strength.TotalPlayerValue)',
    'Position tiers: $positionTierText',
    'Complete governed competitive and roster posture evidence is required',
    'Roster intelligence evidence',
    'Rank pending',
    'Coverage needed',
    'Direction pending'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-827 BLOCKED: required roster-intelligence marker is missing: $required"
    }
}

if ($core -match 'BF-827.*Method = "POST"|BF-827.*Invoke-RestMethod|BF-827.*SleeperClient') {
    throw 'BF-827 BLOCKED: roster-intelligence presentation introduced a write or direct provider path.'
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))
