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
const readiness = 'v04-sourced-onopen-bf1098';
const csp = "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'";
const observed = new Date().toISOString().replace(/\.\d{3}Z$/, 'Z');
const currentProof = '<p class="meta butler-startsit-source-proof" role="status">' +
  '1 proposed lineup changes have exact player-status checks from Sleeper at the recorded fetch time. ' +
  'Full scoreable projection coverage; 0 player holds. Recheck injury updates before kickoff. ' +
  'A projection is not availability clearance and no Sleeper move was submitted. ' +
  'Sources: projection snapshot retrieved ' + observed + ' UTC; swap players status map retrieved ' +
  observed + ' UTC. This records a source fetch, not an injury report publication time.</p>';
const audited = '<div>Decision state: CURRENT_AND_ACTIONABLE</div>' +
  '<div>BF-629: LIVE_ACTIONABLE_VERIFIED</div>' +
  '<div>BF-631: LATEST_EVIDENCE_LINEAGE_VERIFIED</div>';

function html(body) { return '<!doctype html><html><body>' + body + '</body></html>'; }

const good = {
  '/': html('<div class="dashboard-summary-row"><div class="dashboard-summary-card"><span>Start/Sit</span><strong>REVIEW</strong></div></div>' + audited),
  '/team': html('My Team <section>Roster hub Lineup and depth at a glance ' +
    '<section id="roster-starters"><div class="player-row">synthetic player</div></section></section>'),
  '/waivers': html('Waiver Board <section class="waiver-decision-hero">Butler waiver decision</section>'),
  '/matchup': html('Matchup <section class="panel butler-live-week-status" role="status" data-butler-week-state="MATCH" data-butler-week-season="2026" data-butler-week-number="5"></section><div class="target" data-butler-matchup-season="2026">League &middot; Week 5</div><section class="hero-panel">Weekly matchup <h1>Team A vs Team B</h1></section>'),
  '/matchup/autofill': html('Start/Sit Assistant <section class="panel butler-live-week-status" role="status" data-butler-week-state="MATCH" data-butler-week-season="2026" data-butler-week-number="5"></section><div class="target" data-butler-matchup-season="2026">League &middot; Week 5</div><section class="panel recommendation-panel start-sit-assistant">Review only' + currentProof + '</section>'),
  '/league': html('League <section>League intelligence Governed guidance</section>'),
  '/autopilot': html('<section><div class="autopilot-watch-head"><div><div class="eyebrow">CURRENT WEEKLY WATCH</div><h2>What Butler sees right now</h2><span class="autopilot-watch-week" role="status" data-butler-watch-week-state="MATCH">NFL 2026 season - Week 5 verified against the public Sleeper week.</span></div><span class="status good">CURRENT SNAPSHOT</span></div>' +
    '<div class="autopilot-watch-grid"><div class="autopilot-watch-card"><span>Start/Sit</span><strong>REVIEW</strong></div></div>' +
    '<div class="autopilot-prepared-head"><div><div class="eyebrow">PREPARED START/SIT REVIEW</div><h2>Recommendation packet</h2></div><span class="status good">READY FOR MANAGER REVIEW</span></div></section>')
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
        featureSet: mutations.feature || feature,
        ...(mutations.omitReadiness ? {} : {readinessContract: mutations.readiness || readiness}),
        bind:'127.0.0.1'
      }));
      return;
    }
    // BF-1107: exercise real HttpWebRequest exception responses without
    // printing server response bodies or private diagnostics.
    const failureStatus = mutations.httpStatus && mutations.httpStatus[req.url];
    if (Number.isInteger(failureStatus)) {
      res.writeHead(failureStatus, {'Content-Type':'text/plain; charset=utf-8'});
      res.end('SENSITIVE_LOCAL_ERROR_BODY: roster, league and audit data');
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
          ps,'-Port',String(port),'-TimeoutSeconds','5', ...(mutations.strict ? ['-RequireReady'] : [])],
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
  assert.equal(child.code, expect.code, name + ' wrong readiness exit code\n' + output);
  assert.match(output, expect.output, name + ' missing evidence status');
  assert.doesNotMatch(output,/SENSITIVE_LOCAL_ERROR_BODY|roster, league and audit data/,
    name + ' leaked HTTP response body');
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
  // BF-1107: the Windows user's real readiness run reported GET ERROR for
  // My Team and Waivers. Diagnose HTTP server failures separately from
  // timeouts; do not print response bodies or user-specific exception text.
  await scenario('manager-http-failures', {
    httpStatus: {'/team':500, '/waivers':503}
  }, {
    code:1,
    output:/[/]team[^\r\n]*FAIL[^\r\n]*HTTP 500[\s\S]*[/]waivers[^\r\n]*FAIL[^\r\n]*HTTP 503/,
    allRoutes:true
  });
  // BF-1102: the on-screen Auto-Pilot season/week must agree with the
  // independently read Matchup. Duplicate/missing/old week labels warn.
  const weekBadge = '<span class="autopilot-watch-week" role="status" data-butler-watch-week-state="MATCH">NFL 2026 season - Week 5 verified against the public Sleeper week.</span>';
  await scenario('strict-auto-week-drift', {
    strict:true,
    routes:{'/autopilot':good['/autopilot'].replace(
      'Week 5 verified against the public Sleeper week.',
      'Week 4 verified against the public Sleeper week.')}
  }, {
    code:2, output:/Auto-Pilot week\s+WARN\s+evidence=WATCH WEEK DRIFT/, allRoutes:true
  });
  await scenario('strict-auto-week-missing', {
    strict:true,
    routes:{'/autopilot':good['/autopilot'].replace(weekBadge, '')}
  }, {
    code:2, output:/Auto-Pilot week\s+WARN\s+evidence=WATCH WEEK MISSING/, allRoutes:true
  });
  await scenario('strict-auto-week-duplicate', {
    strict:true,
    routes:{'/autopilot':good['/autopilot'].replace(weekBadge, weekBadge.repeat(2))}
  }, {
    code:2, output:/Auto-Pilot week\s+WARN\s+evidence=WATCH WEEK MISSING/, allRoutes:true
  });
  // BF-1097: the actual one-click localhost checker must catch a missing
  // automatic Auto-Pilot browser trigger when the same governed Dashboard
  // audit would arm it, and reject an armed trigger on current evidence.
  const autoAudit = '11111111-1111-1111-1111-111111111111';
  const bf1097Nonce = 'a'.repeat(64);
  const autoMarker = '<div id="butler-auto-refresh-status"></div>' +
    '<script nonce="' + bf1097Nonce + '">window.bf1097Fixture=true;</script>';
  const autoCsp = csp + "; script-src 'nonce-" + bf1097Nonce + "'; connect-src 'self'";
  const nav = '<nav class="nav" aria-label="Butler sections"></nav>';
  const currentWithNav = good['/'].replace(audited, nav + audited);
  const staleProof = '<div>Decision state: STALE_DO_NOT_ACT</div>' +
    '<div>BF-629: LIVE_ACTIONABLE_VERIFIED</div>' +
    '<div>BF-631: MARKET_LINEAGE_SUPERSEDED</div>' +
    '<div>Audit ID: ' + autoAudit + '</div>';
  const staleWithArmedDashboard = good['/'].replace(
    audited, nav + staleProof + autoMarker);
  await scenario('strict-current-onopen-gated', {
    strict:true, routes:{'/':currentWithNav}
  }, {
    code:0, output:/Auto-Pilot on-open\s+PASS\s+evidence=ON-OPEN GATED/, allRoutes:true
  });
  await scenario('strict-current-onopen-unexpected', {
    strict:true,csp:autoCsp,
    routes:{'/':currentWithNav, '/autopilot':good['/autopilot'].replace('</section>',autoMarker+'</section>')}
  }, {
    code:2, output:/Auto-Pilot on-open\s+WARN\s+evidence=AUTO-PILOT AUTO UNEXPECTED/, allRoutes:true
  });
  await scenario('strict-stale-onopen-missing', {
    strict:true,csp:autoCsp,
    routes:{'/':staleWithArmedDashboard}
  }, {
    code:2, output:/Auto-Pilot on-open\s+WARN\s+evidence=AUTO-PILOT AUTO MISSING/, allRoutes:true
  });
  await scenario('strict-stale-onopen-armed', {
    strict:true,csp:autoCsp,
    routes:{'/':staleWithArmedDashboard,
      '/autopilot':good['/autopilot'].replace('</section>',autoMarker+'</section>')}
  }, {
    code:2, output:/Auto-Pilot on-open\s+PASS\s+evidence=ON-OPEN ARMED/, allRoutes:true
  });
  // BF-1094: cross-page contracts check the displayed decision, not just
  // individual HTTP/CSP/source provenance status. A Dashboard vs Auto-Pilot
  // disagreement must block strict local readiness without leaking names.
  await scenario('strict-cross-route-lineup-drift', {
    strict:true,
    routes:{'/autopilot':good['/autopilot'].replace(
      '<span>Start/Sit</span><strong>REVIEW</strong>',
      '<span>Start/Sit</span><strong>NO CHANGE VERIFIED</strong>')}
  }, {
    code:2, output:/Cross-route lineup\s+WARN\s+evidence=LINEUP SIGNAL DRIFT/, allRoutes:true
  });
  await scenario('strict-cross-route-lineup-duplicate', {
    strict:true,
    routes:{'/autopilot':good['/autopilot'].replace(
      '<div class="autopilot-watch-card"><span>Start/Sit</span><strong>REVIEW</strong></div>',
      '<div class="autopilot-watch-card"><span>Start/Sit</span><strong>REVIEW</strong></div>'.repeat(2))}
  }, {
    code:2, output:/Cross-route lineup\s+WARN\s+evidence=LINEUP CARD MISSING/, allRoutes:true
  });
  // BF-1095: independently passing cards cannot contradict the actual
  // Start/Sit source paragraph and still pass one-click strict readiness.
  const noChangeProof = '<p class="meta butler-startsit-source-proof" role="status">' +
    'No lineup change is ready. Full scoreable projection coverage; 0 player holds. ' +
    'Recheck before kickoff. Sources: projection snapshot retrieved ' +
    observed + ' UTC; exact swap status fetch time not verified.</p>';
  await scenario('strict-source-nochange-vs-dashboard-review', {
    strict:true,
    routes:{'/matchup/autofill':good['/matchup/autofill'].replace(currentProof,noChangeProof)}
  }, {
    code:2, output:/Source lineup\s+WARN\s+evidence=START\/SIT DECISION DRIFT/, allRoutes:true
  });
  const noChangeDashboard = good['/'].replace(
    '<span>Start/Sit</span><strong>REVIEW</strong>',
    '<span>Start/Sit</span><strong>NO CHANGE</strong>');
  const noChangePilot = good['/autopilot'].replace(
    '<span>Start/Sit</span><strong>REVIEW</strong>',
    '<span>Start/Sit</span><strong>NO CHANGE VERIFIED</strong>').replace(
    '<span class="status good">READY FOR MANAGER REVIEW</span>',
    '<span class="status good">NO ACTION REQUIRED</span>');
  await scenario('strict-source-change-vs-keep-lineup', {
    strict:true,
    routes:{'/':noChangeDashboard,'/autopilot':noChangePilot}
  }, {
    code:2, output:/Source lineup\s+WARN\s+evidence=START\/SIT DECISION DRIFT/, allRoutes:true
  });
  await scenario('strict-all-three-no-change', {
    strict:true,
    routes:{'/':noChangeDashboard,'/autopilot':noChangePilot,
            '/matchup/autofill':good['/matchup/autofill'].replace(currentProof,noChangeProof)}
  }, {
    code:0, output:/Source lineup\s+PASS\s+evidence=START\/SIT ALIGNED/, allRoutes:true
  });
  // BF-1078: the same local GETs under the real-league strict mode only
  // return OS success when every locally observable requirement passes.
  await scenario('strict-all-pages-ready', {strict:true}, {
    code:0, output:/READINESS GATE: LOCAL PAGE CHECKS PASS ONLY; PUBLIC NFL WEEK NOT REQUESTED/, allRoutes:true
  });
  await scenario('strict-decorative-startsit-panel', {
    strict:true,
    routes:{'/matchup/autofill':good['/matchup/autofill'].replace(currentProof, '')}
  }, {
    code:2, output:/\/matchup\/autofill\s+WARN\s+evidence=START\/SIT SOURCE HOLD/, allRoutes:true
  });
  await scenario('strict-expired-player-availability', {
    strict:true,
    routes:{'/matchup/autofill':good['/matchup/autofill'].replace(
      'swap players status map retrieved ' + observed + ' UTC',
      'swap players status map retrieved 2025-01-01T10:00:00Z UTC')}
  }, {
    code:2, output:/\/matchup\/autofill\s+WARN\s+evidence=START\/SIT SOURCE HOLD/, allRoutes:true
  });
  // BF-1096: an extra contradictory player hold, projection coverage
  // or change count invalidates the strict release gate, despite a valid
  // leading phrase and recent timestamps in the same source paragraph.
  for (const [name, corrupted] of [
    ['strict-contradictory-holds', currentProof.replace(
      '0 player holds.', '0 player holds. 10 player holds.')],
    ['strict-duplicated-coverage', currentProof.replace(
      'Full scoreable projection coverage;', 'Full scoreable projection coverage; Full scoreable projection coverage;')],
    ['strict-contradictory-change-count', currentProof.replace(
      '1 proposed lineup changes', '1 proposed lineup changes. 2 proposed lineup changes')],
    ['strict-zero-proposed-changes', currentProof.replace(
      '1 proposed lineup changes', '0 proposed lineup changes')],
    ['strict-nochange-with-added-proposal', noChangeProof.replace(
      'Sources:', '1 proposed lineup changes. Sources:')]
  ]) {
    await scenario(name, {
      strict:true,
      routes:{'/matchup/autofill':good['/matchup/autofill'].replace(currentProof,corrupted)}
    }, {
      code:2, output:/\/matchup\/autofill\s+WARN\s+evidence=START\/SIT SOURCE HOLD/, allRoutes:true
    });
  }
  await scenario('strict-incomplete-projection-coverage', {
    strict:true,
    routes:{'/matchup/autofill':good['/matchup/autofill'].replace(
      'Full scoreable projection coverage', 'Partial projection coverage; missing projections are not zeros')}
  }, {
    code:2, output:/\/matchup\/autofill\s+WARN\s+evidence=START\/SIT SOURCE HOLD/, allRoutes:true
  });
  await scenario('strict-unverified-player-status', {
    strict:true,
    routes:{'/matchup/autofill':good['/matchup/autofill'].replace(
      'swap players status map retrieved ' + observed + ' UTC',
      'exact swap status fetch time not verified')}
  }, {
    code:2, output:/\/matchup\/autofill\s+WARN\s+evidence=START\/SIT SOURCE HOLD/, allRoutes:true
  });
  await scenario('strict-missing-matchup-proof', {
    strict:true,
    routes:{'/matchup':good['/matchup'].replace(' data-butler-week-season="2026" data-butler-week-number="5"', '')}
  }, {
    code:2, output:/READINESS GATE: BLOCKED BY WARNINGS/, allRoutes:true
  });
  await scenario('strict-held-startsit', {
    strict:true,
    routes:{'/matchup/autofill':good['/matchup/autofill'].replace('Review only',
      '<div class="callout callout-danger start-sit-blocker"><strong>Blocking evidence:</strong> SOURCE UNVERIFIED</div>')}
  }, {
    code:2, output:/\/matchup\/autofill\s+WARN\s+evidence=START\/SIT BLOCKED/, allRoutes:true
  });
  await scenario('strict-incomplete-roster', {
    strict:true,
    routes:{'/team':good['/team'].replace('<div class="player-row">synthetic player</div>', '')}
  }, {
    code:2, output:/\/team\s+WARN\s+evidence=ROSTER NOT SHOWN/, allRoutes:true
  });
  await scenario('strict-unverified-autopilot', {
    strict:true,
    routes:{'/autopilot':good['/autopilot'].replace('CURRENT SNAPSHOT', 'LINEUP SOURCE NOT VERIFIED')}
  }, {
    code:2, output:/\/autopilot\s+WARN\s+evidence=WATCH INCOMPLETE/, allRoutes:true
  });
  // BF-1093: actual prepared packet must agree with the weekly watch.
  const packet = '<div class="autopilot-prepared-head"><div><div class="eyebrow">PREPARED START/SIT REVIEW</div><h2>Recommendation packet</h2></div><span class="status good">READY FOR MANAGER REVIEW</span></div>';
  for (const [name, page] of [
    ['strict-missing-autopilot-packet', good['/autopilot'].replace(packet, '')],
    ['strict-held-autopilot-packet', good['/autopilot'].replace(
      '<span class="status good">READY FOR MANAGER REVIEW</span>',
      '<span class="status warn">BLOCKED</span>')],
    ['strict-duplicate-autopilot-packet', good['/autopilot'].replace(packet, packet + packet)],
    ['strict-decorative-autopilot-packet', good['/autopilot'].replace('class="autopilot-prepared-head"', 'class="footer-packet"')]
  ]) {
    await scenario(name, {strict:true, routes:{'/autopilot':page}}, {
      code:2, output:/\/autopilot\s+WARN\s+evidence=WATCH INCOMPLETE/, allRoutes:true
    });
  }
  // BF-1084: decorative "CURRENT SNAPSHOT" in a footer or script
  // cannot outvote the explicitly held watch-header status.
  await scenario('strict-decorative-autopilot-current', {
    strict:true,
    routes:{'/autopilot':good['/autopilot'].replace(
      '<span class="status good">CURRENT SNAPSHOT</span>',
      '<span class="status warn">LINEUP ACTION HELD</span><p>CURRENT SNAPSHOT</p>')}
  }, {
    code:2, output:/\/autopilot\s+WARN\s+evidence=WATCH INCOMPLETE/, allRoutes:true
  });
  await scenario('strict-duplicate-autopilot-badge', {
    strict:true,
    routes:{'/autopilot':good['/autopilot'].replace(
      '</section>', '</section>' + good['/autopilot'])}
  }, {
    code:2, output:/\/autopilot\s+WARN\s+evidence=WATCH INCOMPLETE/, allRoutes:true
  });
  await scenario('false-match-badge', {
    routes: {'/matchup': good['/matchup'].replace(' data-butler-week-season="2026" data-butler-week-number="5"', '')}
  }, {
    code:0, output:/\/matchup\s+WARN\s+evidence=PAIRING UNVERIFIED/, allRoutes:true
  });
  await scenario('conflicting-pairing', {
    routes: {'/matchup': good['/matchup'].replace('&middot; Week 5', '&middot; Week 4')}
  }, {
    code:1, output:/\/matchup\s+FAIL\s+evidence=PAIRING CONFLICT/, allRoutes:true
  });
  await scenario('strict-conflicting-pairing', {
    strict:true,
    routes: {'/matchup': good['/matchup'].replace('&middot; Week 5', '&middot; Week 4')}
  }, {
    code:1, output:/READINESS GATE: FAILED/, allRoutes:true
  });
  // BF-1073: exact opponent/week verification must never clear a held
  // lineup review. Health/projection outages are manager WARN, not PASS.
  await scenario('startsit-sourced-player-hold', {
    routes: {'/matchup/autofill': good['/matchup/autofill'].replace(
      'Review only', '<div class="callout callout-danger start-sit-blocker"><strong>Blocking evidence:</strong> BF-1066 BLOCKED</div>')}
  }, {
    code:0, output:/\/matchup\/autofill\s+WARN\s+evidence=START\/SIT BLOCKED/, allRoutes:true
  });
  await scenario('startsit-projection-unavailable', {
    routes: {'/matchup/autofill': good['/matchup/autofill'].replace(
      'Review only', '<p class="lede">Butler could not prove a complete weekly lineup recommendation.</p>')}
  }, {
    code:0, output:/\/matchup\/autofill\s+WARN\s+evidence=START\/SIT BLOCKED/, allRoutes:true
  });
  // BF-1072: two independently source-MATCH pages can still refer to
  // different saved leagues or weeks. The loopback diagnostic must detect
  // the inconsistency without contacting Sleeper or issuing any writes.
  await scenario('startsit-different-week', {
    routes: {'/matchup/autofill': good['/matchup/autofill']
      .replace('data-butler-week-number="5"', 'data-butler-week-number="6"')
      .replace('&middot; Week 5', '&middot; Week 6')}
  }, {
    code:1, output:/Cross-route week\s+FAIL\s+evidence=CROSS-ROUTE CONFLICT/, allRoutes:true
  });
  await scenario('startsit-different-league', {
    routes: {'/matchup/autofill': good['/matchup/autofill']
      .replace('League &middot; Week 5', 'Another league &middot; Week 5')}
  }, {
    code:1, output:/Cross-route week\s+FAIL\s+evidence=CROSS-ROUTE CONFLICT/, allRoutes:true
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
  // BF-1098: a healthy-looking older v0.4 instance is not the
  // current sourced-onopen development build. Refuse even with HTTP 200.
  await scenario('old-v04-readiness-rejected', {omitReadiness:true}, {
    code:1, output:/BF-1048 BLOCKED/, allRoutes:false
  });
  await scenario('wrong-v04-readiness-rejected', {readiness:'unverified-old-v04'}, {
    code:1, output:/BF-1048 BLOCKED/, allRoutes:false
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
