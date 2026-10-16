param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath
)

# BF-1525: first-scan saved waiver evidence age at the GOVERNED telemetry time.
# Presentation only: does not run a new source read or infer current NFL week.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $DashboardPath -PathType Leaf)) {
    throw 'BF-1525 BLOCKED: final Waiver Board source missing.'
}
$source = [IO.File]::ReadAllText($DashboardPath)
$tokens = $null
$parseErrors = $null
$ast = [Management.Automation.Language.Parser]::ParseInput($source,[ref]$tokens,[ref]$parseErrors)
if (@($parseErrors).Count -ne 0) {
    throw 'BF-1525 BLOCKED: Waiver Board source does not parse.'
}
$functions = @($ast.FindAll({
    param($n)
    $n -is [Management.Automation.Language.FunctionDefinitionAst] -and
        $n.Name -ceq 'ConvertTo-WaiverHtml'
},$true))
if ($functions.Count -ne 1) {
    throw 'BF-1525 BLOCKED: expected one exact Waiver Board renderer.'
}
$fn = $functions[0]
$waiver = $fn.Extent.Text
if ($waiver.Contains('$bf1525AgePanel')) {
    throw 'BF-1525 BLOCKED: evidence-age panel already installed.'
}
foreach ($required in @('$Summary','$bf1521Clarity','waiver-decision-hero','$waiverPairHtml')) {
    if (-not $waiver.Contains($required)) {
        throw "BF-1525 BLOCKED: existing Waiver Board contract missing $required"
    }
}
$returnAt = $waiver.LastIndexOf('    return @"',[StringComparison]::Ordinal)
if ($returnAt -lt 0) { throw 'BF-1525 BLOCKED: final Waiver return missing.' }
$stamp = @'
    # BF-1525 is the provider's already-reported telemetry as-of time,
    # NOT an assertion that injury, market or Week N data was rechecked now.
    $bf1525TelemetryRaw = Get-LineValue -Text $Summary -Label 'Telemetry observed at UTC:'
    $bf1525MarketRaw = Get-LineValue -Text $Summary -Label 'Latest BF-603 observed / age seconds:'
    $bf1525WaiverRaw = Get-LineValue -Text $Summary -Label 'Latest BF-602 observed / age seconds:'
    $bf1525Market = ConvertTo-AgeView $bf1525MarketRaw
    $bf1525Waiver = ConvertTo-AgeView $bf1525WaiverRaw
    $bf1525AsOf = [DateTimeOffset]::MinValue
    $bf1525ValidAsOf = (-not [string]::IsNullOrWhiteSpace($bf1525TelemetryRaw)) -and
        ($bf1525TelemetryRaw -match '(?:Z|[+-][0-9]{2}:[0-9]{2})$') -and
        [DateTimeOffset]::TryParse(
            $bf1525TelemetryRaw,
            [Globalization.CultureInfo]::InvariantCulture,
            [Globalization.DateTimeStyles]::AllowWhiteSpaces,
            [ref]$bf1525AsOf
        )
    $bf1525MarketValid = $null -ne $bf1525Market.Seconds -and $bf1525Market.Seconds -ge 0
    $bf1525WaiverValid = $null -ne $bf1525Waiver.Seconds -and $bf1525Waiver.Seconds -ge 0
    if ($bf1525ValidAsOf -and ($bf1525MarketValid -or $bf1525WaiverValid)) {
        $bf1525AsOfLabel = $bf1525AsOf.ToUniversalTime().ToString(
            "MMM d, yyyy HH:mm 'UTC'",[Globalization.CultureInfo]::InvariantCulture)
        $bf1525MarketLabel = if ($bf1525MarketValid) { $bf1525Market.Human } else { 'unavailable' }
        $bf1525WaiverLabel = if ($bf1525WaiverValid) { $bf1525Waiver.Human } else { 'unavailable' }
        $bf1525AgePanel = '<div class="waiver-pair-note"><strong>Saved waiver evidence age</strong><p>At ' +
            (ConvertTo-HtmlText $bf1525AsOfLabel) +
            ': market source ' + (ConvertTo-HtmlText $bf1525MarketLabel) +
            '; waiver source ' + (ConvertTo-HtmlText $bf1525WaiverLabel) +
            '. These ages are relative to the telemetry observation, not the present moment. This is not a current-week free-agent review or live injury clearance.</p></div>'
    }
    else {
        $bf1525AgePanel = '<div class="waiver-pair-note"><strong>Saved waiver evidence age unavailable</strong><p>Butler cannot verify source age from this summary. No current-week free-agent or injury freshness is implied.</p></div>'
    }

'@
$waiver = $waiver.Insert($returnAt,$stamp)
# Add one visible source-age line directly under the governed pair or hold.
# The BF-1521 helper runs BEFORE this pass, so the link/hold policy is intact.
$anchor = '  $waiverPairHtml'
if ([regex]::Matches($waiver,'(?m)^' + [regex]::Escape($anchor) + '$').Count -ne 1) {
    throw 'BF-1525 BLOCKED: final Waiver hero insertion point ambiguous.'
}
$waiver = [regex]::Replace(
    $waiver,
    '(?m)^' + [regex]::Escape($anchor) + '$',
    { param($m) $m.Value + [Environment]::NewLine + '  $bf1525AgePanel' }
)
$source = $source.Substring(0,$fn.Extent.StartOffset) +
    $waiver + $source.Substring($fn.Extent.EndOffset)
$parseErrors = $null
[void][Management.Automation.Language.Parser]::ParseInput($source,[ref]$tokens,[ref]$parseErrors)
if (@($parseErrors).Count -ne 0) {
    throw 'BF-1525 BLOCKED: final Waiver renderer no longer parses.'
}
[IO.File]::WriteAllText($DashboardPath,$source,[Text.UTF8Encoding]::new($false))
Write-Host 'BF-1525 Waiver evidence as-of display applied.'
