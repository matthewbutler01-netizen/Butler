from pathlib import Path

path = Path("scripts/butler-dashboard.ps1")
text = path.read_text(encoding="ascii")
old = 'If shown, <strong>Current governed ADD</strong> identifies the already-audited current add and <strong>Paired audited DROP</strong> identifies only its exact audited counterpart; the paired context is traceability only, and the current ADD itself does not alter BF-616 order or rank the board.'
new = 'If shown, <strong>Current governed ADD</strong> identifies the already-audited current add and <strong>Paired audited DROP</strong> identifies only its exact audited counterpart. The paired context is traceability only; it does not alter BF-616 order or rank the board.'
if old not in text:
    raise SystemExit("BF-652 BF-649 literal contract phrase marker missing")
text = text.replace(old, new, 1)
text.encode("ascii")
path.write_text(text, encoding="ascii", newline="\n")
