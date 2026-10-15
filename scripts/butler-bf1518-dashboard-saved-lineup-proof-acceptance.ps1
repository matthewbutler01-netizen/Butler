Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$script=Join-Path $PSScriptRoot 'butler-bf1518-dashboard-saved-lineup-trust-transform.ps1'
$tokens=$null
$errors=$null
[void][Management.Automation.Language.Parser]::ParseFile($script,[ref]$tokens,[ref]$errors)
if (@($errors).Count -gt 0) { throw 'BF-1518 BLOCKED: transform parse failed.' }
$root=Join-Path ([IO.Path]::GetTempPath()) ('butler-bf1518-'+[Guid]::NewGuid().ToString('N'))
try {
    [IO.Directory]::CreateDirectory($root) | Out-Null
    $path=Join-Path $root 'dashboard.ps1'
    # Reproduce the final BF-808/BF-816 slot without performing any source call.
    $fixture=@'
function ConvertTo-DashboardHtml {
    param([object]$verification)
    $lineupSnapshotAttentionGroup = ''
    $lineupSnapshot = $null
    $snapshotFresh = $false
    $snapshotTargetMatches = $false
    $lineupSignalStatus='NOT REVIEWED'
    $lineupSignalClass='done'
    $lineupSignalTitle='Review lineup'
    $lineupSignalCopy=''
    $lineupAttentionGroup = if (-not [string]::IsNullOrWhiteSpace($lineupSnapshotAttentionGroup)) {
        $lineupSnapshotAttentionGroup
    }
    elseif ($verification.RosterOk) {
        'review'
    }
    else { 'attention' }
    return [pscustomobject]@{
        Status=$lineupSignalStatus
        Title=$lineupSignalTitle
        Copy=$lineupSignalCopy
        Tone=$lineupSignalClass
        Group=$lineupAttentionGroup
    }
}
'@
    [IO.File]::WriteAllText($path,$fixture)
    & $script -DashboardPath $path
    if ($LASTEXITCODE -ne 0) { throw 'BF-1518 BLOCKED: transform execution failed.' }
    . $path
    $idle=ConvertTo-DashboardHtml -verification ([pscustomobject]@{RosterOk=$true})
    if ($idle.Status -cne 'NOT REVIEWED') {
        throw 'BF-1518 BLOCKED: no-saved-review state changed.'
    }
    $text=[IO.File]::ReadAllText($path)
    # Activate the synthetic saved-ready branch without provider calls.
    $ready=$text.Replace('$lineupSnapshot = $null',@'
$lineupSnapshot = [pscustomobject]@{Ready=$true;Week='6'}
'@.Trim()).Replace('$snapshotFresh = $false','$snapshotFresh = $true').Replace('$snapshotTargetMatches = $false','$snapshotTargetMatches = $true')
    [IO.File]::WriteAllText($path,$ready)
    . $path
    $held=ConvertTo-DashboardHtml -verification ([pscustomobject]@{RosterOk=$true})
    if ($held.Status -cne 'RECHECK LINEUP' -or
        $held.Tone -cne 'warn' -or $held.Group -cne 'attention' -or
        $held.Title -cne 'Saved lineup review - verify current starters' -or
        $held.Copy -notmatch 'current Sleeper lineup' -or
        $held.Copy -notmatch 'no lineup was submitted') {
        throw 'BF-1518 BLOCKED: saved-ready review was misrepresented as current.'
    }
    $unverified=ConvertTo-DashboardHtml -verification ([pscustomobject]@{RosterOk=$false})
    if ($unverified.Status -ceq 'RECHECK LINEUP') {
        throw 'BF-1518 BLOCKED: unverifiable roster was upgraded to saved-review status.'
    }
    Write-Host 'BF-1518 DASHBOARD SAVED LINEUP PROOF ACCEPTANCE: PASS'
}
finally {
    if (Test-Path -LiteralPath $root) { Remove-Item -LiteralPath $root -Recurse -Force }
}
