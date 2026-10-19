const assert = require('node:assert/strict');
const http = require('node:http');
const path = require('node:path');
const { spawn } = require('node:child_process');

async function scenario(failPath, expected, disabled = false) {
  const requests = [];
  const server = http.createServer((req, res) => {
    requests.push(req.url);
    assert.equal(req.method, 'GET');
    res.writeHead(req.url === failPath ? 503 : 200, { 'Content-Type': 'text/html' });
    res.end('synthetic warmup fixture');
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const source = path.join(__dirname, 'butler-app-shell-core.ps1').replace(/'/g, "''");
  const code = `
    $ErrorActionPreference = 'Stop'
    $tokens=$null; $errors=$null
    $ast=[Management.Automation.Language.Parser]::ParseFile('${source}',[ref]$tokens,[ref]$errors)
    if (@($errors).Count) { throw 'Parser failed' }
    $functions=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq 'Invoke-PreservedCoreWarmup'},$true))
    if ($functions.Count -ne 1) { throw 'Warmup function missing/duplicate' }
    . ([scriptblock]::Create($functions[0].Extent.Text))
    Invoke-PreservedCoreWarmup -BackendPort ${server.address().port}
    Write-Output 'WARMUP RETURNED'
  `;
  try {
    const output = await new Promise((resolve, reject) => {
      const child = spawn('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', code], {
        windowsHide: true,
        env: { ...process.env, BUTLER_APP_CORE_POOL_WARMUP: disabled ? '0' : '1' },
      });
      let text = '';
      child.stdout.on('data', b => { text += b; });
      child.stderr.on('data', b => { text += b; });
      const timer = setTimeout(() => { child.kill(); reject(new Error('Warmup fixture deadline')); }, 20000);
      child.on('error', error => { clearTimeout(timer); reject(error); });
      child.on('close', code => { clearTimeout(timer); code === 0 ? resolve(text) : reject(new Error(text)); });
    });
    assert.match(output, /WARMUP RETURNED/, 'optional failure must return without blocking startup');
    assert.deepEqual(requests, expected);
  } finally {
    await new Promise(resolve => server.close(resolve));
  }
}

(async () => {
  await scenario(null, ['/waivers', '/team', '/']);
  await scenario('/waivers', ['/waivers']);
  await scenario('/team', ['/waivers', '/team']);
  await scenario('/', ['/waivers', '/team', '/']);
  await scenario(null, [], true);
  console.log('Startup warmup HTTP fixtures PASS: success, each failed stage, disabled.');
})().catch(error => { console.error(error); process.exitCode = 1; });
