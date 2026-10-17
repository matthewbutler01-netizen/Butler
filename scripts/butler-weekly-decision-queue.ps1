function Get-ButlerWeeklyDecisionQueue {
    param([string]$LineupState, [string]$WaiverState, [string]$Actionability, [string]$Lineage)

    # Presentation model only. Dashboard has no independent live starter proof.
    $lineup = [pscustomobject]@{
        Kind = 'Lineup'; Status = 'REVIEW EVIDENCE'; Priority = 0
        Reason = 'Open Start/Sit for a current review. Dashboard does not independently verify current starters or player availability.'
        Href = '/matchup/autofill'; Label = 'Review Start/Sit'
    }
    if (@('EVIDENCE GAP','REFRESH AUTOFILL','NEEDS ATTENTION','RECHECK LINEUP') -ccontains $LineupState) {
        $lineup.Status = 'LINEUP HELD'
        $lineup.Reason = 'The saved lineup state needs an evidence or starter check. Review the source page before changing your lineup; this is a review task, not a recommended swap.'
    }
    elseif (@('NOT REVIEWED','AUTOFILL READY','NO CHANGES') -ccontains $LineupState) {
        $lineup.Priority = 1
        $lineup.Status = 'SOURCE REVIEW'
    }

    $waiver = [pscustomobject]@{
        Kind = 'Waivers'; Status = 'WAIVER HELD'; Priority = 0
        Reason = 'The saved waiver evidence is incomplete, stale or unverified. Review the Waiver Board; do not reuse a saved move as current advice.'
        Href = '/waivers'; Label = 'Review Waiver Board'
    }
    if ($WaiverState -ceq 'TRANSACTION_PENDING_DO_NOT_DUPLICATE') {
        $waiver.Status = 'DO NOT DUPLICATE'
        $waiver.Reason = 'The recorded transaction is pending. Check its history before considering another move.'
        $waiver.Href = '/history?load=1'; $waiver.Label = 'Review transaction history'
    }
    elseif ($WaiverState -ceq 'TRANSACTION_ALREADY_COMPLETE') {
        $waiver.Status = 'RECORDED COMPLETE'; $waiver.Priority = 2
        $waiver.Reason = 'The recorded transaction is complete. No duplicate action is needed; this does not certify current free-agent coverage.'
        $waiver.Href = '/history?load=1'; $waiver.Label = 'Review transaction history'
    }
    elseif ($WaiverState -ceq 'CURRENT_AND_ACTIONABLE' -and
            $Actionability -ceq 'LIVE_ACTIONABLE_VERIFIED' -and
            $Lineage -ceq 'LATEST_EVIDENCE_LINEAGE_VERIFIED') {
        $waiver.Status = 'RECORDED MOVE TO REVIEW'; $waiver.Priority = 1
        $waiver.Reason = 'The recorded waiver move passed the Dashboard audit checks. Review the exact add/drop pair and source coverage on the Waiver Board; this is not live injury clearance.'
    }
    elseif ($WaiverState -ceq 'NO_TRANSACTION_TO_ACT_ON' -and
            $Actionability -ceq 'NO_TRANSACTION_TO_REVALIDATE' -and
            $Lineage -ceq 'LATEST_EVIDENCE_LINEAGE_VERIFIED') {
        $waiver.Status = 'NO RECORDED MOVE'; $waiver.Priority = 2
        $waiver.Reason = 'This evidence frame has no recorded waiver transaction. That is not proof that no useful free agent exists today.'
    }
    return @(@($lineup, $waiver) | Sort-Object Priority, Kind)
}

function ConvertTo-ButlerWeeklyDecisionQueueHtml {
    param([Parameter(Mandatory=$true)][object[]]$Items, [string]$Team)
    $cards = foreach ($item in $Items) {
        # Routes are fixed by the model; never render a supplied external URL.
        $href = if (@('/matchup/autofill','/waivers','/history?load=1') -ccontains [string]$item.Href) { [string]$item.Href } else { '/' }
        '<li><div class="eyebrow">' + [Net.WebUtility]::HtmlEncode([string]$item.Kind) +
            '</div><strong>' + [Net.WebUtility]::HtmlEncode([string]$item.Status) +
            '</strong><p>' + [Net.WebUtility]::HtmlEncode([string]$item.Reason) +
            '</p><a href="' + $href + '">' + [Net.WebUtility]::HtmlEncode([string]$item.Label) + '</a></li>'
    }
    return '<section class="panel weekly-decision-queue" aria-label="Weekly decision queue"><style>.weekly-decision-queue{min-width:0}.weekly-decision-queue ol{padding-left:22px}.weekly-decision-queue li{padding:12px 0;border-bottom:1px solid var(--line);overflow-wrap:anywhere}.weekly-decision-queue li:last-child{border:0}.weekly-decision-queue p{max-width:75ch}.weekly-decision-queue a{display:inline-block;padding:8px 0}</style><div class="eyebrow">Your next reviews</div><h2>Weekly decision queue</h2><p>' +
        [Net.WebUtility]::HtmlEncode($Team) +
        '</p><p>Holds come first. These are review tasks; Butler submits no roster changes. Week and source freshness must be checked on the linked pages.</p><ol>' + ($cards -join '') + '</ol></section>'
}
