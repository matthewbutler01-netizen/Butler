'use strict';

// BF-1050: real loopback HTTP integration for the v0.4 *GET-only diagnostic*.
// A deliberately synthetic Node server mimics public Butler route responses,
// then invokes the actual Windows PowerShell 5.1 CLI. No Sleeper access, no
// app data, no real user's league, and no POSTs of any kind.
const assert = require('node:assert/strict');
const http = require('node:http');
const cp = require('node:child_process');
const path = require('node:path');

const ps = path.join(__dirname, 'butler-v04-live-page-check.ps1');
const feature = 'v04-audited-onopen-freshness-bf1048';
const csp = "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'";
const audited = '<div>Decision state: CURRENT_AND_ACTIONABLE</div>' +
  '<div>BF-629: LIVE_ACTIONABLE_VERIFIED</div>' +
  '<div>BF-631: LATEST_EVIDENCE_LINEAGE_VERIFIED</div>';

function html(body) { return '<!doctype html><html><body>' + body + '</body></html>'; }

const good = {
  '/': html('<div class="dashboard-summary-row">Weekly command center</div>' + audited),
  '/team': html('My Team <section>Roster hub Lineup and depth at a glance ' +
    '<section id="roster-starters"><div class="player-row">synthetic player</div></section></section>'),
  '/waivers': html('Waiver Board <section class="waiver-decision-hero">Butler waiver decision</section>'),
  '/matchup': html('Matchup <section data-butler-week-state="MATCH"></section><section class="hero-panel">Weekly matchup <h1>Team A vs Team B</h1></section>'),
  '/matchup/autofill': html('Start/Sit Assistant <section data-butler-week-state="MATCH"></section><section class="panel recommendation-panel start-sit-assistant">Review only</section>'),
  '/league': html('League <section>League intelligence Governed guidance</section>'),
  '/autopilot': html('<section>CURRENT WEEKLY WATCH <span>CURRENT SNAPSHOT</span></section>')
};
const expectedGet = ['/health','/','/team','/waivers','/matchup','/matchup/autofill','/league','/autopilot'];
async function scenario(name, mutations, expect) {
  const requests = [];
  const server = http.createServer((req,res) => {
    requests.push({ method: req.method, url: req.url });
    // No remote routes, no POST, no query/fragment: fail closed.
    if (req.method !== 'GET' || !expectedGet.includes(req.url)) {
      res.writeHead(405); res.end('GET only'); return;
    }
    if (req.url === '/health') {
      res.writeHead(200, {'Content-Type':'application/json; charset=utf-8'});
      res.end(JSON.stringify({
        status:'ok',service:'butler-app-shell',
        featureSet: mutations.feature || feature, bind:'127.0.0.1'
      }));
      return;
    }
    const page = { ...good, ...(mutations.routes || {}) };
    const content = page[req.url];
    res.writeHead(200, {
      'Content-Type':'text/html; charset=utf-8',
      'Cache-Control':'no-store',
      'Content-Security-Policy': mutations.csp || csp
    });
    res.end(content);
  });
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0,'127.0.0.1', resolve);
  });
  const port = server.address().port;
  assert.ok(port > 1024);
  let child;
  try {
    child = await new Promise((resolve,reject) => {
      const runner = cp.spawn('powershell.exe',
        ['-NoLogo','-NoProfile','-ExecutionPolicy','Bypass','-File',
          ps,'-Port',String(port),'-TimeoutSeconds','5'],
        { windowsHide:true, stdio:['ignore','pipe','pipe'] });
      let stdout='', stderr='', settled=false;
      runner.stdout.on('data', b => { stdout += b.toString(); });
      runner.stderr.on('data', b => { stderr += b.toString(); });
      runner.on('error', reject);
      const timeout = setTimeout(() => { runner.kill(); reject(new Error(name + ' hung')); }, 60000);
      runner.on('close', (code, signal) => {
        clearTimeout(timeout);
        if (settled) return;
        settled=true;
        resolve({code,signal,stdout,stderr});
      });
    });
  } finally {
    await new Promise(resolve => server.close(resolve));
  }
  const output = child.stdout + '\n' + child.stderr;
  if (expect.code === 0) assert.equal(child.code, 0, name + '\n' + output);
  else assert.notEqual(child.code, 0, name + ' erroneously succeeded\n' + output);
  assert.match(output, expect.output, name + ' missing evidence status');
  for (const entry of requests) {
    assert.equal(entry.method,'GET', name + ' attempted a write');
    assert.ok(expectedGet.includes(entry.url), name + ' requested unapproved route');
  }
  if (expect.allRoutes) assert.deepEqual(requests.map(x=>x.url), expectedGet);
  else assert.deepEqual(requests.map(x=>x.url), ['/health']);
  console.log('BF-1050 HTTP ' + name.toUpperCase() + ': PASS');
}

(async () => {
  // Verified healthy pages actually pass through HTTP; route names alone
  // are never sufficient for team/waiver/matchup/StartSit.
  await scenario('healthy', {}, {
    code:0, output:/RESULT: 0 page failure\(s\), 0 watch warning\(s\)/, allRoutes:true
  });
  const stale = {
    ...good,
    '/': good['/'].replace('CURRENT_AND_ACTIONABLE','STALE_DO_NOT_ACT'),
    '/team':good['/team'].replace('<div class="player-row">synthetic player</div>',''),
    '/matchup':good['/matchup'].replace('Team A vs Team B','Opponent not confirmed'),
    '/autopilot':good['/autopilot'].replace('CURRENT SNAPSHOT','EVIDENCE NEEDS REFRESH')
  };
  await scenario('stale-or-incomplete', {routes:stale}, {
    code:0, output:/RESULT: 0 page failure\(s\), 4 watch warning\(s\)/, allRoutes:true
  });
  const nonce = 'a'.repeat(64);
  const autoDashboard = good['/'].replace('</body>',
    '<div id="butler-auto-refresh-status">Checking evidence</div>' +
    '<script nonce="' + nonce + '">/* mocked inert script */</script></body>');
  await scenario('nonce-armed', {
    routes: {'/':autoDashboard},
    csp: csp + "; script-src 'nonce-" + nonce + "'; connect-src 'self'"
  }, {
    code:0, output:/\/\s+PASS\s+evidence=AUDIT CURRENT\s+auto=ARMED/, allRoutes:true
  });
  await scenario('nonce-mismatch', {
    routes: {'/':autoDashboard},
    csp: csp + "; script-src 'nonce-" + 'b'.repeat(64) + "'; connect-src 'self'"
  }, {
    code:1, output:/\/\s+FAIL\s+evidence=AUTO CSP/, allRoutes:true
  });
  await scenario('navigation-only', {
    routes: {'/team':html('My Team navigation only')}
  }, {code:1, output:/\/team\s+FAIL\s+evidence=PAGE CONTENT/, allRoutes:true});
  await scenario('stale-matchup-no-unsafe-advice', {
    routes: {
      '/matchup': good['/matchup'].replace('data-butler-week-state="MATCH"', 'data-butler-week-state="MISMATCH"'),
      '/matchup/autofill': good['/matchup/autofill'].replace('data-butler-week-state="MATCH"', 'data-butler-week-state="UNVERIFIED"')
    }
  }, {
    code:0, output:/RESULT: 0 page failure\(s\), 2 watch warning\(s\)/, allRoutes:true
  });
  await scenario('missing-week-proof', {
    routes: {'/matchup': good['/matchup'].replace('data-butler-week-state="MATCH"', 'data-butler-week-state="BROKEN"')}
  }, {
    code:1, output:/\/matchup\s+FAIL\s+evidence=WEEK PROOF/, allRoutes:true
  });
  await scenario('unverified-week-advice-leak', {
    routes: {'/matchup/autofill':
      good['/matchup/autofill'].replace('data-butler-week-state="MATCH"', 'data-butler-week-state="UNVERIFIED"').replace('Review only', 'Promote to lineup')}
  }, {
    code:1, output:/\/matchup\/autofill\s+FAIL\s+evidence=HELD ADVICE/, allRoutes:true
  });
  await scenario('old-build-rejected', {feature:'old-v03'}, {
    code:1, output:/BF-1048 BLOCKED/, allRoutes:false
  });
  await scenario('incorrect-security', {csp:"default-src 'self'"}, {
    code:1, output:/\/team\s+FAIL\s+evidence=HTTP SAFETY/, allRoutes:true
  });
  console.log('BF-1050 REAL HTTP v0.4 MANAGER SMOKE: PASS');
  console.log('Coverage: GET-only local transport, confirmed/missing/ambiguous/stale-week evidence, blocked Start/Sit advice leaks, nonce-gated CSP, old v0.3 identity rejection, no POST/provider writes.');
})().catch(err => { console.error(err); process.exitCode = 1; });
