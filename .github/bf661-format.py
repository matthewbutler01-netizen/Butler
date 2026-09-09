from pathlib import Path

DASHBOARD = Path("scripts/butler-dashboard.ps1")
JAVA = Path("bet/bet-cli/src/test/java/io/butler/bet/cli/ButlerDashboardBf661ScriptTest.java")

text = DASHBOARD.read_text(encoding="ascii")

# Format only the BF-661 explanation reconciliation block.
function_start = text.index("function Get-GovernedExplanationView {")
start_marker = '    $decisionState = Get-LineValue -Text $Summary -Label "Decision status:"\n'
end_marker = '    if ($lookupState -ceq "EXPLANATION_NOT_CAPTURED") {\n'
start = text.index(start_marker, function_start) + len(start_marker)
end = text.index(end_marker, start)
block = text[start:end]
if not block.startswith('if ([string]::IsNullOrWhiteSpace($decisionState)) {\n'):
    raise SystemExit("BF-661 FORMAT BLOCKED: explanation block start drifted")
formatted = "".join(("    " + line if line.strip() else line) for line in block.splitlines(keepends=True))
text = text[:start] + formatted + text[end:]

# Format only the BF-661 move-card gating block. Keep here-string content and
# terminator flush-left because Windows PowerShell 5.1 requires that terminator.
moves_anchor = '    $movesSection = ""\n'
moves_start = text.index(moves_anchor) + len(moves_anchor)
moves_end_marker = '    return @"\n<!doctype html><html lang="en"><head><meta charset="utf-8"'
moves_end = text.index(moves_end_marker, moves_start)
moves_block = text[moves_start:moves_end]
if not moves_block.startswith('$transactionStates = @(\n'):
    raise SystemExit("BF-661 FORMAT BLOCKED: moves block start drifted")

formatted_lines = []
inside_here_string = False
for line in moves_block.splitlines(keepends=True):
    raw = line.rstrip("\r\n")
    if not inside_here_string and raw.lstrip().endswith('$movesSection = @"'):
        formatted_lines.append("    " + line)
        inside_here_string = True
        continue
    if inside_here_string:
        formatted_lines.append(line)
        if raw == '"@':
            inside_here_string = False
        continue
    formatted_lines.append(("    " + line) if line.strip() else line)
if inside_here_string:
    raise SystemExit("BF-661 FORMAT BLOCKED: unterminated moves here-string")
text = text[:moves_start] + "".join(formatted_lines) + text[moves_end:]
DASHBOARD.write_text(text, encoding="ascii", newline="\n")

java_text = JAVA.read_text(encoding="ascii")
malformed = '''        for (int depth = 0; depth < 7 && current != null; depth++) {
  Path candidate = current.resolve("scripts/butler-dashboard.ps1");
  if (Files.isRegularFile(candidate)) {
      return Files.readString(candidate, StandardCharsets.US_ASCII);
  }
  current = current.getParent();
        }
'''
clean = '''        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve("scripts/butler-dashboard.ps1");
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.US_ASCII);
            }
            current = current.getParent();
        }
'''
if malformed not in java_text:
    raise SystemExit("BF-661 FORMAT BLOCKED: Java indentation anchor drifted")
JAVA.write_text(java_text.replace(malformed, clean, 1), encoding="ascii", newline="\n")
