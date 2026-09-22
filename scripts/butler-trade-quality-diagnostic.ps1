param(
    [ValidateRange(1, 600)]
    [int]$StartupTimeoutSeconds = 180,
    [ValidateRange(1, 300)]
    [int]$RequestTimeoutSeconds = 120
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $scriptDir
$appLauncher = Join-Path $scriptDir 'butler-app.ps1'
$powershell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$taskkill = Join-Path $env:SystemRoot 'System32\taskkill.exe'
$git = (Get-Command git.exe -ErrorAction Stop).Source
$loopback = [System.Net.IPAddress]::Parse('127.0.0.1')

foreach ($required in @($appLauncher, $powershell, $taskkill, $git)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "BF-904 BLOCKED: required component not found at $required"
    }
}

function Get-WorkingTreeState {
    Push-Location $repoRoot
    try {
        $lines = & $git status --porcelain=v1 --untracked-files=all 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "BF-904 BLOCKED: git status failed with exit code $LASTEXITCODE."
        }
        return (($lines | ForEach-Object { "$_" }) -join [Environment]::NewLine).Trim()
    }
    finally {
        Pop-Location
    }
}

function Get-FreePort {
    $probe = [System.Net.Sockets.TcpListener]::new($loopback, 0)
    try {
        $probe.Start()
        return ([System.Net.IPEndPoint]$probe.LocalEndpoint).Port
    }
    finally {
        try { $probe.Stop() } catch {}
    }
}

function Start-OwnedButler {
    param([int]$Port)
    $quote = [char]34
    $start = [System.Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $powershell
    $start.Arguments = '-NoLogo -NoProfile -ExecutionPolicy Bypass -File "{0}" -Port {1} -NoBrowser' -f $appLauncher, $Port
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $process = [System.Diagnostics.Process]::Start($start)
    if ($null -eq $process) {
        throw 'BF-904 BLOCKED: unable to launch owned Butler process.'
    }
    return $process
}

function Stop-OwnedButler {
    param([AllowNull()]$Process, [int]$Port)
    if ($null -ne $Process) {
        if (-not $Process.HasExited) {
            & $taskkill /PID $Process.Id /T /F 2>$null | Out-Null
            if ($LASTEXITCODE -ne 0) {
                throw "BF-904 CLEANUP FAILED: taskkill exited with code $LASTEXITCODE."
            }
        }
    }

    try {
        $localAppData = $env:LOCALAPPDATA
        if ([string]::IsNullOrWhiteSpace($localAppData)) {
            $localAppData = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)
        }
        if (-not [string]::IsNullOrWhiteSpace($localAppData)) {
            $runState = Join-Path (Join-Path $localAppData 'Butler') ("running-port-{0}.txt" -f $Port)
            if (Test-Path -LiteralPath $runState -PathType Leaf) {
                $raw = [IO.File]::ReadAllText($runState, [Text.Encoding]::ASCII).Trim()
                if ($null -ne $Process -and $raw.StartsWith(([string]$Process.Id + '|'), [System.StringComparison]::Ordinal)) {
                    Remove-Item -LiteralPath $runState -Force
                }
            }
        }
    }
    catch {
        Write-Warning ("BF-904 could not remove owned run-state marker: {0}" -f $_.Exception.Message)
    }
}

function Invoke-Get {
    param([string]$Url, [int]$TimeoutMs)
    $request = [System.Net.HttpWebRequest]::Create($Url)
    $request.Method = 'GET'
    $request.Timeout = $TimeoutMs
    $request.ReadWriteTimeout = $TimeoutMs
    $request.Proxy = $null
    $request.KeepAlive = $false
    $response = $null
    try {
        try {
            $response = $request.GetResponse()
        }
        catch [System.Net.WebException] {
            if ($null -eq $_.Exception.Response) { throw }
            $response = $_.Exception.Response
        }
        $reader = [System.IO.StreamReader]::new($response.GetResponseStream(), [System.Text.Encoding]::UTF8)
        try { $body = $reader.ReadToEnd() } finally { $reader.Dispose() }
        return [pscustomobject]@{
            StatusCode = [int]$response.StatusCode
            StatusText = [string]$response.StatusDescription
            Body = $body
        }
    }
    finally {
        if ($null -ne $response) { $response.Close() }
    }
}

function Assert-Ok {
    param($Response, [string]$Stage)
    if ($Response.StatusCode -ne 200) {
        $plain = ConvertTo-PlainText -Html $Response.Body
        if ($plain.Length -gt 1200) { $plain = $plain.Substring(0, 1200) + '...' }
        throw "BF-904 BLOCKED: $Stage returned HTTP $($Response.StatusCode). body=$plain"
    }
}

function ConvertTo-PlainText {
    param([string]$Html)
    $plain = [regex]::Replace([string]$Html, '<[^>]+>', ' ')
    $plain = [System.Net.WebUtility]::HtmlDecode($plain)
    return [regex]::Replace($plain, '\s+', ' ').Trim()
}

function Escape-Value {
    param([string]$Value)
    return [System.Uri]::EscapeDataString($Value)
}

function Get-Opponent {
    param([string]$Html)
    $matches = [regex]::Matches(
        $Html,
        '<option\s+value="(?<id>[^"]+)"(?:\s+selected)?>(?<name>.*?)</option>',
        [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
    foreach ($match in $matches) {
        $id = [System.Net.WebUtility]::HtmlDecode($match.Groups['id'].Value).Trim()
        $name = [regex]::Replace(
            [System.Net.WebUtility]::HtmlDecode($match.Groups['name'].Value),
            '<[^>]+>', '').Trim()
        if (-not [string]::IsNullOrWhiteSpace($id)) {
            return [pscustomobject]@{ Id = $id; Name = $name }
        }
    }
    throw 'BF-904 BLOCKED: no selectable current league opponent was rendered.'
}

function Get-ValuedAssets {
    param(
        [string]$Html,
        [ValidateSet('give','receive')]
        [string]$Name
    )

    $pattern = '<label\s+class="asset-option">\s*<input\s+type="checkbox"\s+name="{0}"\s+value="(?<token>[^"]+)"[^>]*>\s*<span>\s*<strong>(?<label>.*?)</strong>\s*<small>.*?<span\s+class="asset-value">value\s+(?<value>-?\d+(?:\.\d+)?)</span>.*?</small>\s*</span>\s*</label>' -f [regex]::Escape($Name)
    $options = [System.Text.RegularExpressions.RegexOptions]::IgnoreCase -bor
        [System.Text.RegularExpressions.RegexOptions]::Singleline
    $assets = @()
    foreach ($match in [regex]::Matches($Html, $pattern, $options)) {
        $token = [System.Net.WebUtility]::HtmlDecode($match.Groups['token'].Value).Trim()
        $labelHtml = [System.Net.WebUtility]::HtmlDecode($match.Groups['label'].Value)
        $label = [regex]::Replace($labelHtml, '<[^>]+>', '').Trim()
        $value = [double]::Parse(
            $match.Groups['value'].Value,
            [System.Globalization.CultureInfo]::InvariantCulture)
        $assets += [pscustomobject]@{
            Token = $token
            Label = $label
            Value = $value
        }
    }
    if ($assets.Count -eq 0) {
        throw "BF-904 BLOCKED: no currently valued $Name asset was rendered."
    }
    return @($assets | Sort-Object Value, Label)
}

function Select-SampleAssets {
    param([object[]]$Assets)
    $ordered = @($Assets | Sort-Object Value, Label)
    $count = [int]$ordered.Length
    if ($count -le 3) { return $ordered }

    $middleIndex = [int][Math]::Floor(($count - 1) / 2.0)
    $lastIndex = $count - 1
    $indices = @(0, $middleIndex, $lastIndex)
    $selected = @()
    $seen = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    foreach ($index in $indices) {
        $asset = $ordered[[int]$index]
        if ($seen.Add([string]$asset.Token)) { $selected += $asset }
    }
    return @($selected)
}

function Parse-Recommendation {
    param([string]$Html)
    $plain = ConvertTo-PlainText -Html $Html

    $action = [regex]::Match($plain, '(?:^|\s)Action:\s+(?<value>ACCEPT|REJECT|HOLD|INCONCLUSIVE)(?:\s|$)')
    $package = [regex]::Match($plain, '(?:^|\s)Package recommendation:\s+(?<value>\S+)')
    $evidence = [regex]::Match($plain, '(?:^|\s)Evidence complete:\s+(?<value>true|false)(?:\s|$)')
    $reason = [regex]::Match($plain, '(?:^|\s)Reason:\s+(?<value>.*?)(?=\s+No hidden weighting|\s+Trade recommendation|$)')

    if (-not $action.Success -or -not $package.Success -or -not $evidence.Success) {
        throw 'BF-904 BLOCKED: evaluated trade page is missing governed recommendation fields.'
    }

    return [pscustomobject]@{
        Action = $action.Groups['value'].Value.Trim()
        Package = $package.Groups['value'].Value.Trim()
        EvidenceComplete = $evidence.Groups['value'].Value -ceq 'true'
        Reason = if ($reason.Success) { $reason.Groups['value'].Value.Trim() } else { '' }
    }
}

function Parse-Counter {
    param([string]$Html)
    $plain = ConvertTo-PlainText -Html $Html
    if ($plain.IndexOf('Counter available', [System.StringComparison]::Ordinal) -ge 0) {
        $instruction = [regex]::Match(
            $plain,
            'Counter available\s+(?<value>.*?)(?=\s+COUNTER\s+Your revised package)')
        return [pscustomobject]@{
            State = 'COUNTER'
            Summary = if ($instruction.Success) { $instruction.Groups['value'].Value.Trim() } else { 'Counter available' }
        }
    }
    if ($plain.IndexOf('No governed counteroffer', [System.StringComparison]::Ordinal) -ge 0) {
        return [pscustomobject]@{ State = 'NO_ACTION'; Summary = 'No governed counteroffer' }
    }
    if ($plain.IndexOf('Counteroffer unavailable', [System.StringComparison]::Ordinal) -ge 0) {
        return [pscustomobject]@{ State = 'INCONCLUSIVE'; Summary = 'Counteroffer unavailable' }
    }
    throw 'BF-904 BLOCKED: counter page did not expose a governed counter state.'
}

$before = Get-WorkingTreeState
if (-not [string]::IsNullOrWhiteSpace($before)) {
    throw "BF-904 BLOCKED: repository must be clean before diagnostic. status=$before"
}

$port = Get-FreePort
$root = "http://127.0.0.1:$port"
$process = $null
$failure = $null

Write-Host 'Butler Trade Analyzer quality diagnostic (BF-904)'
Write-Host "Target: $root"
Write-Host 'Sample: one live opponent; low/mid/high valued asset from each side; maximum 9 exact 1-for-1 deals.'
Write-Host 'Boundary: GET-only local Butler requests; /refresh excluded; no Butler or Sleeper transaction write.'

try {
    $process = Start-OwnedButler -Port $port
    $deadline = [DateTime]::UtcNow.AddSeconds($StartupTimeoutSeconds)
    $healthy = $false
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($process.HasExited) {
            throw "BF-904 FAILED: Butler exited during startup with code $($process.ExitCode)."
        }
        try {
            $health = Invoke-Get -Url ($root + '/health') -TimeoutMs 1000
            if ($health.StatusCode -eq 200) {
                $identity = $health.Body | ConvertFrom-Json
                if ($null -ne $identity -and ([string]$identity.service) -ceq 'butler-app-shell' -and ([string]$identity.status) -ceq 'ok') {
                    $healthy = $true
                    break
                }
                throw 'BF-904 BLOCKED: selected port is not the expected butler-app-shell.'
            }
        }
        catch [System.Net.WebException] {}
        Start-Sleep -Milliseconds 250
    }
    if (-not $healthy) {
        throw "BF-904 FAILED: Butler did not become healthy within $StartupTimeoutSeconds seconds."
    }
    Write-Host 'Health: BUTLER_APP_SHELL_VERIFIED'

    $timeoutMs = $RequestTimeoutSeconds * 1000
    $landing = Invoke-Get -Url ($root + '/trade?load=1') -TimeoutMs $timeoutMs
    Assert-Ok -Response $landing -Stage 'Trade Analyzer landing'
    $opponent = Get-Opponent -Html $landing.Body
    Write-Host ("Opponent: {0} [{1}]" -f $opponent.Name, $opponent.Id)

    $opponentPage = Invoke-Get -Url (
        $root + '/trade?opponent=' + (Escape-Value -Value $opponent.Id)) -TimeoutMs $timeoutMs
    Assert-Ok -Response $opponentPage -Stage 'opponent load'

    $giveAssets = @(Select-SampleAssets -Assets @(Get-ValuedAssets -Html $opponentPage.Body -Name 'give'))
    $receiveAssets = @(Select-SampleAssets -Assets @(Get-ValuedAssets -Html $opponentPage.Body -Name 'receive'))

    Write-Host 'Your sampled outgoing assets:'
    foreach ($asset in $giveAssets) {
        Write-Host ("  {0} | value={1}" -f $asset.Label, $asset.Value)
    }
    Write-Host 'Opponent sampled incoming assets:'
    foreach ($asset in $receiveAssets) {
        Write-Host ("  {0} | value={1}" -f $asset.Label, $asset.Value)
    }

    $results = @()
    $firstCompleteRejectUrl = $null
    foreach ($give in $giveAssets) {
        foreach ($receive in $receiveAssets) {
            $url = '{0}/trade?opponent={1}&evaluate=1&give={2}&receive={3}' -f $root, (Escape-Value -Value $opponent.Id), (Escape-Value -Value $give.Token), (Escape-Value -Value $receive.Token)
            $page = Invoke-Get -Url $url -TimeoutMs $timeoutMs
            Assert-Ok -Response $page -Stage ("trade evaluation {0} for {1}" -f $give.Label, $receive.Label)
            $decision = Parse-Recommendation -Html $page.Body

            $result = [pscustomobject]@{
                Give = $give.Label
                GiveValue = $give.Value
                Receive = $receive.Label
                ReceiveValue = $receive.Value
                Action = $decision.Action
                Package = $decision.Package
                EvidenceComplete = $decision.EvidenceComplete
                Reason = $decision.Reason
            }
            $results += $result

            Write-Host ("TRADE | GIVE {0} ({1}) | GET {2} ({3}) | action={4} | evidence={5} | package={6}" -f
                $give.Label, $give.Value, $receive.Label, $receive.Value,
                $decision.Action, $decision.EvidenceComplete, $decision.Package)
            if (-not [string]::IsNullOrWhiteSpace($decision.Reason)) {
                Write-Host ("  reason={0}" -f $decision.Reason)
            }

            if ($null -eq $firstCompleteRejectUrl -and $decision.EvidenceComplete -and $decision.Action -ceq 'REJECT') {
                $firstCompleteRejectUrl = $url
            }
        }
    }

    $complete = @($results | Where-Object EvidenceComplete).Count
    $accept = @($results | Where-Object Action -CEQ 'ACCEPT').Count
    $reject = @($results | Where-Object Action -CEQ 'REJECT').Count
    $hold = @($results | Where-Object Action -CEQ 'HOLD').Count
    $inconclusive = @($results | Where-Object Action -CEQ 'INCONCLUSIVE').Count

    Write-Host ''
    Write-Host ("Evaluated deals: {0}" -f $results.Count)
    Write-Host ("Evidence complete: {0}/{1}" -f $complete, $results.Count)
    Write-Host ("Action distribution: ACCEPT={0} REJECT={1} HOLD={2} INCONCLUSIVE={3}" -f
        $accept, $reject, $hold, $inconclusive)

    if ($null -ne $firstCompleteRejectUrl) {
        $counterPage = Invoke-Get -Url ($firstCompleteRejectUrl + '&counter=1') -TimeoutMs $timeoutMs
        Assert-Ok -Response $counterPage -Stage 'first evidence-complete reject counter'
        $counter = Parse-Counter -Html $counterPage.Body
        Write-Host ("First rejected-deal counter state: {0}" -f $counter.State)
        Write-Host ("First rejected-deal counter summary: {0}" -f $counter.Summary)
    }
    else {
        Write-Host 'First rejected-deal counter state: NOT_APPLICABLE'
        Write-Host 'First rejected-deal counter summary: no evidence-complete REJECT occurred in the bounded sample.'
    }

    Write-Host 'BF-904 DIAGNOSTIC: COMPLETE'
}
catch {
    $failure = $_
}
finally {
    try { Stop-OwnedButler -Process $process -Port $port }
    catch {
        if ($null -eq $failure) { $failure = $_ } else { Write-Warning $_.Exception.Message }
    }
}

$after = $null
try { $after = Get-WorkingTreeState }
catch {
    if ($null -eq $failure) { $failure = $_ } else { Write-Warning $_.Exception.Message }
}
if ($null -eq $failure -and -not [string]::IsNullOrWhiteSpace($after)) {
    $failure = [System.Exception]::new(
        "BF-904 FAILED: repository became dirty during diagnostic. status=$after")
}

if ($null -ne $failure) {
    Write-Host 'BF-904 DIAGNOSTIC: FAIL'
    throw $failure
}

Write-Host 'Working tree: CLEAN'
