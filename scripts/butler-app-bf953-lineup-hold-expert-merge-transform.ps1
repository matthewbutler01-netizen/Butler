param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-953 BLOCKED: staged Butler core not found at $CorePath"
}

function Replace-ExactlyOnce {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Old,
        [Parameter(Mandatory = $true)][string]$New,
        [Parameter(Mandatory = $true)][string]$Contract
    )
    $matches = [regex]::Matches($Text, [regex]::Escape($Old)).Count
    if ($matches -ne 1) { throw "BF-953 BLOCKED: $Contract expected one match, found $matches." }
    return $Text.Replace($Old, $New)
}

$core = [System.IO.File]::ReadAllText($CorePath)
foreach ($required in @(
    '$holdLink = if ($null -ne $hold.PSObject.Properties[''Reason''])',
    '$queuePicks = @()',
    '$expertStandaloneTask = ''''',
    'Review $reviewQueueCount unresolved $reviewQueueNoun'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-953 BLOCKED: required prior lineup queue capability is missing: $required"
    }
}

$functionStart = $core.IndexOf('function ConvertTo-AutoFillHtml {', [System.StringComparison]::Ordinal)
$functionEnd = $core.IndexOf('function ConvertTo-TeamHtml {', $functionStart, [System.StringComparison]::Ordinal)
if ($functionStart -lt 0 -or $functionEnd -le $functionStart) {
    throw 'BF-953 BLOCKED: final Lineup Advisor renderer boundary is missing.'
}
$function = $core.Substring($functionStart, $functionEnd - $functionStart)

$holdOld = @'
    $queueItems = ''
    $holdIndex = 0
    foreach ($hold in @($AutoFill.ProjectionHolds)) {
        $holdLink = if ($null -ne $hold.PSObject.Properties['Reason']) { " <a href=`"#lineup-hold-$holdIndex`">Review hold evidence</a>" } else { '' }
        $queueItems += "<li><strong>$(ConvertTo-HtmlText $hold.Name)</strong>: review hold. Keep the current lineup state pending review.$holdLink</li>"
        $holdIndex++
    }
    $queuePicks = @()
    if ($null -ne $AutoFill.PSObject.Properties['ExpertPicks']) { $queuePicks = @($AutoFill.ExpertPicks) }
'@

$holdNew = @'
    $queueItems = ''
    $queuePicks = @()
    if ($null -ne $AutoFill.PSObject.Properties['ExpertPicks']) { $queuePicks = @($AutoFill.ExpertPicks) }
    $heldPlayerIds = @()
    $holdIndex = 0
    foreach ($hold in @($AutoFill.ProjectionHolds)) {
        $holdLink = if ($null -ne $hold.PSObject.Properties['Reason']) { " <a href=`"#lineup-hold-$holdIndex`">Review hold evidence</a>" } else { '' }
        $holdExpertTail = ''
        $holdId = if ($null -ne $hold.PSObject.Properties['Id']) { [string]$hold.Id } else { '' }
        if (-not [string]::IsNullOrWhiteSpace($holdId)) {
            $heldPlayerIds += $holdId
            $holdPicks = @($queuePicks | Where-Object { [string]$_.playerId -ceq $holdId })
            if ($holdPicks.Count -eq 1 -and [string]$holdPicks[0].selection -ceq 'SIT') {
                $holdPickIndex = [array]::IndexOf($queuePicks, $holdPicks[0])
                $holdExpertTail = " Expert signal: attributed SIT selection from $(ConvertTo-HtmlText $holdPicks[0].author); this opinion does not establish consensus. <a href=`"#lineup-expert-$holdPickIndex`">Review expert source</a>"
            }
        }
        $queueItems += "<li><strong>$(ConvertTo-HtmlText $hold.Name)</strong>: review hold. Keep the current lineup state pending review.$holdLink$holdExpertTail</li>"
        $holdIndex++
    }
'@

$expertConditionOld = @'
        if ($matchingPicks.Count -eq 1 -and [string]$matchingPicks[0].selection -ceq 'SIT') {
'@
$expertConditionNew = @'
        if ($matchingPicks.Count -eq 1 -and [string]$matchingPicks[0].selection -ceq 'SIT' -and $starterId -cnotin $heldPlayerIds) {
'@

$function = Replace-ExactlyOnce -Text $function -Old $holdOld.TrimEnd() -New $holdNew.TrimEnd() -Contract 'hold/expert identity merge'
$function = Replace-ExactlyOnce -Text $function -Old $expertConditionOld.TrimEnd() -New $expertConditionNew.TrimEnd() -Contract 'held starter expert de-duplication'
$core = $core.Substring(0, $functionStart) + $function + $core.Substring($functionEnd)

if ($core.IndexOf('$heldPlayerIds += $holdId', [System.StringComparison]::Ordinal) -lt 0 -or
    $core.IndexOf('$starterId -cnotin $heldPlayerIds', [System.StringComparison]::Ordinal) -lt 0 -or
    $core.IndexOf('$holdExpertTail', [System.StringComparison]::Ordinal) -lt 0) {
    throw 'BF-953 BLOCKED: exact-ID hold/expert merge was not installed.'
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))
$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($CorePath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $summary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-953 BLOCKED: generated staged core failed PowerShell parse: $summary"
}

Write-Host 'BF-953 Lineup Review hold/expert merge applied.'

$bf1010Transform = Join-Path $PSScriptRoot 'butler-app-bf1010-lineup-slot-placement-decision-count-transform.ps1'
if (-not (Test-Path -LiteralPath $bf1010Transform -PathType Leaf)) {
    throw "BF-1010 BLOCKED: lineup slot-placement decision-count transform not found at $bf1010Transform"
}
& $bf1010Transform -CorePath $CorePath
