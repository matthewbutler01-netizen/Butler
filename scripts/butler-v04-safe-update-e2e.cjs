'use strict';
const assert = require('node:assert/strict');
const cp = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const net = require('node:net');

if (process.platform !== 'win32') throw new Error('Windows required');
const root = fs.mkdtempSync(path.join(os.tmpdir(), 'butler-m1-upgrader-'));
const repo = path.join(root,'isolated-checkout');
const script = path.join(__dirname,'butler-v04-safe-update-and-start.ps1');
const cmd = path.join(__dirname,'butler-v04-safe-update-and-start.cmd');
const source = fs.readFileSync(script,'utf8');
const cmdSource = fs.readFileSync(cmd,'utf8');
for (const forbidden of [/Stop-Process/i,/taskkill/i,/git\s+reset/i,/git\s+clean/i,/git\s+push/i,/submitTransaction/i,/Invoke-RestMethod/i]) {
  assert.doesNotMatch(source,forbidden);
}
assert.match(source,/eb7ffb37edf1d8852226003d788c0eef62c5e659/);
assert.match(source,/refs\/heads\/bf-v04-fantasy-workflow-study/);
assert.match(source,/merge-base --is-ancestor \$targetCommit FETCH_HEAD/);
assert.match(source,/status --porcelain=v1 --untracked-files=normal/);
assert.match(source,/switch --detach \$targetCommit/);
assert.match(source,/\[switch\]\$UpdateOnly/);
assert.match(source,/if \(\$UpdateOnly\)/);
assert.match(cmdSource,/butler-v04-safe-update-and-start.ps1/);
assert.match(cmdSource,/EXTRACT ALL REQUIRED/);
function git(args) {
  const r = cp.spawnSync('git',['-C',repo,...args],{encoding:'utf8',windowsHide:true,timeout:10000});
  assert.equal(r.status,0,r.stderr);
}
function testRun(port) {
  return cp.spawnSync('powershell.exe', [
    '-NoLogo','-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass',
    '-File',script,'-PreflightOnly','-CheckoutPath',repo,'-TargetPort',String(port)
  ],{encoding:'utf8',windowsHide:true,timeout:10000});
}
(async()=>{
  try {
    // Actual entry points must reject a non-default launch port before even
    // inspecting the checkout. Preflight may still probe isolated test ports.
    for (const entry of [script,path.join(__dirname,'butler-v04-beta-one-click.ps1')]) {
      const rejected=cp.spawnSync('powershell.exe',[
        '-NoLogo','-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass',
        '-File',entry,'-CheckoutPath',repo,'-TargetPort','18081'
      ],{encoding:'utf8',windowsHide:true,timeout:10000});
      assert.equal(rejected.status,2,rejected.stdout+rejected.stderr);
      assert.match(rejected.stdout,/PORT MISMATCH/);
      assert.doesNotMatch(rejected.stdout,/Fetching pinned|Starting v0.4|BETA CHECKOUT: VERIFIED/);
      assert.equal(fs.existsSync(repo),false,'must reject before creating a checkout');
    }
    fs.mkdirSync(path.join(repo,'scripts'),{recursive:true});
    fs.writeFileSync(path.join(repo,'scripts','butler-v04-start-18080.cmd'),'@echo off\r\nexit /b 0\r\n');
    git(['init','-q']);
    git(['config','user.name','Butler Test']);
    git(['config','user.email','noreply@example.invalid']);
    git(['add','.']);
    git(['commit','-qm','isolated fixture']);
    git(['remote','add','origin','https://github.com/OTHER/OTHER']);
    let r=testRun(18080);
    assert.equal(r.status,2,r.stdout+r.stderr);
    assert.match(r.stdout,/UNRECOGNIZED GIT ORIGIN/);
    git(['remote','set-url','origin','https://github.com/matthewbutler01-netizen/Butler.git']);
    let freePort;
    let busy = net.createServer();
    await new Promise((ok,fail)=>busy.once('error',fail).listen(0,'127.0.0.1',ok));
    try {
      const port=busy.address().port;
      freePort=port;
      r=testRun(port);
      assert.equal(r.status,2,r.stdout+r.stderr);
      assert.match(r.stdout,/PORT \d+ OCCUPIED/);
    } finally {
      await new Promise(resolve=>busy.close(resolve));
    }
    // Probe the released fixture port, never the owner's running beta.
    r=testRun(freePort);
    assert.equal(r.status,0,r.stdout+r.stderr);
    assert.match(r.stdout,/PREFLIGHT ONLY/);
    assert.doesNotMatch(r.stdout,/Fetching pinned/);
    fs.writeFileSync(path.join(repo,'local-untracked.txt'),'test');
    // Probe the released fixture port, never the owner's running beta.
    r=testRun(freePort);
    assert.equal(r.status,2,r.stdout+r.stderr);
    assert.match(r.stdout,/LOCAL CHANGES PRESENT/);
    fs.unlinkSync(path.join(repo,'local-untracked.txt'));
    console.log('BF-M1 PINNED ONE-CLICK UPGRADE PREFLIGHT: PASS (offline; no fetch/switch/launch)');
  } finally {
    fs.rmSync(root,{recursive:true,force:true});
  }
})().catch(err=>{console.error(err);process.exitCode=1;});
