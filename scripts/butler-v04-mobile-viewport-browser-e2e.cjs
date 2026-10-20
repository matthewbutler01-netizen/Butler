'use strict';

// BF-1536: real Chrome/Edge geometry of the production-source evidence CSS
// inside independent 304px srcdoc frames. This is a fixture, NOT a Butler
// manager-page or owner/live-Sleeper acceptance test. No provider access,
// database, profile or transaction; temp HTML and browser process only.
const assert = require('node:assert/strict');
const cp = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {pathToFileURL} = require('node:url');

if (process.platform !== 'win32') {
  throw new Error('BF-1536 requires a Windows CI Chrome/Edge browser');
}

const waiverSource = fs.readFileSync(path.join(__dirname,
  'butler-dashboard-bf834-waiver-decision-surface-transform.ps1'), 'utf8');
const lineupSource = fs.readFileSync(path.join(__dirname,
  'butler-app-bf1021-v04-start-sit-assistant-transform.ps1'), 'utf8');

function cssRule(source, selector) {
  const escaped = selector.replace(/[.*+?^\x24{}()|[\]\\]/g, '\\$&');
  const matches = [...source.matchAll(new RegExp(escaped + '\\s*\\{([^}]*)\\}', 'g'))];
  assert.equal(matches.length, 1, 'Expected one production CSS rule: ' + selector);
  return selector + '{' + matches[0][1] + '}';
}
const rules = {
  waiver: cssRule(waiverSource, '.waiver-pair-note') +
    cssRule(waiverSource, '.waiver-pair-note pre'),
  lineup: cssRule(lineupSource, '.start-sit-assistant details') +
    cssRule(lineupSource, '.start-sit-assistant .review-queue li')
};
const token = 'unbroken_saved_source_evidence_'.repeat(45);
const cases = [
  {name:'waiver-fixed',type:'waiver',broken:false},
  {name:'waiver-negative',type:'waiver',broken:true},
  {name:'lineup-fixed',type:'lineup',broken:false},
  {name:'lineup-negative',type:'lineup',broken:true}
];
function srcdoc(test) {
  let style = rules[test.type];
  if (test.broken && test.type === 'waiver') {
    style = style.replace(/(\.waiver-pair-note\{[^}]*?)overflow-wrap:anywhere(?=\})/, '$1');
  }
  if (test.broken && test.type === 'lineup') {
    style = style.replace(/\.start-sit-assistant details\{overflow-wrap:anywhere\}/, '');
  }
  assert.notEqual(style, rules[test.type], 'Negative fixture must remove a real wrap rule');
  const body = test.type === 'waiver' ?
    '<article class="panel waiver-pair-note"><p>' + token +
      '</p><details open><summary>Recorded source and scoring coverage</summary><pre>' +
      token + '</pre></details></article>' :
    '<article class="panel start-sit-assistant"><details open>' +
      '<summary>View evidence details</summary><p>' + token +
      '</p></details><ul class="review-queue"><li>' + token + '</li></ul></article>';
  return '<!doctype html><html><head>' +
    '<meta name="viewport" content="width=device-width,initial-scale=1">' +
    '<style>*{box-sizing:border-box}html,body{margin:0;padding:0;width:304px}' +
    'body{overflow-x:visible;font:14px sans-serif}.panel{padding:14px;' +
    'border:1px solid #333;width:100%;max-width:100%}' + style +
    '</style></head><body>' + body + '</body></html>';
}
function attr(value) {
  return value.replace(/&/g, '&amp;').replace(/"/g, '&quot;')
    .replace(/</g, '&lt;').replace(/>/g, '&gt;');
}
const iframes = cases.map(test =>
  '<iframe id="' + test.name + '" title="' + test.name + '" ' +
  'style="width:304px;height:350px;border:0" srcdoc="' +
  attr(srcdoc(test)) + '"></iframe>').join('\n');
const observer = [
  'function measure(){',
  'const names=' + JSON.stringify(cases.map(test=>test.name)) + ';',
  'const readings=names.map(function(name){',
  'const frame=document.getElementById(name);',
  'const doc=frame.contentDocument;',
  'if(!doc||doc.readyState!=="complete") return null;',
  'return {name:name,width:doc.documentElement.clientWidth,',
  'scroll:Math.max(doc.documentElement.scrollWidth,doc.body.scrollWidth)};',
  '});',
  'if(readings.some(function(x){return !x;})){setTimeout(measure,80);return;}',
  'const out=document.getElementById("measurements");',
  'out.textContent=JSON.stringify(readings);out.setAttribute("data-done","yes");',
  '}',
  'window.addEventListener("load",function(){setTimeout(measure,80);});'
].join('');
const outer = '<!doctype html><html><head><meta charset="utf-8"></head><body>' +
  iframes + '<pre id="measurements" data-done="no"></pre>' +
  '<script>' + observer + '</script></body></html>';

function findBrowser() {
  for (const root of [
    process.env.PROGRAMFILES,
    process.env['ProgramFiles(x86)'],
    process.env.LOCALAPPDATA
  ].filter(Boolean)) {
    for (const segments of [
      ['Google','Chrome','Application','chrome.exe'],
      ['Microsoft','Edge','Application','msedge.exe']
    ]) {
      const candidate = path.join(root, ...segments);
      if (fs.existsSync(candidate)) return candidate;
    }
  }
  throw new Error('BF-1536 BLOCKED: Windows Chrome or Edge unavailable');
}

function render(file) {
  return new Promise((resolve,reject)=>{
    const args = ['--headless=new', '--disable-gpu', '--no-first-run',
      '--no-default-browser-check','--disable-extensions',
      '--disable-background-networking','--no-proxy-server','--no-sandbox',
      '--disable-features=MediaRouter,OptimizationHints',
      '--window-size=1100,900','--virtual-time-budget=8000',
      '--dump-dom',pathToFileURL(file).href];
    const proc=cp.spawn(findBrowser(),args,{windowsHide:true,stdio:['ignore','pipe','pipe']});
    let stdout='',stderr='',finished=false;
    const done=(error,html)=>{
      if(finished)return;
      finished=true;clearTimeout(timer);
      if(error)reject(error);else resolve(html);
    };
    const timer=setTimeout(()=>{
      proc.kill();done(new Error('BF-1536 browser timed out before geometry proof'));
    },30000);
    proc.stdout.on('data',data=>{stdout+=data.toString('utf8');});
    proc.stderr.on('data',data=>{stderr+=data.toString('utf8');});
    proc.once('error',error=>done(error));
    proc.once('close',(code)=>{
      if(code!==0)done(new Error('BF-1536 browser exit '+code+': '+stderr.slice(-900)));
      else done(null,stdout);
    });
  });
}
(async()=>{
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'butler-bf1536-'));
  try {
    const htmlPath=path.join(dir,'narrow-evidence.html');
    fs.writeFileSync(htmlPath,outer,'utf8');
    const dom=await render(htmlPath);
    const found=dom.match(/<pre id="measurements" data-done="yes">([^<]+)<\/pre>/);
    assert.ok(found,'Browser did not execute geometry measurement');
    const rows=JSON.parse(found[1]);
    assert.equal(rows.length,4);
    for(const row of rows) {
      assert.equal(row.width,304,row.name+' frame is not 304 CSS pixels');
      assert.ok(Number.isFinite(row.scroll),row.name+' missing document width');
      if(row.name.endsWith('-fixed')) {
        assert.ok(row.scroll<=row.width,
          row.name+' still overflows: '+row.scroll+' > '+row.width);
      } else {
        assert.ok(row.scroll>row.width+20,
          row.name+' negative control did not reproduce overflow');
      }
      console.log('BF-1536 '+row.name+': '+row.width+'/'+row.scroll+'px');
    }
    console.log('BF-1536 REAL 304PX CHROMIUM EVIDENCE FIXTURE: PASS');
  } finally {
    fs.rmSync(dir,{recursive:true,force:true,maxRetries:3,retryDelay:200});
  }
})().catch(error=>{console.error(error);process.exitCode=1;});
