Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# BF-M1: test exact production staging and rendered evidence fragments
# without opening a network connection, reading local user data or
# inventing a green verification state. This catches stale static-only
# assertions that did not execute real manager HTML in Windows PowerShell.
$root = Join-Path ([System.IO.Path]::GetTempPath()) ('butler-m1-render-' + [guid]::NewGuid().ToString('N'))
try {
    New-Item -ItemType Directory -Path $root -Force | Out-Null
    $stagedCore = Join-Path $root 'butler-app-shell-core-single.ps1'
    $stagedDashboard = Join-Path $root 'butler-dashboard.ps1'
    Copy-Item (Join-Path $PSScriptRoot 'butler-app-shell-core-single.ps1') $stagedCore -Force
    Copy-Item (Join-Path $PSScriptRoot 'butler-dashboard.ps1') $stagedDashboard -Force

    & (Join-Path $PSScriptRoot 'butler-dashboard-bf715-transform.ps1') -DashboardPath $stagedDashboard

    function Get-SyntheticFunction {
        param([string]$Path, [string]$Name)
        $tokens = $null
        $errors = $null
        $ast = [System.Management.Automation.Language.Parser]::ParseFile(
            $Path, [ref]$tokens, [ref]$errors)
        if (@($errors).Count -ne 0) {
            throw 'BF-M1 BLOCKED: final staged module does not parse.'
        }
        $found = @($ast.FindAll({
            param($node)
            $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
            $node.Name -ceq $Name
        }, $true))
        if ($found.Count -ne 1) {
            throw ('BF-M1 BLOCKED: expected one staged {0} renderer, found {1}.' -f $Name, $found.Count)
        }
        return $found[0].Extent.Text
    }

    function Get-SourceProofFragment {
        param([string]$Renderer, [string]$Class)
        $expression = '(?s)<div class="' + [regex]::Escape($Class) +
            '" hidden>.*?</div></div>'
        $matches = [regex]::Matches($Renderer, $expression)
        if ($matches.Count -ne 1) {
            throw ('BF-M1 BLOCKED: final staged {0} proof must occur once.' -f $Class)
        }
        return $matches[0].Value
    }

    # Extract the *generated* renderer, not transform sources or a hand-
    # authored HTTP fixture. Evaluate the actual expandable HTML literal
    # against synthetic BOUND_TARGET_LIVE_VERIFIED objects.
    $team = Get-SyntheticFunction -Path $stagedCore -Name 'ConvertTo-TeamHtml'
    $waiver = Get-SyntheticFunction -Path $stagedDashboard -Name 'ConvertTo-WaiverHtml'
    $teamFragment = Get-SourceProofFragment -Renderer $team -Class 'butler-m1-roster-proof'
    $waiverFragment = Get-SourceProofFragment -Renderer $waiver -Class 'butler-m1-waiver-proof'

    function ConvertTo-HtmlText {
        param($Text)
        return [System.Net.WebUtility]::HtmlEncode([string]$Text)
    }
    function Invoke-SourceProofRender {
        param(
            [string]$Fragment,
            $Roster, $target, $current
        )
        # The generated function uses an expandable PowerShell here-string.
        # Only the bounded extracted proof fragment is evaluated.
        $literal = '@"' + [Environment]::NewLine +
            $Fragment + [Environment]::NewLine + '"@'
        return & ([scriptblock]::Create($literal))
    }

    $roster = [pscustomobject]@{
        Role = 'OWNER'
        SleeperLeagueId = '2026'
        RosterId = 3
    }
    $target = [pscustomobject]@{
        Gate = 'BOUND_TARGET_LIVE_VERIFIED'
        Role = 'OWNER'
        SleeperLeagueId = '2026'
        RosterId = 3
    }
    $current = [pscustomobject]@{
        State = 'NO_TRANSACTION_TO_ACT_ON'
        Bf629 = 'NO_TRANSACTION_TO_REVALIDATE'
        Bf631 = 'LATEST_EVIDENCE_LINEAGE_VERIFIED'
    }
    $teamProof = Invoke-SourceProofRender -Fragment $teamFragment -Roster $roster
    $waiverProof = Invoke-SourceProofRender -Fragment $waiverFragment -target $target -current $current

    # Import the *real* diagnostic gates from their AST. No diagnostic entry
    # point runs; no HTTP or source import side effect.
    . (Join-Path $PSScriptRoot 'butler-decision-refresh.ps1')
    $smokePath = Join-Path $PSScriptRoot 'butler-v04-live-page-check.ps1'
    $astTokens = $null
    $astErrors = $null
    $smokeAst = [System.Management.Automation.Language.Parser]::ParseFile(
        $smokePath, [ref]$astTokens, [ref]$astErrors)
    if (@($astErrors).Count -ne 0) { throw 'BF-M1 BLOCKED: real proof checker is unparseable.' }
    foreach ($functionName in @('Test-ButlerMilestoneTargetProof',
            'Test-ButlerMilestoneWaiverAudit')) {
        $matches = @($smokeAst.FindAll({
            param($node)
            $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
            $node.Name -ceq $functionName
        }, $true))
        if ($matches.Count -ne 1) { throw 'BF-M1 BLOCKED: real proof function missing or duplicated.' }
        . ([scriptblock]::Create($matches[0].Extent.Text))
    }

    $passTeam = Test-ButlerMilestoneTargetProof -Html $teamProof -Route '/team'
    $passWaiver = Test-ButlerMilestoneTargetProof -Html $waiverProof -Route '/waivers'
    $passAudit = Test-ButlerMilestoneWaiverAudit -Html $waiverProof
    if ($passTeam.Status -cne 'PASS' -or $passWaiver.Status -cne 'PASS' -or
        $passAudit.Status -cne 'PASS' -or
        $passTeam.Identity -cne $passWaiver.Identity) {
        throw 'BF-M1 BLOCKED: final staged renderer and actual diagnostic disagree on synthetic valid proof.'
    }

    $negativeTarget = @(
        $teamProof.Replace('BOUND_TARGET_LIVE_VERIFIED', 'TARGET_NOT_VERIFIED'),
        $teamProof.Replace('BF668_PROVEN_ROSTER', 'SOURCE_MISMATCH'),
        $teamProof.Replace('BF-M1 role: OWNER', 'BF-M1 role: NOT_OWNER'),
        $teamProof.Replace('2026 / 3', '0 / 0')
    )
    foreach ($case in $negativeTarget) {
        $result = Test-ButlerMilestoneTargetProof -Html $case -Route '/team'
        if ($result.Status -cne 'WARN') {
            throw 'BF-M1 BLOCKED: invalid roster proof incorrectly accepted.'
        }
    }

    $badWaivers = @(
        $waiverProof.Replace('NO_TRANSACTION_TO_ACT_ON', 'CURRENT_REFRESH_RECOMMENDED'),
        $waiverProof.Replace('LATEST_EVIDENCE_LINEAGE_VERIFIED', 'LINEAGE_NOT_VERIFIED'),
        $waiverProof.Replace('BF623_GOVERNED_WAIVER', 'SOURCE_MISMATCH'),
        $waiverProof.Replace('BF-M1 role: OWNER', 'BF-M1 role: NOT_OWNER')
    )
    foreach ($case in $badWaivers) {
        $result = Test-ButlerMilestoneWaiverAudit -Html $case
        $targetResult = Test-ButlerMilestoneTargetProof -Html $case -Route '/waivers'
        if ($result.Status -ceq 'PASS' -and $targetResult.Status -ceq 'PASS') {
            throw 'BF-M1 BLOCKED: invalid waiver proof incorrectly accepted.'
        }
    }
    if ((Test-ButlerMilestoneWaiverAudit -Html $waiverProof.Replace(
        '<div>BF-M1 lineage: LATEST_EVIDENCE_LINEAGE_VERIFIED</div>', '')).Status -cne 'WARN') {
        throw 'BF-M1 BLOCKED: missing lineage was not held.'
    }
    if ((Test-ButlerMilestoneTargetProof -Html $teamProof.Replace(
        '<div>BF-M1 role: OWNER</div>',
        '<div>BF-M1 role: OWNER</div><div>BF-M1 role: OWNER</div>') -Route '/team').Status -cne 'FAIL') {
        throw 'BF-M1 BLOCKED: duplicate source identity was not rejected.'
    }

    Write-Host 'BF-M1 REAL STAGED RENDERER PROOF CONTRACT: PASS'
    Write-Host 'Coverage: complete v0.4 staging, expandable HTML markers, exact source-bound identity, stale waiver holds, duplicate proof rejection, no user data or network.'
}
finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue
    }
}
