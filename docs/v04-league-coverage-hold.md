# Explain held franchise rankings

Fresh-profile testing found partial franchise-value coverage: 341 of 344 assets
had recorded values, with three missing player values and no missing pick values.
Inspection of the current DynastyProcess value feed found no rows for those
players. That observation does not justify guessing values or releasing ranks.

The League overview now includes a bounded count summary from the existing
franchise-readiness report. When ranks are held, the League card displays valued
assets, total assets, missing player/pick values and stale values. It explains
that rechecking the same source can leave gaps and that recorded coverage is
not a live source check. Ranking eligibility and valuation are unchanged.

The parser accepts exactly one well-formed summary with consistent totals and
stale counts. Missing, duplicated, malformed or inconsistent summaries retain
the generic hold without displaying uncertain counts. Older overview output
remains supported. Counts never promote a held board to ranked output.

Java output/readiness tests and the existing Windows staged League-renderer
fixture cover the output and fallback behavior. Exact-head CI and installed
browser acceptance remain required before including this in a tested beta.
