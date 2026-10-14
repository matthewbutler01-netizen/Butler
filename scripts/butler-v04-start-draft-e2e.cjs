'use strict';

// Windows-only, data-free acceptance for the optional v0.4 app starter.
// The test NEVER launches Butler: the allowed app.cmd is a local mock.
const assert = require('node:assert/strict');
const cp = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

if (process.platform !== 'win32') throw new Error('v0.4 starter contract needs Windows');
const root = fs.mkdtempSync(path.join(os.tmpdir(), 'butler-v04-start-mock-'));
const cmd = 'butler-v04-start-18080.cmd';
function run() {
  const proc = cp.spawnSync('cmd.exe', ['/d', '/c', path.join(root, cmd)], {
    input:'\r\n', encoding:'utf8', timeout:10000, windowsHide:true
  });
  if (proc.error) throw proc.error;
  return {code:proc.status, output:String(proc.stdout||'')+String(proc.stderr||'')};
}
try {
  fs.copyFileSync(path.join(__dirname,cmd), path.join(root,cmd));
  const missing = run();
  assert.equal(missing.code,2,missing.output);
  assert.match(missing.output,/COMPLETE V0.4 DEVELOPMENT CHECKOUT REQUIRED/);
  assert.doesNotMatch(missing.output,/Launching current source checkout/);

  for (const name of ['butler-app-supervisor.ps1','butler-app-guard.ps1',
                      'butler-app.ps1','butler-java-preflight.ps1']) {
    fs.writeFileSync(path.join(root,name),'# mock only\r\n');
  }
  fs.writeFileSync(path.join(root,'butler-app.cmd'),
    '@echo off\r\necho MOCK_LOCAL_START %*\r\nexit /b 37\r\n');
  const start = run();
  assert.equal(start.code,37,start.output);
  assert.match(start.output,/MOCK_LOCAL_START -Port 18080/);
  assert.match(start.output,/Butler process ended with status 37/);
  assert.doesNotMatch(start.output,/COMPLETE V0.4 DEVELOPMENT CHECKOUT REQUIRED/);
  console.log('BF-M1 ONE-CLICK STARTER: GUARDED MOCK START / NO ZIP LAUNCH PASS');
} finally {
  fs.rmSync(root,{recursive:true,force:true});
}
