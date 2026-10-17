# Waiver explanation presentation fidelity

A saved same-position explanation ends with an internal method identifier
(BF-615), not a missing replacement-player name. The public engineering-label
cleanup removed the identifier and left the sentence ending "replacement by .".

Only the exact known frozen explanation is translated into plain language:
recorded historical comparison of same-position finalists and the supported
bench/reserve comparator. Unknown method text is not reinterpreted. The original
saved method is HTML-escaped inside the existing source/scoring disclosure; the
public formatter preserves that preformatted record without stripping its IDs.
The persisted Java explanation, audit, recommendation and selection logic are
unchanged.

The recorded explanation schema does not contain a source season. The existing
"Source season: not recorded" limitation is retained. Current NFL season, local
capture time and a source name are not used to fabricate historical coverage.

Tests execute the real evidence fragment and public formatter with the frozen
Java method text. They verify readable copy, original-record preservation,
unknown-method fallback, escaping, missing-source-year honesty, unavailable and
inactive explanations, and audit-mismatch failure. Existing no-verified-move and
evidence-age acceptance also pass. No provider write or background task is added.
