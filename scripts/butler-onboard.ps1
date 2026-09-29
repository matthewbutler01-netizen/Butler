param(
    [Parameter(Mandatory = $true)][string]$RuntimeZip,
    [ValidateRange(1024, 65535)][int]$Port = 8765,
    [switch]$NoBrowser,
    [switch]$VerifyOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$scriptDir = $PSScriptRoot
$packageRoot = Split-Path -Parent $scriptDir
$shell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$setup = Join-Path $scriptDir 'butler-setup-new-league.ps1'
$check = Join-Path $scriptDir 'butler-setup-check.ps1'
$preflight = Join-Path $scriptDir 'butler-java-preflight.ps1'
$libs = Join-Path $packageRoot 'bet\bet-cli\build\install\bet-cli\lib'
$localData = [string]$env:LOCALAPPDATA
if ([string]::IsNullOrWhiteSpace($localData)) { $localData = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData) }
if ([string]::IsNullOrWhiteSpace($localData)) { throw 'LocalApplicationData is unavailable.' }
$configDir = Join-Path $localData 'Butler'
$selectionPath = Join-Path $configDir 'app-league.txt'
$dataDir = if ([string]::IsNullOrWhiteSpace([string]$env:BUTLER_APP_DATA_DIR)) { Join-Path $configDir 'data' } else { [string]$env:BUTLER_APP_DATA_DIR }
$databasePath = Join-Path $dataDir 'butler.db'
if ((Test-Path -LiteralPath $selectionPath) -or (Test-Path -LiteralPath $databasePath)) {
    throw 'A Butler profile already exists. This connection screen is for a fresh profile only.'
}
foreach ($path in @($RuntimeZip, ($RuntimeZip + '.sha256'), $shell, $setup, $check, $preflight, $libs)) {
    if (-not (Test-Path -LiteralPath $path)) { throw "Required setup component missing: $path" }
}
if ($RuntimeZip.Contains('"')) { throw 'RuntimeZip path contains an unsupported quote.' }
& $shell -NoLogo -NoProfile -ExecutionPolicy Bypass -File $check -RuntimeZip $RuntimeZip -RuntimeOnly
if ($LASTEXITCODE -ne 0) { throw 'Runtime integrity or prerequisite check failed.' }
$javaInfo = & $preflight -PassThru
$java = [string]$javaInfo.Executable
$classPath = Join-Path $libs '*'
$token = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
$selectedUsername = $null
$leagueOptions = @()
$setupProcess = $null
$runDir = $null
$stdoutPath = $null
$stderrPath = $null

function Escape-Html([AllowNull()][string]$Value) {
    return [Net.WebUtility]::HtmlEncode([string]$Value)
}

function Page([string]$Body) {
    return @"
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Connect Sleeper · Butler</title><style>
:root{color-scheme:dark}*{box-sizing:border-box}body{margin:0;background:#101416;color:#f4f5f1;font:16px/1.5 system-ui,Segoe UI,sans-serif}
main{max-width:760px;margin:6vh auto;padding:0 20px}header,.panel{background:#181d1f;border:1px solid #33403d;border-radius:14px;padding:26px;margin-bottom:18px}
h1{font-size:30px;margin:0 0 6px}h2{font-size:23px;margin:0 0 12px}p{color:#c6d1cc}small{color:#aebdb5}
label{display:block;margin:14px 0}input[type=text]{display:block;width:100%;padding:12px;border:1px solid #72877b;border-radius:8px;background:#101416;color:white;font:inherit}
button,.button{display:inline-block;border:0;border-radius:8px;background:#75b592;color:#102018;font:700 15px system-ui;padding:12px 18px;cursor:pointer;text-decoration:none}
.choice{display:flex;gap:12px;align-items:start;border:1px solid #34413d;border-radius:10px;padding:14px;margin:10px 0;cursor:pointer}.choice strong{display:block}
.note{border-left:3px solid #75b592;padding-left:13px}code{overflow-wrap:anywhere}pre{white-space:pre-wrap;overflow-wrap:anywhere}
</style></head><body><main><header><h1>BUTLER</h1><small>We're here to serve you. Less Research. Better Decisions.</small></header>$Body</main></body></html>
"@
}

function Form-Fields([string]$Raw) {
    $result = @{}
    foreach ($pair in ($Raw -split '&')) {
        $separator = $pair.IndexOf('=')
        if ($separator -ge 0) {
            $key = [Uri]::UnescapeDataString($pair.Substring(0, $separator).Replace('+', ' '))
            $value = [Uri]::UnescapeDataString($pair.Substring($separator + 1).Replace('+', ' '))
            if ($result.ContainsKey($key)) { throw 'Duplicate form field.' }
            $result[$key] = $value
        }
    }
    return $result
}

function Send-Page($Response, [int]$Code, [string]$Body) {
    $bytes = [Text.Encoding]::UTF8.GetBytes($Body)
    $Response.StatusCode = $Code
    $Response.ContentType = 'text/html; charset=utf-8'
    $Response.Headers.Add('Cache-Control', 'no-store')
    $Response.Headers.Add('X-Content-Type-Options', 'nosniff')
    $Response.Headers.Add('Referrer-Policy', 'no-referrer')
    $Response.Headers.Add('Content-Security-Policy', "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; form-action 'self'; base-uri 'none'")
    $Response.ContentLength64 = $bytes.Length
    $Response.OutputStream.Write($bytes, 0, $bytes.Length)
    $Response.Close()
}

function Welcome([string]$ErrorText = '') {
    $errorBlock = if ($ErrorText) { '<p role="alert">' + (Escape-Html $ErrorText) + '</p>' } else { '' }
    return Page ("<section class='panel'><h2>Connect your Sleeper profile</h2><p>Enter your public Sleeper username to find your 2026 leagues.</p>$errorBlock<form method='post' action='/lookup'><input type='hidden' name='token' value='$token'><label>Sleeper username<input type='text' name='username' required maxlength='32' autocomplete='username'></label><button>Find my leagues</button></form><p class='note'>This is a read-only username lookup, not Sleeper account sign-in. Butler never submits fantasy transactions.</p></section>")
}

function Choices {
    $items = @()
    foreach ($league in $leagueOptions) {
        $id = Escape-Html $league.Id
        $name = Escape-Html $league.Name
        $status = Escape-Html $league.Status
        $items += "<label class='choice'><input type='radio' name='league' value='$id' required><span><strong>$name</strong><small>2026 · $status · $id</small></span></label>"
    }
    $user = Escape-Html $selectedUsername
    return Page ("<section class='panel'><h2>Choose your team’s league</h2><p>Leagues found for <strong>$user</strong>. Butler will verify your exact current roster before creating a local profile.</p><form method='post' action='/import'><input type='hidden' name='token' value='$token'>$($items -join '')<button>Import selected league</button></form><p><a href='/'>Use another username</a></p></section>")
}

function Status {
    if ($null -eq $setupProcess) { return '<p>No import is running.</p>' }
    if (-not $setupProcess.HasExited) { return '<p>Importing and verifying your league. This can take several minutes. Keep this window open.</p>' }
    $setupProcess.WaitForExit()
    $setupProcess.Refresh()
    $exitCode = $setupProcess.ExitCode
    $output = if (Test-Path -LiteralPath $stdoutPath) { [IO.File]::ReadAllText($stdoutPath) } else { '' }
    $errors = if (Test-Path -LiteralPath $stderrPath) { [IO.File]::ReadAllText($stderrPath) } else { '' }
    if (($null -eq $exitCode -or $exitCode -eq 0) -and $errors.Trim().Length -eq 0 -and
        $output.Contains('BUTLER MVP ONBOARDING: PASS') -and $output.Contains('BUTLER NEW LEAGUE PROCESS: COMPLETE')) {
        if ($VerifyOnly) { return '<h2>Verification passed</h2><p>Your selected team reached all seven Butler pages in this temporary profile. The app was stopped after verification.</p>' }
        $dashboard = [regex]::Match($output, '(?m)^BUTLER DASHBOARD:\s+(http://127\.0\.0\.1:\d+/)')
        $link = if ($dashboard.Success) { "<p><a class='button' href='$($dashboard.Groups[1].Value)'>Open Dashboard</a></p>" } else { '<p>Butler opened Dashboard in a new browser window.</p>' }
        return "<h2>Your team is ready</h2>$link<p>Exact roster binding and seven-page verification passed.</p>"
    }
    $tail = ($output + "`n" + $errors)
    if ($tail.Length -gt 2600) { $tail = $tail.Substring($tail.Length - 2600) }
    $exitLabel = if ($null -eq $exitCode) { 'unavailable' } else { [string]$exitCode }
    return ('<h2>Setup needs attention</h2><p>Setup process exit code: ' + $exitLabel + '. Review the last setup messages:</p><pre>' + (Escape-Html $tail) + '</pre>')
}

$listener = [Net.HttpListener]::new()
$url = "http://127.0.0.1:$Port/"
$listener.Prefixes.Add($url)
try {
    $listener.Start()
    Write-Host "Butler fresh-profile connection: $url"
    Write-Host 'Boundary: loopback only; public Sleeper reads, then explicit Butler-local import; no Sleeper transaction write.'
    Write-Host 'Press Ctrl+C to stop this setup window.'
    if (-not $NoBrowser) { Start-Process $url }
    while ($listener.IsListening) {
        $context = $listener.GetContext()
        $request = $context.Request
        $path = $request.Url.AbsolutePath
        try {
            if ($request.Url.Host -cne '127.0.0.1') { Send-Page $context.Response 400 (Page '<p>Invalid host.</p>'); continue }
            if ($request.HttpMethod -ceq 'GET') {
                $body = switch ($path) {
                    '/' { Welcome }
                    '/choices' { if ($leagueOptions.Count -gt 0) { Choices } else { Welcome } }
                    '/status' { Status }
                    '/progress' { Page ('<section class="panel"><h2>Preparing your team</h2><div id="status">' + (Status) + '</div><script>async function poll(){try{let r=await fetch("/status",{cache:"no-store"});document.getElementById("status").innerHTML=await r.text()}finally{setTimeout(poll,2500)}}setTimeout(poll,2500)</script></section>') }
                    default { Page '<p>Page not found.</p>' }
                }
                Send-Page $context.Response $(if ($path -in @('/','/choices','/status','/progress')) { 200 } else { 404 }) $body
                continue
            }
            if ($request.HttpMethod -cne 'POST' -or $path -notin @('/lookup','/import')) { Send-Page $context.Response 405 (Page '<p>Method not allowed.</p>'); continue }
            if ($request.ContentLength64 -lt 0 -or $request.ContentLength64 -gt 4096 -or $request.ContentType -notlike 'application/x-www-form-urlencoded*') { Send-Page $context.Response 400 (Page '<p>Invalid form.</p>'); continue }
            $origin = [string]$request.Headers['Origin']
            if ($origin -and $origin -cne 'null' -and $origin -cne $url.TrimEnd('/')) {
                Send-Page $context.Response 403 (Page ('<section class="panel"><h2>Browser request blocked</h2><p>The browser sent origin <code>' + (Escape-Html $origin) + '</code>; this setup page expects <code>' + (Escape-Html $url.TrimEnd('/')) + '</code>.</p></section>'))
                continue
            }
            $reader = [IO.StreamReader]::new($request.InputStream, $request.ContentEncoding)
            try { $form = Form-Fields $reader.ReadToEnd() } finally { $reader.Dispose() }
            if (-not $form.ContainsKey('token') -or $form['token'] -cne $token) { Send-Page $context.Response 403 (Page '<p>Invalid setup token.</p>'); continue }
            if ($null -ne $setupProcess) { Send-Page $context.Response 409 (Page '<p>Setup has already started.</p>'); continue }
            if ($path -ceq '/lookup') {
                $username = [string]$form['username']
                if ($username -cnotmatch '^[A-Za-z0-9_-]{1,32}$') { Send-Page $context.Response 400 (Welcome 'Enter a valid Sleeper username.'); continue }
                $previousPreference = $ErrorActionPreference
                try {
                    $ErrorActionPreference = 'Continue'
                    $lines = @(& $java '--enable-native-access=ALL-UNNAMED' '-cp' $classPath 'io.butler.bet.cli.ButlerSleeperLeagueSelectionCli' $username 2>&1)
                }
                finally { $ErrorActionPreference = $previousPreference }
                if ($LASTEXITCODE -ne 0) { Send-Page $context.Response 502 (Welcome 'Sleeper lookup failed. Check the username or try again.'); continue }
                $found = @()
                foreach ($line in $lines) {
                    $match = [regex]::Match([string]$line, '^LEAGUE\t(?<id>\d+)\t(?<name>[^\t\r\n]+)\t2026\t(?<status>[^\t\r\n]+)$')
                    if ($match.Success) { $found += [pscustomobject]@{ Id = $match.Groups['id'].Value; Name = $match.Groups['name'].Value; Status = $match.Groups['status'].Value } }
                }
                if ($found.Count -eq 0) { Send-Page $context.Response 404 (Welcome 'No 2026 leagues were found for that username.'); continue }
                $selectedUsername = $username
                $leagueOptions = $found
                Send-Page $context.Response 200 (Choices)
                continue
            }
            $leagueId = [string]$form['league']
            if ($leagueId -notmatch '^\d+$' -or @($leagueOptions | Where-Object { $_.Id -ceq $leagueId }).Count -ne 1) { Send-Page $context.Response 400 (Page '<p>Select a league from the displayed list.</p>'); continue }
            if ((Test-Path -LiteralPath $selectionPath) -or (Test-Path -LiteralPath $databasePath)) { Send-Page $context.Response 409 (Page '<p>A Butler profile already exists. Setup stopped without overwriting it.</p>'); continue }
            $runDir = Join-Path $configDir ('onboard-runs\' + [Guid]::NewGuid().ToString('N'))
            [IO.Directory]::CreateDirectory($runDir) | Out-Null
            $stdoutPath = Join-Path $runDir 'setup.out.log'
            $stderrPath = Join-Path $runDir 'setup.err.log'
            $arguments = '-NoLogo -NoProfile -ExecutionPolicy Bypass -File "' + $setup + '" -RuntimeZip "' + $RuntimeZip + '" -SleeperUsername ' + $selectedUsername + ' -SleeperLeagueId ' + $leagueId
            if ($VerifyOnly) { $arguments += ' -VerifyOnly' }
            $setupProcess = Start-Process -FilePath $shell -ArgumentList $arguments -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath -WindowStyle Hidden -PassThru
            [IO.File]::WriteAllText((Join-Path $runDir 'setup.pid'), ($setupProcess.Id.ToString() + '|' + $setupProcess.StartTime.ToUniversalTime().Ticks.ToString()))
            $context.Response.Redirect($url + 'progress')
            $context.Response.Close()
        }
        catch {
            Send-Page $context.Response 500 (Page ('<p>Setup could not continue: ' + (Escape-Html $_.Exception.Message) + '</p>'))
        }
    }
}
finally {
    if ($listener.IsListening) { $listener.Stop() }
    $listener.Close()
}
