param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$BaseUrl,

    [ValidateRange(100, 30000)]
    [int]$TimeoutMs = 5000
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

try {
    $baseUri = [System.Uri]$BaseUrl
}
catch {
    throw "BF-768 BLOCKED: BaseUrl is not a valid URI. $($_.Exception.Message)"
}

if (-not $baseUri.IsAbsoluteUri -or
    $baseUri.Scheme -cne 'http' -or
    $baseUri.Host -cne '127.0.0.1' -or
    $baseUri.AbsolutePath -cne '/' -or
    -not [string]::IsNullOrEmpty($baseUri.Query) -or
    -not [string]::IsNullOrEmpty($baseUri.Fragment)) {
    throw 'BF-768 BLOCKED: release-security smoke requires exact loopback HTTP BaseUrl http://127.0.0.1:<port>/.'
}

$root = $baseUri.GetLeftPart([System.UriPartial]::Authority)
$expectedCsp = "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'"

function Invoke-Bf768Request {
    param(
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][string]$Path
    )

    $request = [System.Net.HttpWebRequest]::Create($root + $Path)
    $request.Method = $Method
    $request.Timeout = $TimeoutMs
    $request.ReadWriteTimeout = $TimeoutMs
    $request.Proxy = $null
    $request.KeepAlive = $false
    if ($Method -ceq 'POST') {
        $request.ContentLength = 0
    }

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
        try {
            $body = $reader.ReadToEnd()
        }
        finally {
            $reader.Dispose()
        }

        return [pscustomobject]@{
            StatusCode = [int]$response.StatusCode
            ContentType = [string]$response.ContentType
            CacheControl = [string]$response.Headers['Cache-Control']
            XContentTypeOptions = [string]$response.Headers['X-Content-Type-Options']
            ContentSecurityPolicy = [string]$response.Headers['Content-Security-Policy']
            Body = $body
        }
    }
    finally {
        if ($null -ne $response) { $response.Close() }
    }
}

function Assert-Bf768Equal {
    param(
        [Parameter(Mandatory = $true)][string]$Label,
        [AllowNull()]$Actual,
        [AllowNull()]$Expected
    )

    if ([string]$Actual -cne [string]$Expected) {
        throw "BF-768 FAILED: $Label expected '$Expected' but received '$Actual'."
    }
}

function Assert-Bf768SecurityHeaders {
    param(
        [Parameter(Mandatory = $true)]$Result,
        [Parameter(Mandatory = $true)][string]$Label
    )

    Assert-Bf768Equal -Label "$Label Cache-Control" -Actual $Result.CacheControl -Expected 'no-store'
    Assert-Bf768Equal -Label "$Label X-Content-Type-Options" -Actual $Result.XContentTypeOptions -Expected 'nosniff'
    Assert-Bf768Equal -Label "$Label Content-Security-Policy" -Actual $Result.ContentSecurityPolicy -Expected $expectedCsp
}

Write-Host 'Butler runtime release-security smoke (BF-768)'
Write-Host 'Boundary: loopback-only HTTP probes after BF-688; exact POST /refresh is never submitted; no Butler evidence or Sleeper transaction write is invoked.'

$health = Invoke-Bf768Request -Method 'GET' -Path '/health'
Assert-Bf768Equal -Label 'GET /health status' -Actual $health.StatusCode -Expected 200
Assert-Bf768SecurityHeaders -Result $health -Label 'GET /health'
try {
    $healthJson = $health.Body | ConvertFrom-Json
}
catch {
    throw "BF-768 FAILED: GET /health did not return valid JSON. $($_.Exception.Message)"
}
Assert-Bf768Equal -Label 'GET /health service' -Actual $healthJson.service -Expected 'butler-app-shell'
Assert-Bf768Equal -Label 'GET /health state' -Actual $healthJson.status -Expected 'ok'
Assert-Bf768Equal -Label 'GET /health bind' -Actual $healthJson.bind -Expected '127.0.0.1'
Write-Host 'BF-768 health identity + security headers: PASS'

$methodProbe = Invoke-Bf768Request -Method 'POST' -Path '/health'
Assert-Bf768Equal -Label 'POST /health status' -Actual $methodProbe.StatusCode -Expected 405
Assert-Bf768Equal -Label 'POST /health body' -Actual $methodProbe.Body -Expected 'GET only'
Assert-Bf768SecurityHeaders -Result $methodProbe -Label 'POST /health'
Write-Host 'BF-768 non-refresh POST rejection: PASS'

$queryProbe = Invoke-Bf768Request -Method 'GET' -Path '/refresh?bf768=1'
Assert-Bf768Equal -Label 'GET /refresh query status' -Actual $queryProbe.StatusCode -Expected 400
if ($queryProbe.Body -notmatch 'BF-675 refresh confirmation accepts no query parameters\.') {
    throw 'BF-768 FAILED: GET /refresh query did not preserve the BF-675 fail-closed query guard.'
}
Assert-Bf768SecurityHeaders -Result $queryProbe -Label 'GET /refresh query'
Write-Host 'BF-768 refresh query guard: PASS'

$refresh = Invoke-Bf768Request -Method 'GET' -Path '/refresh'
Assert-Bf768Equal -Label 'GET /refresh status' -Actual $refresh.StatusCode -Expected 200
if ($refresh.ContentType -notmatch '^text/html') {
    throw "BF-768 FAILED: GET /refresh content type was '$($refresh.ContentType)', expected text/html."
}
Assert-Bf768SecurityHeaders -Result $refresh -Label 'GET /refresh'

$formMatches = [regex]::Matches($refresh.Body, '<form method="post" action="/refresh">', [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
if ($formMatches.Count -ne 1) {
    throw "BF-768 FAILED: GET /refresh contained $($formMatches.Count) exact POST /refresh forms; expected 1."
}
$tokenMatches = [regex]::Matches($refresh.Body, '<input type="hidden" name="token" value="[0-9a-f]{64}">', [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
if ($tokenMatches.Count -ne 1) {
    throw "BF-768 FAILED: GET /refresh contained $($tokenMatches.Count) valid one-use token fields; expected 1."
}
if ($refresh.Body -match '(?i)<script\b') {
    throw 'BF-768 FAILED: GET /refresh introduced a script element.'
}
if ($refresh.Body -match '(?i)javascript\s*:') {
    throw 'BF-768 FAILED: GET /refresh introduced a javascript: URL.'
}
Write-Host 'BF-768 read-only refresh confirmation + one-use token surface: PASS'
Write-Host 'BF-768 RELEASE SECURITY: PASS'
