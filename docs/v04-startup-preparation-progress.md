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

Optional cache priming now stops for that worker at the first failed read.
If waivers fails, team and Dashboard warmups are deferred to normal page reads;
if team fails, Dashboard warmup is deferred. A successful warmup still uses all
three GETs in the same order. This avoids adding optional work behind an error
or timed-out request. The next worker is still started only after preparation
returns. Worker health, public-listener gating, request limits and cleanup are
unchanged. Normal page checks still determine whether the app pages work.

Loopback HTTP fixtures exercise success, failure at each stage, and disabled
warmups. Failures must return normally and must not request later pages. No
provider calls, database writes or live source failures are manufactured.
