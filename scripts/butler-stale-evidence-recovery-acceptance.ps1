Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'butler-decision-refresh.ps1')
$runner = Join-Path $PSScriptRoot 'sleeper-live-waiver-no-transaction-refresh.ps1'
$tokens = $null; $errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($runner, [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw 'Runner parse failed' }
# Exercise the production orchestration with only the external runtime boundary stubbed.
foreach ($name in @('Get-Bf676SingleField', 'Test-Bf676NoTransactionLineage', 'Assert-Bf676WarningRefreshPlan')) {
    $node = $ast.Find({ param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq $name }, $true)
    . ([scriptblock]::Create($node.Extent.Text))
}
$source = [IO.File]::ReadAllText($runner)
$flow = [scriptblock]::Create($source.Substring($source.LastIndexOf('Push-Location $repoRoot', [StringComparison]::Ordinal)))
$repoRoot = Split-Path -Parent $PSScriptRoot
$LeagueId = '00000000-0000-0000-0000-000000000001'
$PreflightOnly = $false
function Invoke-Bf676RuntimeStep {
    param($Label, $Task, [switch]$CaptureOutput)
    $script:calls.Add($Task)
    if ($script:calls.Count -eq 1) { return $script:summary }
    if ($script:failAt -eq $Task) { throw 'Simulated runtime failure' }
    return 'Fixture runtime result'
}
$allowedLineages = @('MARKET_LINEAGE_SUPERSEDED','WAIVER_LINEAGE_SUPERSEDED','MARKET_AND_WAIVER_LINEAGE_SUPERSEDED')
foreach ($live in @('LIVE_ACTIONABLE_VERIFIED','AUDITED_TRANSACTION_PENDING','AUDITED_TRANSACTION_COMPLETE','UNKNOWN','')) {
    foreach ($lineage in ($allowedLineages + @('LATEST_EVIDENCE_LINEAGE_VERIFIED','UNKNOWN',''))) {
        $script:summary = "Decision status: STALE_DO_NOT_ACT`nBF-629 live actionability: $live`nBF-631 evidence lineage: $lineage"
        $script:calls = [Collections.Generic.List[string]]::new()
        $script:failAt = ''
        $expected = $live -ceq 'LIVE_ACTIONABLE_VERIFIED' -and $allowedLineages -ccontains $lineage
        $failed = $false
        try { & $flow | Out-Null } catch { $failed = $true }
        if ($expected -eq $failed) { throw "Unexpected eligibility: $live / $lineage" }
        if (-not $expected -and $script:calls.Count -ne 1) { throw 'Rejected state executed a write stage' }
        if ($expected -and ($script:calls.Count -ne 11 -or $script:calls[9] -notmatch 'AuditCapture$' -or $script:calls[10] -notmatch 'DecisionSummary$')) { throw 'Incomplete refresh/capture/verification sequence' }
        $html = '<nav class="nav" aria-label="Butler sections"></nav>' + "<div>Decision state: STALE_DO_NOT_ACT</div><div>BF-629: $live</div><div>BF-631: $lineage</div>"
        $rendered = Add-DecisionRefreshControl -Html $html -RequestTarget '/'
        if (($rendered -match 'href="/refresh"') -ne $expected) { throw 'Presentation and runner eligibility differ' }
    }
}
# BF-1529: only the independently verified immediately next NFL week can
# re-enter the already governed nine-stage refresh chain. The old player pair
# is never actionable; the UI offers manual confirmation only.
$rolloverLineages = @(
    'LATEST_EVIDENCE_LINEAGE_VERIFIED',
    'MARKET_LINEAGE_SUPERSEDED',
    'WAIVER_LINEAGE_SUPERSEDED',
    'MARKET_AND_WAIVER_LINEAGE_SUPERSEDED')
foreach ($bf629 in @(
    'AUDITED_WEEK_ROLLOVER_DO_NOT_ACT',
    'AUDITED_WEEK_MISMATCH_DO_NOT_ACT',
    'AUDITED_WEEK_UNVERIFIED_DO_NOT_ACT',
    'AUDITED_TRANSACTION_PENDING',
    'AUDITED_TRANSACTION_COMPLETE',
    '')) {
    foreach ($lineage in ($rolloverLineages + @('UNKNOWN', ''))) {
        $script:summary = "Decision status: STALE_DO_NOT_ACT`nBF-629 live actionability: $bf629`nBF-631 evidence lineage: $lineage"
        $script:calls = [Collections.Generic.List[string]]::new()
        $script:failAt = ''
        $allowed = $bf629 -ceq 'AUDITED_WEEK_ROLLOVER_DO_NOT_ACT' -and
            $rolloverLineages -ccontains $lineage
        $blocked=$false
        try { & $flow | Out-Null } catch { $blocked=$true }
        if ($blocked -eq $allowed) {
            throw "BF-1529 BLOCKED: rollover preflight mismatch for $bf629 / $lineage"
        }
        if ($allowed) {
            if ($script:calls.Count -ne 11 -or
                $script:calls[1] -notmatch 'CurrentWeekMatchupSync$' -or
                $script:calls[9] -notmatch 'AuditCapture$' -or
                $script:calls[10] -notmatch 'DecisionSummary$') {
                throw 'BF-1529 BLOCKED: fresh current-week cycle did not preserve nine governed stages and BF-840 first.'
            }
        } elseif ($script:calls.Count -ne 1) {
            throw 'BF-1529 BLOCKED: unsafe rollover executed an evidence write stage.'
        }
        $dashboard = '<nav class="nav" aria-label="Butler sections"></nav>' +
            "<div>Decision state: STALE_DO_NOT_ACT</div><div>BF-629: $bf629</div><div>BF-631: $lineage</div>"
        $link = Add-DecisionRefreshControl -Html $dashboard -RequestTarget '/'
        if ($link.Contains('href="/refresh"')) {
            throw 'BF-1529 BLOCKED: automatic Dashboard source acquired rollover refresh capability.'
        }
        $auto = Add-AutomaticDashboardRefresh -Html $link -RequestTarget '/' -Token ('f' * 64)
        if (-not [string]::IsNullOrWhiteSpace([string]$auto.Nonce)) {
            throw 'BF-1529 BLOCKED: automatic Dashboard refresh was armed by old-week evidence.'
        }
    }
}
$PreflightOnly = $true
$script:summary = "Decision status: STALE_DO_NOT_ACT`nBF-629 live actionability: AUDITED_WEEK_ROLLOVER_DO_NOT_ACT`nBF-631 evidence lineage: LATEST_EVIDENCE_LINEAGE_VERIFIED"
$script:calls = [Collections.Generic.List[string]]::new()
& $flow | Out-Null
if ($script:calls.Count -ne 1) {
    throw 'BF-1529 BLOCKED: rollover preflight-only wrote new evidence.'
}
$PreflightOnly = $false
# BF-1529: an independently failed current-week BF-840 read MUST abort
# before BF-602 or any other Butler-local evidence write, even though
# public Week N+1 was verified by the earlier BF-629 read-only gate.
$script:summary = "Decision status: STALE_DO_NOT_ACT`nBF-629 live actionability: AUDITED_WEEK_ROLLOVER_DO_NOT_ACT`nBF-631 evidence lineage: LATEST_EVIDENCE_LINEAGE_VERIFIED"
$script:calls = [Collections.Generic.List[string]]::new()
$script:failAt = ':bet:bet-cli:sleeperCurrentWeekMatchupSync'
$failed = $false
try { & $flow | Out-Null } catch { $failed = $true }
if (-not $failed -or $script:calls.Count -ne 2 -or
    $script:calls[1] -notmatch 'CurrentWeekMatchupSync
$script:calls = [Collections.Generic.List[string]]::new()
$PreflightOnly = $true
& $flow | Out-Null
if ($script:calls.Count -ne 1) { throw 'Preflight wrote data' }
$PreflightOnly = $false
$script:calls = [Collections.Generic.List[string]]::new()
$script:failAt = ':bet:bet-cli:sleeperLiveWaiverSnapshotSync'
$failed = $false
try { & $flow | Out-Null } catch { $failed = $true }
if (-not $failed -or $script:calls.Count -ne 3) { throw 'Failure did not stop later stages' }
$script:failAt = ''
$script:summary += "`nBF-629 live actionability: LIVE_ACTIONABLE_VERIFIED"
$script:calls = [Collections.Generic.List[string]]::new()
$failed = $false
try { & $flow | Out-Null } catch { $failed = $true }
if (-not $failed -or $script:calls.Count -ne 1) { throw 'Duplicate evidence accepted' }
Write-Host 'STALE EVIDENCE RECOVERY ACCEPTANCE: PASS (30 state combinations, preflight, failure stop, duplicate rejection)'
) {
    throw 'BF-1529 BLOCKED: BF-840 failure allowed rollover evidence writes.'
}
$script:failAt = ''

$script:summary = "Decision status: STALE_DO_NOT_ACT`nBF-629 live actionability: LIVE_ACTIONABLE_VERIFIED`nBF-631 evidence lineage: MARKET_LINEAGE_SUPERSEDED"
$script:calls = [Collections.Generic.List[string]]::new()
$PreflightOnly = $true
& $flow | Out-Null
if ($script:calls.Count -ne 1) { throw 'Preflight wrote data' }
$PreflightOnly = $false
$script:calls = [Collections.Generic.List[string]]::new()
$script:failAt = ':bet:bet-cli:sleeperLiveWaiverSnapshotSync'
$failed = $false
try { & $flow | Out-Null } catch { $failed = $true }
if (-not $failed -or $script:calls.Count -ne 3) { throw 'Failure did not stop later stages' }
$script:failAt = ''
$script:summary += "`nBF-629 live actionability: LIVE_ACTIONABLE_VERIFIED"
$script:calls = [Collections.Generic.List[string]]::new()
$failed = $false
try { & $flow | Out-Null } catch { $failed = $true }
if (-not $failed -or $script:calls.Count -ne 1) { throw 'Duplicate evidence accepted' }
Write-Host 'STALE EVIDENCE RECOVERY ACCEPTANCE: PASS (30 state combinations, preflight, failure stop, duplicate rejection)'
