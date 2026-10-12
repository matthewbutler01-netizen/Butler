'use strict';

// Milestone 1: Windows ZIP viewers may launch only the clicked .cmd from a
// temporary folder, leaving its helper scripts still inside the ZIP.
// The user must see clear Extract All steps, not "is not recognized".
// This fixture copies only the launcher; no Butler app or source data runs.
const assert = require('node:assert/strict');
const cp = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

if (process.platform !== 'win32') {
  throw new Error('BF-M1 ZIP extraction regression requires Windows');
}
const root = fs.mkdtempSync(path.join(os.tmpdir(), 'butler-m1-unextracted-'));
try {
  const name = 'butler-v04-roster-waiver-first-open.cmd';
  const isolated = path.join(root,name);
  fs.copyFileSync(path.join(__dirname,name), isolated);
  const result = cp.spawnSync('cmd.exe',['/d','/c',isolated], {
    encoding:'utf8', input:'\r\n', timeout:10000, windowsHide:true
  });
  if(result.error) throw result.error;
  const output = String(result.stdout || '') + String(result.stderr || '');
  assert.equal(result.status,2, 'missing ZIP dependencies should exit 2\n' + output);
  assert.match(output,/EXTRACT ALL FILES FIRST/, 'missing Extract All instructions');
  assert.match(output,/Right-click the ZIP, choose Extract All/, 'missing explicit Windows steps');
  assert.doesNotMatch(output,/is not recognized|MILESTONE 1 RESULT:/i,
    'attempted missing helper instead of failing before network/diagnostic');
  console.log('BF-M1 ZIP-ONLY CMD: SAFE EXTRACT-ALL INSTRUCTIONS PASS');
} finally {
  fs.rmSync(root,{recursive:true,force:true});
}
