from pathlib import Path

path = Path('scripts/butler-dashboard.ps1')
text = path.read_text(encoding='ascii')

anchor = '''function Get-GovernedExplanationView {
'''
insert = r'''function Get-GovernedNextDecisionPlanView {
    param([Parameter(Mandatory = $true)][string]$Summary)

    $decisionState = Get-LineValue -Text $Summary -Label "Decision status:"
    if ([string]::IsNullOrWhiteSpace($decisionState)) {
        throw "BF-657 BLOCKED: current governed decision state is missing"
    }
    if ($decisionState -cne "TRANSACTION_ALREADY_COMPLETE") {
        return [pscustomobject]@{
            Active = $false
            Policy = "none"
            State = "NOT_REQUIRED"
            Instruction = "none"
            Steps = @()
        }
    }

    $convergenceState = Get-LineValue -Text $Summary -Label "BF-639 post-transaction roster convergence:"
    if ($convergenceState -cne "POST_TRANSACTION_ROSTER_CONVERGED") {
        return [pscustomobject]@{
            Active = $false
            Policy = "none"
            State = "NOT_REQUIRED"
            Instruction = "none"
            Steps = @()
        }
    }

    $refreshMarkers = [regex]::Matches($Summary, '(?m)^BF-636 - governed MANUAL refresh plan\r?$')
    if ($refreshMarkers.Count -ne 0) {
        throw "BF-657 BLOCKED: BF-636 stale refresh plan must not coexist with BF-640 completed next-decision plan"
    }

    $planMarkers = [regex]::Matches($Summary, '(?m)^BF-640 - governed MANUAL next-decision plan\r?$')
    if ($planMarkers.Count -ne 1) {
        throw "BF-657 BLOCKED: completed/converged lifecycle requires exactly one BF-640 governed manual next-decision plan"
    }
    $planText = $Summary.Substring($planMarkers[0].Index)
    $policy = Get-LineValue -Text $planText -Label "Plan policy:"
    $planState = Get-LineValue -Text $planText -Label "Plan state:"
    $instruction = Get-LineValue -Text $planText -Label "Operator instruction:"
    if ([string]::IsNullOrWhiteSpace($policy)) {
        throw "BF-657 BLOCKED: BF-640 plan policy is missing"
    }
    if ($planState -cne "NEXT_DECISION_PLAN_READY") {
        throw "BF-657 BLOCKED: completed/converged lifecycle requires BF-640 NEXT_DECISION_PLAN_READY"
    }
    if ([string]::IsNullOrWhiteSpace($instruction)) {
        throw "BF-657 BLOCKED: BF-640 operator instruction is missing"
    }

    $stepPattern = '(?m)^ {2}(?<order>\d+)\. (?<bf>[^|\r\n]+?) \| (?<mode>[^|\r\n]+?) \| (?<task>[^\r\n]+)\r?\n {5}(?<command>[^\r\n]+)\r?\n {5}Purpose: (?<purpose>[^\r\n]+)$'
    $stepMatches = [regex]::Matches($planText, $stepPattern)
    if ($stepMatches.Count -ne 9) {
        throw "BF-657 BLOCKED: BF-640 ready plan must contain exactly nine rendered steps"
    }

    $steps = @()
    for ($index = 0; $index -lt $stepMatches.Count; $index++) {
        $match = $stepMatches[$index]
        $order = [int]$match.Groups['order'].Value
        if ($order -ne ($index + 1)) {
            throw "BF-657 BLOCKED: BF-640 step order is malformed or non-contiguous"
        }
        $mode = $match.Groups['mode'].Value.Trim()
        if ($mode -cne "BUTLER_WRITE" -and $mode -cne "READ_ONLY") {
            throw "BF-657 BLOCKED: BF-640 step mode is unsupported: $mode"
        }
        $steps += [pscustomobject]@{
            Order = $order
            Bf = $match.Groups['bf'].Value.Trim()
            Mode = $match.Groups['mode'].Value.Trim()
            TaskName = $match.Groups['task'].Value.Trim()
            Command = $match.Groups['command'].Value.Trim()
            Purpose = $match.Groups['purpose'].Value.Trim()
        }
    }

    return [pscustomobject]@{
        Active = $true
        Policy = $policy
        State = $planState
        Instruction = $instruction
        Steps = @($steps)
    }
}

'''
if anchor not in text:
    raise SystemExit('BF-657 function anchor not found')
text = text.replace(anchor, insert + anchor, 1)

anchor = '''    $explanation = Get-GovernedExplanationView -Summary $Summary
'''
insert = r'''    $nextDecisionPlan = Get-GovernedNextDecisionPlanView -Summary $Summary
    $nextDecisionPlanSection = ""
    if ($nextDecisionPlan.Active) {
        $nextDecisionCards = ""
        foreach ($step in $nextDecisionPlan.Steps) {
            $modeClass = if ($step.Mode -ceq "READ_ONLY") { "read" } else { "write" }
            $nextDecisionCards += @"
<article class="refresh-step">
  <div class="refresh-step-head"><span class="refresh-step-num">$(ConvertTo-HtmlText $step.Order)</span><span class="refresh-step-bf">$(ConvertTo-HtmlText $step.Bf)</span><span class="refresh-mode $modeClass">$(ConvertTo-HtmlText $step.Mode)</span></div>
  <div class="refresh-task">$(ConvertTo-HtmlText $step.TaskName)</div>
  <div class="refresh-copy-hint">Copy safely: focus the read-only field, then Press Ctrl+A, then Ctrl+C.</div>
  <textarea class="refresh-command-copy" rows="2" readonly>$(ConvertTo-HtmlText $step.Command)</textarea>
  <div class="refresh-purpose">$(ConvertTo-HtmlText $step.Purpose)</div>
</article>
"@
        }
        $nextDecisionPlanSection = @"
<section class="panel">
  <div class="eyebrow">Governed manual next-decision plan</div>
  <h2>Start Butler's next decision safely</h2>
  <p class="lede">The prior audited transaction is closed and roster convergence is verified. Run these manually, one at a time, and inspect each result before continuing.</p>
  <div class="refresh-plan-note"><strong>MANUAL ONLY.</strong> BF-640 executes none of these commands, and this dashboard does not run them for you. `BUTLER_WRITE` means the listed CLI task persists governed Butler data; `READ_ONLY` means it only projects/revalidates governed state.</div>
  <div class="refresh-steps">$nextDecisionCards</div>
  <details><summary>Technical details</summary><div class="tech"><div>BF-640 plan state: $(ConvertTo-HtmlText $nextDecisionPlan.State)</div><div>BF-640 plan policy: $(ConvertTo-HtmlText $nextDecisionPlan.Policy)</div><div>Governed step count: $($nextDecisionPlan.Steps.Count)</div><div>Source: existing compact governed decision summary</div></div><div class="raw-guard">$(ConvertTo-HtmlText $nextDecisionPlan.Instruction)</div></details>
</section>
"@
    }

'''
if anchor not in text:
    raise SystemExit('BF-657 render anchor not found')
text = text.replace(anchor, insert + anchor, 1)

old = '''$refreshPlanSection
$whySection
'''
new = '''$refreshPlanSection
$nextDecisionPlanSection
$whySection
'''
if old not in text:
    raise SystemExit('BF-657 placement anchor not found')
text = text.replace(old, new, 1)

old = '''<section class="panel boundary"><span class="lock">READ ONLY.</span> If BF-636 manual refresh instructions are shown, they are copyable operator instructions only. Butler does not execute those commands, refresh evidence, rerank players, capture an audit, set FAAB, submit a Sleeper transaction, cancel a transaction, or mutate your league from this dashboard.</section>
'''
new = '''<section class="panel boundary"><span class="lock">READ ONLY.</span> If BF-636 refresh or BF-640 next-decision manual instructions are shown, they are copyable operator instructions only. Butler does not execute those commands, refresh evidence, rerank players, capture an audit, set FAAB, submit a Sleeper transaction, cancel a transaction, or mutate your league from this dashboard.</section>
'''
if old not in text:
    raise SystemExit('BF-657 boundary anchor not found')
text = text.replace(old, new, 1)

path.write_text(text, encoding='ascii')
