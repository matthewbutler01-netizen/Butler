# BF-808 AutoFill Command Center Snapshot

BF-808 lets Butler remember the latest explicit, read-only AutoFill result and reuse that manager-safe summary in the Command Center.

The snapshot is written only after the manager opens the AutoFill route. Dashboard load never triggers a FantasyPros request. The saved payload contains the target identity, result state, week/scoring basis, proven projection totals when available, change count, source attribution, fail-closed reason, and generation time. It does not contain the FantasyPros API key, provider request details, or a Sleeper write instruction.

A saved result is treated as current only when it matches the active league/team target, Butler still verifies the roster context, and the snapshot is recent. Otherwise the Lineup card asks for a fresh AutoFill run. A fresh unavailable result remains an evidence gap; Butler does not convert it into a recommendation. A fresh ready result with starter changes becomes a concrete lineup priority, while a proven no-change result becomes neutral.

BF-808 does not change projection semantics, optimize differently, submit a lineup, submit a waiver transaction, execute a trade, or mutate Sleeper.