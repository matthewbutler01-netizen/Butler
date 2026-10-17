param([Parameter(Mandatory=$true)][string]$DashboardPath)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$source=[IO.File]::ReadAllText($DashboardPath)
$tokens=$null; $errors=$null
$ast=[Management.Automation.Language.Parser]::ParseInput($source,[ref]$tokens,[ref]$errors)
if (@($errors).Count -or $source.Contains('function Get-ButlerSavedLineupContextHtml')) { throw 'Saved lineup context: invalid or duplicate input.' }
$found=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq 'Get-Bf979SnapshotWeeklyAttentionHtml'},$true))
if ($found.Count -ne 1) { throw 'Saved lineup context: attention renderer is not unique.' }
$fn=$found[0]; $body=$fn.Extent.Text
$anchor='    $nameText = $names -join '' &middot; '''
$slot='$nameText</p><p class='
foreach($text in @($anchor,$slot)) {
    if ([regex]::Matches($body,[regex]::Escape($text)).Count -ne 1) { throw 'Saved lineup context: attention slot is not unique.' }
}
$body=$body.Replace($anchor,$anchor+[Environment]::NewLine+'    $savedLineupContext = Get-ButlerSavedLineupContextHtml -Snapshot $Snapshot')
$body=$body.Replace($slot,'$nameText</p>$savedLineupContext<p class=')
$helper=@'
function Get-ButlerSavedLineupContextHtml {
    param([AllowNull()]$Snapshot)
    $week='unknown'; $source='unknown'; $generated='unknown'
    if ($null -ne $Snapshot) {
        $number=0
        if ($null -ne $Snapshot.PSObject.Properties['Week'] -and
            [int]::TryParse([string]$Snapshot.Week,[ref]$number) -and $number -ge 1 -and $number -le 18) { $week=[string]$number }
        if ($null -ne $Snapshot.PSObject.Properties['Source'] -and
            -not [string]::IsNullOrWhiteSpace([string]$Snapshot.Source)) { $source=[string]$Snapshot.Source }
        if ($null -ne $Snapshot.PSObject.Properties['GeneratedUtc']) {
            $raw=[string]$Snapshot.GeneratedUtc
            $stamp=[DateTimeOffset]::MinValue
            # Require an explicit zone; never invent UTC for a local/ambiguous time.
            if ($raw -match '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$' -and
                [DateTimeOffset]::TryParse($raw,[Globalization.CultureInfo]::InvariantCulture,[Globalization.DateTimeStyles]::None,[ref]$stamp)) {
                $generated=$stamp.UtcDateTime.ToString("yyyy-MM-dd HH:mm:ss 'UTC'",[Globalization.CultureInfo]::InvariantCulture)
            }
        }
    }
    return '<p class="meta saved-lineup-context">Saved Lineup Review: week ' + [Net.WebUtility]::HtmlEncode($week) +
        '; source: ' + [Net.WebUtility]::HtmlEncode($source) + '; generated: ' + [Net.WebUtility]::HtmlEncode($generated) +
        '. This is a saved review, not a live injury or starter check.</p>'
}

'@
$source=$source.Substring(0,$fn.Extent.StartOffset)+$helper+[Environment]::NewLine+$body+$source.Substring($fn.Extent.EndOffset)
[void][Management.Automation.Language.Parser]::ParseInput($source,[ref]$tokens,[ref]$errors)
if (@($errors).Count) { throw 'Saved lineup context: generated source does not parse.' }
[IO.File]::WriteAllText($DashboardPath,$source,[Text.UTF8Encoding]::new($false))
