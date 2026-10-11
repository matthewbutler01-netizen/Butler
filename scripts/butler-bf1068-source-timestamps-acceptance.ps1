Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$path = Join-Path $PSScriptRoot 'butler-app-bf1021-v04-start-sit-assistant-transform.ps1'
$source = [IO.File]::ReadAllText($path)
$tokens = $null
$parseErrors = $null
[void][Management.Automation.Language.Parser]::ParseFile($path, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) { throw 'BF-1068 BLOCKED: source transform parse error.' }

$open = $source.IndexOf('$timestampParser = @' + [char]39, [StringComparison]::Ordinal)
if ($open -lt 0) { throw 'BF-1068 BLOCKED: timestamp source parser missing.' }
$start = $source.IndexOf([char]10, $open)
if ($start -lt 0) { throw 'BF-1068 BLOCKED: timestamp parser has no opening newline.' }
$start++
$closing = [regex]::Match($source.Substring($start), "(?m)^'@\s*$")
if (-not $closing.Success) { throw 'BF-1068 BLOCKED: timestamp parser has no end.' }
$parser = $source.Substring($start, $closing.Index)
if ([regex]::Matches($source, 'ProjectionFetchedAt = \$projectionFetched').Count -ne 1 -or
    [regex]::Matches($source, 'SwapStatusFetchedAt = \$swapStatusFetched').Count -ne 1) {
    throw 'BF-1068 BLOCKED: timestamps not mapped exactly once into the ready evidence view.'
}

function Invoke-SourceProof {
    param([string]$Payload)
    $Text = $Payload
    $projectionFetched = ''
    $swapStatusFetched = ''
    . ([scriptblock]::Create($parser))
    return [pscustomobject]@{ Projection = $projectionFetched; Status = $swapStatusFetched }
}

$verified = Invoke-SourceProof -Payload ("Projection retrieved at (UTC): 2026-10-10T08:00:00.123456789Z" +
    "`nSwap player status fetched at (UTC): 2026-10-10T08:01:00Z")
if ($verified.Projection -cne '2026-10-10T08:00:00.123456789Z' -or
    $verified.Status -cne '2026-10-10T08:01:00Z') {
    throw 'BF-1068 BLOCKED: exact UTC fetch times were not preserved.'
}

$unrecorded = Invoke-SourceProof -Payload ("Projection retrieved at (UTC): 2026-10-10T08:00:00Z" +
    "`nSwap player status fetched at (UTC): UNVERIFIED")
if ($unrecorded.Status -cne 'UNVERIFIED') {
    throw 'BF-1068 BLOCKED: unavailable status timestamp was fabricated.'
}
$legacy = Invoke-SourceProof -Payload 'State: READY'
if ($legacy.Projection -cne 'UNVERIFIED' -or $legacy.Status -cne 'UNVERIFIED') {
    throw 'BF-1068 BLOCKED: legacy fixture missing source times was deemed verified.'
}

foreach ($bad in @(
    'Projection retrieved at (UTC): 2026-10-10T08:00:00Z',
    'Swap player status fetched at (UTC): 2026-10-10T08:00:00Z',
    "Projection retrieved at (UTC): yesterday`nSwap player status fetched at (UTC): UNVERIFIED",
    "Projection retrieved at (UTC): 2026-13-10T08:00:00Z`nSwap player status fetched at (UTC): UNVERIFIED",
    "Projection retrieved at (UTC): 2026-02-30T08:00:00Z`nSwap player status fetched at (UTC): UNVERIFIED",
    "Projection retrieved at (UTC): 2025-02-29T08:00:00Z`nSwap player status fetched at (UTC): UNVERIFIED",
    "Projection retrieved at (UTC): 2026-10-10T08:00:00Z`nSwap player status fetched at (UTC): sometime",
    "Projection retrieved at (UTC): 2026-10-10T08:00:00Z`nProjection retrieved at (UTC): 2026-10-10T08:00:00Z`nSwap player status fetched at (UTC): UNVERIFIED",
    "Projection retrieved at (UTC): 2026-10-10T08:00:00Z`nSwap player status fetched at (UTC): UNVERIFIED`nSwap player status fetched at (UTC): 2026-10-10T08:00:00Z"
)) {
    $blocked = $false
    try { $ignored = Invoke-SourceProof -Payload $bad } catch { $blocked = $true }
    if (-not $blocked) { throw 'BF-1068 BLOCKED: incomplete, duplicate, or forged source time was accepted.' }
}
if ($parser -match 'Invoke-RestMethod|Invoke-WebRequest|Method\s*=\s*POST|submitTransaction|setFaab') {
    throw 'BF-1068 BLOCKED: timestamp presentation initiated a provider write.'
}
Write-Host 'BF-1068 EXACT SOURCE FETCH TIMESTAMPS: PASS'
Write-Host 'Coverage: valid UTC fractional timestamps, unverified/legacy observation, duplicate/partial/forged fields, no provider writes.'
