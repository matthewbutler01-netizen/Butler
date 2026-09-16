param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-808 BLOCKED: staged Butler core not found at $CorePath"
}
if (-not (Test-Path -LiteralPath $DashboardPath -PathType Leaf)) {
    throw "BF-808 BLOCKED: staged Butler dashboard not found at $DashboardPath"
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
        throw "BF-808 BLOCKED: $Contract anchor is missing."
    }
    $second = $Text.IndexOf($Old, $first + $Old.Length, [System.StringComparison]::Ordinal)
    if ($second -ge 0) {
        throw "BF-808 BLOCKED: $Contract anchor is ambiguous."
    }
    return $Text.Substring(0, $first) + $New + $Text.Substring($first + $Old.Length)
}

$core = [System.IO.File]::ReadAllText($CorePath)
$coreTeamAnchor = 'function ConvertTo-TeamHtml {'
$coreSnapshotHelpers = @'
function Get-Bf808AutoFillSnapshotPath {
    param([Parameter(Mandatory = $true)][string]$LeagueKey)

    $root = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)
    if ([string]::IsNullOrWhiteSpace($root)) {
        $root = [string]$env:LOCALAPPDATA
    }
    if ([string]::IsNullOrWhiteSpace($root)) {
        $root = [System.IO.Path]::GetTempPath()
    }

    $safeLeagueKey = [regex]::Replace($LeagueKey, '[^A-Za-z0-9._-]', '_')
    if ([string]::IsNullOrWhiteSpace($safeLeagueKey)) {
        $safeLeagueKey = 'league'
    }
    $directory = Join-Path $root 'Butler\autofill-preview'
    return Join-Path $directory ($safeLeagueKey + '.json')
}

function Save-Bf808AutoFillSnapshot {
    param(
        [Parameter(Mandatory = $true)]$AutoFill,
        [Parameter(Mandatory = $true)]$RosterView
    )

    $reason = [string]$AutoFill.Reason
    $providerKey = [string]$env:BUTLER_FANTASYPROS_API_KEY
    if (-not [string]::IsNullOrWhiteSpace($providerKey) -and $reason.Contains($providerKey)) {
        $reason = $reason.Replace($providerKey, '[REDACTED]')
    }

    $changedCount = @($AutoFill.Assignments | Where-Object { $_.Changed }).Count
    $targetHuman = "$($RosterView.LeagueName) | $($RosterView.TeamName) | roster $($RosterView.RosterId)"
    $snapshot = [ordered]@{
        Schema = 'BF-808-1'
        LeagueId = [string]$LeagueId
        ButlerTeamId = [string]$RosterView.ButlerTeamId
        SleeperLeagueId = [string]$RosterView.SleeperLeagueId
        RosterId = [string]$RosterView.RosterId
        TargetHuman = $targetHuman
        Ready = [bool]$AutoFill.Ready
        Reason = $reason
        Source = [string]$AutoFill.Source
        Season = [string]$AutoFill.Season
        Week = [string]$AutoFill.Week
        Scoring = [string]$AutoFill.Scoring
        CurrentTotal = [string]$AutoFill.CurrentTotal
        RecommendedTotal = [string]$AutoFill.RecommendedTotal
        Gain = [string]$AutoFill.Gain
        ChangedCount = [int]$changedCount
        AssignmentCount = [int](@($AutoFill.Assignments).Count)
        GeneratedUtc = [DateTimeOffset]::UtcNow.ToString('o')
    }

    $json = $snapshot | ConvertTo-Json -Depth 4
    if (-not [string]::IsNullOrWhiteSpace($providerKey) -and $json.Contains($providerKey)) {
        throw 'BF-808 BLOCKED: provider credential detected in AutoFill snapshot payload.'
    }

    $path = Get-Bf808AutoFillSnapshotPath -LeagueKey ([string]$LeagueId)
    $directory = Split-Path -Parent $path
    [System.IO.Directory]::CreateDirectory($directory) | Out-Null
    $tempPath = $path + '.' + [Guid]::NewGuid().ToString('N') + '.tmp'
    try {
        [System.IO.File]::WriteAllText($tempPath, $json, [System.Text.UTF8Encoding]::new($false))
        Move-Item -LiteralPath $tempPath -Destination $path -Force
    }
    finally {
        if (Test-Path -LiteralPath $tempPath -PathType Leaf) {
            Remove-Item -LiteralPath $tempPath -Force -ErrorAction SilentlyContinue
        }
    }
}

function ConvertTo-TeamHtml {
'@
$core = Replace-ExactlyOnce -Text $core -Old $coreTeamAnchor -New $coreSnapshotHelpers -Contract 'core AutoFill snapshot helper insertion'

$autoFillRouteAnchor = @'
                    $autoFill = ConvertTo-AutoFillView -Text (Get-TeamEvidenceBundleSection -Text $bundleText -Name "AUTOFILL")
                    $html = ConvertTo-TeamHtml -Roster $rosterView -Context $context -Strength $strength -Pressure $pressure -Posture $posture -Capital $capital -AutoFill $autoFill
'@
$autoFillRouteReplacement = @'
                    $autoFill = ConvertTo-AutoFillView -Text (Get-TeamEvidenceBundleSection -Text $bundleText -Name "AUTOFILL")
                    try {
                        Save-Bf808AutoFillSnapshot -AutoFill $autoFill -RosterView $rosterView
                    }
                    catch {
                        Write-Warning 'BF-808 AutoFill snapshot was not saved; the read-only AutoFill result remains available on My Team.'
                    }
                    $html = ConvertTo-TeamHtml -Roster $rosterView -Context $context -Strength $strength -Pressure $pressure -Posture $posture -Capital $capital -AutoFill $autoFill
'@
$core = Replace-ExactlyOnce -Text $core -Old $autoFillRouteAnchor -New $autoFillRouteReplacement -Contract 'explicit AutoFill snapshot persistence'
[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))

$dashboard = [System.IO.File]::ReadAllText($DashboardPath)
$dashboardFunctionAnchor = 'function ConvertTo-DashboardHtml {'
$dashboardSnapshotHelpers = @'
function Get-Bf808AutoFillSnapshotPath {
    param([Parameter(Mandatory = $true)][string]$LeagueKey)

    $root = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)
    if ([string]::IsNullOrWhiteSpace($root)) {
        $root = [string]$env:LOCALAPPDATA
    }
    if ([string]::IsNullOrWhiteSpace($root)) {
        $root = [System.IO.Path]::GetTempPath()
    }

    $safeLeagueKey = [regex]::Replace($LeagueKey, '[^A-Za-z0-9._-]', '_')
    if ([string]::IsNullOrWhiteSpace($safeLeagueKey)) {
        $safeLeagueKey = 'league'
    }
    $directory = Join-Path $root 'Butler\autofill-preview'
    return Join-Path $directory ($safeLeagueKey + '.json')
}

function Get-Bf808AutoFillSnapshot {
    param([Parameter(Mandatory = $true)][string]$LeagueKey)

    $path = Get-Bf808AutoFillSnapshotPath -LeagueKey $LeagueKey
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        return $null
    }

    try {
        $raw = [System.IO.File]::ReadAllText($path)
        if ([string]::IsNullOrWhiteSpace($raw)) {
            return $null
        }
        $snapshot = $raw | ConvertFrom-Json
        $required = @('Schema', 'LeagueId', 'TargetHuman', 'Ready', 'Reason', 'Source', 'Season', 'Week', 'Scoring', 'CurrentTotal', 'RecommendedTotal', 'Gain', 'ChangedCount', 'AssignmentCount', 'GeneratedUtc')
        $propertyNames = @($snapshot.PSObject.Properties.Name)
        foreach ($name in $required) {
            if ($propertyNames -cnotcontains $name) {
                return $null
            }
        }
        if ([string]$snapshot.Schema -cne 'BF-808-1') {
            return $null
        }
        return $snapshot
    }
    catch {
        return $null
    }
}

function ConvertTo-DashboardHtml {
'@
$dashboard = Replace-ExactlyOnce -Text $dashboard -Old $dashboardFunctionAnchor -New $dashboardSnapshotHelpers -Contract 'dashboard AutoFill snapshot reader insertion'

$tradeAnchor = '    $tradeSignalClass = "done"'
$lineupSnapshotOverride = @'
    $tradeSignalClass = "done"

    $lineupSnapshotAttentionGroup = ""
    $lineupSnapshot = Get-Bf808AutoFillSnapshot -LeagueKey ([string]$LeagueId)
    if ($null -ne $lineupSnapshot) {
        $snapshotGenerated = [DateTimeOffset]::MinValue
        $generatedOk = [DateTimeOffset]::TryParse([string]$lineupSnapshot.GeneratedUtc, [ref]$snapshotGenerated)
        $snapshotAgeHours = if ($generatedOk) { ([DateTimeOffset]::UtcNow - $snapshotGenerated).TotalHours } else { [double]::PositiveInfinity }
        $snapshotFresh = $generatedOk -and $snapshotAgeHours -ge -0.1 -and $snapshotAgeHours -le 6.0
        $snapshotTargetMatches = ([string]$lineupSnapshot.LeagueId -ceq [string]$LeagueId) -and ([string]$lineupSnapshot.TargetHuman -ceq [string]$target)

        if (-not $snapshotFresh -or -not $snapshotTargetMatches -or -not $verification.RosterOk) {
            $lineupSignalTitle = "Refresh this week's AutoFill"
            $lineupSignalCopy = "Butler has a saved AutoFill result, but it is no longer current for this roster and evidence frame. Run AutoFill again from My Team."
            $lineupSignalStatus = "REFRESH AUTOFILL"
            $lineupSignalClass = "warn"
            $lineupSnapshotAttentionGroup = "attention"
        }
        elseif (-not [bool]$lineupSnapshot.Ready) {
            $snapshotReason = [string]$lineupSnapshot.Reason
            if ([string]::IsNullOrWhiteSpace($snapshotReason)) {
                $snapshotReason = "Butler could not prove a complete weekly lineup recommendation from the latest AutoFill evidence."
            }
            $lineupSignalTitle = "Latest AutoFill hit an evidence gap"
            $lineupSignalCopy = $snapshotReason
            $lineupSignalStatus = "EVIDENCE GAP"
            $lineupSignalClass = "warn"
            $lineupSnapshotAttentionGroup = "attention"
        }
        elseif ([int]$lineupSnapshot.ChangedCount -gt 0) {
            $changeWord = if ([int]$lineupSnapshot.ChangedCount -eq 1) { "change" } else { "changes" }
            $lineupSignalTitle = "Latest AutoFill recommends $($lineupSnapshot.ChangedCount) lineup $changeWord"
            $lineupSignalCopy = "Week $($lineupSnapshot.Week), $($lineupSnapshot.Scoring): current projection $($lineupSnapshot.CurrentTotal) pts -> AutoFill $($lineupSnapshot.RecommendedTotal) pts; projected change $($lineupSnapshot.Gain) pts. Projection source: $($lineupSnapshot.Source)."
            $lineupSignalStatus = "AUTOFILL READY"
            $lineupSignalClass = "good"
            $lineupSnapshotAttentionGroup = "attention"
        }
        else {
            $lineupSignalTitle = "Latest AutoFill found no lineup changes"
            $lineupSignalCopy = "Week $($lineupSnapshot.Week), $($lineupSnapshot.Scoring): the latest proven AutoFill keeps the current starters. Projection source: $($lineupSnapshot.Source)."
            $lineupSignalStatus = "NO CHANGES"
            $lineupSignalClass = "done"
            $lineupSnapshotAttentionGroup = "neutral"
        }
    }
'@
$dashboard = Replace-ExactlyOnce -Text $dashboard -Old $tradeAnchor -New $lineupSnapshotOverride -Contract 'Command Center lineup snapshot override'

$lineupOrderingOld = '    $lineupAttentionGroup = if ($verification.RosterOk) { "review" } else { "attention" }'
$lineupOrderingNew = @'
    $lineupAttentionGroup = if (-not [string]::IsNullOrWhiteSpace($lineupSnapshotAttentionGroup)) {
        $lineupSnapshotAttentionGroup
    }
    elseif ($verification.RosterOk) {
        "review"
    }
    else {
        "attention"
    }
'@
$dashboard = Replace-ExactlyOnce -Text $dashboard -Old $lineupOrderingOld -New $lineupOrderingNew.TrimEnd() -Contract 'BF-807 lineup attention integration'

[System.IO.File]::WriteAllText($DashboardPath, $dashboard, [System.Text.UTF8Encoding]::new($false))
