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
assert.match(ps,/EXISTING v0\.4 WORKER\. The tested beta update was NOT applied/);
assert.doesNotMatch(ps,/BUTLER: ALREADY RUNNING\. No update applied/);
assert.match(cmd,/butler-v04-beta-one-click\.ps1/);
assert.match(cmd,/EXTRACT ALL REQUIRED/);
console.log('BF-BETA ONE-CLICK CONTRACT: PASS (guarded update, launch, one report, no transaction)');
