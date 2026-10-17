# Roster drift and local evidence recovery

When the current Sleeper roster no longer matches Butler's saved evidence,
My Team and Start/Sit must withhold advice. The public page does not expose
the raw exception, roster identifiers or database paths.

The inner core recognizes the exact BF-610 roster-membership failure before
sanitizing its error. It emits a fixed recovery marker and an explanation.
Its authenticated inner-dashboard client reads at most 4,096 characters of a
failed team-bundle response to classify that exact failure, then carries only
a fixed sentinel onward. Other paths/statuses and unrelated failures do not
receive that classification. No raw response is copied into the public page.
The outer worker accepts that marker only once, on an HTTP 500 response from
the exact My Team or Start/Sit route. Successful pages, unknown markers,
duplicates and other routes do not initiate this recovery.

The marker only identifies a possible repair. The existing shared write claim
and BF-723 runner still authorize the operation: the runner independently
checks exact roster drift and verifies the current matchup before waiver
evidence writes. Failures retain the hold. After an attempt, cached reads are
invalidated even after partial writes; the route is retried once on success.

This repairs Butler-local evidence only. It does not change a Sleeper roster,
waiver claim, FAAB amount or trade. Repair can take time. A responding page,
completed repair or saved review timestamp does not clear player/projection
holds or prove live injury availability.

If repair cannot complete, the safe page keeps advice held and offers
Check refresh options. No manual PowerShell command is required to reach it.
