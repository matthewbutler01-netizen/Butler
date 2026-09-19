param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $DashboardPath -PathType Leaf)) {
    throw "BF-809 BLOCKED: staged Butler dashboard not found at $DashboardPath"
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
        throw "BF-809 BLOCKED: $Contract anchor is missing."
    }
    $second = $Text.IndexOf($Old, $first + $Old.Length, [System.StringComparison]::Ordinal)
    if ($second -ge 0) {
        throw "BF-809 BLOCKED: $Contract anchor is ambiguous."
    }
    return $Text.Substring(0, $first) + $New + $Text.Substring($first + $Old.Length)
}

$text = [System.IO.File]::ReadAllText($DashboardPath)
$readerStart = $text.IndexOf('function Get-Bf808AutoFillSnapshot {', [System.StringComparison]::Ordinal)
$dashboardStart = $text.IndexOf('function ConvertTo-DashboardHtml {', $readerStart, [System.StringComparison]::Ordinal)
if ($readerStart -lt 0 -or $dashboardStart -le $readerStart) {
    throw 'BF-809 BLOCKED: BF-808 snapshot reader boundary is missing.'
}

$readerReplacement = @'
function ConvertTo-Bf809AutoFillTargetKey {
    param([AllowNull()][string]$Value)

    if ([string]::IsNullOrWhiteSpace($Value)) {
        return ''
    }

    $normalized = [regex]::Replace($Value.Trim(), '\s*\|\s*', '|')
    $normalized = [regex]::Replace($normalized, '\s+', ' ')
    return $normalized.ToLowerInvariant()
}

function Read-Bf809AutoFillSnapshotFile {
    param([Parameter(Mandatory = $true)][string]$Path)

    try {
        $raw = [System.IO.File]::ReadAllText($Path)
        if ([string]::IsNullOrWhiteSpace($raw)) {
            return $null
        }

        $snapshot = $raw | ConvertFrom-Json
        foreach ($name in @('Schema', 'LeagueId', 'TargetHuman', 'Ready', 'Reason', 'Source', 'Season', 'Week', 'Scoring', 'CurrentTotal', 'RecommendedTotal', 'Gain', 'ChangedCount', 'AssignmentCount', 'GeneratedUtc')) {
            if ($null -eq $snapshot.PSObject.Properties[$name]) {
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

function Get-Bf809AutoFillSnapshot {
    param(
        [Parameter(Mandatory = $true)][string]$LeagueKey,
        [Parameter(Mandatory = $true)][string]$TargetHuman
    )

    $directPath = Get-Bf808AutoFillSnapshotPath -LeagueKey $LeagueKey
    $targetKey = ConvertTo-Bf809AutoFillTargetKey -Value $TargetHuman

    # BF-858: BF-808 persists the normal snapshot directly at <league-key>.json.
    # Validate that exact file first so the common Dashboard path does not enumerate
    # and sort every historical/fallback JSON file on every render.
    if (Test-Path -LiteralPath $directPath -PathType Leaf) {
        $directSnapshot = Read-Bf809AutoFillSnapshotFile -Path $directPath
        if ($null -ne $directSnapshot) {
            $directLeagueMatches = [string]$directSnapshot.LeagueId -ceq $LeagueKey
            $directTargetKey = ConvertTo-Bf809AutoFillTargetKey -Value ([string]$directSnapshot.TargetHuman)
            $directTargetMatches = -not [string]::IsNullOrWhiteSpace($targetKey) -and $directTargetKey -ceq $targetKey
            if ($directLeagueMatches -or $directTargetMatches) {
                return $directSnapshot
            }
        }
    }

    # Preserve BF-809 recovery semantics only when the exact file is absent,
    # malformed, stale-by-identity, or otherwise unusable.
    $directory = Split-Path -Parent $directPath
    if (Test-Path -LiteralPath $directory -PathType Container) {
        foreach ($candidate in @(Get-ChildItem -LiteralPath $directory -Filter '*.json' -File -ErrorAction SilentlyContinue | Sort-Object LastWriteTimeUtc -Descending)) {
            if ([string]::Equals($candidate.FullName, $directPath, [System.StringComparison]::OrdinalIgnoreCase)) {
                continue
            }

            $snapshot = Read-Bf809AutoFillSnapshotFile -Path $candidate.FullName
            if ($null -eq $snapshot) {
                continue
            }

            $leagueMatches = [string]$snapshot.LeagueId -ceq $LeagueKey
            $snapshotTargetKey = ConvertTo-Bf809AutoFillTargetKey -Value ([string]$snapshot.TargetHuman)
            $targetMatches = -not [string]::IsNullOrWhiteSpace($targetKey) -and $snapshotTargetKey -ceq $targetKey
            if ($leagueMatches -or $targetMatches) {
                return $snapshot
            }
        }
    }

    return $null
}

'@
$text = $text.Substring(0, $readerStart) + $readerReplacement + $text.Substring($dashboardStart)

$readerCallOld = '$lineupSnapshot = Get-Bf808AutoFillSnapshot -LeagueKey ([string]$LeagueId)'
$readerCallNew = '$lineupSnapshot = Get-Bf809AutoFillSnapshot -LeagueKey ([string]$LeagueId) -TargetHuman ([string]$target)'
$text = Replace-ExactlyOnce -Text $text -Old $readerCallOld -New $readerCallNew -Contract 'BF-808 Command Center snapshot lookup'

$targetMatchOld = '$snapshotTargetMatches = ([string]$lineupSnapshot.LeagueId -ceq [string]$LeagueId) -and ([string]$lineupSnapshot.TargetHuman -ceq [string]$target)'
$targetMatchNew = @'
$currentTargetKey = ConvertTo-Bf809AutoFillTargetKey -Value ([string]$target)
        $snapshotTargetKey = ConvertTo-Bf809AutoFillTargetKey -Value ([string]$lineupSnapshot.TargetHuman)
        $snapshotTargetMatches = -not [string]::IsNullOrWhiteSpace($currentTargetKey) -and $snapshotTargetKey -ceq $currentTargetKey
'@
$text = Replace-ExactlyOnce -Text $text -Old $targetMatchOld -New $targetMatchNew.TrimEnd() -Contract 'BF-808 target identity comparison'

if ($text -notmatch 'Get-Bf809AutoFillSnapshot') {
    throw 'BF-809 BLOCKED: hardened snapshot reader was not installed.'
}
if ($text -match 'function Get-Bf808AutoFillSnapshot \{') {
    throw 'BF-809 BLOCKED: brittle BF-808 snapshot reader still remains.'
}
if (-not $text.Contains('Get-ChildItem -LiteralPath $directory -Filter ''*.json''')) {
    throw 'BF-809 BLOCKED: fallback snapshot discovery was not installed.'
}

[System.IO.File]::WriteAllText($DashboardPath, $text, [System.Text.UTF8Encoding]::new($false))
