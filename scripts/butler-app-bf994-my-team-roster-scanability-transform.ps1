param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-994 BLOCKED: staged Butler core not found at $CorePath"
}

function Get-ParsedAst {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Contract
    )

    $tokens = $null
    $errors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseInput($Text, [ref]$tokens, [ref]$errors)
    if (@($errors).Count -gt 0) {
        $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
        throw "BF-994 BLOCKED: $Contract failed PowerShell parse: $summary"
    }
    return $ast
}

function Get-OneFunction {
    param(
        [Parameter(Mandatory = $true)]$Ast,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Contract
    )

    $matches = @($Ast.FindAll({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq $Name
    }, $true))
    if ($matches.Count -ne 1) {
        throw "BF-994 BLOCKED: $Contract expected one $Name function, found $($matches.Count)."
    }
    return $matches[0]
}

function Replace-ExactlyOnce {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Old,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$New,
        [Parameter(Mandatory = $true)][string]$Contract
    )

    $count = [regex]::Matches($Text, [regex]::Escape($Old)).Count
    if ($count -ne 1) {
        throw "BF-994 BLOCKED: $Contract expected one match, found $count."
    }
    return $Text.Replace($Old, $New)
}

$core = [IO.File]::ReadAllText($CorePath)
$ast = Get-ParsedAst -Text $core -Contract 'pre-transform staged core'
$teamFn = Get-OneFunction -Ast $ast -Name 'ConvertTo-TeamHtml' -Contract 'My Team renderer'
$team = $teamFn.Extent.Text

foreach ($required in @(
    '$weeklyAttentionHtml = Get-Bf979WeeklyAttentionHtml -AutoFill $AutoFill',
    'id=`"weekly-attention`"',
    '<div class="rail-label">ON THIS PAGE</div>',
    'id="roster-starters"',
    'id="roster-bench"',
    'id="roster-reserve"',
    '$reserveTaxiCount = [int]$Roster.ReserveCount + [int]$Roster.TaxiCount'
)) {
    if ($team.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0 -and
        $core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-994 BLOCKED: finalized My Team capability is missing: $required"
    }
}

$attentionOld = @'
    $weeklyAttentionHtml = Get-Bf979WeeklyAttentionHtml -AutoFill $AutoFill
    $autoFillHtml = $weeklyAttentionHtml + $autoFillHtml
'@.TrimEnd()

$attentionNew = @'
    $weeklyAttentionHtml = Get-Bf979WeeklyAttentionHtml -AutoFill $AutoFill
    $weeklyAttentionRailHtml = if (-not [string]::IsNullOrWhiteSpace($weeklyAttentionHtml)) {
        '<a class="rail-jump rail-attention" href="#weekly-attention">Weekly attention</a>'
    }
    else {
        ''
    }
    $autoFillHtml = $weeklyAttentionHtml + $autoFillHtml
'@.TrimEnd()

$team = Replace-ExactlyOnce -Text $team -Old $attentionOld -New $attentionNew -Contract 'conditional Weekly Attention rail state'

$railOld = '<div class="rail-label">ON THIS PAGE</div><a class="rail-jump" href="#team-roster">Roster</a>'
$railNew = '<div class="rail-label">ON THIS PAGE</div>$weeklyAttentionRailHtml<a class="rail-jump" href="#team-roster">Roster</a>'
$team = Replace-ExactlyOnce -Text $team -Old $railOld -New $railNew -Contract 'conditional Weekly Attention rail link'

$starterOld = '<section id="roster-starters" class="roster-group" tabindex="-1"><div class="roster-group-head"><h3>Starting lineup</h3>'
$starterNew = '<section id="roster-starters" class="roster-group roster-group-starters" tabindex="-1" aria-label="Starting lineup"><div class="roster-group-head"><h3>Starting lineup <span class="roster-group-count">$(ConvertTo-HtmlText $Roster.StarterCount)</span></h3>'
$team = Replace-ExactlyOnce -Text $team -Old $starterOld -New $starterNew -Contract 'starter group count and semantics'

$benchOld = '<section id="roster-bench" class="roster-group" tabindex="-1"><div class="roster-group-head"><h3>Bench</h3>'
$benchNew = '<section id="roster-bench" class="roster-group roster-group-bench" tabindex="-1" aria-label="Bench"><div class="roster-group-head"><h3>Bench <span class="roster-group-count">$(ConvertTo-HtmlText $Roster.BenchCount)</span></h3>'
$team = Replace-ExactlyOnce -Text $team -Old $benchOld -New $benchNew -Contract 'bench group count and semantics'

$reserveOld = '<section id="roster-reserve" class="roster-group" tabindex="-1"><div class="roster-group-head"><h3>Reserve &amp; taxi</h3>'
$reserveNew = '<section id="roster-reserve" class="roster-group roster-group-reserve" tabindex="-1" aria-label="Reserve and taxi"><div class="roster-group-head"><h3>Reserve &amp; taxi <span class="roster-group-count">$(ConvertTo-HtmlText $reserveTaxiCount)</span></h3>'
$team = Replace-ExactlyOnce -Text $team -Old $reserveOld -New $reserveNew -Contract 'reserve group count and semantics'

$core = $core.Substring(0, $teamFn.Extent.StartOffset) + $team + $core.Substring($teamFn.Extent.EndOffset)

$cssStart = $core.IndexOf('function Get-AppCss {', [System.StringComparison]::Ordinal)
$cssEnd = $core.IndexOf('function Get-AppNav {', $cssStart, [System.StringComparison]::Ordinal)
if ($cssStart -lt 0 -or $cssEnd -le $cssStart) {
    throw 'BF-994 BLOCKED: manager CSS boundary is missing.'
}
$cssBlock = $core.Substring($cssStart, $cssEnd - $cssStart)
$cssTerminator = $cssBlock.LastIndexOf("'@", [System.StringComparison]::Ordinal)
if ($cssTerminator -lt 0) {
    throw 'BF-994 BLOCKED: manager CSS terminator is missing.'
}

$bf994Css = @'
/* BF-994 My Team roster scanability batch. */
.roster-group-count{display:inline-flex;align-items:center;justify-content:center;min-width:28px;margin-left:7px;padding:2px 7px;border:1px solid var(--line);border-radius:999px;font-size:10px;line-height:1.2;color:var(--muted);vertical-align:middle}.roster-group-starters{border-left:3px solid var(--turf)}.roster-group-bench{border-left:3px solid color-mix(in srgb,var(--turf) 45%,var(--line))}.roster-group-reserve{border-left:3px solid var(--line)}.roster-group .player-row{padding-top:9px;padding-bottom:9px}.roster-group .player-primary{min-width:0}.team-rail .rail-attention{font-weight:800;color:var(--ink)}.team-rail .rail-attention::before{content:"!";display:inline-flex;align-items:center;justify-content:center;width:16px;height:16px;margin-right:7px;border-radius:999px;background:color-mix(in srgb,var(--warn) 18%,transparent);color:var(--warn)}@media(max-width:760px){.roster-group .player-row{padding:9px 10px}.roster-group-head{gap:8px}.roster-group-count{margin-left:5px}}
'@

$cssBlock = $cssBlock.Substring(0, $cssTerminator) + [Environment]::NewLine + $bf994Css.TrimEnd() + [Environment]::NewLine + $cssBlock.Substring($cssTerminator)
$core = $core.Substring(0, $cssStart) + $cssBlock + $core.Substring($cssEnd)

$finalAst = Get-ParsedAst -Text $core -Contract 'generated staged core'
$installed = Get-OneFunction -Ast $finalAst -Name 'ConvertTo-TeamHtml' -Contract 'scanable My Team renderer'
$installedText = $installed.Extent.Text

foreach ($required in @(
    '$weeklyAttentionRailHtml = if (-not [string]::IsNullOrWhiteSpace($weeklyAttentionHtml))',
    'href="#weekly-attention">Weekly attention</a>',
    '$weeklyAttentionRailHtml<a class="rail-jump" href="#team-roster">Roster</a>',
    'class="roster-group roster-group-starters"',
    'class="roster-group roster-group-bench"',
    'class="roster-group roster-group-reserve"',
    'aria-label="Starting lineup"',
    'aria-label="Bench"',
    'aria-label="Reserve and taxi"',
    'roster-group-count">$(ConvertTo-HtmlText $Roster.StarterCount)',
    'roster-group-count">$(ConvertTo-HtmlText $Roster.BenchCount)',
    'roster-group-count">$(ConvertTo-HtmlText $reserveTaxiCount)'
)) {
    if ($installedText.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-994 BLOCKED: My Team scanability marker is missing: $required"
    }
}

if ($core.IndexOf('BF-994 My Team roster scanability batch', [System.StringComparison]::Ordinal) -lt 0) {
    throw 'BF-994 BLOCKED: roster scanability CSS was not installed.'
}

$surface = $installedText + [Environment]::NewLine + $bf994Css
if ($surface -match 'Invoke-RestMethod|Invoke-WebRequest|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|returnUrl|redirectUrl|javascript:') {
    throw 'BF-994 BLOCKED: My Team roster scanability introduced provider, optimizer, write, or open-redirect behavior.'
}

[IO.File]::WriteAllText($CorePath, $core, [Text.UTF8Encoding]::new($false))
Write-Host 'BF-994 My Team roster scanability batch applied.'
