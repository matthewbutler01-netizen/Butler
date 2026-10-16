# BF-1525: exact saved-evidence as-of presentation, no source request or write.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$transform = Join-Path $PSScriptRoot 'butler-bf1525-waiver-evidence-age-transform.ps1'
$root = Join-Path ([IO.Path]::GetTempPath()) ('butler-bf1525-' + [guid]::NewGuid().ToString('N'))
try {
    [IO.Directory]::CreateDirectory($root) | Out-Null
    $source = Join-Path $root 'waiver-fixture.ps1'
    $fixture = @'
function Get-LineValue {
    param([string]$Text,[string]$Label)
    $found = [regex]::Match($Text,'(?m)^' + [regex]::Escape($Label) + '\s*(.*)$')
    if (-not $found.Success) { return $null }
    return $found.Groups[1].Value.Trim()
}
function ConvertTo-HtmlText {
    param([AllowNull()][object]$Value)
    return [Net.WebUtility]::HtmlEncode([string]$Value)
}
function ConvertTo-AgeView {
    param([AllowNull()][string]$Line)
    if ([string]::IsNullOrWhiteSpace($Line)) {
        return [pscustomobject]@{ Seconds=$null; Human='Unavailable' }
    }
    $parts = $Line -split '\s*/\s*'
    $seconds = 0L
    if ($parts.Count -lt 2 -or
        -not [long]::TryParse([string]$parts[1], [ref]$seconds)) {
        return [pscustomobject]@{ Seconds=$null; Human='Unavailable' }
    }
    return [pscustomobject]@{ Seconds=$seconds; Human=("$([math]::Floor($seconds / 3600)) hr ago") }
}
function ConvertTo-WaiverHtml {
    param([string]$Summary)
    $bf1521State = 'NO VERIFIED MOVE'
    $waiverPairHtml = '<p>Saved audited pair</p>'
    return @"
<section class="panel waiver-decision-hero">
  <h1>$bf1521State</h1>
  $waiverPairHtml
  <div class="next">No new current-week transaction</div>
</section>
"@
}
'@
    [IO.File]::WriteAllText($source,$fixture)
    & $transform -DashboardPath $source
    . $source

    $saved = @'
Telemetry observed at UTC: 2026-10-16T06:00:00Z
Latest BF-603 observed / age seconds: 2026-10-16T05:00:00Z / 3600
Latest BF-602 observed / age seconds: 2026-10-16T04:00:00Z / 7200
'@
    $page = ConvertTo-WaiverHtml -Summary $saved
    foreach ($marker in @(
        'Saved waiver evidence age',
        'Oct 16, 2026 06:00 UTC',
        'market source 1 hr ago',
        'waiver source 2 hr ago',
        'relative to the telemetry observation',
        'not a current-week free-agent review',
        'NO VERIFIED MOVE',
        'Saved audited pair'
    )) {
        if (-not $page.Contains($marker)) { throw "BF-1525 BLOCKED: missing $marker" }
    }
    $bad = ConvertTo-WaiverHtml -Summary @'
Telemetry observed at UTC: 2026-10-16T06:00:00
Latest BF-603 observed / age seconds: none / -1
Latest BF-602 observed / age seconds: none / none
'@
    if (-not $bad.Contains('Saved waiver evidence age unavailable') -or
        -not $bad.Contains('No current-week free-agent or injury freshness is implied.') -or
        $bad.Contains('market source 1 hr ago')) {
        throw 'BF-1525 BLOCKED: unverified telemetry was advertised as fresh.'
    }
    $partial = ConvertTo-WaiverHtml -Summary @'
Telemetry observed at UTC: 2026-10-16T06:00:00Z
Latest BF-603 observed / age seconds: none / none
Latest BF-602 observed / age seconds: 2026-10-16T05:00:00Z / 3600
'@
    if (-not $partial.Contains('market source unavailable') -or
        -not $partial.Contains('waiver source 1 hr ago')) {
        throw 'BF-1525 BLOCKED: partial source age is not differentiated.'
    }
    $sourceText=[IO.File]::ReadAllText($source)
    foreach ($forbidden in @('Invoke-RestMethod','Invoke-WebRequest','submitTransaction','Stop-Process','setFaab')) {
        if ($sourceText.Contains($forbidden)) {
            throw "BF-1525 BLOCKED: evidence display introduced forbidden $forbidden"
        }
    }
    Write-Host 'BF-1525 WAIVER EVIDENCE AS-OF ACCEPTANCE: PASS'
}
finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
