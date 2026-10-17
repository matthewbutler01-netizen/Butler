'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const ps=fs.readFileSync(path.join(__dirname,'butler-v04-beta-one-click.ps1'),'utf8');
const cmd=fs.readFileSync(path.join(__dirname,'butler-v04-beta-one-click.cmd'),'utf8');
const updater=fs.readFileSync(path.join(__dirname,'butler-v04-safe-update-and-start.ps1'),'utf8');
for(const bad of [/Stop-Process/i,/taskkill/i,/git\s+reset/i,/git\s+clean/i,/Invoke-RestMethod/i]){
 assert.doesNotMatch(ps,bad);
}
for(const good of [/-UpdateOnly/,/Test-BetaHealth/,/Test-BetaPortBusy/,/Start-Process -FilePath \$cmd/,/-CheckSleeperWeek/,/v04-beta-one-click-latest\.txt/,/BETA STATUS: PAGE ERROR/,/BETA STATUS: PAGES LOAD/]){
 assert.match(ps,good);
}
assert.match(updater,/\[switch\]\$UpdateOnly/);
// Exact immutable pin in the updater and the beta launcher must agree.
// A source checkout check is deliberately NOT proof of a live Sleeper week.
const pinnedUpdater=updater.match(/^\$targetCommit = '([0-9a-f]{40})'\r?$/m);
const pinnedLauncher=ps.match(/^\$approvedCandidateCommit = '([0-9a-f]{40})'\r?$/m);
assert.ok(pinnedUpdater && pinnedLauncher,'both immutable source pins required');
assert.equal(pinnedUpdater[1],pinnedLauncher[1],'one-click candidate pin drift');
assert.match(ps,/function Assert-BetaCandidateCheckout/);
assert.match(ps,/git -C \$Path rev-parse HEAD/);
assert.match(ps,/BETA CHECKOUT MISMATCH/);
assert.match(ps,/\$verifiedCheckout = Assert-BetaCandidateCheckout -Path \$CheckoutPath/);
assert.ok(ps.indexOf('Assert-BetaCandidateCheckout -Path $CheckoutPath') < ps.indexOf("Write-Host 'Starting v0.4 in a separate app window...'"),'must verify pin before launching');
assert.match(ps,/BETA SOURCE CHECKOUT: VERIFIED/);
assert.match(ps,/not a Sleeper week, injury, waiver availability or browser execution check/);
assert.match(ps,/EXISTING v0\.4 WORKER\. The tested beta update was NOT applied/);
assert.match(ps,/\$finalCheck = Save-BetaPageCheckReport/);
const finalGate=ps.indexOf('if (-not $finalCheck.Healthy)');
assert.ok(finalGate>ps.indexOf('$finalCheck = Save-BetaPageCheckReport'));
assert.ok(finalGate<ps.indexOf("Start-Process -FilePath ('http://127.0.0.1:'"),'final health gate must precede browser opening');
assert.match(ps,/\$startupReportWritten = \$false\s+if \(-not \$finalCheck.Healthy\)/);
assert.match(ps,/SERVICE NO LONGER VERIFIED AFTER PAGE CHECKS/);
assert.doesNotMatch(ps,/BUTLER: ALREADY RUNNING\. No update applied/);
assert.match(cmd,/butler-v04-beta-one-click\.ps1/);
assert.match(cmd,/EXTRACT ALL REQUIRED/);
console.log('BF-BETA ONE-CLICK CONTRACT: PASS (guarded update, launch, one report, no transaction)');

// The downloadable instructions must describe the actual held-worker behavior
// and ship the browser procedure; source identity alone is not acceptance.
const workflow=fs.readFileSync(path.join(__dirname,'../.github/workflows/windows-powershell-parse.yml'),'utf8');
assert.doesNotMatch(workflow,/370cd7bc|launcher opens',/);
assert.match(workflow,/If any v0\.4 Butler is already running, the launcher HOLDS/);
assert.match(workflow,/Copy-Item -LiteralPath .*v04-week-rollover-browser-acceptance\.md.*BROWSER-ACCEPTANCE\.md/);
assert.ok(workflow.includes(pinnedUpdater[1]),'packaged README must name the actual immutable pin');
const checklist=fs.readFileSync(path.join(__dirname,'../docs/v04-week-rollover-browser-acceptance.md'),'utf8');
assert.ok(checklist.includes(pinnedUpdater[1]),'browser checklist app pin drift');
console.log('BETA PACKAGE INSTRUCTIONS: PASS (pin, held worker, browser checklist)');
