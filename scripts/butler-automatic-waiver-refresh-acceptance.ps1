Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'butler-decision-refresh.ps1')
$token = 'a' * 64
$html = '<html><body><span hidden data-butler-auto-waiver="11111111-1111-1111-1111-111111111111"></span></body></html>'
$result = Add-AutomaticWaiverRefresh -Html $html -RequestTarget '/waivers' -Token $token
if ($result.Nonce -cnotmatch '^[0-9a-f]{64}$' -or $result.Html -notmatch "method: 'POST'" -or $result.Html -notmatch 'sessionStorage.setItem' -or $result.Html -notmatch 'Date.now\(\)' -or $result.Html -notmatch 'now - previous < 300000' -or $result.Html -notmatch "window.location.replace\('/waivers'\)") { throw 'Eligible automatic update missing its protected request, cooldown or retry bound.' }
foreach ($route in @('/', '/waivers?position=RB', '/history')) {
    $result = Add-AutomaticWaiverRefresh -Html $html -RequestTarget $route -Token $token
    if ($result.Nonce -ne '' -or $result.Html -ne $html) { throw 'Unexpected automatic update route.' }
}
foreach ($body in @('<html><body>No update</body></html>', ($html + $html), ($html.Replace('<body>', '<BODY>')), ($html.Replace('</body>', '</BODY>')))) {
    $result = Add-AutomaticWaiverRefresh -Html $body -RequestTarget '/waivers' -Token $token
    if ($result.Nonce -ne '') { throw 'Missing or ambiguous update metadata accepted.' }
}
foreach ($name in @('butler-app-request-worker.ps1', 'butler-decision-refresh.ps1', 'butler-dashboard-bf834-waiver-decision-surface-transform.ps1')) {
    $tokens = $null; $errors = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $name), [ref]$tokens, [ref]$errors)
    if ($errors.Count -gt 0) { throw ($errors | Out-String) }
}
Write-Host 'AUTOMATIC WAIVER REFRESH ACCEPTANCE: PASS'
$tokens = $null; $errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'butler-app-request-worker.ps1'), [ref]$tokens, [ref]$errors)
$function = $ast.Find({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Consume-RefreshToken' }, $true)
. ([scriptblock]::Create($function.Extent.Text))
$state = [hashtable]::Synchronized(@{ Token = $token })
Consume-RefreshToken -State $state -SubmittedToken $token
if (-not $state.InProgress -or $state.Token -ceq $token) { throw 'Refresh claim was not atomic.' }
$rejected = $false
try { Consume-RefreshToken -State $state -SubmittedToken $state.Token } catch { $rejected = $true }
if (-not $rejected) { throw 'Overlapping refresh accepted.' }
Write-Host 'AUTOMATIC WAIVER REFRESH CONCURRENCY: PASS'
