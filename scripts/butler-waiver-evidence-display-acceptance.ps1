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
$script:fixture.ExplanationText = 'BF-624 produced the unique complete add/drop transaction.'
. $render
if ($waiverEvidenceHtml -notmatch '<p>Butler produced the unique' -or $waiverEvidenceHtml -notmatch '<pre>BF-624 produced') { throw 'Explanation subject or original record missing' }
# Use the actual frozen Java statement, then exercise public presentation.
$java = [IO.File]::ReadAllText((Join-Path $PSScriptRoot '..\bet\bet-cli\src\main\java\io\butler\bet\sleeper\SleeperLiveWaiverGovernedExplanationCapture.java'))
$reason = [regex]::Match($java, '(?s)static final String SAME_POSITION_REASON =(?<block>.*?);')
if (-not $reason.Success) { throw 'Frozen same-position fixture missing' }
$method = (([regex]::Matches($reason.Groups['block'].Value, '"(?<part>[^"\r\n]*)"') | ForEach-Object {$_.Groups['part'].Value}) -join '')
$script:fixture.ExplanationText = $method
. $render
$cache = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'butler-app-request-worker-cache.ps1'))
$presentation = [regex]::Match($cache, '(?s)\$presentationFunctions = @''\r?\n(?<block>.*?)\r?\n''@')
if (-not $presentation.Success) { throw 'Real public presentation missing' }
. ([scriptblock]::Create($presentation.Groups['block'].Value))
$public = ConvertTo-ButlerUserFacingHtml -Html $waiverEvidenceHtml
$details = $public.IndexOf('<details>', [StringComparison]::Ordinal)
if ($details -lt 0) { throw 'Original evidence disclosure missing' }
$visible = $public.Substring(0, $details)
if ($visible -notmatch 'saved historical comparison' -or $visible -notmatch 'weakest supported bench or reserve comparator' -or $visible -match 'replacement by\s*\.' -or $visible -match 'BF-61[45]') { throw 'Known same-position explanation remains broken after presentation' }
if ($public -notmatch [regex]::Escape('<pre>' + [Net.WebUtility]::HtmlEncode($method) + '</pre>')) { throw 'Exact saved method record was changed' }
if ($public -notmatch 'Source season: not recorded' -or $public -match 'Source season: 20[0-9]{2}') { throw 'Missing source season was filled from unrelated data' }
$script:fixture.ExplanationText = $method.Replace('BF-615.', 'UNKNOWN-METHOD.')
. $render
if ($waiverEvidenceHtml -match 'saved historical comparison supports' -or $waiverEvidenceHtml -notmatch 'UNKNOWN-METHOD') { throw 'Unknown saved method was reinterpreted' }
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
