# Startup preparation visibility

The preserved core prepares six workers sequentially before opening its listener.
Optional page warmups may time out even though worker health succeeds. A warning
from that stage is not itself a failed launch or evidence verification.

The app window now reports runtime preparation, worker index/total, time to
worker health, total worker preparation time, and core-listener startup.
It explicitly distinguishes these stages from outer readiness and page checks.
Messages contain no league, roster, player, token, database path or remote URL.

This is visibility for diagnosis, not a startup performance fix. Warmup routes,
timeouts, ordering, worker count, safety checks and cleanup are unchanged.
