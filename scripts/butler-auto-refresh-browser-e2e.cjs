'use strict';

// BF-1043: execute the ACTUAL PowerShell-rendered automatic-refresh JavaScript
// in an isolated browser-like VM. Never makes an HTTP request or Sleeper write.
// This tests navigation, cooldowns, failures and same-origin POST intent rather
// than merely asserting that JavaScript-looking strings exist in HTML.
const assert = require('node:assert/strict');
const vm = require('node:vm');
const cp = require('node:child_process');
const path = require('node:path');

const fixturePath = path.join(__dirname, 'butler-auto-refresh-browser-fixtures.ps1');
const run = cp.spawnSync('powershell.exe', [
  '-NoLogo', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', fixturePath
], { encoding: 'utf8', timeout: 30000, windowsHide: true });
if (run.error || run.status !== 0) {
  throw new Error('BF-1043 PowerShell HTML fixture failed: ' +
    String(run.error || (run.stderr || run.stdout || '').slice(-900)));
}
const raw = (run.stdout || '').trim();
const data = JSON.parse(raw);
assert.match(data.audit, /^[0-9a-f-]{36}$/);
assert.match(data.token, /^[0-9a-f]{64}$/);

function extractScript(page) {
  assert.match(page.Nonce, /^[0-9a-f]{64}$/, 'missing generated script nonce');
  const scripts = [...page.Html.matchAll(/<script nonce="([0-9a-f]{64})">([\s\S]*?)<\/script>/g)];
  assert.equal(scripts.length, 1, 'must render exactly one script');
  assert.equal(scripts[0][1], page.Nonce, 'nonce mismatch');
  return scripts[0][2];
}
for (const route of ['dashboard', 'waivers', 'autopilot']) {
  assert.ok(extractScript(data[route]).includes("fetch('/refresh'"));
}
assert.equal(data.blockedAutopilot.Nonce, '');
assert.doesNotMatch(data.blockedAutopilot.Html, /<script\b/i);

// One simulated browser tab: shared sessionStorage across navigation, but
// fresh DOM/location per page view. All network calls are mocked.
const storage = new Map();
const calls = [];
let now = 1000000000;

async function visit(route, options = {}) {
  const page = data[route];
  const script = extractScript(page);
  const redirected = [];
  const status = { textContent: '' };
  const network = options.network || 'ok';
  const sandbox = {
    document: {
      getElementById: name => {
        assert.equal(name, 'butler-auto-refresh-status');
        return status;
      }
    },
    Date: { now: () => now },
    Number, String,
    sessionStorage: {
      getItem: k => storage.has(k) ? storage.get(k) : null,
      setItem: (k, v) => storage.set(k, v)
    },
    fetch: async (url, settings) => {
      calls.push({ route, url, settings });
      if (network === 'throw') { throw new Error('offline synthetic fixture'); }
      return { ok: network === 'ok' };
    },
    window: { location: { replace: href => redirected.push(href) } }
  };
  vm.runInNewContext(script, sandbox, { timeout: 2000, filename: route + '.rendered.js' });
  await new Promise(resolve => setImmediate(resolve));
  return { redirected, text: status.textContent };
}

(async () => {
  // First stale Dashboard visit initiates a same-origin governed refresh
  // immediately on script load, without any click.
  let x = await visit('dashboard');
  assert.equal(calls.length, 1);
  assert.deepEqual(x.redirected, ['/']);
  assert.equal(calls[0].url, '/refresh');
  assert.equal(calls[0].settings.method, 'POST');
  assert.equal(calls[0].settings.credentials, 'same-origin');
  assert.equal(calls[0].settings.headers['Content-Type'], 'application/x-www-form-urlencoded');
  assert.equal(calls[0].settings.body, 'token=' + data.token);
  assert.equal(storage.get('butler-auto-refresh:/:11111111-1111-1111-1111-111111111111'), String(now));

  // Moving to Auto-Pilot does not repeat the same audit's update.
  x = await visit('autopilot');
  assert.equal(calls.length, 1);
  assert.deepEqual(x.redirected, []);
  assert.match(x.text, /checked this decision recently/);

  // Waivers has an independent cooldown (different evidence surface).
  x = await visit('waivers');
  assert.equal(calls.length, 2);
  assert.deepEqual(x.redirected, ['/waivers']);
  assert.equal(calls[1].route, 'waivers');

  // Session refresh loop remains bounded while the same audit is active.
  x = await visit('dashboard');
  assert.equal(calls.length, 2);
  assert.deepEqual(x.redirected, []);

  // On a visit after five minutes, Auto-Pilot can recheck the governed gate.
  now += 300001;
  x = await visit('autopilot');
  assert.equal(calls.length, 3);
  assert.deepEqual(x.redirected, ['/autopilot']);

  // Rejected server-side decision does NOT navigate or retry automatically.
  now += 300001;
  x = await visit('dashboard', { network: 'reject' });
  assert.equal(calls.length, 4);
  assert.deepEqual(x.redirected, []);
  assert.match(x.text, /Automatic update stopped/);
  x = await visit('autopilot');
  assert.equal(calls.length, 4);
  assert.deepEqual(x.redirected, []);

  // A network exception is visible and likewise remains bounded.
  now += 300001;
  x = await visit('waivers', { network: 'throw' });
  assert.equal(calls.length, 5);
  assert.deepEqual(x.redirected, []);
  assert.match(x.text, /Automatic update could not finish/);
  x = await visit('waivers');
  assert.equal(calls.length, 5);

  // Separate audit identity authorizes a fresh visit, not a replay.
  // This is still only the local browser intent; the POST server gate must
  // independently validate its one-use token and policy.
  const audit2 = '22222222-2222-2222-2222-222222222222';
  const changed = {
    Html: data.dashboard.Html.replaceAll(data.audit, audit2),
    Nonce: data.dashboard.Nonce
  };
  data.audit2 = changed;
  x = await visit('audit2');
  assert.equal(calls.length, 6);
  assert.deepEqual(x.redirected, ['/']);
  assert.ok(storage.has('butler-auto-refresh:/:' + audit2));

  for (const call of calls) {
    assert.equal(call.url, '/refresh');
    assert.equal(call.settings.method, 'POST');
    assert.equal(call.settings.credentials, 'same-origin');
    assert.equal(call.settings.body, 'token=' + data.token);
  }
  console.log('BF-1043 REAL RENDERED AUTO-REFRESH BROWSER EXECUTION: PASS');
  console.log('Coverage: clickless update, Dashboard/Auto-Pilot shared audit cooldown, Waiver independence, 5-minute expiry, browser reload, server rejection and network failure, audit rotation, same-origin token-only POST intent.');
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
