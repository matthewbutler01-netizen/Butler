'use strict';
// Regression guard for the two long-evidence mobile overflow fixes.
// Source-level contract only; a real 304px browser is still required for
// geometry acceptance. This does not contact Sleeper or start the app.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const waiver = fs.readFileSync(path.join(__dirname,
  'butler-dashboard-bf834-waiver-decision-surface-transform.ps1'), 'utf8');
const startSit = fs.readFileSync(path.join(__dirname,
  'butler-app-bf1021-v04-start-sit-assistant-transform.ps1'), 'utf8');

function rule(source, selector) {
  const escaped = selector.replace(/[.*+?^\x24{}()|[\]\\]/g, '\\$&');
  const found = source.match(new RegExp(escaped + '\\s*\\{([^}]*)\\}'));
  assert.ok(found, 'CSS rule missing: ' + selector);
  return found[1];
}
function requireWrap(source, selector) {
  assert.match(rule(source, selector), /(?:^|;)\\s*overflow-wrap\\s*:\\s*anywhere\\s*(?:;|$)/i,
    'Mobile evidence must wrap: ' + selector);
}
function check(waiverSource, startSitSource) {
  requireWrap(waiverSource, '.waiver-pair-note');
  const pre = rule(waiverSource, '.waiver-pair-note pre');
  assert.match(pre, /(?:^|;)\\s*white-space\\s*:\\s*pre-wrap\\s*(?:;|$)/i,
    'The exact historical evidence record must retain line breaks but wrap');
  assert.match(pre, /(?:^|;)\\s*overflow-wrap\\s*:\\s*anywhere\\s*(?:;|$)/i);
  requireWrap(startSitSource, '.start-sit-assistant details');
  requireWrap(startSitSource, '.start-sit-assistant .review-queue li');
}
check(waiver, startSit);
// Deliberately regress each fix independently: this test must fail closed.
const waiverBroken = waiver.replace(/(\\.waiver-pair-note\\{[^}]*?)overflow-wrap:anywhere;/,
  '$1');
assert.notEqual(waiverBroken, waiver, 'waiver negative fixture never changed source');
assert.throws(() => check(waiverBroken, startSit), /Mobile evidence must wrap/);
const lineupBroken = startSit.replace(
  /\\.start-sit-assistant details\\{overflow-wrap:anywhere\\}/, '');
assert.notEqual(lineupBroken, startSit, 'Start/Sit negative fixture never changed source');
assert.throws(() => check(waiver, lineupBroken), /CSS rule missing/);
console.log('BUTLER V0.4 MOBILE EVIDENCE WRAP: PASS (source contract and negative controls; browser viewport still required)');
