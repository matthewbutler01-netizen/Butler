# Shared Start/Sit schedule and stats resources

Kickoff-lock and defensive-matchup checks use the same nflverse schedule.
Usage and defensive-matchup checks use the same season's weekly stats resource.
The production composition now supplies those checks with the existing usage
provider's downloader, allowing its successful URI cache to serve all three.

This removes duplicate successful cold downloads. The downloader's 15-minute
expiry, HTTP retry policy, size/decompression limits and failure behavior are
unchanged. A failed download is not cached as evidence. An expired resource
still requires a successful fetch; no stale fallback is introduced.

Only the raw resource is shared. Every consumer still parses and validates its
own season, week, player identity, schedule uniqueness, coverage and kickoff
state. Cached data for one interpretation cannot turn another week into a
verified matchup or unlocked player. Existing source and recommendation holds
remain. No Sleeper transaction, new concurrency or overall latency target is
introduced.

Local HTTP fixtures check request counts, independent week interpretation and
retry after a failed resource. Live timings remain observations rather than a
benchmark or proof of a universal response-time improvement.

A controlled-clock HTTP fixture also checks the exact 15-minute expiry boundary:
successful evidence is reusable just before expiry, a failed refresh at expiry
throws rather than returning expired data, further calls retry, and a later
successful refresh restores evidence. Refreshed schedule data remains shared
with kickoff-lock checks. The production constructor still uses the UTC system
clock; the package-private clock constructor exists for deterministic validation.
