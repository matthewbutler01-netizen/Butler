param([Parameter(Mandatory=$true)][string]$DashboardPath)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$source = [IO.File]::ReadAllText($DashboardPath)
$tokens=$null; $errors=$null
$ast=[Management.Automation.Language.Parser]::ParseInput($source,[ref]$tokens,[ref]$errors)
if (@($errors).Count) { throw 'Weekly queue: input parse failed.' }
$functions=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq 'ConvertTo-DashboardHtml'},$true))
if ($functions.Count -ne 1 -or $source.Contains('function Get-ButlerWeeklyDecisionQueue')) { throw 'Weekly queue: missing or duplicate renderer contract.' }
$fn=$functions[0]; $body=$fn.Extent.Text
$position=$body.LastIndexOf('    return @"',[StringComparison]::Ordinal)
if ($position -lt 0) { throw 'Weekly queue: final HTML return missing.' }
$tail=$body.Substring($position)
if ([regex]::Matches($tail,[regex]::Escape('$bf907WeekGlanceHtml')).Count -ne 1) { throw 'Weekly queue: overview slot ambiguous.' }
$tail=$tail.Replace('$bf907WeekGlanceHtml', '$weeklyDecisionQueueHtml' + [Environment]::NewLine + '$bf907WeekGlanceHtml')
$prelude=@'
    $weeklyDecisionQueue = @(Get-ButlerWeeklyDecisionQueue -LineupState ([string]$lineupSignalStatus) -WaiverState ([string]$state) -Actionability ([string]$bf629) -Lineage ([string]$bf631) -AuditFieldsUnique (Test-ButlerQueueAuditFieldsUnique -Summary $Summary))
    $weeklyDecisionQueueHtml = ConvertTo-ButlerWeeklyDecisionQueueHtml -Items $weeklyDecisionQueue -Team ([string]$target)

'@
$body=$body.Substring(0,$position)+$prelude+[Environment]::NewLine+$tail
$module=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'butler-weekly-decision-queue.ps1'))
$source=$source.Substring(0,$fn.Extent.StartOffset)+$module+[Environment]::NewLine+$body+$source.Substring($fn.Extent.EndOffset)
[void][Management.Automation.Language.Parser]::ParseInput($source,[ref]$tokens,[ref]$errors)
if (@($errors).Count) { throw 'Weekly queue: generated renderer parse failed.' }
[IO.File]::WriteAllText($DashboardPath,$source,[Text.UTF8Encoding]::new($false))
