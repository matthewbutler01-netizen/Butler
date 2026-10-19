Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$path=Join-Path $PSScriptRoot 'butler-app-request-worker.ps1'
$tokens=$null; $errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile($path,[ref]$tokens,[ref]$errors)
if (@($errors).Count) { throw 'Worker parser failed' }
foreach($name in @('Save-LocalRosterRecoveryDiagnostic','Invoke-StartSitRosterDriftAutoRecovery')) {
 $nodes=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq $name},$true))
 if($nodes.Count -ne 1){throw 'Missing unique recovery helper'}
 . ([scriptblock]::Create($nodes[0].Extent.Text))
}
$originalLocal=$env:LOCALAPPDATA
$root=Join-Path ([IO.Path]::GetTempPath()) ('butler-recovery-diagnostic-'+[Guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory((Join-Path $root 'scripts'))
$env:LOCALAPPDATA=$root
$report=Join-Path $root ("Butler\diagnostics\v04-roster-recovery-{0}.txt" -f $PID)
$fake=Join-Path $root 'scripts\butler-recover-roster-drift.ps1'
try {
 $text=@('SECRET player id 123456 and private path','BF-723 STAGE|BF-840|FAIL|42','BF-723 STAGE|SECRET|PASS|9','BF-723 STAGE|BF-602|PASS|SECRET') -join [Environment]::NewLine
 Save-LocalRosterRecoveryDiagnostic -Outcome FAILED -Text $text -ElapsedMilliseconds 50
 $body=[IO.File]::ReadAllText($report)
 if($body -match 'SECRET|123456' -or $body -notmatch 'BF-723 STAGE\|BF-840\|FAIL\|42'){throw 'Diagnostic leaked raw data or lost safe stage'}
 $many=(1..40|ForEach-Object{'BF-723 STAGE|BF-840|FAIL|42'}) -join [Environment]::NewLine
 Save-LocalRosterRecoveryDiagnostic -Outcome FAILED -Text $many -ElapsedMilliseconds 50
 if(([regex]::Matches([IO.File]::ReadAllText($report),'BF-723 STAGE\|')).Count -ne 16){throw 'Stage report not bounded to 16'}
 @'
param([switch]$AppAutoRecovery,[string]$JavaHome)
Write-Output 'SECRET runtime payload'
Write-Output 'BF-723 STAGE|BF-840|FAIL|42'
exit 2
'@ | Set-Content -LiteralPath $fake -Encoding ASCII
 $failed=$false
 try { Invoke-StartSitRosterDriftAutoRecovery -Root $root } catch { $failed=$true }
 if(-not $failed){throw 'Failed process became successful recovery'}
 $body=[IO.File]::ReadAllText($report)
 if($body -notmatch 'Outcome: FAILED' -or $body -match 'SECRET'){throw 'Failed process diagnostic invalid'}
 @'
param([switch]$AppAutoRecovery,[string]$JavaHome)
Write-Output 'BF-723 STAGE|BF-840|PASS|12'
Write-Output 'BF-723 APP AUTO RECOVERY: COMPLETE'
exit 0
'@ | Set-Content -LiteralPath $fake -Encoding ASCII
 Invoke-StartSitRosterDriftAutoRecovery -Root $root
 $body=[IO.File]::ReadAllText($report)
 if($body -notmatch 'Outcome: COMPLETE' -or $body -notmatch 'BF-723 STAGE\|BF-840\|PASS\|12' -or $body -match 'FAIL\|42'){throw 'Success did not replace old failure'}
 $blocked=Join-Path $root 'blocked-file'
 [IO.File]::WriteAllText($blocked,'fixture')
 $env:LOCALAPPDATA=$blocked
 Invoke-StartSitRosterDriftAutoRecovery -Root $root
 Write-Host 'Recovery diagnostics acceptance PASS: filtered/bounded report, failed/success child, storage failure.'
} finally {
 $env:LOCALAPPDATA=$originalLocal
 $resolved=[IO.Path]::GetFullPath($root)
 $temp=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')+'\'
 if(-not $resolved.StartsWith($temp,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($resolved) -notlike 'butler-recovery-diagnostic-*'){throw 'Unsafe fixture cleanup path'}
 Remove-Item -LiteralPath $resolved -Recurse -Force
}
