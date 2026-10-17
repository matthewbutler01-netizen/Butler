Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$workerPath = Join-Path $PSScriptRoot 'butler-app-request-worker.ps1'
$recoveryPath = Join-Path $PSScriptRoot 'butler-recover-roster-drift.ps1'
foreach ($requiredPath in @($workerPath, $recoveryPath)) {
    if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
        throw "BF-1037 BLOCKED: required file missing at $requiredPath"
    }
}

$worker = [IO.File]::ReadAllText($workerPath)
$recovery = [IO.File]::ReadAllText($recoveryPath)
$tokens = $null
$errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($workerPath, [ref]$tokens, [ref]$errors)
if (@($errors).Count -ne 0) {
    throw 'BF-1037 BLOCKED: request worker does not parse.'
}

$matches = @($ast.FindAll({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
    $node.Name -eq 'Test-StartSitRosterDriftResponse'
}, $true))
if ($matches.Count -ne 1) {
    throw "BF-1037 BLOCKED: expected one roster-drift response detector, found $($matches.Count)."
}
. ([scriptblock]::Create($matches[0].Extent.Text))

$drift = '<div>BLOCKED: current roster membership drifted from BF-603/BF-602 frame; added=[9508] removed=[11618]; refresh BF-602/BF-603 and downstream live evidence before target-roster review</div>'
$source=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'butler-app-bf884-manager-error-pages-transform.ps1'))
$transformAst=[Management.Automation.Language.Parser]::ParseInput($source,[ref]$tokens,[ref]$errors)
$helper=@($transformAst.FindAll({param($n)$n -is [Management.Automation.Language.StringConstantExpressionAst] -and $n.Value.StartsWith('function Test-ButlerExactRosterDrift {')},$true))
if ($helper.Count -ne 1) { throw 'Recovery helper source missing or ambiguous.' }
. ([scriptblock]::Create($helper[0].Value))
function Get-AppCss { return '' }
function Get-AppNav { param($Active) return '<nav></nav>' }
function ConvertTo-HtmlText { param($Value) return [Net.WebUtility]::HtmlEncode([string]$Value) }
$privateReason='BF-610 BLOCKED: current roster membership drifted from BF-603/BF-602 frame; added=[] removed=[6768]; refresh BF-602/BF-603 and downstream live evidence before target-roster review'
if (-not (Test-ButlerExactRosterDrift -Reason $privateReason)) { throw 'Exact internal drift rejected.' }
$safe=New-ManagerRecoveryPageHtml -Title 'My Team unavailable' -Status 'STOPPED SAFELY' -Summary 'Advice held.' -Detail 'UNVERIFIED_SOURCE' -Active team -RosterDrift (Test-ButlerExactRosterDrift -Reason $privateReason)
if ($safe.Contains('6768') -or $safe.Contains($privateReason) -or -not $safe.Contains('Saved roster evidence no longer matches')) { throw 'Recovery page leaked raw context or lost safe explanation.' }
foreach($route in @('/team','/matchup/autofill')) {
    if (-not (Test-StartSitRosterDriftResponse -RequestTarget $route -Body $safe -StatusCode 500)) { throw 'Sanitized drift no longer reaches governed repair.' }
    if (Test-StartSitRosterDriftResponse -RequestTarget $route -Body $safe -StatusCode 200) { throw 'Successful page initiated repair.' }
}
foreach($bad in @($safe.Replace('bf610-exact-drift-v1','unknown'),($safe+$safe),$safe.Replace('role="status"','role="note"'))) {
    if (Test-StartSitRosterDriftResponse -RequestTarget '/team' -Body $bad) { throw 'Unknown or duplicated safe proof accepted.' }
}
foreach($reason in @('projection gap',$privateReason.Replace('BF-610 BLOCKED:','OTHER:'),$privateReason.Replace('removed=[6768]','removed=[<script>]'))) {
    if (Test-ButlerExactRosterDrift -Reason $reason) { throw 'Unproven drift classified as exact.' }
    $generic=New-ManagerRecoveryPageHtml -Title 'Unavailable' -Status 'HELD' -Summary 'Advice held.' -RosterDrift (Test-ButlerExactRosterDrift -Reason $reason)
    if ($generic.Contains('data-butler-roster-recovery') -or (Test-StartSitRosterDriftResponse -RequestTarget '/team' -Body $generic)) { throw 'Generic error authorized repair.' }
}

# Exercise the authenticated private HTTP boundary, not just marker rendering.
$innerSource=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'butler-core-bf742-transform.ps1'))
$innerAst=[Management.Automation.Language.Parser]::ParseInput($innerSource,[ref]$tokens,[ref]$errors)
$innerHelper=@($innerAst.FindAll({param($n)$n -is [Management.Automation.Language.StringConstantExpressionAst] -and $n.Value.StartsWith('function Invoke-Bf742DashboardWorkerRead {')},$true))
if ($innerHelper.Count -ne 1) { throw 'Private worker read helper missing.' }
$innerText=$innerHelper[0].Value
. ([scriptblock]::Create($innerText.Substring(0,$innerText.IndexOf('function Invoke-ButlerReadOnly {',[StringComparison]::Ordinal))))
if (-not ('ButlerDriftHttpFixture' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading.Tasks;
public sealed class ButlerDriftHttpFixture : IDisposable {
    readonly TcpListener listener;
    readonly Task serve;
    public int Port { get; private set; }
    public bool Authenticated { get; private set; }
    public ButlerDriftHttpFixture(string body, int status) {
        listener = new TcpListener(IPAddress.Loopback, 0); listener.Start();
        Port = ((IPEndPoint)listener.LocalEndpoint).Port;
        serve = Task.Run(() => {
            using (var client = listener.AcceptTcpClient()) {
                var stream = client.GetStream(); stream.ReadTimeout = 5000;
                var reader = new StreamReader(stream); string line;
                while (!String.IsNullOrEmpty(line = reader.ReadLine())) {
                    if (line.Equals("X-Butler-Internal-Token: fixture-only", StringComparison.OrdinalIgnoreCase)) Authenticated = true;
                }
                var bytes = Encoding.UTF8.GetBytes(body);
                var header = Encoding.ASCII.GetBytes("HTTP/1.1 " + status + " Fixture\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: " + bytes.Length + "\r\nConnection: close\r\n\r\n");
                stream.Write(header, 0, header.Length); stream.Write(bytes, 0, bytes.Length); stream.Flush();
            }
        });
    }
    public void Dispose() { listener.Stop(); serve.Wait(6000); }
}
'@
}
$script:Bf742DashboardToken='fixture-only'
foreach($case in @(
    @{Status=500;Path='/__butler/internal/team-bundle';Body=$privateReason;Expected=$true},
    @{Status=500;Path='/__butler/internal/team-bundle';Body='private-unrelated-error';Expected=$false},
    @{Status=403;Path='/__butler/internal/team-bundle';Body=$privateReason;Expected=$false},
    @{Status=500;Path='/__butler/internal/league-overview';Body=$privateReason;Expected=$false},
    @{Status=500;Path='/__butler/internal/team-bundle';Body=(('x'*4096)+$privateReason);Expected=$false}
)) {
    $fixture=[ButlerDriftHttpFixture]::new($case.Body,$case.Status)
    try {
        $script:Bf742InnerDashboardPort=$fixture.Port
        $message=''
        try { [void](Invoke-Bf742DashboardWorkerRead -Path $case.Path -BoundaryName 'FIXTURE') } catch {$message=$_.Exception.Message}
        if (($message -ceq 'BF-610_ROSTER_DRIFT_VERIFIED') -ne $case.Expected -or
            $message.Contains('6768') -or $message.Contains('private-unrelated-error') -or -not $fixture.Authenticated) { throw "Private HTTP fixture failed: status=$($case.Status); path=$($case.Path); authenticated=$($fixture.Authenticated); message=$message" }
    } finally { $fixture.Dispose() }
}
if (-not (Test-StartSitRosterDriftResponse -RequestTarget '/matchup/autofill' -Body $drift)) {
    throw 'BF-1037 BLOCKED: exact Start/Sit roster drift was not detected.'
}
if (-not (Test-StartSitRosterDriftResponse -RequestTarget '/team' -Body $drift)) {
    throw 'BF-1037 BLOCKED: exact My Team roster drift was not detected.'
}
foreach ($route in @('/matchup', '/', '/waivers', '/team?position=RB', '/players')) {
    if (Test-StartSitRosterDriftResponse -RequestTarget $route -Body $drift) {
        throw "BF-1037 BLOCKED: unsupported route $route initiated roster recovery."
    }
}
if (Test-StartSitRosterDriftResponse -RequestTarget '/team' -Body '<div>projection gap</div>') {
    throw 'BF-1037 BLOCKED: non-roster My Team failures must not trigger roster recovery.'
}
if (Test-StartSitRosterDriftResponse -RequestTarget '/matchup/autofill' -Body '<div>projection gap</div>') {
    throw 'BF-1037 BLOCKED: non-roster Start/Sit failures must not trigger roster recovery.'
}

foreach ($required in @(
    'Invoke-StartSitRosterDriftAutoRecovery -Root $RepoRoot',
    '$proxied = Invoke-AppCoreGet -Port $InnerPort -RequestTarget $requestTarget',
    'BF-723 APP AUTO RECOVERY: COMPLETE',
    '-AppAutoRecovery',
    'Local\Butler.StartSit.RosterRecovery.',
    'Claim-LocalEvidenceRecovery -State $RefreshState',
    'Complete-DecisionRefreshAttempt -State $RefreshState'
)) {
    if ($worker.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0 -and
        $recovery.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-1037 BLOCKED: auto-recovery contract missing: $required"
    }
}

if ($recovery.IndexOf('if ($AppAutoRecovery)', [System.StringComparison]::Ordinal) -lt 0) {
    throw 'BF-1037 BLOCKED: recovery script has no app auto-recovery boundary.'
}
if ($recovery.IndexOf('BF-610 failed for a reason other than exact roster drift. No Butler evidence write was attempted.', [System.StringComparison]::Ordinal) -lt 0) {
    throw 'BF-1037 BLOCKED: exact-drift authorization guard was weakened.'
}
foreach ($forbidden in @('create_transaction','submitTransaction','setFaab','cancel waiver','replace a Sleeper transaction')) {
    if ($worker.IndexOf($forbidden, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
        throw "BF-1037 BLOCKED: request-worker auto-recovery introduced forbidden transaction behavior: $forbidden"
    }
}

Write-Host 'BF-1037 START/SIT AUTO ROSTER RECOVERY ACCEPTANCE: PASS'
Write-Host 'Coverage: exact drift detection on My Team and Start/Sit only, governed BF-723 local-evidence recovery, cache invalidation even after partial writes, single route retry, bounded mutex, and no Sleeper transaction behavior.'
