param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-1010 BLOCKED: staged Butler core not found at $CorePath"
}

function Replace-ExactlyOnce {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Old,
        [Parameter(Mandatory = $true)][string]$New,
        [Parameter(Mandatory = $true)][string]$Contract
    )
    $matches = [regex]::Matches($Text, [regex]::Escape($Old)).Count
    if ($matches -ne 1) { throw "BF-1010 BLOCKED: $Contract expected one match, found $matches." }
    return $Text.Replace($Old, $New)
}

$core = [System.IO.File]::ReadAllText($CorePath)
foreach ($required in @(
    '$managerMoveCount = [Math]::Max(',
    '$slotChangeCount = [int]$changedCount',
    '$expertConflictTail',
    '$queueContextHtml',
    'Review $reviewQueueCount unresolved $reviewQueueNoun'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-1010 BLOCKED: required prior lineup decision-count capability is missing: $required"
    }
}

$functionStart = $core.IndexOf('function ConvertTo-AutoFillHtml {', [System.StringComparison]::Ordinal)
$functionEnd = $core.IndexOf('function ConvertTo-TeamHtml {', $functionStart, [System.StringComparison]::Ordinal)
if ($functionStart -lt 0 -or $functionEnd -le $functionStart) {
    throw 'BF-1010 BLOCKED: final Lineup Advisor renderer boundary is missing.'
}
$function = $core.Substring($functionStart, $functionEnd - $functionStart)

$loopAnchor = '    foreach ($assignment in @($AutoFill.Assignments)) {'
$loopSetup = @'
    $aggregateSlotPlacements = $slotChangeCount -gt 0 -and $slotChangeCount -ne $managerMoveCount
    $slotPlacementContextItems = ''
    if ($aggregateSlotPlacements -and $managerMoveCount -gt 0) {
        $promotionMoves = @($AutoFill.Promotions)
        $benchMoves = @($AutoFill.BenchMoves)
        for ($managerMoveIndex = 0; $managerMoveIndex -lt $managerMoveCount; $managerMoveIndex++) {
            $promotionName = if ($managerMoveIndex -lt $promotionMoves.Count -and
                $null -ne $promotionMoves[$managerMoveIndex].PSObject.Properties['Name'] -and
                -not [string]::IsNullOrWhiteSpace([string]$promotionMoves[$managerMoveIndex].Name)) {
                [string]$promotionMoves[$managerMoveIndex].Name
            }
            else {
                ''
            }
            $benchName = if ($managerMoveIndex -lt $benchMoves.Count -and
                $null -ne $benchMoves[$managerMoveIndex].PSObject.Properties['Name'] -and
                -not [string]::IsNullOrWhiteSpace([string]$benchMoves[$managerMoveIndex].Name)) {
                [string]$benchMoves[$managerMoveIndex].Name
            }
            else {
                ''
            }
            $moveNumber = $managerMoveIndex + 1
            $moveText = if (-not [string]::IsNullOrWhiteSpace($benchName) -and -not [string]::IsNullOrWhiteSpace($promotionName)) {
                "$(ConvertTo-HtmlText $benchName) &rarr; $(ConvertTo-HtmlText $promotionName)"
            }
            elseif (-not [string]::IsNullOrWhiteSpace($promotionName)) {
                "open starting slot &rarr; $(ConvertTo-HtmlText $promotionName)"
            }
            elseif (-not [string]::IsNullOrWhiteSpace($benchName)) {
                "$(ConvertTo-HtmlText $benchName) &rarr; bench"
            }
            else {
                'review the exact promotion/bench summary'
            }
            $queueItems += "<li><strong>Manager move $moveNumber</strong>: review $moveText. Optimizer slot placements are supporting evidence for this move, not separate decisions.</li>"
        }
    }
'@

$function = Replace-ExactlyOnce -Text $function -Old $loopAnchor -New ($loopSetup.TrimEnd() + "`n" + $loopAnchor) -Contract 'slot-placement aggregation setup'

$changedOld = @'
        if ($assignment.Changed) {
            $comparisonTail = if ($comparisonLinks.Length -gt 0) { " Comparison evidence:$comparisonLinks" } else { '' }
            $queueItems += "<li><strong>$(ConvertTo-HtmlText $assignment.Slot)</strong>: optimizer slot placement $(ConvertTo-HtmlText $assignment.Current) &rarr; $(ConvertTo-HtmlText $assignment.Recommended). This may be part of the same manager move; check the promotion/bench summary and comparison evidence.$expertConflictTail$comparisonTail</li>"
        }
'@

$changedNew = @'
        if ($assignment.Changed) {
            $comparisonTail = if ($comparisonLinks.Length -gt 0) { " Comparison evidence:$comparisonLinks" } else { '' }
            if ($aggregateSlotPlacements) {
                $placementExpertTail = if ($managerMoveCount -eq 0) { '' } else { $expertConflictTail }
                $placementComparisonTail = if ($managerMoveCount -eq 0 -and $expertConflictTail.Length -gt 0) { '' } else { $comparisonTail }
                $slotPlacementContextItems += "<li><strong>$(ConvertTo-HtmlText $assignment.Slot)</strong>: optimizer slot placement $(ConvertTo-HtmlText $assignment.Current) &rarr; $(ConvertTo-HtmlText $assignment.Recommended).$placementExpertTail$placementComparisonTail</li>"
                if ($managerMoveCount -eq 0 -and $expertConflictTail.Length -gt 0) {
                    $queueItems += "<li><strong>$(ConvertTo-HtmlText $assignment.Current)</strong>: current starter with an attributed SIT selection from $(ConvertTo-HtmlText $matchingPicks[0].author). Review scoring, roster fit and the source below; this opinion does not establish consensus. <a href=`"#lineup-expert-$pickIndex`">Review expert source</a>$comparisonTail</li>"
                }
            }
            else {
                $queueItems += "<li><strong>$(ConvertTo-HtmlText $assignment.Slot)</strong>: optimizer slot placement $(ConvertTo-HtmlText $assignment.Current) &rarr; $(ConvertTo-HtmlText $assignment.Recommended). This is the manager move under review; check the promotion/bench summary and comparison evidence.$expertConflictTail$comparisonTail</li>"
            }
        }
'@

$function = Replace-ExactlyOnce -Text $function -Old $changedOld.TrimEnd() -New $changedNew.TrimEnd() -Contract 'slot-placement unresolved-task de-count'

$contextOld = @'
    $queueContextHtml = if ($queueContextItems.Length -gt 0) {
        "<details class=`"lineup-review-context`"><summary>Replacement search context</summary><p class=`"meta`">This explains replacement coverage; it does not add another manager decision.</p><ul>$queueContextItems</ul></details>"
    }
    else {
        ''
    }
'@

$contextNew = @'
    $queueContextHtml = ''
    if ($slotPlacementContextItems.Length -gt 0) {
        $slotPlacementNoun = if ($slotChangeCount -eq 1) { 'placement' } else { 'placements' }
        $queueContextHtml += "<details class=`"lineup-review-context`"><summary>Optimizer slot placement evidence ($slotChangeCount $slotPlacementNoun)</summary><p class=`"meta`">These rows explain how the optimizer arranged legal scoreable slots. They do not add unresolved manager decisions beyond the promotion/bench summary.</p><ul>$slotPlacementContextItems</ul></details>"
    }
    if ($queueContextItems.Length -gt 0) {
        $queueContextHtml += "<details class=`"lineup-review-context`"><summary>Replacement search context</summary><p class=`"meta`">This explains replacement coverage; it does not add another manager decision.</p><ul>$queueContextItems</ul></details>"
    }
'@

$function = Replace-ExactlyOnce -Text $function -Old $contextOld.TrimEnd() -New $contextNew.TrimEnd() -Contract 'slot-placement supporting context'

$queueOld = @'
    $reviewQueueReturn = ''
    if ($queueItems.Length -gt 0) {
        $reviewQueueCount = [regex]::Matches($queueItems, '<li>').Count
        $reviewQueueNoun = if ($reviewQueueCount -eq 1) { 'item' } else { 'items' }
        $reviewQueueBadge = "$reviewQueueCount $($reviewQueueNoun.ToUpperInvariant())"
        $decisionTitle = "Review $reviewQueueCount unresolved $reviewQueueNoun"
        $reviewQueueReturn = '<p><a href="#lineup-review-queue">Back to review queue</a></p>'
        $holdEvidenceHtml = "<section id=`"lineup-review-queue`" tabindex=`"-1`" class=`"swap-review-card review-queue`"><div class=`"lineup-review-queue-head`"><h3>Review queue</h3><span class=`"status warn`">$reviewQueueBadge</span></div><p class=`"meta`">Unresolved signals may overlap. Projection totals do not settle these decisions.</p><ul>$queueItems</ul>$queueContextHtml</section>$holdEvidenceHtml"
    }
'@

$queueNew = @'
    $reviewQueueReturn = ''
    if ($queueItems.Length -gt 0) {
        $reviewQueueCount = [regex]::Matches($queueItems, '<li>').Count
        $reviewQueueNoun = if ($reviewQueueCount -eq 1) { 'item' } else { 'items' }
        $reviewQueueBadge = "$reviewQueueCount $($reviewQueueNoun.ToUpperInvariant())"
        $decisionTitle = "Review $reviewQueueCount unresolved $reviewQueueNoun"
        $reviewQueueReturn = '<p><a href="#lineup-review-queue">Back to review queue</a></p>'
        $holdEvidenceHtml = "<section id=`"lineup-review-queue`" tabindex=`"-1`" class=`"swap-review-card review-queue`"><div class=`"lineup-review-queue-head`"><h3>Review queue</h3><span class=`"status warn`">$reviewQueueBadge</span></div><p class=`"meta`">Unresolved signals may overlap. Projection totals do not settle these decisions.</p><ul>$queueItems</ul>$queueContextHtml</section>$holdEvidenceHtml"
    }
    elseif ($queueContextHtml.Length -gt 0) {
        $holdEvidenceHtml = "<section class=`"swap-review-card review-queue`"><h3>Supporting lineup evidence</h3><p class=`"meta`">Optimizer slot placement alone does not create another manager decision.</p>$queueContextHtml</section>$holdEvidenceHtml"
    }
'@

$function = Replace-ExactlyOnce -Text $function -Old $queueOld.TrimEnd() -New $queueNew.TrimEnd() -Contract 'context-only lineup evidence disclosure'

$comparisonRenderOld = @'
        if ([string]$review.status -ceq 'MANUAL_REVIEW_REPLACEMENT') {
            $holdEvidenceHtml += "<details class=`"swap-review-card`"><summary>Bench comparison: $(ConvertTo-HtmlText $review.current) &rarr; $(ConvertTo-HtmlText $review.proposed)</summary>$comparisonHtml</details>"
        } else { $holdEvidenceHtml += $comparisonHtml }
'@

$comparisonRenderNew = @'
        $slotPlacementReview = $false
        if ($aggregateSlotPlacements) {
            $matchingSlotAssignments = @($AutoFill.Assignments | Where-Object {
                $_.Changed -and [string]$_.Ordinal -ceq [string]$review.ordinal
            })
            $slotPlacementReview = $matchingSlotAssignments.Count -eq 1
        }
        if ($slotPlacementReview) {
            $comparisonId = "lineup-comparison-$reviewIndex"
            $comparisonHtml = $comparisonHtml.Replace(
                "id=`"$comparisonId`" tabindex=`"-1`" class=`"swap-review-card`"",
                "class=`"swap-review-card slot-placement-review-body`""
            )
            $holdEvidenceHtml += "<details id=`"$comparisonId`" tabindex=`"-1`" class=`"swap-review-card slot-placement-review`"><summary>Slot placement evidence: $(ConvertTo-HtmlText $review.slot) - $(ConvertTo-HtmlText $review.current) &rarr; $(ConvertTo-HtmlText $review.proposed)</summary>$comparisonHtml</details>"
        }
        elseif ([string]$review.status -ceq 'MANUAL_REVIEW_REPLACEMENT') {
            $holdEvidenceHtml += "<details class=`"swap-review-card`"><summary>Bench comparison: $(ConvertTo-HtmlText $review.current) &rarr; $(ConvertTo-HtmlText $review.proposed)</summary>$comparisonHtml</details>"
        }
        else {
            $holdEvidenceHtml += $comparisonHtml
        }
'@

$function = Replace-ExactlyOnce -Text $function -Old $comparisonRenderOld.TrimEnd() -New $comparisonRenderNew.TrimEnd() -Contract 'internal slot-placement comparison collapse'

$core = $core.Substring(0, $functionStart) + $function + $core.Substring($functionEnd)

foreach ($required in @(
    '$aggregateSlotPlacements = $slotChangeCount -gt 0 -and $slotChangeCount -ne $managerMoveCount',
    'Optimizer slot placement evidence',
    'not separate decisions',
    'Supporting lineup evidence'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-1010 BLOCKED: slot-placement decision-count marker is missing: $required"
    }
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))
$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($CorePath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $summary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-1010 BLOCKED: generated staged core failed PowerShell parse: $summary"
}

Write-Host 'BF-1010 Lineup slot-placement decision count applied.'
