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
for (const route of ['dashboard', 'waivers', 'autopilot', 'startsit']) {
  assert.ok(extractScript(data[route]).includes("fetch('/refresh'"));
}
assert.equal(data.blockedStartSit.Nonce, '');
assert.doesNotMatch(data.blockedStartSit.Html, /<script\b/i);
assert.equal(data.blockedAutopilot.Nonce, '');
assert.doesNotMatch(data.blockedAutopilot.Html, /<script\b/i);

// Cross-page rollover suppression uses the actual PowerShell renderers:
// six actionability states x six lineage states x three source-based pages.
assert.equal(data.rolloverPages.length, 36);
assert.equal(new Set(data.rolloverPages.map(x => x.Actionability + ':' + x.Lineage)).size, 36);
for (const state of data.rolloverPages) {
  for (const surface of ['Dashboard', 'AutoPilot', 'StartSit']) {
    const page = state[surface];
    const context = `${surface}: ${state.Actionability}/${state.Lineage}`;
    assert.equal(page.Nonce, '', context);
    assert.doesNotMatch(page.Html, /<script\b|data-butler-auto-waiver=/i, context);
    assert.doesNotMatch(page.Html, /href="\/refresh"/, context);
  }
}
// Merely opening confirmation cannot refresh or replay the previous player
// pair. The only submitted field is the token, under explicit user action.
assert.doesNotMatch(data.confirmation, /<script\b|\bon\w+\s*=|http-equiv\s*=\s*["']?refresh/i);
const forms = [...data.confirmation.matchAll(/<form\b([^>]*)>([\s\S]*?)<\/form>/gi)];
assert.equal(forms.length, 1);
assert.match(forms[0][1], /method="post"/);
assert.match(forms[0][1], /action="\/refresh"/);
const inputs = [...forms[0][2].matchAll(/<input\b([^>]*)>/gi)];
assert.equal(inputs.length, 1);
assert.match(inputs[0][1], /type="hidden"/);
assert.match(inputs[0][1], /name="token"/);
assert.ok(inputs[0][1].includes(`value="${data.token}"`));
assert.match(forms[0][2], /<button\b[^>]*type="submit"[^>]*>Confirm refresh<\/button>/);
assert.match(forms[0][2], /href="\/">Cancel<\/a>/);

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
      if (network === 'pending') { return new Promise(() => {}); }
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
  assert.equal(storage.get('butler-auto-refresh:/:11111111-1111-1111-1111-111111111111'), 's:' + String(now));

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

  // BF-1103: a rejected local POST gets a brief failed-attempt lock,
  // NOT a five-minute success lock, so future visits can self-heal.
  now += 300001;
  x = await visit('dashboard', { network: 'reject' });
  assert.equal(calls.length, 4);
  assert.deepEqual(x.redirected, []);
  assert.match(x.text, /Automatic update stopped/);
  const dashboardKey = 'butler-auto-refresh:/' + ':' + data.audit;
  assert.equal(storage.get(dashboardKey), 'f:' + String(now));
  x = await visit('autopilot');
  assert.equal(calls.length, 4);
  assert.deepEqual(x.redirected, []);
  assert.match(x.text, /could not complete the last update/);
  now += 60001;
  x = await visit('autopilot');
  assert.equal(calls.length, 5);
  assert.deepEqual(x.redirected, ['/autopilot']);
  assert.equal(storage.get(dashboardKey), 's:' + String(now));

  // A network exception is visible and likewise remains bounded,
  // with an automatic retry on a LATER page visit, not in the same view.
  now += 300001;
  x = await visit('waivers', { network: 'throw' });
  assert.equal(calls.length, 6);
  assert.deepEqual(x.redirected, []);
  assert.match(x.text, /Automatic update could not finish/);
  const waiverKey = 'butler-auto-refresh:/waivers:' + data.audit;
  assert.equal(storage.get(waiverKey), 'f:' + String(now));
  x = await visit('waivers');
  assert.equal(calls.length, 6);
  now += 60001;
  x = await visit('waivers');
  assert.equal(calls.length, 7);
  assert.deepEqual(x.redirected, ['/waivers']);
  assert.equal(storage.get(waiverKey), 's:' + String(now));

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
  assert.equal(calls.length, 8);
  assert.deepEqual(x.redirected, ['/']);
  assert.ok(storage.has('butler-auto-refresh:/:' + audit2));

  // A previous legacy numeric timestamp must still suppress a loop for
  // five minutes. Pending requests also lock a second same-audit visit
  // without claiming that the first request succeeded.
  const audit3 = '33333333-3333-3333-3333-333333333333';
  data.audit3 = {
    Html: data.dashboard.Html.replaceAll(data.audit, audit3),
    Nonce: data.dashboard.Nonce
  };
  const thirdKey = 'butler-auto-refresh:/:' + audit3;
  x = await visit('audit3', { network: 'pending' });
  assert.equal(calls.length, 9);
  assert.deepEqual(x.redirected, []);
  assert.equal(storage.get(thirdKey), 'p:' + String(now));
  x = await visit('audit3');
  assert.equal(calls.length, 9);
  assert.match(x.text, /could not complete the last update/);
  now += 60001;
  x = await visit('audit3');
  assert.equal(calls.length, 10);
  assert.deepEqual(x.redirected, ['/']);
  assert.equal(storage.get(thirdKey), 's:' + String(now));

  // Version migration: old 5-minute timestamp is interpreted as a
  // successful cooldown; a second POST must NOT be sent immediately.
  storage.set(thirdKey, String(now));
  x = await visit('audit3');
  assert.equal(calls.length, 10);
  assert.match(x.text, /checked this decision recently/);

  for (const call of calls) {
    assert.equal(call.url, '/refresh');
    assert.equal(call.settings.method, 'POST');
    assert.equal(call.settings.credentials, 'same-origin');
    assert.equal(call.settings.body, 'token=' + data.token);
  }
  // BF-1104: a blocked Start/Sit page can recheck the current audited
  // Dashboard decision on open. Its cooldown is shared with Dashboard
  // and Auto-Pilot, so navigating between them never repeats the POST.
  storage.clear();
  calls.length = 0;
  now += 300001;
  x = await visit('startsit');
  assert.equal(calls.length, 1);
  assert.equal(calls[0].route, 'startsit');
  assert.equal(calls[0].settings.body, 'token=' + data.token);
  assert.deepEqual(x.redirected, ['/matchup/autofill']);
  const sharedKey = 'butler-auto-refresh:/:' + data.audit;
  assert.equal(storage.get(sharedKey), 's:' + String(now));
  x = await visit('dashboard');
  assert.equal(calls.length, 1);
  assert.deepEqual(x.redirected, []);
  x = await visit('autopilot');
  assert.equal(calls.length, 1);
  assert.deepEqual(x.redirected, []);

  // If the blocked Start/Sit source recovery fails, only a later page
  // visit after the bounded failure lock is allowed to try again.
  now += 300001;
  x = await visit('startsit', { network: 'throw' });
  assert.equal(calls.length, 2);
  assert.deepEqual(x.redirected, []);
  assert.equal(storage.get(sharedKey), 'f:' + String(now));
  x = await visit('dashboard');
  assert.equal(calls.length, 2);
  now += 60001;
  x = await visit('dashboard');
  assert.equal(calls.length, 3);
  assert.deepEqual(x.redirected, ['/']);
  assert.equal(storage.get(sharedKey), 's:' + String(now));

  console.log('BF-1043 REAL RENDERED AUTO-REFRESH BROWSER EXECUTION: PASS');
  console.log('Rollover: 108 source-page/state combinations stay script-free; confirmation requires explicit token-only submit; no real HTTP or provider writes.');
  console.log('Coverage: clickless Dashboard/Waivers/Auto-Pilot/blocked Start-Sit refresh, shared success/failure cooldown across manager pages, later-visit recovery, pending lock, legacy migration, no healthy Start/Sit script, token-only POST.');
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
