from pathlib import Path

path = Path('scripts/butler-dashboard.ps1')
text = path.read_text(encoding='ascii')

old = '''            $modeClass = if ($step.Mode -ceq "READ_ONLY") { "read" } else { "write" }
            $commandPayload = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes([string]$step.Command))
            $refreshCards += @"
'''
new = '''            $modeClass = if ($step.Mode -ceq "READ_ONLY") { "read" } else { "write" }
            $refreshCards += @"
'''
if old not in text:
    raise SystemExit('BF-656 no-script anchor 1 not found')
text = text.replace(old, new, 1)

old = '''  <div class="refresh-command">$(ConvertTo-HtmlText $step.Command)</div>
  <div class="refresh-copy"><button type="button" class="copy-refresh-command" data-refresh-command-b64="$commandPayload">Copy command</button><span class="copy-refresh-status" aria-live="polite"></span></div>
  <div class="refresh-purpose">$(ConvertTo-HtmlText $step.Purpose)</div>
'''
new = '''  <div class="refresh-copy-hint">Copy safely: focus the read-only field, then Press Ctrl+A, then Ctrl+C.</div>
  <textarea class="refresh-command-copy" rows="2" readonly>$(ConvertTo-HtmlText $step.Command)</textarea>
  <div class="refresh-purpose">$(ConvertTo-HtmlText $step.Purpose)</div>
'''
if old not in text:
    raise SystemExit('BF-656 no-script anchor 2 not found')
text = text.replace(old, new, 1)

start = text.find('<script>\n(function(){\n  document.addEventListener("click", function(event){')
if start < 0:
    raise SystemExit('BF-656 no-script script start not found')
end_marker = '</script>\n"@\n'
end = text.find(end_marker, start)
if end < 0:
    raise SystemExit('BF-656 no-script script end not found')
text = text[:start] + '"@\n' + text[end + len(end_marker):]

old = '''.refresh-plan-note{margin-top:14px;padding:14px 16px;border:1px solid #6a5427;border-radius:14px;background:#261f10;color:#f0d79a}.refresh-steps{display:grid;gap:10px;margin-top:16px}.refresh-step{padding:15px 16px;border:1px solid #2b3962;border-radius:14px;background:#0d1630}.refresh-step-head{display:flex;align-items:center;gap:9px;flex-wrap:wrap}.refresh-step-num{font-size:18px;font-weight:900}.refresh-step-bf{color:#a9c6ff;font-weight:800}.refresh-mode{display:inline-block;padding:4px 7px;border-radius:999px;font-size:10px;font-weight:900}.refresh-mode.read{background:#1c315c;color:#a9c6ff}.refresh-mode.write{background:#4b3713;color:#ffd98b}.refresh-task{margin-top:7px;font-weight:800}.refresh-command{margin-top:9px;padding:11px 12px;border-radius:10px;background:#080f20;border:1px solid #26345c;color:#c7d9ff;font-family:Consolas,monospace;font-size:12px;overflow-wrap:anywhere}.refresh-copy{display:flex;align-items:center;gap:9px;margin-top:8px}.copy-refresh-command{border:1px solid #315dca;border-radius:9px;background:#17254a;color:#c7d9ff;padding:7px 10px;font:inherit;font-size:11px;font-weight:800;cursor:pointer}.copy-refresh-command:hover{background:#203d79}.copy-refresh-status{color:#8ff0b9;font-size:11px;font-weight:800}.refresh-purpose{margin-top:8px;color:#aebada;font-size:12px}
'''
new = '''.refresh-plan-note{margin-top:14px;padding:14px 16px;border:1px solid #6a5427;border-radius:14px;background:#261f10;color:#f0d79a}.refresh-steps{display:grid;gap:10px;margin-top:16px}.refresh-step{padding:15px 16px;border:1px solid #2b3962;border-radius:14px;background:#0d1630}.refresh-step-head{display:flex;align-items:center;gap:9px;flex-wrap:wrap}.refresh-step-num{font-size:18px;font-weight:900}.refresh-step-bf{color:#a9c6ff;font-weight:800}.refresh-mode{display:inline-block;padding:4px 7px;border-radius:999px;font-size:10px;font-weight:900}.refresh-mode.read{background:#1c315c;color:#a9c6ff}.refresh-mode.write{background:#4b3713;color:#ffd98b}.refresh-task{margin-top:7px;font-weight:800}.refresh-copy-hint{margin-top:8px;color:#94a2c5;font-size:11px}.refresh-command-copy{display:block;width:100%;min-height:52px;margin-top:6px;padding:11px 12px;border-radius:10px;background:#080f20;border:1px solid #26345c;color:#c7d9ff;font-family:Consolas,monospace;font-size:12px;line-height:1.35;resize:vertical;overflow-wrap:anywhere}.refresh-command-copy:focus{outline:2px solid #315dca;outline-offset:1px}.refresh-purpose{margin-top:8px;color:#aebada;font-size:12px}
'''
if old not in text:
    raise SystemExit('BF-656 no-script anchor 3 not found')
text = text.replace(old, new, 1)

path.write_text(text, encoding='ascii')
