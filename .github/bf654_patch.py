from pathlib import Path

path = Path('scripts/butler-dashboard.ps1')
text = path.read_text(encoding='ascii')

invoke_marker = '''function Invoke-ButlerReadOnlyWaiverBoard {\n    return Invoke-ButlerReadOnlyTask -Task ":bet:bet-cli:sleeperLiveWaiverComparisonBundle" -BoundaryName "BF-646"\n}\n'''
invoke_addition = invoke_marker + '''\nfunction Invoke-ButlerReadOnlyExplanationLookup {\n    param([Parameter(Mandatory = $true)][string]$AuditId)\n    if ([string]::IsNullOrWhiteSpace($AuditId)) {\n        throw "BF-654 BLOCKED: current BF-627 audit id is missing"\n    }\n    $previousPreference = $ErrorActionPreference\n    $lines = $null\n    $exitCode = $null\n    try {\n        $ErrorActionPreference = "Continue"\n        $lines = & $gradle ":bet:bet-cli:sleeperLiveWaiverGovernedExplanationLookup" "--args=$LeagueId $AuditId" 2>&1\n        $exitCode = $LASTEXITCODE\n    }\n    finally {\n        $ErrorActionPreference = $previousPreference\n    }\n    $text = ($lines | ForEach-Object { "$_" }) -join "`n"\n    if ($exitCode -ne 0) {\n        throw "BF-654 BLOCKED: BF-653 governed explanation lookup failed with Gradle exit code $exitCode.`n$text"\n    }\n    return $text\n}\n'''
if invoke_marker not in text:
    raise SystemExit('BF-654 invoke insertion marker missing')
if 'function Invoke-ButlerReadOnlyExplanationLookup' in text:
    raise SystemExit('BF-654 explanation lookup function already present')
text = text.replace(invoke_marker, invoke_addition, 1)

resolve_marker = 'function Resolve-WaiverCandidateById {'
explanation_function = r'''function Get-GovernedExplanationView {
    param([Parameter(Mandatory = $true)][string]$Summary)

    $audit = ConvertTo-AuditView (Get-LineValue -Text $Summary -Label "Audit:")
    if ([string]::IsNullOrWhiteSpace($audit.Id) -or $audit.Id -ceq "none") {
        return [pscustomobject]@{
            Ready = $false
            State = "EXPLANATION_NOT_CAPTURED"
            AuditId = "none"
            ExplanationId = "none"
            ExplanationType = "none"
            ExplanationText = "none"
            MarketSnapshotId = "none"
            WaiverSnapshotId = "none"
            AddSleeperId = "none"
            DropSleeperId = "none"
            EvidencePolicy = "none"
            EvidenceTrace = "none"
        }
    }

    $lookup = Invoke-ButlerReadOnlyExplanationLookup -AuditId $audit.Id
    $lookupState = Get-LineValue -Text $lookup -Label "Lookup state:"
    if ([string]::IsNullOrWhiteSpace($lookupState)) {
        throw "BF-654 BLOCKED: BF-653 lookup state is missing"
    }

    $summaryTarget = Get-Bf623TargetView -Text $Summary -BoundaryName "BF-654"
    $lookupTarget = Get-Bf623TargetView -Text $lookup -BoundaryName "BF-654"
    foreach ($field in @("SleeperLeagueId", "RosterId", "LeagueName", "DisplayName", "TeamName", "Role")) {
        if ([string]$summaryTarget.$field -cne [string]$lookupTarget.$field) {
            throw "BF-654 BLOCKED: summary BF-623 target identity disagrees with BF-653 lookup target identity"
        }
    }

    $lookupAuditId = Get-LineValue -Text $lookup -Label "BF-627 audit id:"
    if ([string]::IsNullOrWhiteSpace($lookupAuditId) -or $lookupAuditId -cne $audit.Id) {
        throw "BF-654 BLOCKED: BF-653 audit id disagrees with current BF-627 audit"
    }

    $summaryLineageRaw = Get-LineValue -Text $Summary -Label "BF-631 audited BF-603 / BF-602 snapshot:"
    $lookupLineageRaw = Get-LineValue -Text $lookup -Label "BF-603 market / BF-602 waiver snapshot:"
    $summaryLineage = ConvertTo-SnapshotPairView -Line $summaryLineageRaw -BoundaryName "BF-654"
    $lookupLineage = ConvertTo-SnapshotPairView -Line $lookupLineageRaw -BoundaryName "BF-654"
    if ($summaryLineage.Market -cne $lookupLineage.Market -or $summaryLineage.Waiver -cne $lookupLineage.Waiver) {
        throw "BF-654 BLOCKED: BF-653 BF-603/BF-602 lineage disagrees with current BF-631 audit lineage"
    }

    $add = ConvertTo-PlayerView (Get-LineValue -Text $Summary -Label "ADD:")
    $drop = ConvertTo-PlayerView (Get-LineValue -Text $Summary -Label "DROP:")
    if ([string]::IsNullOrWhiteSpace($add.SleeperId) -or [string]::IsNullOrWhiteSpace($drop.SleeperId)) {
        throw "BF-654 BLOCKED: current audited ADD/DROP exact Sleeper ids are missing"
    }
    $lookupIdsRaw = Get-LineValue -Text $lookup -Label "Audited add / drop Sleeper ids:"
    $lookupIds = [regex]::Match([string]$lookupIdsRaw, '^(?<add>[0-9]+)\s*/\s*(?<drop>[0-9]+)$')
    if (-not $lookupIds.Success) {
        throw "BF-654 BLOCKED: unable to parse BF-653 audited ADD/DROP exact Sleeper ids"
    }
    $lookupAddId = $lookupIds.Groups['add'].Value.Trim()
    $lookupDropId = $lookupIds.Groups['drop'].Value.Trim()
    if ($lookupAddId -cne $add.SleeperId -or $lookupDropId -cne $drop.SleeperId) {
        throw "BF-654 BLOCKED: BF-653 audited ADD/DROP ids disagree with current audited transaction"
    }

    if ($lookupState -ceq "EXPLANATION_NOT_CAPTURED") {
        return [pscustomobject]@{
            Ready = $false
            State = $lookupState
            AuditId = $audit.Id
            ExplanationId = "none"
            ExplanationType = "none"
            ExplanationText = "none"
            MarketSnapshotId = $lookupLineage.Market
            WaiverSnapshotId = $lookupLineage.Waiver
            AddSleeperId = $lookupAddId
            DropSleeperId = $lookupDropId
            EvidencePolicy = "none"
            EvidenceTrace = "none"
        }
    }
    if ($lookupState -cne "EXPLANATION_READY") {
        throw "BF-654 BLOCKED: unsupported BF-653 lookup state $lookupState"
    }

    $explanationId = Get-LineValue -Text $lookup -Label "Explanation id:"
    $explanationType = Get-LineValue -Text $lookup -Label "Explanation type:"
    $explanationText = Get-LineValue -Text $lookup -Label "Why this move:"
    $evidencePolicy = Get-LineValue -Text $lookup -Label "Evidence policy:"
    $evidenceTrace = Get-LineValue -Text $lookup -Label "Evidence trace:"
    if ([string]::IsNullOrWhiteSpace($explanationId) -or $explanationId -ceq "none") {
        throw "BF-654 BLOCKED: BF-653 explanation id is missing"
    }
    if ([string]::IsNullOrWhiteSpace($explanationType) -or $explanationType -ceq "none") {
        throw "BF-654 BLOCKED: BF-653 explanation type is missing"
    }
    if ([string]::IsNullOrWhiteSpace($explanationText) -or $explanationText -ceq "none") {
        throw "BF-654 BLOCKED: BF-653 persisted explanation text is missing"
    }

    return [pscustomobject]@{
        Ready = $true
        State = $lookupState
        AuditId = $audit.Id
        ExplanationId = $explanationId
        ExplanationType = $explanationType
        ExplanationText = $explanationText
        MarketSnapshotId = $lookupLineage.Market
        WaiverSnapshotId = $lookupLineage.Waiver
        AddSleeperId = $lookupAddId
        DropSleeperId = $lookupDropId
        EvidencePolicy = if ([string]::IsNullOrWhiteSpace($evidencePolicy)) { "none" } else { $evidencePolicy }
        EvidenceTrace = if ([string]::IsNullOrWhiteSpace($evidenceTrace)) { "none" } else { $evidenceTrace }
    }
}

'''
if resolve_marker not in text:
    raise SystemExit('BF-654 explanation function insertion marker missing')
if 'function Get-GovernedExplanationView' in text:
    raise SystemExit('BF-654 explanation view already present')
text = text.replace(resolve_marker, explanation_function + resolve_marker, 1)

header_marker = '    $header = Get-HeaderHtml -Target $target -Active "dashboard"\n\n    return @"\n'
why_logic = r'''    $header = Get-HeaderHtml -Target $target -Active "dashboard"
    $explanation = Get-GovernedExplanationView -Summary $Summary
    if ($explanation.Ready) {
        $whySection = @"
<section class="panel">
  <div class="eyebrow">Governed explanation</div>
  <h2>Why this move?</h2>
  <div class="next"><p>$(ConvertTo-HtmlText $explanation.ExplanationText)</p></div>
  <div class="subtle" style="margin-top:10px">Persisted BF-653 explanation for this immutable BF-627 audit. This dashboard does not rerun recommendation or evidence selection.</div>
  <details><summary>Technical details</summary><div class="tech"><div>BF-627 audit ID: $(ConvertTo-HtmlText $explanation.AuditId)</div><div>BF-653 lookup state: $(ConvertTo-HtmlText $explanation.State)</div><div>BF-653 explanation ID: $(ConvertTo-HtmlText $explanation.ExplanationId)</div><div>BF-653 explanation type: $(ConvertTo-HtmlText $explanation.ExplanationType)</div><div>BF-603 / BF-602: $(ConvertTo-HtmlText $explanation.MarketSnapshotId) / $(ConvertTo-HtmlText $explanation.WaiverSnapshotId)</div><div>Audited ADD / DROP Sleeper IDs: $(ConvertTo-HtmlText $explanation.AddSleeperId) / $(ConvertTo-HtmlText $explanation.DropSleeperId)</div><div>BF-653 evidence policy: $(ConvertTo-HtmlText $explanation.EvidencePolicy)</div><div>BF-653 evidence trace: $(ConvertTo-HtmlText $explanation.EvidenceTrace)</div></div></details>
</section>
"@
    }
    else {
        $whySection = @"
<section class="panel">
  <div class="eyebrow">Governed explanation</div>
  <h2>Why this move?</h2>
  <p class="lede">No persisted BF-653 explanation is available for this exact audit.</p>
  <div class="subtle" style="margin-top:10px">Butler will not invent or recompute an explanation from this dashboard.</div>
</section>
"@
    }

    return @"
'''
if header_marker not in text:
    raise SystemExit('BF-654 dashboard logic insertion marker missing')
text = text.replace(header_marker, why_logic, 1)

html_marker = '''  <div class="next"><strong>$(ConvertTo-HtmlText $presentation.ActionTitle)</strong><p>$(ConvertTo-HtmlText $presentation.ActionCopy)</p></div>\n</section>\n<section class="panel"><div class="eyebrow">Safety checks</div>'''
html_replacement = '''  <div class="next"><strong>$(ConvertTo-HtmlText $presentation.ActionTitle)</strong><p>$(ConvertTo-HtmlText $presentation.ActionCopy)</p></div>\n</section>\n$whySection\n<section class="panel"><div class="eyebrow">Safety checks</div>'''
if html_marker not in text:
    raise SystemExit('BF-654 dashboard HTML insertion marker missing')
text = text.replace(html_marker, html_replacement, 1)

path.write_text(text, encoding='ascii')
