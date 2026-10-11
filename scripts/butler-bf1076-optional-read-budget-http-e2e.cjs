'use strict';
// BF-1076: black-box transport test of the optional Auto-Pilot Start/Sit
// deadline. Only localhost GETs and synthetic delayed responses; no Butler
// runtime, Sleeper connection, accounts, databases or mutations.
const assert = require('node:assert/strict');
const http = require('node:http');
const cp = require('node:child_process');
const path = require('node:path');

(async () => {
  const seen = [];
  const server = http.createServer((req, res) => {
    seen.push({ method:req.method, path:req.url });
    if (req.method !== 'GET') {
      res.writeHead(405);res.end('GET only');return;
    }
    if (req.url === '/probe') {
      res.writeHead(200, {'Content-Type':'text/plain; charset=utf-8'});
      res.end('BF-1076 LOCAL PROBE');
    } else if (req.url === '/delay') {
      setTimeout(() => {
        if (!res.destroyed) {
          res.writeHead(200, {'Content-Type':'text/plain; charset=utf-8'});
          res.end('TOO LATE');
        }
      }, 2700);
    } else {
      res.writeHead(404);res.end('Not found');
    }
  });
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  const port = server.address().port;
  assert.ok(Number.isInteger(port) && port > 0);
  const sourceFile = path.join(__dirname, 'butler-app-request-worker.ps1')
    .replace(/'/g, "''");
  const code = [
    "$ErrorActionPreference = 'Stop'",
    "$tokens = $null; $errors = $null",
    "$ast = [System.Management.Automation.Language.Parser]::ParseFile('" + sourceFile + "',[ref]$tokens,[ref]$errors)",
    "if (@($errors).Count -ne 0) { throw 'BF-1076 worker parse error' }",
    "$node = $ast.Find({ param($x) $x -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $x.Name -ceq 'Invoke-AppCoreGet' }, $true)",
    "if ($null -eq $node) { throw 'BF-1076 core reader missing' }",
    ". ([scriptblock]::Create($node.Extent.Text))",
    "$ok = Invoke-AppCoreGet -Port " + port + " -RequestTarget '/probe' -TimeoutMs 1000",
    "if ([int]$ok.StatusCode -ne 200 -or $ok.Body -cne 'BF-1076 LOCAL PROBE') { throw 'BF-1076 GET regression' }",
    "Write-Host 'BF-1076 HEALTHY GET PASS'",
    "$timer = [System.Diagnostics.Stopwatch]::StartNew()",
    "$blocked = $false",
    "try { $null = Invoke-AppCoreGet -Port " + port + " -RequestTarget '/delay' -TimeoutMs 1000 }",
    "catch [System.Net.WebException] { $blocked = $true }",
    "$timer.Stop()",
    "if (-not $blocked -or $timer.Elapsed.TotalSeconds -gt 6.0) { throw 'BF-1076 missing deadline or unexpectedly long GET' }",
    "Write-Host 'BF-1076 BOUNDED GET TIMEOUT PASS'",
    "Write-Host 'BF-1076 NO SLEEPER WRITE PASS'"
  ].join('\n');
  const encoded = Buffer.from(code,'utf16le').toString('base64');
  try {
    const child = cp.spawn('powershell.exe', [
      '-NoLogo','-NoProfile','-NonInteractive','-EncodedCommand',encoded
    ], { windowsHide:true, stdio:['ignore','pipe','pipe'] });
    let stdout = '', stderr = '';
    child.stdout.on('data', b => { stdout += b.toString('utf8'); });
    child.stderr.on('data', b => { stderr += b.toString('utf8'); });
    const result = await new Promise((resolve,reject) => {
      const watchdog = setTimeout(() => {
        child.kill();
        reject(new Error('BF-1076 process exceeded finite 12-second test deadline'));
      }, 12000);
      child.once('error',error => {
        clearTimeout(watchdog);
        reject(error);
      });
      child.once('close',(code,signal) => {
        clearTimeout(watchdog);
        resolve({code,signal});
      });
    });
    assert.equal(result.code,0,'BF-1076 Windows PowerShell failed\n'+stdout+'\n'+stderr);
    assert.match(stdout,/BF-1076 HEALTHY GET PASS/);
    assert.match(stdout,/BF-1076 BOUNDED GET TIMEOUT PASS/);
    assert.deepEqual(seen,[
      {method:'GET',path:'/probe'},{method:'GET',path:'/delay'}
    ],'No provider or write requests are permitted');
    process.stdout.write('BF-1076 OPTIONAL START/SIT READ TIME BUDGET: PASS\n');
  } finally {
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
  }
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
