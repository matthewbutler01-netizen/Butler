# BF-670 read-only Trade Analyzer app module.
# Dot-sourced by butler-app-shell.ps1. The app shell remains responsible for loopback routing.

function ConvertFrom-TradeRequestTarget {
    param([Parameter(Mandatory = $true)][string]$RequestTarget)

    $query = @{}
    $question = $RequestTarget.IndexOf('?')
    if ($question -lt 0 -or $question + 1 -ge $RequestTarget.Length) { return $query }

    $rawQuery = $RequestTarget.Substring($question + 1)
    foreach ($pair in ($rawQuery -split '&')) {
        if ([string]::IsNullOrWhiteSpace($pair)) { continue }
        $parts = $pair.Split(@('='), 2)
        $rawKey = $parts[0].Replace('+', ' ')
        $rawValue = if ($parts.Length -eq 2) { $parts[1].Replace('+', ' ') } else { '' }
        $key = [System.Uri]::UnescapeDataString($rawKey)
        $value = [System.Uri]::UnescapeDataString($rawValue)
        if ([string]::IsNullOrWhiteSpace($key)) { continue }
        if ($query.ContainsKey($key)) {
            $query[$key] = @($query[$key]) + @($value)
        }
        else {
            $query[$key] = @($value)
        }
    }
    return $query
}

function Get-TradeQueryValues {
    param(
        [Parameter(Mandatory = $true)]$Query,
        [Parameter(Mandatory = $true)][string]$Name
    )
    if (-not $Query.ContainsKey($Name)) { return @() }
    return @($Query[$Name])
}

function Get-TradeQueryFirst {
    param(
        [Parameter(Mandatory = $true)]$Query,
        [Parameter(Mandatory = $true)][string]$Name
    )
    $values = @(Get-TradeQueryValues -Query $Query -Name $Name)
    if ($values.Count -eq 0) { return $null }
    return [string]$values[0]
}

function ConvertTo-TradeInventoryView {
    param([Parameter(Mandatory = $true)][string]$Text)

    $league = [regex]::Match($Text, '(?m)^League ID:\s+(?<id>\S+)\s*$')
    $source = [regex]::Match($Text, '(?m)^Source:\s+(?<source>.+?)\s*$')
    $coverage = [regex]::Match($Text, '(?m)^Coverage:\s+(?<valued>\d+)/(?<total>\d+)\s+assets\s+\((?<coverage>[0-9.]+)%\)\s+missing-players=(?<missingPlayers>\d+)\s+missing-picks=(?<missingPicks>\d+)\s*$')
    if (-not $league.Success -or -not $source.Success -or -not $coverage.Success) {
        throw 'BF-670 BLOCKED: governed league asset inventory is missing required header fields.'
    }

    $teams = @()
    $current = $null
    foreach ($line in ($Text -split "`r?`n")) {
        $team = [regex]::Match($line, '^(?<name>\S.*?)\s{2}assets=(?<assets>\d+)\s+\[(?<id>[^\]]+)\]\s*$')
        if ($team.Success) {
            $current = [pscustomobject]@{
                Name = $team.Groups['name'].Value.Trim()
                TeamId = $team.Groups['id'].Value.Trim()
                DeclaredAssets = [int]$team.Groups['assets'].Value
                Assets = @()
            }
            $teams += $current
            continue
        }
        if ($null -eq $current) { continue }

        $valuedPlayer = [regex]::Match($line, '^\s{2}PLAYER\s+(?<value>-?\d+(?:\.\d+)?)\s+(?<label>.+?)\s+slot=(?<slot>\S+)\s+as-of=(?<asof>\S+)\s+\[(?<id>[^\]]+)\]\s*$')
        if ($valuedPlayer.Success) {
            $id = $valuedPlayer.Groups['id'].Value.Trim()
            $current.Assets += [pscustomobject]@{
                Type = 'player'; Id = $id; Token = "player:$id"; Label = $valuedPlayer.Groups['label'].Value.Trim()
                Slot = $valuedPlayer.Groups['slot'].Value.Trim(); Valued = $true; Value = $valuedPlayer.Groups['value'].Value
                AsOf = $valuedPlayer.Groups['asof'].Value.Trim()
            }
            continue
        }

        $missingPlayer = [regex]::Match($line, '^\s{2}PLAYER\s+MISSING\s+(?<label>.+?)\s+slot=(?<slot>\S+)\s+\[(?<id>[^\]]+)\]\s*$')
        if ($missingPlayer.Success) {
            $id = $missingPlayer.Groups['id'].Value.Trim()
            $current.Assets += [pscustomobject]@{
                Type = 'player'; Id = $id; Token = "player:$id"; Label = $missingPlayer.Groups['label'].Value.Trim()
                Slot = $missingPlayer.Groups['slot'].Value.Trim(); Valued = $false; Value = 'MISSING'; AsOf = '-'
            }
            continue
        }

        $valuedPick = [regex]::Match($line, '^\s{2}PICK\s+(?<value>-?\d+(?:\.\d+)?)\s+(?<label>.+?)\s+as-of=(?<asof>\S+)\s+\[(?<id>[^\]]+)\]\s*$')
        if ($valuedPick.Success) {
            $id = $valuedPick.Groups['id'].Value.Trim()
            $current.Assets += [pscustomobject]@{
                Type = 'pick'; Id = $id; Token = "pick:$id"; Label = $valuedPick.Groups['label'].Value.Trim()
                Slot = 'PICK'; Valued = $true; Value = $valuedPick.Groups['value'].Value; AsOf = $valuedPick.Groups['asof'].Value.Trim()
            }
            continue
        }

        $missingPick = [regex]::Match($line, '^\s{2}PICK\s+MISSING\s+(?<label>.+?)\s+\[(?<id>[^\]]+)\]\s*$')
        if ($missingPick.Success) {
            $id = $missingPick.Groups['id'].Value.Trim()
            $current.Assets += [pscustomobject]@{
                Type = 'pick'; Id = $id; Token = "pick:$id"; Label = $missingPick.Groups['label'].Value.Trim()
                Slot = 'PICK'; Valued = $false; Value = 'MISSING'; AsOf = '-'
            }
        }
    }

    if ($teams.Count -lt 2) {
        throw 'BF-670 BLOCKED: governed league asset inventory did not contain at least two teams.'
    }
    foreach ($team in $teams) {
        if ($team.Assets.Count -ne $team.DeclaredAssets) {
            throw "BF-670 BLOCKED: parsed asset count for $($team.Name) does not match governed inventory."
        }
    }

    return [pscustomobject]@{
        LeagueId = $league.Groups['id'].Value.Trim()
        Source = $source.Groups['source'].Value.Trim()
        ValuedAssets = [int]$coverage.Groups['valued'].Value
        TotalAssets = [int]$coverage.Groups['total'].Value
        Coverage = $coverage.Groups['coverage'].Value
        MissingPlayers = [int]$coverage.Groups['missingPlayers'].Value
        MissingPicks = [int]$coverage.Groups['missingPicks'].Value
        Teams = @($teams)
    }
}

function Get-TradeTeamById {
    param(
        [Parameter(Mandatory = $true)]$Inventory,
        [Parameter(Mandatory = $true)][string]$TeamId,
        [Parameter(Mandatory = $true)][string]$BoundaryName
    )
    $matches = @($Inventory.Teams | Where-Object { $_.TeamId -ceq $TeamId })
    if ($matches.Count -ne 1) {
        throw "BF-670 BLOCKED: $BoundaryName did not resolve exactly one current persisted team."
    }
    return $matches[0]
}

function Assert-TradeAssetSelection {
    param(
        [Parameter(Mandatory = $true)]$Team,
        [Parameter(Mandatory = $true)][object[]]$Tokens,
        [Parameter(Mandatory = $true)][string]$SideName
    )

    if ($Tokens.Count -eq 0) {
        throw "BF-670 BLOCKED: $SideName requires at least one selected asset."
    }
    if ($Tokens.Count -gt 20) {
        throw "BF-670 BLOCKED: $SideName is limited to 20 exact assets per evaluation."
    }

    $allowed = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    foreach ($asset in $Team.Assets) { [void]$allowed.Add([string]$asset.Token) }
    $seen = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    $validated = @()
    foreach ($raw in $Tokens) {
        $token = ([string]$raw).Trim()
        if (-not $allowed.Contains($token)) {
            throw "BF-670 BLOCKED: $SideName contains an asset that is not currently owned by $($Team.Name)."
        }
        if (-not $seen.Add($token)) {
            throw "BF-670 BLOCKED: $SideName contains a duplicate asset."
        }
        $validated += $token
    }
    return @($validated)
}

function ConvertTo-TradeRecommendationView {
    param([Parameter(Mandatory = $true)][string]$Text)

    $perspective = [regex]::Match($Text, '(?m)^Perspective:\s+(?<name>.*?)\s+\[(?<id>[^\]]+)\]\s*$')
    $evidence = [regex]::Match($Text, '(?m)^Evidence complete:\s+(?<value>true|false)\s*$')
    $gatesV6 = [regex]::Match($Text, '(?m)^Evidence gates:\s+market-direction=(?<market>true|false)\s+future-capital=(?<capital>true|false)\s+positional-pressure=(?<position>true|false)\s+flexible-pressure=(?<flex>true|false)\s+advisory-posture=(?<posture>true|false)\s*$')
    $gatesV5 = [regex]::Match($Text, '(?m)^Evidence gates:\s+market-direction=(?<market>true|false)\s+posture=(?<posture>true|false)\s+future-capital=(?<capital>true|false)\s+positional-pressure=(?<position>true|false)\s+flexible-pressure=(?<flex>true|false)\s*$')
    $gates = if ($gatesV6.Success) { $gatesV6 } else { $gatesV5 }
    $postureAdvisory = $gatesV6.Success
    $pressure = [regex]::Match($Text, '(?m)^Flexible pressure:\s+(?<value>\S+)\s*$')
    $veto = [regex]::Match($Text, '(?m)^Strategic veto:\s+(?<value>\S+)\s*$')
    $recommendation = [regex]::Match($Text, '(?m)^Package recommendation:\s+(?<value>\S+)\s*$')
    $action = [regex]::Match($Text, '(?m)^Action:\s+(?<value>\S+)\s*$')
    if (-not $perspective.Success -or -not $evidence.Success -or -not $gates.Success -or
        -not $pressure.Success -or -not $veto.Success -or -not $recommendation.Success -or -not $action.Success) {
        throw 'BF-670 BLOCKED: governed trade recommendation is missing required app fields.'
    }

    $reason = [regex]::Match($Text, '(?m)^Reason:\s+(?<value>.+?)\s*$')
    $pressureReason = [regex]::Match($Text, '(?m)^Flexible pressure reason:\s+(?<value>.+?)\s*$')
    $protectedCoverage = [regex]::Match($Text, '(?m)^Flexible protected coverage:\s+(?<value>.+?)\s*$')
    $transition = [regex]::Match($Text, '(?m)^Flexible pressure transition:\s+(?<before>\S+)\s+->\s+(?<after>\S+)\s*$')
    $transitionState = [regex]::Match($Text, '(?m)^Flexible transition state:\s+(?<value>\S+)\s*$')
    $transitionCoverage = [regex]::Match($Text, '(?m)^Flexible transition coverage:\s+(?<value>.+?)\s*$')
    $vetoReasons = @()
    foreach ($line in ($Text -split "`r?`n")) {
        $match = [regex]::Match($line, '^Veto reason:\s+(?<value>.+?)\s*$')
        if ($match.Success) { $vetoReasons += $match.Groups['value'].Value.Trim() }
    }

    return [pscustomobject]@{
        PerspectiveName = $perspective.Groups['name'].Value.Trim()
        PerspectiveTeamId = $perspective.Groups['id'].Value.Trim()
        EvidenceComplete = $evidence.Groups['value'].Value -ceq 'true'
        MarketGate = $gates.Groups['market'].Value -ceq 'true'
        PostureGate = $gates.Groups['posture'].Value -ceq 'true'
        PostureAdvisory = $postureAdvisory
        FutureCapitalGate = $gates.Groups['capital'].Value -ceq 'true'
        PositionGate = $gates.Groups['position'].Value -ceq 'true'
        FlexibleGate = $gates.Groups['flex'].Value -ceq 'true'
        FlexiblePressure = $pressure.Groups['value'].Value.Trim()
        FlexiblePressureReason = if ($pressureReason.Success) { $pressureReason.Groups['value'].Value.Trim() } else { '' }
        ProtectedCoverage = if ($protectedCoverage.Success) { $protectedCoverage.Groups['value'].Value.Trim() } else { '' }
        Transition = if ($transition.Success) { "$($transition.Groups['before'].Value) -> $($transition.Groups['after'].Value)" } else { '' }
        TransitionState = if ($transitionState.Success) { $transitionState.Groups['value'].Value.Trim() } else { '' }
        TransitionCoverage = if ($transitionCoverage.Success) { $transitionCoverage.Groups['value'].Value.Trim() } else { '' }
        StrategicVeto = $veto.Groups['value'].Value.Trim()
        VetoReasons = @($vetoReasons)
        PackageRecommendation = $recommendation.Groups['value'].Value.Trim()
        Action = $action.Groups['value'].Value.Trim()
        Reason = if ($reason.Success) { $reason.Groups['value'].Value.Trim() } else { '' }
        Raw = $Text
    }
}

function ConvertTo-TradeCounterProposalView {
    param([Parameter(Mandatory = $true)][string]$Text)

    $perspective = [regex]::Match($Text, '(?m)^Perspective:\s+(?<name>.*?)\s+\[(?<id>[^\]]+)\]\s*$')
    $v6Action = [regex]::Match($Text, '(?m)^V6 team action:\s+(?<value>\S+)\s*$')
    $opportunity = [regex]::Match($Text, '(?m)^Counter opportunity:\s+(?<value>\S+)\s*$')
    $selection = [regex]::Match($Text, '(?m)^Counter candidate selection:\s+(?<value>\S+)\s*$')
    $action = [regex]::Match($Text, '(?m)^Counter action:\s+(?<value>\S+)\s*$')
    $reason = [regex]::Match($Text, '(?m)^Counter action reason:\s+(?<value>\S+)\s*$')
    $materialized = [regex]::Match($Text, '(?m)^Counter materialized package state:\s+(?<value>\S+)\s*$')
    $materializedReason = [regex]::Match($Text, '(?m)^Counter materialized package reason:\s+(?<value>\S+)\s*$')
    $messageState = [regex]::Match($Text, '(?m)^Counter negotiation message state:\s+(?<value>\S+)\s*$')
    $messageReason = [regex]::Match($Text, '(?m)^Counter negotiation message reason:\s+(?<value>\S+)\s*$')
    if (-not $perspective.Success -or -not $v6Action.Success -or -not $opportunity.Success -or
        -not $selection.Success -or -not $action.Success -or -not $reason.Success -or
        -not $materialized.Success -or -not $materializedReason.Success -or
        -not $messageState.Success -or -not $messageReason.Success) {
        throw 'BF-878 BLOCKED: governed counter proposal is missing required app fields.'
    }

    $counter = [regex]::Match($Text, '(?m)^COUNTER:\s+(?<value>.+?)\s*$')
    $revisedA = [regex]::Match($Text, '(?m)^Revised Side A package:\s+(?<value>.+?)\s*$')
    $revisedB = [regex]::Match($Text, '(?m)^Revised Side B package:\s+(?<value>.+?)\s*$')
    $message = [regex]::Match($Text, '(?m)^Negotiation message:\s+(?<value>.+?)\s*$')
    $actor = [regex]::Match($Text, '(?m)^Counter negotiation actor:\s+(?<value>\S+)\s*$')
    $fingerprint = [regex]::Match($Text, '(?m)^Counter proposal fingerprint:\s+(?<value>\S+)\s*$')

    $view = [pscustomobject]@{
        PerspectiveName = $perspective.Groups['name'].Value.Trim()
        PerspectiveTeamId = $perspective.Groups['id'].Value.Trim()
        V6Action = $v6Action.Groups['value'].Value.Trim()
        Opportunity = $opportunity.Groups['value'].Value.Trim()
        Selection = $selection.Groups['value'].Value.Trim()
        Action = $action.Groups['value'].Value.Trim()
        Reason = $reason.Groups['value'].Value.Trim()
        MaterializedState = $materialized.Groups['value'].Value.Trim()
        MaterializedReason = $materializedReason.Groups['value'].Value.Trim()
        CounterText = if ($counter.Success) { $counter.Groups['value'].Value.Trim() } else { '' }
        RevisedSideA = if ($revisedA.Success) { $revisedA.Groups['value'].Value.Trim() } else { '' }
        RevisedSideB = if ($revisedB.Success) { $revisedB.Groups['value'].Value.Trim() } else { '' }
        MessageState = $messageState.Groups['value'].Value.Trim()
        MessageReason = $messageReason.Groups['value'].Value.Trim()
        MessageActor = if ($actor.Success) { $actor.Groups['value'].Value.Trim() } else { '' }
        Message = if ($message.Success) { $message.Groups['value'].Value.Trim() } else { '' }
        Fingerprint = if ($fingerprint.Success) { $fingerprint.Groups['value'].Value.Trim() } else { '' }
        Raw = $Text
    }

    switch ($view.Action) {
        'COUNTER' {
            if ($view.MaterializedState -cne 'MATERIALIZED' -or $view.MessageState -cne 'MESSAGE_AVAILABLE' -or
                [string]::IsNullOrWhiteSpace($view.CounterText) -or [string]::IsNullOrWhiteSpace($view.RevisedSideA) -or
                [string]::IsNullOrWhiteSpace($view.RevisedSideB) -or [string]::IsNullOrWhiteSpace($view.Message)) {
                throw 'BF-878 BLOCKED: governed COUNTER is missing its materialized package or negotiation message.'
            }
        }
        'NO_ACTION' {
            if ($view.MaterializedState -cne 'NO_PACKAGE' -or $view.MessageState -cne 'NO_MESSAGE') {
                throw 'BF-878 BLOCKED: governed NO_ACTION counter state is inconsistent.'
            }
        }
        'INCONCLUSIVE' {
            if ($view.MaterializedState -cne 'INCONCLUSIVE' -or $view.MessageState -cne 'INCONCLUSIVE') {
                throw 'BF-878 BLOCKED: governed INCONCLUSIVE counter state is inconsistent.'
            }
        }
        default { throw "BF-878 BLOCKED: unsupported governed counter action $($view.Action)." }
    }
    return $view
}

function ConvertTo-TradeCounterManagerText {
    param(
        [Parameter(Mandatory = $true)][string]$CounterText,
        [Parameter(Mandatory = $true)][string]$OpponentName
    )
    $match = [regex]::Match($CounterText, '^(?<operation>ADD|REMOVE)\s+(?<type>PLAYER|DRAFT_PICK)\s+(?<name>.+?)\s+\[(?<id>[^\]]+)\]\s+(?<direction>TO|FROM)\s+(?<side>SIDE_A|SIDE_B)$')
    if (-not $match.Success) { throw 'BF-878 BLOCKED: governed COUNTER adjustment has an unsupported display shape.' }
    $operation = $match.Groups['operation'].Value
    $name = $match.Groups['name'].Value.Trim()
    $side = $match.Groups['side'].Value
    if ($operation -ceq 'ADD' -and $side -ceq 'SIDE_A') { return "Add $name to your side." }
    if ($operation -ceq 'ADD' -and $side -ceq 'SIDE_B') { return "Ask $OpponentName to add $name to their side." }
    if ($operation -ceq 'REMOVE' -and $side -ceq 'SIDE_A') { return "Remove $name from your side." }
    if ($operation -ceq 'REMOVE' -and $side -ceq 'SIDE_B') { return "Ask $OpponentName to remove $name from their side." }
    throw 'BF-878 BLOCKED: governed COUNTER adjustment could not be translated for the bound side-a perspective.'
}

function ConvertTo-TradePackageDisplay {
    param(
        [Parameter(Mandatory = $true)]$Inventory,
        [Parameter(Mandatory = $true)][string]$PackageText
    )
    $match = [regex]::Match($PackageText, '^players=\[(?<players>[^\]]*)\]\s+picks=\[(?<picks>[^\]]*)\]$')
    if (-not $match.Success) { throw 'BF-878 BLOCKED: governed materialized package has an unsupported display shape.' }

    $labels = @()
    foreach ($spec in @(
        [pscustomobject]@{ Type = 'player'; Values = $match.Groups['players'].Value },
        [pscustomobject]@{ Type = 'pick'; Values = $match.Groups['picks'].Value }
    )) {
        if ([string]::IsNullOrWhiteSpace($spec.Values)) { continue }
        foreach ($id in @($spec.Values -split ',' | ForEach-Object { $_.Trim() } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })) {
            $matches = @($Inventory.Teams | ForEach-Object { $_.Assets } | Where-Object { $_.Type -ceq $spec.Type -and $_.Id -ceq $id })
            if ($matches.Count -ne 1) {
                throw "BF-878 BLOCKED: materialized $($spec.Type) $id did not resolve exactly once in current league inventory."
            }
            $labels += [string]$matches[0].Label
        }
    }
    if ($labels.Count -eq 0) { return 'No assets' }
    return ($labels -join ', ')
}

function ConvertTo-TradeSelectionDisplay {
    param(
        [Parameter(Mandatory = $true)]$Inventory,
        [object[]]$Tokens = @()
    )

    $labels = @()
    foreach ($raw in @($Tokens)) {
        $token = ([string]$raw).Trim()
        if ([string]::IsNullOrWhiteSpace($token)) { continue }
        $matches = @(
            $Inventory.Teams |
                ForEach-Object { $_.Assets } |
                Where-Object { [string]$_.Token -ceq $token }
        )
        if ($matches.Count -ne 1) {
            throw "BF-910 BLOCKED: evaluated trade asset $token did not resolve exactly once in current league inventory."
        }
        $labels += [string]$matches[0].Label
    }

    if ($labels.Count -eq 0) { return 'No assets selected' }
    return ($labels -join ', ')
}

function ConvertTo-TradeHiddenInputs {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [object[]]$Values = @()
    )
    $html = ''
    foreach ($value in @($Values)) {
        $html += "<input type=`"hidden`" name=`"$(ConvertTo-HtmlText $Name)`" value=`"$(ConvertTo-HtmlText $value)`">"
    }
    return $html
}

function Get-TradeSelectionSet {
    param([object[]]$Values)
    $set = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    foreach ($value in @($Values)) { [void]$set.Add([string]$value) }
    return $set
}

function ConvertTo-TradeAssetCheckboxes {
    param(
        [Parameter(Mandatory = $true)]$Team,
        [Parameter(Mandatory = $true)][string]$Name,
        [object[]]$Selected = @()
    )
    $selectedSet = Get-TradeSelectionSet -Values @($Selected)
    $html = ''
    foreach ($asset in @($Team.Assets | Sort-Object @{Expression={ if ($_.Type -ceq 'player') { 0 } else { 1 } }}, @{Expression='Label'})) {
        $checked = if ($selectedSet.Contains([string]$asset.Token)) { ' checked' } else { '' }
        $valueText = if ($asset.Valued) { "value $($asset.Value)" } else { 'MISSING VALUE' }
        $valueClass = if ($asset.Valued) { 'asset-value' } else { 'asset-missing' }
        $html += "<label class=`"asset-option`"><input type=`"checkbox`" name=`"$Name`" value=`"$(ConvertTo-HtmlText $asset.Token)`"$checked><span><strong>$(ConvertTo-HtmlText $asset.Label)</strong><small>$(ConvertTo-HtmlText ($asset.Type.ToUpperInvariant())) &middot; <span class=`"$valueClass`">$(ConvertTo-HtmlText $valueText)</span> &middot; $(ConvertTo-HtmlText $asset.Slot)</small></span></label>"
    }
    if ([string]::IsNullOrWhiteSpace($html)) {
        return '<div class="empty">No persisted assets are available for this team.</div>'
    }
    return $html
}

function ConvertTo-TradeLabHtml {
    param(
        [Parameter(Mandatory = $true)]$Roster,
        [Parameter(Mandatory = $true)]$Inventory,
        [Parameter(Mandatory = $true)]$UserTeam,
        $Opponent,
        [object[]]$Give = @(),
        [object[]]$Receive = @(),
        $Evaluation,
        $CounterProposal
    )

    $nav = Get-AppNav -Active 'trade'
    $css = Get-AppCss
    $tradeCss = @'
.trade-setup{display:grid;grid-template-columns:1fr auto;gap:12px;align-items:end;margin-top:16px}.field label{display:block;color:var(--muted);font-size:10px;font-weight:900;letter-spacing:.07em;text-transform:uppercase;margin-bottom:6px}.field select,.trade-button{font:inherit;border-radius:var(--radius);border:1px solid var(--line);background:var(--surface);color:var(--ink);padding:10px 12px}.field select{width:100%}.trade-button{cursor:pointer;background:var(--turf);border-color:var(--turf);color:#fff;font-weight:900;text-transform:uppercase;letter-spacing:.05em}.trade-button:hover{background:var(--turf-deep);border-color:var(--turf-deep)}.trade-columns{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px;margin-top:16px}.asset-list{display:grid;gap:8px;margin-top:12px}.asset-option{display:flex;gap:10px;align-items:flex-start;padding:12px;border:1px solid var(--line);border-radius:var(--radius);background:var(--surface);cursor:pointer}.asset-option:hover{background:var(--surface-2)}.asset-option input{margin-top:4px}.asset-option strong{display:block}.asset-option small{display:block;color:var(--muted);margin-top:3px}.asset-value{color:var(--turf)}.asset-missing{color:#8a6319}.trade-submit{margin-top:16px}.trade-result{border-left:4px solid var(--turf)}.trade-result>.empty{font-size:14px;color:var(--ink)}.trade-proof{margin-top:14px}.trade-proof>summary{font-size:13px}.trade-proof-body{margin-top:12px}.counter-form{margin-top:14px;display:flex;flex-wrap:wrap;align-items:center;gap:10px}.counter-form .meta{margin:0}.counter-result{border-left:4px solid var(--gold)}.counter-message{margin-top:14px;padding:14px 16px;border:1px solid var(--line);border-radius:10px;background:var(--surface-2);font-size:16px;line-height:1.55}.counter-packages{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;margin-top:14px}.counter-package{padding:13px;border:1px solid var(--line);border-radius:10px;background:var(--surface-2)}.counter-package strong{display:block;color:var(--muted);font-size:10px;text-transform:uppercase;letter-spacing:.05em}.counter-package span{display:block;margin-top:5px;font-weight:800}.gate-grid{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:0;margin-top:14px;border-top:1px solid var(--line);border-left:1px solid var(--line)}.gate{padding:10px;border-right:1px solid var(--line);border-bottom:1px solid var(--line);background:var(--surface);font-size:12px}.gate strong{display:block;color:var(--muted);font-size:9px;text-transform:uppercase;letter-spacing:.06em}.gate span{display:block;margin-top:4px;font-weight:900}.raw-output{white-space:pre-wrap;word-break:break-word;background:var(--surface-2);border:1px solid var(--line);border-radius:var(--radius);padding:12px;color:var(--ink);font:12px Consolas,monospace}.veto-list{display:grid;gap:8px;margin-top:10px}.veto-item{padding:10px;border:1px solid var(--brick);border-radius:var(--radius);background:color-mix(in srgb,var(--brick) 7%,var(--surface));color:var(--brick)}@media(max-width:800px){.trade-setup,.trade-columns,.counter-packages{grid-template-columns:1fr}.gate-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}
'@
$opponentOptions = '<option value="">Choose a league opponent</option>'
    foreach ($team in @($Inventory.Teams | Where-Object { $_.TeamId -cne $UserTeam.TeamId } | Sort-Object Name)) {
        $selected = if ($null -ne $Opponent -and $team.TeamId -ceq $Opponent.TeamId) { ' selected' } else { '' }
        $opponentOptions += "<option value=`"$(ConvertTo-HtmlText $team.TeamId)`"$selected>$(ConvertTo-HtmlText $team.Name)</option>"
    }

    $builder = ''
    if ($null -ne $Opponent) {
        $giveHtml = ConvertTo-TradeAssetCheckboxes -Team $UserTeam -Name 'give' -Selected @($Give)
        $receiveHtml = ConvertTo-TradeAssetCheckboxes -Team $Opponent -Name 'receive' -Selected @($Receive)
        $builder = @"
<section class="panel"><div class="eyebrow">Build the deal</div><h2>$([System.Net.WebUtility]::HtmlEncode($UserTeam.Name)) gives / $([System.Net.WebUtility]::HtmlEncode($Opponent.Name)) gives</h2><p class="lede">Select exact persisted assets. Butler validates current ownership again before evaluation.</p><form method="get" action="/trade"><input type="hidden" name="opponent" value="$(ConvertTo-HtmlText $Opponent.TeamId)"><input type="hidden" name="evaluate" value="1"><div class="trade-columns"><div><h3>You give</h3><div class="asset-list">$giveHtml</div></div><div><h3>You receive</h3><div class="asset-list">$receiveHtml</div></div></div><div class="trade-submit"><button class="trade-button" type="submit">Get Butler recommendation</button></div></form></section>
"@
    }

    $resultHtml = ''
    $counterHtml = ''
    if ($null -ne $Evaluation) {
        $evidenceState = if ($Evaluation.EvidenceComplete) { 'COMPLETE' } else { 'INCOMPLETE' }
        $evidenceClass = if ($Evaluation.EvidenceComplete) { 'good' } else { 'warn' }
        $vetoClass = if ($Evaluation.StrategicVeto -ceq 'BLOCKED') { 'danger' } else { 'done' }
        $postureLabel = if ($Evaluation.PostureAdvisory) { 'Posture (advisory)' } else { 'Posture' }
        $postureState = if ($Evaluation.PostureAdvisory) {
            if ($Evaluation.PostureGate) { 'AVAILABLE' } else { 'UNAVAILABLE' }
        } else {
            if ($Evaluation.PostureGate) { 'READY' } else { 'BLOCKED' }
        }
        $reasonHtml = if ([string]::IsNullOrWhiteSpace($Evaluation.Reason)) { '' } else { "<div class=`"empty`">$(ConvertTo-HtmlText $Evaluation.Reason)</div>" }
        $flexDetail = if ([string]::IsNullOrWhiteSpace($Evaluation.FlexiblePressureReason)) { '' } else { "<div class=`"meta`">$(ConvertTo-HtmlText $Evaluation.FlexiblePressureReason)</div>" }
        $transitionText = if ([string]::IsNullOrWhiteSpace($Evaluation.Transition)) { 'Not evaluated' } else { $Evaluation.Transition }
        $transitionMeta = if ([string]::IsNullOrWhiteSpace($Evaluation.TransitionState)) { '' } else { "$(ConvertTo-HtmlText $Evaluation.TransitionState)" }
        if (-not [string]::IsNullOrWhiteSpace($Evaluation.TransitionCoverage)) { $transitionMeta += " &middot; $(ConvertTo-HtmlText $Evaluation.TransitionCoverage)" }
        if (-not [string]::IsNullOrWhiteSpace($Evaluation.ProtectedCoverage)) { $transitionMeta += " &middot; protected $(ConvertTo-HtmlText $Evaluation.ProtectedCoverage)" }
        $vetoReasons = ''
        foreach ($reason in $Evaluation.VetoReasons) { $vetoReasons += "<div class=`"veto-item`">$(ConvertTo-HtmlText $reason)</div>" }
        if ([string]::IsNullOrWhiteSpace($vetoReasons)) { $vetoReasons = '<div class="empty">No governed material-loss veto reason was returned.</div>' }
        $counterActionHtml = ''
        if ($Evaluation.EvidenceComplete -and $Evaluation.Action -ceq 'REJECT' -and $null -eq $CounterProposal) {
            $giveHidden = ConvertTo-TradeHiddenInputs -Name 'give' -Values @($Give)
            $receiveHidden = ConvertTo-TradeHiddenInputs -Name 'receive' -Values @($Receive)
            $counterActionHtml = "<form class=`"counter-form`" method=`"get`" action=`"/trade`"><input type=`"hidden`" name=`"opponent`" value=`"$(ConvertTo-HtmlText $Opponent.TeamId)`"><input type=`"hidden`" name=`"evaluate`" value=`"1`"><input type=`"hidden`" name=`"counter`" value=`"1`">$giveHidden$receiveHidden<button class=`"trade-button`" type=`"submit`">Build Counteroffer</button><span class=`"meta`">Read-only. Butler will not send or submit anything.</span></form>"
        }
        $resultHtml = @"
<section class="panel trade-result"><div class="eyebrow">Butler recommendation</div><div class="statusrow"><div><h2 class="headline">$(ConvertTo-HtmlText $Evaluation.Action)</h2><p class="lede">Package recommendation: <strong>$(ConvertTo-HtmlText $Evaluation.PackageRecommendation)</strong>. This is evaluated from your exact bound team's perspective.</p></div><div class="status $evidenceClass">$evidenceState EVIDENCE</div></div>$reasonHtml$counterActionHtml<details class="trade-proof"><summary>Why Butler says this</summary><div class="trade-proof-body"><div class="stats"><div class="stat"><strong>Strategic veto</strong><span class="$vetoClass">$(ConvertTo-HtmlText $Evaluation.StrategicVeto)</span></div><div class="stat"><strong>Flexible pressure</strong><span>$(ConvertTo-HtmlText $Evaluation.FlexiblePressure)</span>$flexDetail</div><div class="stat"><strong>Pressure transition</strong><span>$(ConvertTo-HtmlText $transitionText)</span><div class="meta">$transitionMeta</div></div></div><div class="gate-grid"><div class="gate"><strong>Market direction</strong><span>$(if ($Evaluation.MarketGate) {'READY'} else {'BLOCKED'})</span></div><div class="gate"><strong>$(ConvertTo-HtmlText $postureLabel)</strong><span>$(ConvertTo-HtmlText $postureState)</span></div><div class="gate"><strong>Future capital</strong><span>$(if ($Evaluation.FutureCapitalGate) {'READY'} else {'BLOCKED'})</span></div><div class="gate"><strong>Position pressure</strong><span>$(if ($Evaluation.PositionGate) {'READY'} else {'BLOCKED'})</span></div><div class="gate"><strong>Flexible pressure</strong><span>$(if ($Evaluation.FlexibleGate) {'READY'} else {'BLOCKED'})</span></div></div><h3>Material-loss veto evidence</h3><div class="veto-list">$vetoReasons</div></div></details><details><summary>Raw decision record</summary><pre class="raw-output">$(ConvertTo-HtmlText $Evaluation.Raw)</pre></details></section>
"@
    }

    if ($null -ne $CounterProposal) {
        if ($null -eq $Opponent) { throw 'BF-878 BLOCKED: counter proposal rendering requires the exact selected opponent.' }
        switch ($CounterProposal.Action) {
            'COUNTER' {
                $managerCounter = ConvertTo-TradeCounterManagerText -CounterText $CounterProposal.CounterText -OpponentName $Opponent.Name
                $yourPackage = ConvertTo-TradePackageDisplay -Inventory $Inventory -PackageText $CounterProposal.RevisedSideA
                $theirPackage = ConvertTo-TradePackageDisplay -Inventory $Inventory -PackageText $CounterProposal.RevisedSideB
                $counterHtml = @"
<section class="panel counter-result"><div class="eyebrow">Butler counteroffer</div><div class="statusrow"><div><h2 class="headline">Counter available</h2><p class="lede">$(ConvertTo-HtmlText $managerCounter)</p></div><div class="status good">COUNTER</div></div><div class="counter-packages"><div class="counter-package"><strong>Your revised package</strong><span>$(ConvertTo-HtmlText $yourPackage)</span></div><div class="counter-package"><strong>Their revised package</strong><span>$(ConvertTo-HtmlText $theirPackage)</span></div></div><h3>Message you can send manually</h3><div class="counter-message">$(ConvertTo-HtmlText $CounterProposal.Message)</div><p class="meta">Butler generated this wording from the governed counter proposal. It has not been sent and the trade has not been submitted.</p><details><summary>Counteroffer details</summary><div class="technical">Opportunity: $(ConvertTo-HtmlText $CounterProposal.Opportunity) &middot; selection: $(ConvertTo-HtmlText $CounterProposal.Selection) &middot; reason: $(ConvertTo-HtmlText $CounterProposal.Reason) &middot; message actor: $(ConvertTo-HtmlText $CounterProposal.MessageActor) &middot; fingerprint: $(ConvertTo-HtmlText $CounterProposal.Fingerprint)</div><pre class="raw-output">$(ConvertTo-HtmlText $CounterProposal.Raw)</pre></details></section>
"@
            }
            'NO_ACTION' {
                $counterHtml = @"
<section class="panel counter-result"><div class="eyebrow">Butler counteroffer</div><div class="statusrow"><div><h2 class="headline">No counteroffer</h2><p class="lede">Butler did not find one uniquely governed counter package for this rejected deal.</p></div><div class="status done">NO ACTION</div></div><details><summary>Counteroffer details</summary><div class="technical">Opportunity: $(ConvertTo-HtmlText $CounterProposal.Opportunity) &middot; selection: $(ConvertTo-HtmlText $CounterProposal.Selection) &middot; reason: $(ConvertTo-HtmlText $CounterProposal.Reason)</div><pre class="raw-output">$(ConvertTo-HtmlText $CounterProposal.Raw)</pre></details></section>
"@
            }
            'INCONCLUSIVE' {
                $counterHtml = @"
<section class="panel counter-result"><div class="eyebrow">Butler counteroffer</div><div class="statusrow"><div><h2 class="headline">Counteroffer unavailable</h2><p class="lede">Butler does not have enough governed counter evidence to construct a safe proposal for this deal.</p></div><div class="status warn">INCONCLUSIVE</div></div><details><summary>Counteroffer details</summary><div class="technical">Opportunity: $(ConvertTo-HtmlText $CounterProposal.Opportunity) &middot; selection: $(ConvertTo-HtmlText $CounterProposal.Selection) &middot; reason: $(ConvertTo-HtmlText $CounterProposal.Reason)</div><pre class="raw-output">$(ConvertTo-HtmlText $CounterProposal.Raw)</pre></details></section>
"@
            }
        }
    }

    return @"
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="dark"><title>Butler - Trade Analyzer</title><style>$css$tradeCss</style></head><body><main class="shell">
<header class="top"><div class="brand"><h1>BUTLER</h1><p>We're here to serve you. Less Research. Better Decisions.</p></div><div class="target">$(ConvertTo-HtmlText $Roster.LeagueName) &middot; $(ConvertTo-HtmlText $UserTeam.Name) &middot; $(ConvertTo-HtmlText $Roster.Season)</div></header>
$nav
<section class="panel"><div class="eyebrow">Trade Analyzer</div><div class="statusrow"><div><h1 class="headline">Analyze a trade</h1><p class="lede">Build an exact deal and review Butler's governed recommendation. No new trade score is created here.</p></div><div class="status done">READ ONLY</div></div><div class="stats"><div class="stat"><strong>Your side</strong><span>$(ConvertTo-HtmlText $UserTeam.Name)</span></div><div class="stat"><strong>Season</strong><span>$(ConvertTo-HtmlText $Roster.Season)</span></div><div class="stat"><strong>Asset coverage</strong><span>$(ConvertTo-HtmlText $Inventory.Coverage)%</span></div></div><form method="get" action="/trade"><div class="trade-setup"><div class="field"><label for="opponent">Trade partner</label><select id="opponent" name="opponent">$opponentOptions</select></div><button class="trade-button" type="submit">Load opponent</button></div></form></section>
$builder
$resultHtml
$counterHtml
<section class="panel boundary"><span class="lock">READ ONLY.</span> Butler can evaluate exact currently owned assets and, after an explicit request, build an existing governed read-only counteroffer. It cannot refresh evidence, authorize, hand off, send, finalize, or submit a counter, alter a roster, or submit a Sleeper transaction.</section>
</main></body></html>
"@
}

function Invoke-TradeLabHtml {
    param(
        [Parameter(Mandatory = $true)][string]$LeagueId,
        [Parameter(Mandatory = $true)][string]$RequestTarget
    )

    $rosterText = Invoke-ButlerReadOnlyTask -Task ':bet:bet-cli:sleeperLiveWaiverTargetRosterContextAudit' -Arguments $LeagueId -BoundaryName 'BF-670'
    $roster = ConvertTo-RosterContextView -Text $rosterText
    $inventory = ConvertTo-TradeInventoryView -Text (Invoke-ButlerReadOnly -Arguments "league assets $LeagueId" -BoundaryName 'BF-670')
    if ($inventory.LeagueId -cne $LeagueId) {
        throw 'BF-670 BLOCKED: governed asset inventory league does not match the configured Butler league.'
    }
    $userTeam = Get-TradeTeamById -Inventory $inventory -TeamId $roster.ButlerTeamId -BoundaryName 'bound user team'

    $query = ConvertFrom-TradeRequestTarget -RequestTarget $RequestTarget
    $opponentId = Get-TradeQueryFirst -Query $query -Name 'opponent'
    $opponent = $null
    if (-not [string]::IsNullOrWhiteSpace($opponentId)) {
        if ($opponentId -ceq $userTeam.TeamId) {
            throw 'BF-670 BLOCKED: trade opponent cannot be the bound user team.'
        }
        $opponent = Get-TradeTeamById -Inventory $inventory -TeamId $opponentId -BoundaryName 'selected opponent'
    }

    $give = @(Get-TradeQueryValues -Query $query -Name 'give')
    $receive = @(Get-TradeQueryValues -Query $query -Name 'receive')
    $evaluate = (Get-TradeQueryFirst -Query $query -Name 'evaluate') -ceq '1'
    $counterRequested = (Get-TradeQueryFirst -Query $query -Name 'counter') -ceq '1'
    if ($counterRequested -and -not $evaluate) {
        throw 'BF-878 BLOCKED: counter proposal requires the exact evaluated trade coordinates.'
    }
    $evaluation = $null
    $counterProposal = $null
    if ($evaluate) {
        if ($null -eq $opponent) {
            throw 'BF-670 BLOCKED: choose one current league opponent before evaluation.'
        }
        $give = @(Assert-TradeAssetSelection -Team $userTeam -Tokens $give -SideName 'outgoing package')
        $receive = @(Assert-TradeAssetSelection -Team $opponent -Tokens $receive -SideName 'incoming package')
        $sideA = $give -join ','
        $sideB = $receive -join ','
        $raw = Invoke-ButlerReadOnly -Arguments "trade recommendation $LeagueId $($roster.Season) $sideA $sideB side-a" -BoundaryName 'BF-670'
        $evaluation = ConvertTo-TradeRecommendationView -Text $raw
        if ($evaluation.PerspectiveTeamId -cne $userTeam.TeamId) {
            throw 'BF-670 BLOCKED: governed trade recommendation perspective does not match the exact bound user team.'
        }

        if ($counterRequested) {
            if (-not $evaluation.EvidenceComplete -or $evaluation.Action -cne 'REJECT') {
                throw 'BF-878 BLOCKED: counter proposal is available only for an evidence-complete REJECT.'
            }
            $counterRaw = Invoke-ButlerReadOnly -Arguments "trade counter-proposal $LeagueId $($roster.Season) $sideA $sideB side-a" -BoundaryName 'BF-878'
            $counterProposal = ConvertTo-TradeCounterProposalView -Text $counterRaw
            if ($counterProposal.PerspectiveTeamId -cne $userTeam.TeamId) {
                throw 'BF-878 BLOCKED: governed counter proposal perspective does not match the exact bound user team.'
            }
        }
    }

    return ConvertTo-TradeLabHtml -Roster $roster -Inventory $inventory -UserTeam $userTeam -Opponent $opponent -Give $give -Receive $receive -Evaluation $evaluation -CounterProposal $counterProposal
}
