from pathlib import Path

path = Path("scripts/butler-dashboard.ps1")
text = path.read_text(encoding="ascii")

old = 'If shown, <strong>Current governed ADD</strong> identifies the already-audited current add and <strong>Paired audited DROP</strong> identifies only its exact audited counterpart; neither alters BF-616 order or ranks the board.'
new = 'If shown, <strong>Current governed ADD</strong> identifies the already-audited current add and <strong>Paired audited DROP</strong> identifies only its exact audited counterpart; the paired context is traceability only, and the current ADD itself does not alter BF-616 order or rank the board.'
if old not in text:
    raise SystemExit("BF-652 waiver disclaimer compatibility marker missing")
text = text.replace(old, new, 1)

old = "identifies Butler's already-audited current ADD and its exact paired audited DROP. It does not rerank candidates, weight market/depth/injury, score newcomers, pick a new winner/drop, set FAAB, run BF-641, refresh evidence, or submit a Sleeper transaction."
new = "identifies Butler's already-audited current ADD and its exact paired audited DROP. It does not rerank candidates, weight market/depth/injury, score newcomers, pick a new winner, identify a new drop, set FAAB, run BF-641, refresh evidence, or submit a Sleeper transaction. Paired context is traceability only and does not make a new selection."
if old not in text:
    raise SystemExit("BF-652 waiver boundary compatibility marker missing")
text = text.replace(old, new, 1)

old = 'This page does not score roster needs, optimize a lineup, rank your players, select a new add/drop, set FAAB, run BF-641, refresh evidence, or submit a Sleeper transaction.'
new = 'This page does not score roster needs, optimize a lineup, rank your players, select a new drop or a new add, set FAAB, run BF-641, refresh evidence, or submit a Sleeper transaction.'
if old not in text:
    raise SystemExit("BF-652 team boundary compatibility marker missing")
text = text.replace(old, new, 1)

text.encode("ascii")
path.write_text(text, encoding="ascii", newline="\n")
