param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-902 BLOCKED: staged Butler core not found at $CorePath"
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
        throw "BF-902 BLOCKED: $Contract anchor is missing."
    }
    $second = $Text.IndexOf($Old, $first + $Old.Length, [System.StringComparison]::Ordinal)
    if ($second -ge 0) {
        throw "BF-902 BLOCKED: $Contract anchor is ambiguous."
    }
    return $Text.Substring(0, $first) + $New + $Text.Substring($first + $Old.Length)
}

$core = [System.IO.File]::ReadAllText($CorePath)

$idleShapeOld = @'
        Gain = ''
        Assignments = @()
'@
$idleShapeNew = @'
        Gain = ''
        ProjectionCoverage = 'NONE'
        ProjectionHolds = @()
        Assignments = @()
'@
$core = Replace-ExactlyOnce -Text $core -Old $idleShapeOld -New $idleShapeNew -Contract 'idle AutoFill projection-hold shape'

$unavailableShapeOld = @'
            Gain = ''
            Assignments = @()
'@
$unavailableShapeNew = @'
            Gain = ''
            ProjectionCoverage = 'NONE'
            ProjectionHolds = @()
            Assignments = @()
'@
$core = Replace-ExactlyOnce -Text $core -Old $unavailableShapeOld -New $unavailableShapeNew -Contract 'unavailable AutoFill projection-hold shape'

$sourceOld = @'
    $source = [regex]::Match($Text, '(?m)^Projection source:\s+(?<value>.+?)\s*$')
    $sourceSurface = [regex]::Match($Text, '(?m)^Projection source surface:\s+(?<value>.+?)\s*$')
    $current = [regex]::Match($Text, '(?m)^Current projected starter total:\s+(?<value>-?\d+(?:\.\d+)?)\s*$')
'@
$sourceNew = @'
    $source = [regex]::Match($Text, '(?m)^Projection source:\s+(?<value>.+?)\s*$')
    $sourceSurface = [regex]::Match($Text, '(?m)^Projection source surface:\s+(?<value>.+?)\s*$')
    $coverage = [regex]::Match($Text, '(?m)^Projection coverage:\s+(?<value>FULL|PARTIAL)\s*$')
    $current = [regex]::Match($Text, '(?m)^Current projected starter total:\s+(?<value>-?\d+(?:\.\d+)?)\s*$')
'@
$core = Replace-ExactlyOnce -Text $core -Old $sourceOld -New $sourceNew -Contract 'projection coverage parser'

$summaryOld = @'
    if (-not $source.Success -or -not $sourceSurface.Success -or -not $current.Success -or
        -not $recommended.Success -or -not $gain.Success) {
        throw 'BF-825 BLOCKED: ready AutoFill bundle section is missing projection summary fields.'
    }
'@
$summaryNew = @'
    if (-not $source.Success -or -not $sourceSurface.Success -or -not $coverage.Success -or -not $current.Success -or
        -not $recommended.Success -or -not $gain.Success) {
        throw 'BF-902 BLOCKED: ready AutoFill bundle section is missing projection summary/coverage fields.'
    }
'@
$core = Replace-ExactlyOnce -Text $core -Old $summaryOld -New $summaryNew -Contract 'projection coverage requirement'

$varsOld = @'
    $promotions = @()
    $availabilityExclusions = @()
    $mode = ''
'@
$varsNew = @'
    $promotions = @()
    $availabilityExclusions = @()
    $projectionHolds = @()
    $mode = ''
'@
$core = Replace-ExactlyOnce -Text $core -Old $varsOld -New $varsNew -Contract 'projection hold collection'

$modeOld = @'
        if ($line -ceq 'Promotions to starting lineup:') { $mode = 'PROMOTE'; continue }
        if ($line -ceq 'Availability exclusions:') { $mode = 'AVAILABILITY'; continue }
'@
$modeNew = @'
        if ($line -ceq 'Promotions to starting lineup:') { $mode = 'PROMOTE'; continue }
        if ($line -ceq 'Availability exclusions:') { $mode = 'AVAILABILITY'; continue }
        if ($line -ceq 'Projection holds:') { $mode = 'HOLD'; continue }
'@
$core = Replace-ExactlyOnce -Text $core -Old $modeOld -New $modeNew -Contract 'projection hold mode'

$availabilityOld = @'
        if ($mode -ceq 'AVAILABILITY' -and $line -match '^\s{2}(?<name>.+?)\s+\[(?<id>[^\]]+)\]\s+\|\s+status=(?<status>.*?)\s+\|\s+injury_status=(?<injury>.*?)\s+\|\s+reason=(?<reason>.+?)\s*$') {
            $availabilityExclusions += [pscustomobject]@{
                Name = $Matches['name'].Trim()
                Id = $Matches['id'].Trim()
                Status = $Matches['status'].Trim()
                InjuryStatus = $Matches['injury'].Trim()
                Reason = $Matches['reason'].Trim()
            }
            continue
        }
'@
$availabilityNew = @'
        if ($mode -ceq 'AVAILABILITY' -and $line -match '^\s{2}(?<name>.+?)\s+\[(?<id>[^\]]+)\]\s+\|\s+status=(?<status>.*?)\s+\|\s+injury_status=(?<injury>.*?)\s+\|\s+reason=(?<reason>.+?)\s*$') {
            $availabilityExclusions += [pscustomobject]@{
                Name = $Matches['name'].Trim()
                Id = $Matches['id'].Trim()
                Status = $Matches['status'].Trim()
                InjuryStatus = $Matches['injury'].Trim()
                Reason = $Matches['reason'].Trim()
            }
            continue
        }
        if ($mode -ceq 'HOLD' -and $line -match '^\s{2}(?<name>.+?)\s+\[(?<id>[^\]]+)\]\s+\|\s+roster_slot=(?<rosterSlot>\S+)\s+\|\s+lineup_slot=(?<lineupSlot>.*?)\s+\|\s+status=(?<status>.*?)\s+\|\s+injury_status=(?<injury>.*?)\s+\|\s+reason=(?<reason>.+?)\s*$') {
            $projectionHolds += [pscustomobject]@{
                Name = $Matches['name'].Trim()
                Id = $Matches['id'].Trim()
                RosterSlot = $Matches['rosterSlot'].Trim()
                LineupSlot = $Matches['lineupSlot'].Trim()
                Status = $Matches['status'].Trim()
                InjuryStatus = $Matches['injury'].Trim()
                Reason = $Matches['reason'].Trim()
            }
            continue
        }
'@
$core = Replace-ExactlyOnce -Text $core -Old $availabilityOld -New $availabilityNew -Contract 'projection hold parser'

$returnOld = @'
        Gain = $gain.Groups['value'].Value
        Assignments = @($assignments | Sort-Object Ordinal)
        BenchMoves = @($benchMoves)
        Promotions = @($promotions)
        AvailabilityExclusions = @($availabilityExclusions)
'@
$returnNew = @'
        Gain = $gain.Groups['value'].Value
        ProjectionCoverage = $coverage.Groups['value'].Value
        ProjectionHolds = @($projectionHolds)
        Assignments = @($assignments | Sort-Object Ordinal)
        BenchMoves = @($benchMoves)
        Promotions = @($promotions)
        AvailabilityExclusions = @($availabilityExclusions)
'@
$core = Replace-ExactlyOnce -Text $core -Old $returnOld -New $returnNew -Contract 'projection hold result shape'

if ($core -notmatch 'ProjectionCoverage' -or $core -notmatch 'ProjectionHolds') {
    throw 'BF-902 BLOCKED: projection coverage/hold fields are missing after transform.'
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($CorePath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $summary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-902 BLOCKED: generated staged core failed PowerShell parse: $summary"
}
