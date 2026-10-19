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
# The old summary reader chooses the first matching field. Reject ambiguity
# before it can populate any older hero, glance card or hidden audit contract.
$entry=$fn.Body.EndBlock.Statements[0].Extent.StartOffset-$fn.Extent.StartOffset
$entryGuard=@'
    if (-not (Test-ButlerQueueAuditFieldsUnique -Summary $Summary)) {
        $heldItems = @(Get-ButlerWeeklyDecisionQueue -LineupState 'UNVERIFIED' -WaiverState 'UNVERIFIED')
        $heldQueue = ConvertTo-ButlerWeeklyDecisionQueueHtml -Items $heldItems -Team 'Team context not independently verified'
        return '<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Butler - Dashboard evidence held</title><style>' + (Get-SharedCss) + '</style></head><body><main class="shell">' + (Get-HeaderHtml -Target 'Audit proof not verified' -Active 'dashboard') + '<section class="panel"><h1>Dashboard evidence needs review</h1><p>Required audit fields are missing or duplicated. Butler has withheld saved recommendations from this Dashboard. Review the source pages before acting.</p></section>' + $heldQueue + '<p>READ ONLY. No Sleeper transaction was submitted by this view.</p></main></body></html>'
    }

'@
$body=$body.Insert($entry,$entryGuard+[Environment]::NewLine)
$module=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'butler-weekly-decision-queue.ps1'))
$source=$source.Substring(0,$fn.Extent.StartOffset)+$module+[Environment]::NewLine+$body+$source.Substring($fn.Extent.EndOffset)
[void][Management.Automation.Language.Parser]::ParseInput($source,[ref]$tokens,[ref]$errors)
if (@($errors).Count) { throw 'Weekly queue: generated renderer parse failed.' }
[IO.File]::WriteAllText($DashboardPath,$source,[Text.UTF8Encoding]::new($false))
