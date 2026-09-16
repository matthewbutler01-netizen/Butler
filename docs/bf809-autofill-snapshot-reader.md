# BF-809 AutoFill Snapshot Reader

BF-809 fixes a live BF-808 acceptance defect where My Team successfully wrote a manager-safe AutoFill snapshot, but the Command Center still fell back to the generic `READY TO REVIEW` lineup state.

Live evidence proved the writer was healthy: `%LOCALAPPDATA%\Butler\autofill-preview` contained a fresh `BF-808-1` JSON snapshot with the expected league, roster, target, week, scoring basis, and fail-closed FantasyPros reason.

BF-809 hardens only the Dashboard reader. It keeps the direct league-key file lookup first, then scans the same local AutoFill preview directory for valid snapshots and accepts a candidate when either the stored league key matches or the normalized manager target (`league | team | roster`) matches. The downstream freshness and target checks remain fail-safe. JSON validation now uses direct property lookup rather than relying on a projected property-name collection.

The Dashboard still does not contact FantasyPros. BF-809 does not persist credentials, invoke the optimizer, submit a Sleeper lineup, execute a waiver or trade, or add any new recommendation semantics.
