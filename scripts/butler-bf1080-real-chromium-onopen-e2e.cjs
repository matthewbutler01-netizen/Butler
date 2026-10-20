'use strict';

// BF-1080: ACTUAL Windows headless Chrome/Edge runs the real PowerShell
// generated nonce-protected on-open script against a tiny loopback fixture.
// Unlike BF-1043's VM, this exercises the browser's CSP, fetch, navigation,
// DOM and local HTTP transport. No league/account/provider, no app process,
// no real token and absolutely no Sleeper transaction.
const assert = require('node:assert/strict');
const cp = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const http = require('node:http');
const path = require('node:path');

if (process.platform !== 'win32') {
  throw new Error('BF-1080 Windows Chrome/Edge headless integration requires Windows');
}

const psFixture = path.join(__dirname, 'butler-auto-refresh-browser-fixtures.ps1');
const fixture = cp.spawnSync('powershell.exe', [
  '-NoLogo','-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass',
  '-File',psFixture
], {encoding:'utf8',timeout:30000,windowsHide:true});
assert.equal(fixture.status,0,'BF-1080 exact PowerShell renderer failed: ' +
  (fixture.stderr || fixture.stdout || '').slice(-700));
const data = JSON.parse((fixture.stdout || '').trim());
const rollover = data.rolloverPages.find(x =>
  x.Actionability === 'AUDITED_WEEK_ROLLOVER_DO_NOT_ACT' &&
  x.Lineage === 'LATEST_EVIDENCE_LINEAGE_VERIFIED');
assert.ok(rollover, 'missing exact adjacent-week browser fixture');
data.rolloverDashboard = rollover.Dashboard;
data.rolloverAutopilot = rollover.AutoPilot;
data.rolloverStartSit = rollover.StartSit;
data.manualConfirmation = {Html:data.confirmation, Nonce:''};

function locateBrowser() {
  const roots = [
    process.env.PROGRAMFILES,
    process.env['ProgramFiles(x86)'],
    process.env.LOCALAPPDATA
  ].filter(Boolean);
  for (const root of roots) {
    for (const entry of [
      ['Google','Chrome','Application','chrome.exe'],
      ['Microsoft','Edge','Application','msedge.exe']
    ]) {
      const found = path.join(root,...entry);
      if (fs.existsSync(found)) return found;
    }
  }
  throw new Error('BF-1080 BLOCKED: CI Windows image has no Chrome or Edge for real-browser verification');
}
const browser = locateBrowser();

function pageFor(config, refreshed) {
  if (refreshed && !config.remainStale) {
    return {html:'<!doctype html><html><body><main id="bf1080-refreshed">Fresh after local post</main></body></html>',nonce:''};
  }
  const page = data[config.page];
  assert.ok(page && typeof page.Html === 'string');
  return {html:page.Html,nonce:page.Nonce};
}

function runHeadless(url, profile, awaitAsyncLocks=false) {
  return new Promise((resolve,reject) => {
    const args = [
      '--headless=new', '--disable-gpu', '--no-first-run',
      '--no-default-browser-check','--disable-extensions',
      '--disable-background-networking','--no-proxy-server',
      '--no-sandbox','--disable-features=MediaRouter,OptimizationHints',
      '--user-data-dir=' + profile,
      // BF-1539: Web Locks callback is asynchronous in Chrome's browser
      // process. Virtual time can dump the initial DOM before acquisition.
      // Use real wall-clock wait for actual Web Locks HTTP scenarios.
      awaitAsyncLocks ? '--timeout=4500' : '--virtual-time-budget=10000',
      '--dump-dom', url
    ];
    const proc = cp.spawn(browser,args,{
      windowsHide:true, stdio:['ignore','pipe','pipe']
    });
    let out='',err='',done=false;
    const finish = (error, value) => {
      if (done) return;
      done=true;
      clearTimeout(watchdog);
      if (error) reject(error); else resolve(value);
    };
    const watchdog = setTimeout(() => {
      proc.kill();
      finish(new Error('BF-1080 headless browser timed out after 30s'));
    },30000);
    proc.stdout.on('data',chunk => {out+=chunk.toString('utf8');});
    proc.stderr.on('data',chunk => {err+=chunk.toString('utf8');});
    proc.once('error',e => finish(e));
    proc.once('close',(code,signal) => {
      if (code !== 0) {
        return finish(new Error('BF-1080 Chrome/Edge failed (' + code +
          ', signal=' + signal + '): ' + err.slice(-1100)));
      }
      finish(null,out);
    });
  });
}

async function scenario(name,config) {
  const observed=[];
  let refreshed=false;
  const server=http.createServer((req,res)=>{
    // Browser favicon and other incidental safe reads are permitted but
    // ignored. POST is allowed ONLY to the synthetic /refresh endpoint.
    if (req.url === '/favicon.ico' && req.method === 'GET') {
      res.writeHead(404);res.end();return;
    }
    observed.push({method:req.method,path:req.url});
    // Hold only the *synthetic document-load event* for asynchronous
    // browser Web Locks. Otherwise --dump-dom can snapshot the initial
    // markup before the lock callback runs at all. Never contact a
    // provider and never alter the application's source CSP.
    if (req.url === '/fixture-load-delay' && req.method === 'GET') {
      setTimeout(()=>{
        res.writeHead(200,{'Content-Type':'image/gif','Cache-Control':'no-store'});
        res.end(Buffer.from('R0lGODlhAQABAAD/ACwAAAAAAQABAAACADs=','base64'));
      },1500);
      return;
    }
    if (req.url === '/refresh') {
      if (req.method !== 'POST') {res.writeHead(405);res.end();return;}
      let body='';
      req.on('data',part=>{body+=part.toString();if (body.length>512) req.destroy();});
      req.on('end',()=>{
        assert.equal(req.headers['content-type'],'application/x-www-form-urlencoded');
        assert.equal(body,'token='+data.token,'Only the fixture token may be POSTed');
        if (config.reject) {
          res.writeHead(409,{'Cache-Control':'no-store'});
          res.end('Synthetic policy rejection');
        } else {
          refreshed=true;
          res.writeHead(200,{'Cache-Control':'no-store','Content-Type':'text/plain'});
          res.end('Local fixture success');
        }
      });
      return;
    }
    if (req.method !== 'GET' ||
        (req.url !== config.route && req.url !== config.nextRoute)) {
      res.writeHead(405);res.end('GET only');return;
    }
    // BF-1082: after the Dashboard redirect, follow a local-only
    // server navigation to Auto-Pilot in the SAME Chrome tab. Both
    // on-open scripts share the exact audit cooldown key.
    if (config.nextRoute && refreshed && req.url === config.route) {
      res.writeHead(302,{'Location':config.nextRoute,'Cache-Control':'no-store'});
      res.end();return;
    }
    const nextPage=data[config.nextPage || 'autopilot'];
    const page=(config.nextRoute && req.url === config.nextRoute) ?
      {html:nextPage.Html,nonce:nextPage.Nonce} :
      pageFor(config,refreshed);
    const nonce=(config.badCsp ? '0'.repeat(64) : page.nonce);
    const policy="default-src 'none'; script-src 'nonce-"+nonce+
      "'; connect-src 'self'; style-src 'unsafe-inline'; img-src 'self'; frame-ancestors 'none'";
    res.writeHead(200, {
      'Content-Type':'text/html; charset=utf-8',
      'Cache-Control':'no-store',
      'Content-Security-Policy':policy
    });
    const delayed=Boolean(config.post && page.nonce);
    res.end(delayed
      ? page.html.replace('</body>',
          '<img src="/fixture-load-delay" alt="" aria-hidden="true"></body>')
      : page.html);
  });
  await new Promise((resolve,reject)=>{
    server.once('error',reject);server.listen(0,'127.0.0.1',resolve);
  });
  const profile=fs.mkdtempSync(path.join(os.tmpdir(),'butler-bf1080-'));
  try {
    const url='http://127.0.0.1:'+server.address().port+config.route;
    const dom=await runHeadless(url,profile,Boolean(config.post));
    const posts=observed.filter(x=>x.method==='POST');
    const paths=observed.filter(x=>x.method==='GET').map(x=>x.path);
    assert.ok(paths.includes(config.route),name+' never loaded target page');
    if (config.post) {
      const shownStatus = /id="butler-auto-refresh-status"[^>]*>([^<]*)</.exec(dom)?.[1] || 'NO STATUS NODE';
      assert.equal(posts.length,1,name+' did not submit exactly one local POST (rendered status: '+shownStatus+'; requests: '+JSON.stringify(observed)+')');
      assert.equal(posts[0].path,'/refresh',name+' wrote to wrong endpoint');
    } else {
      assert.equal(posts.length,0,name+' unexpectedly submitted a POST');
    }
    if (config.complete) {
      assert.ok(refreshed,name+' never produced refreshed local evidence');
      assert.match(dom,/id="bf1080-refreshed"/,name+' browser did not render fresh page after redirect');
      assert.ok(paths.filter(p=>p===config.route).length >= 2,
        name+' browser did not navigate back after local POST');
    }
    if (config.remainStale) {
      assert.ok(refreshed,name+' did not perform its initial refresh');
      assert.equal(posts.length,1,name+' caused a repeated refresh after redirect');
      assert.match(dom,/Butler checked this decision recently/,
        name+' failed real-browser sessionStorage cooldown after navigation');
      assert.ok(paths.filter(p=>p===config.route).length>=2,
        name+' never revisited stale route after successful refresh');
    }
    if (config.nextRoute) {
      assert.ok(paths.includes(config.nextRoute),
        name+' never navigated into the second manager page');
      assert.ok(dom.includes(config.nextRouteText || 'CURRENT WEEKLY WATCH'),
        name+' did not show the expected next manager route');
      assert.equal(posts.length,1,
        name+' made an extra automatic local refresh on the same audit');
    }
    if (config.reject) {
      assert.equal(refreshed,false,name+' policy rejection mutated fixture evidence');
      assert.match(dom,/Automatic update stopped/,name+' did not display failed-policy status');
    }
    if (config.badCsp) {
      assert.equal(refreshed,false,name+' mismatched CSP nonce ran refresh');
      assert.match(dom,/Checking whether Butler/,name+' browser unexpectedly executed blocked script');
    }
    if (config.blocked) {
      assert.match(dom,/CURRENT WEEKLY WATCH/,name+' blocked Auto-Pilot did not render');
      assert.equal(refreshed,false,name+' blocked watch changed evidence');
    }
    if (config.expectedText) {
      assert.ok(dom.includes(config.expectedText),name+' lost its held/confirmation content');
      assert.equal(refreshed,false,name+' changed evidence without confirmation');
    }
    if (config.manualConfirmation) {
      assert.match(dom,/<form method="post" action="\/refresh">/);
      assert.match(dom,/<input type="hidden" name="token" value="a{64}">/);
      assert.match(dom,/>Confirm refresh<\/button>/);
      assert.match(dom,/>Cancel<\/a>/);
    }
    for (const req of observed) {
      assert.ok(req.method==='GET' || (req.method==='POST' && req.path==='/refresh'),
        name+' sent unauthorized HTTP request');
    }
    console.log('BF-1080 REAL HEADLESS '+name.toUpperCase()+': PASS');
  } finally {
    server.closeAllConnections();
    await new Promise(resolve=>server.close(resolve));
    try {fs.rmSync(profile,{recursive:true,force:true,maxRetries:3,retryDelay:200});} catch {}
  }
}


/**
 * BF-1539: genuine Chromium lock and shared-storage geometry. Two same-origin
 * iframe browsing contexts launch independently. This checks actual browser
 * Web Locks, DOM scripting and HTTP concurrency, not independent top-level
 * tab sessionStorage isolation (separately exercised in the BF-1043 VM).
 * CSP in this synthetic fixture permits self-framing, unlike real Butler.
 */
async function sameOriginConcurrentFrames() {
  const observed=[];
  const server=http.createServer((req,res)=>{
    if (req.url==='/favicon.ico' && req.method==='GET') {
      res.writeHead(404);res.end();return;
    }
    observed.push({method:req.method,path:req.url});
    if (req.url==='/fixture-load-delay' && req.method==='GET') {
      setTimeout(()=>{
        res.writeHead(200,{'Content-Type':'image/gif','Cache-Control':'no-store'});
        res.end(Buffer.from('R0lGODlhAQABAAD/ACwAAAAAAQABAAACADs=','base64'));
      },1500);
      return;
    }
    if(req.url==='/two-contexts' && req.method==='GET'){
      res.writeHead(200,{'Content-Type':'text/html; charset=utf-8',
        'Cache-Control':'no-store',
        'Content-Security-Policy':"default-src 'none'; frame-src 'self'"});
      res.end('<!doctype html><html><body><iframe src="/"></iframe>'+
        '<iframe src="/waivers"></iframe></body></html>');
      return;
    }
    if(req.url==='/refresh' && req.method==='POST'){
      let body='';
      req.on('data',chunk=>{body+=String(chunk);if(body.length>256)req.destroy();});
      req.on('end',()=>{
        assert.equal(body,'token='+data.token,'unexpected synthetic refresh payload');
        assert.equal(req.headers['content-type'],'application/x-www-form-urlencoded');
        // Keep the first POST in flight long enough to test overlapping
        // same-origin contexts. A second refresh must not be sent.
        setTimeout(()=>{
          res.writeHead(200,{'Cache-Control':'no-store','Content-Type':'text/plain'});
          res.end('fixture refreshed');
        },350);
      });
      return;
    }
    if(req.method==='GET' && (req.url==='/' || req.url==='/waivers')){
      const page=req.url==='/' ? data.dashboard : data.waivers;
      res.writeHead(200,{'Content-Type':'text/html; charset=utf-8',
        'Cache-Control':'no-store',
        'Content-Security-Policy':"default-src 'none'; script-src 'nonce-"+
          page.Nonce+"'; connect-src 'self'; style-src 'unsafe-inline'; img-src 'self'; frame-ancestors 'self'"});
      res.end(page.Html.replace('</body>', '<img src="/fixture-load-delay" alt="" aria-hidden="true"></body>'));
      return;
    }
    res.writeHead(405);res.end('unapproved synthetic route');
  });
  await new Promise((resolve,reject)=>{
    server.once('error',reject);server.listen(0,'127.0.0.1',resolve);
  });
  const profile=fs.mkdtempSync(path.join(os.tmpdir(),'butler-bf1539-'));
  try {
    const url='http://127.0.0.1:'+server.address().port+'/two-contexts';
    const dom=await runHeadless(url,profile,true);
    assert.match(dom,/<iframe src="\/"><\/iframe>/);
    assert.match(dom,/<iframe src="\/waivers"><\/iframe>/);
    const pages=observed.filter(r=>r.method==='GET'&&['/','/waivers'].includes(r.path));
    const posts=observed.filter(r=>r.method==='POST');
    assert.ok(pages.some(r=>r.path==='/'),'Dashboard frame did not load');
    assert.ok(pages.some(r=>r.path==='/waivers'),'Waiver frame did not load');
    assert.equal(posts.length,1,'two concurrent browser contexts duplicated /refresh');
    assert.equal(posts[0].path,'/refresh');
    assert.ok(observed.every(r=>r.method==='GET' ||
      (r.method==='POST' && r.path==='/refresh')),
      'fixture made an unauthorized request');
    console.log('BF-1539 REAL CHROME TWO-CONTEXT AUDIT LOCK: PASS (one same-origin POST)');
  } finally {
    server.closeAllConnections();
    await new Promise(resolve=>server.close(resolve));
    try{fs.rmSync(profile,{recursive:true,force:true,maxRetries:3,retryDelay:200});}catch{}
  }
}

(async()=>{
  // A real adjacent-week page must stay passive on all sourced manager
  // surfaces. Opening the actual confirmation form is also GET-only;
  // these scenarios never click Submit or replay the old player pair.
  for (const [page,route,expectedText] of [
    ['rolloverDashboard','/','AUDITED_WEEK_ROLLOVER_DO_NOT_ACT'],
    ['rolloverAutopilot','/autopilot','CURRENT WEEKLY WATCH'],
    ['rolloverStartSit','/matchup/autofill','Source unavailable'],
    ['manualConfirmation','/confirmation', 'CONFIRMATION REQUIRED']
  ]) {
    await scenario(page,{page,route,post:false,expectedText,
      manualConfirmation:page==='manualConfirmation'});
  }
  // Real clickless same-origin browser POST and real redirected fresh DOM.
  await scenario('dashboard-clickless-refresh',{
    page:'dashboard',route:'/',post:true,complete:true
  });
  await scenario('waiver-clickless-refresh',{
    page:'waivers',route:'/waivers',post:true,complete:true
  });
  // BF-1081: an update may finish without immediately clearing the
  // upstream stale audit. The browser must not POST in a reload loop.
  await scenario('dashboard-session-cooldown',{
    page:'dashboard',route:'/',post:true,remainStale:true
  });
  await scenario('autopilot-clickless-refresh',{
    page:'autopilot',route:'/autopilot',post:true,complete:true
  });
  // BF-1104: execute real CSP nonce and local token-gated POST from a
  // uniquely source-blocked Start/Sit page. Healthy Start/Sit never arms.
  await scenario('startsit-clickless-refresh',{
    page:'startsit',route:'/matchup/autofill',post:true,complete:true
  });
  await scenario('startsit-not-blocked',{
    page:'blockedStartSit',route:'/matchup/autofill',post:false
  });
  await scenario('startsit-session-cooldown',{
    page:'startsit',route:'/matchup/autofill',post:true,remainStale:true
  });
  // BF-1082: in one real Chrome tab, Dashboard and Auto-Pilot must
  // share the same audited 5-minute cooldown after navigating between
  // distinct routes; server-side route changes cannot cause a second POST.
  await scenario('dashboard-to-autopilot-shared-cooldown',{
    page:'dashboard',route:'/',nextRoute:'/autopilot',
    post:true,remainStale:true
  });
  await scenario('startsit-to-autopilot-shared-cooldown',{
    page:'startsit',route:'/matchup/autofill',nextRoute:'/autopilot',
    post:true,remainStale:true
  });
  // BF-1537: Waivers also posts the identical governed /refresh endpoint.
  // Its matching exact audit must share the Dashboard/Auto-Pilot cooldown
  // in either route order, avoiding a second silent local refresh.
  await scenario('dashboard-to-waivers-shared-cooldown',{
    page:'dashboard',route:'/',nextRoute:'/waivers',nextPage:'waivers',
    nextRouteText:'data-butler-auto-waiver',post:true,remainStale:true
  });
  await scenario('waivers-to-dashboard-shared-cooldown',{
    page:'waivers',route:'/waivers',nextRoute:'/',nextPage:'dashboard',
    nextRouteText:'Decision state: STALE_DO_NOT_ACT',
    post:true,remainStale:true
  });
  // A legitimate no-transaction page must never trigger an automatic POST.
  await scenario('autopilot-not-entitled',{
    page:'blockedAutopilot',route:'/autopilot',post:false,blocked:true
  });
  // Enforce nonce in a *real* browser, not just through regex.
  await scenario('csp-nonce-denied',{
    page:'dashboard',route:'/',post:false,badCsp:true
  });
  // A local backend may refuse refresh: no navigation or retry loop.
  await scenario('policy-rejected',{
    page:'dashboard',route:'/',post:true,reject:true
  });
  await sameOriginConcurrentFrames();
  console.log('BF-1080 REAL CHROME/EDGE ON-OPEN TRANSPORT: PASS');
})().catch(e=>{console.error(e);process.exitCode=1;});
