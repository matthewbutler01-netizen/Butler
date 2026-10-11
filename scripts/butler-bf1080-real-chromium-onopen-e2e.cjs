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

function runHeadless(url, profile) {
  return new Promise((resolve,reject) => {
    const args = [
      '--headless=new', '--disable-gpu', '--no-first-run',
      '--no-default-browser-check','--disable-extensions',
      '--disable-background-networking','--no-proxy-server',
      '--no-sandbox','--disable-features=MediaRouter,OptimizationHints',
      '--user-data-dir=' + profile, '--virtual-time-budget=10000',
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
    const page=(config.nextRoute && req.url === config.nextRoute) ?
      {html:data.autopilot.Html,nonce:data.autopilot.Nonce} :
      pageFor(config,refreshed);
    const nonce=(config.badCsp ? '0'.repeat(64) : page.nonce);
    const policy="default-src 'none'; script-src 'nonce-"+nonce+
      "'; connect-src 'self'; style-src 'unsafe-inline'; frame-ancestors 'none'";
    res.writeHead(200, {
      'Content-Type':'text/html; charset=utf-8',
      'Cache-Control':'no-store',
      'Content-Security-Policy':policy
    });
    res.end(page.html);
  });
  await new Promise((resolve,reject)=>{
    server.once('error',reject);server.listen(0,'127.0.0.1',resolve);
  });
  const profile=fs.mkdtempSync(path.join(os.tmpdir(),'butler-bf1080-'));
  try {
    const url='http://127.0.0.1:'+server.address().port+config.route;
    const dom=await runHeadless(url,profile);
    const posts=observed.filter(x=>x.method==='POST');
    const paths=observed.filter(x=>x.method==='GET').map(x=>x.path);
    assert.ok(paths.includes(config.route),name+' never loaded target page');
    if (config.post) {
      assert.equal(posts.length,1,name+' did not submit exactly one local POST');
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
      assert.match(dom,/CURRENT WEEKLY WATCH/,
        name+' did not show Auto-Pilot after Dashboard navigation');
      assert.equal(posts.length,1,
        name+' made an extra automatic refresh on Auto-Pilot from the same audit');
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

(async()=>{
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
  // BF-1082: in one real Chrome tab, Dashboard and Auto-Pilot must
  // share the same audited 5-minute cooldown after navigating between
  // distinct routes; server-side route changes cannot cause a second POST.
  await scenario('dashboard-to-autopilot-shared-cooldown',{
    page:'dashboard',route:'/',nextRoute:'/autopilot',
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
  console.log('BF-1080 REAL CHROME/EDGE ON-OPEN TRANSPORT: PASS');
})().catch(e=>{console.error(e);process.exitCode=1;});
