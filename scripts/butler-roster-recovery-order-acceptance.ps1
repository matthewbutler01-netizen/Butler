# Execute the real recovery orchestration with simulated CLI results only.
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$path=Join-Path $PSScriptRoot 'butler-recover-roster-drift.ps1'
$source=[IO.File]::ReadAllText($path).Replace("`r`n","`n")
$tokens=$null; $errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile($path,[ref]$tokens,[ref]$errors)
if ($errors.Count) { throw 'Recovery script parse failed' }
foreach($name in @('Invoke-RequiredSuccess','Get-BoundedTail')) {
    $nodes=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq $name},$true))
    if($nodes.Count -ne 1) { throw 'Required real helper missing' }
    . ([scriptblock]::Create($nodes[0].Extent.Text))
}
$start=$source.IndexOf('$leagueId = Get-ConfiguredLeagueId',[StringComparison]::Ordinal)
$finish=$source.IndexOf("Write-Host 'BF-723 RECOVERY: VERIFIED'",[StringComparison]::Ordinal)
if($start -lt 0 -or $finish -le $start) {throw 'Recovery orchestration boundary missing'}
$run=[scriptblock]::Create($source.Substring($start,$finish-$start))
function Get-ConfiguredLeagueId { return 'fixture-league' }
function Write-Host { param([string]$Object) }
$acceptancePreflight={}
$dataDir='fixture-only'
$prefix='io.butler.bet.cli.'
$roster=$prefix+'ButlerSleeperLiveWaiverTargetRosterContextAuditCli'
$week=$prefix+'ButlerSleeperCurrentWeekMatchupSyncCli'
$stages=@('ButlerSleeperLiveWaiverSnapshotSyncCli','ButlerSleeperLiveWaiverMarketAttentionSyncCli','ButlerSleeperLiveWaiverProductionHydrationCli','ButlerSleeperLiveWaiverAvailabilitySyncCli','ButlerSleeperLiveWaiverCurrentWeekStatSyncCli','ButlerSleeperLiveWaiverTargetRosterProductionHydrationCli') | ForEach-Object {$prefix+$_}
$comparison=$prefix+'ButlerSleeperLiveWaiverComparisonBundleCli'
function Invoke-ButlerRuntimeCommand {
    param([string]$MainClass,[string]$LeagueId)
    if($LeagueId -cne 'fixture-league') {throw 'Unexpected league'}
    $script:calls.Add($MainClass)
    if($script:calls.Count -eq 1) {
        if($script:state -eq 'unknown') {return [pscustomobject]@{ExitCode=2;Text='Other blocked state'}}
        if($script:state -eq 'drift') {return [pscustomobject]@{ExitCode=2;Text='BF-610 BLOCKED: current roster membership drifted from BF-603/BF-602 frame; added=[1] removed=[2]; refresh BF-602/BF-603 and downstream live evidence before target-roster review'}}
    }
    if($MainClass -ceq $script:failAt) {return [pscustomobject]@{ExitCode=2;Text='Fixture failure'}}
    return [pscustomobject]@{ExitCode=0;Text='Fixture success'}
}
function Invoke-Case {
    param([string]$State,[string]$FailAt,[string[]]$Expected,[bool]$ShouldFail)
    $script:state=$State; $script:failAt=$FailAt
    $script:calls=[Collections.Generic.List[string]]::new()
    $failed=$false
    try { & $run } catch { $failed=$true }
    if($failed -ne $ShouldFail) {throw "Unexpected result for $State / $FailAt"}
    if(($script:calls -join '|') -cne ($Expected -join '|')) {throw "Unexpected stage order for $State / $FailAt"}
}
Invoke-Case -State drift -FailAt '' -Expected (@($roster,$week)+$stages+@($roster,$comparison)) -ShouldFail $false
Invoke-Case -State current -FailAt '' -Expected @($roster,$week,$roster,$comparison) -ShouldFail $false
Invoke-Case -State unknown -FailAt '' -Expected @($roster) -ShouldFail $true
Invoke-Case -State drift -FailAt $week -Expected @($roster,$week) -ShouldFail $true
for($i=0;$i -lt $stages.Count;$i++) {
    Invoke-Case -State drift -FailAt $stages[$i] -Expected (@($roster,$week)+$stages[0..$i]) -ShouldFail $true
}
Microsoft.PowerShell.Utility\Write-Host 'ROSTER RECOVERY ORDER: PASS (current-week proof before waiver writes, exact-drift guard, no-op state, every failure stops)'
