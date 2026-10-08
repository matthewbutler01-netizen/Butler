Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$source = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'butler-dashboard-bf834-waiver-decision-surface-transform.ps1'))
$start = $source.IndexOf('    $waiverEvidenceHtml = ""', [StringComparison]::Ordinal)
$end = $source.IndexOf('    $waiverPairHtml = ""', $start, [StringComparison]::Ordinal)
if ($start -lt 0 -or $end -le $start) { throw 'Evidence renderer missing' }
$render = [scriptblock]::Create($source.Substring($start, $end - $start))
function ConvertTo-HtmlText { param($Text) [Net.WebUtility]::HtmlEncode([string]$Text) }
function Get-GovernedExplanationView { param($Summary) $script:lookups++; if ($script:lookupFails) { throw 'Audit mismatch' }; return $script:fixture }
$Summary = 'fixture'
$pair = [pscustomobject]@{ Active = $true }
$script:lookups = 0
$script:lookupFails = $false
$script:fixture = [pscustomobject]@{ Ready = $true; ExplanationText = 'Historical comparison <script>'; EvidenceTrace = 'source=nflverse,improvement=7.1457,scoringKeys=[rec, rush_yd]' }
. $render
if ($waiverEvidenceHtml -notmatch 'source=nflverse' -or $waiverEvidenceHtml -notmatch 'scoringKeys' -or $waiverEvidenceHtml -notmatch 'Source season: not recorded' -or $waiverEvidenceHtml -match '<script>') { throw 'Evidence display or escaping failed' }
$script:fixture.Ready = $false
. $render
if ($waiverEvidenceHtml -notmatch 'Saved explanation unavailable' -or $waiverEvidenceHtml -match 'nflverse') { throw 'Missing explanation reused evidence' }
$pair.Active = $false
$before = $script:lookups
. $render
if ($script:lookups -ne $before -or $waiverEvidenceHtml -ne '') { throw 'Inactive pair looked up or displayed evidence' }
$pair.Active = $true
$script:lookupFails = $true
$failed = $false
try { . $render } catch { $failed = $true }
if (-not $failed) { throw 'Mismatched audit was swallowed' }
Write-Host 'WAIVER EVIDENCE DISPLAY ACCEPTANCE: PASS'
