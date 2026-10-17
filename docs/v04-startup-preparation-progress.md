# Startup preparation visibility

The preserved core prepares six workers sequentially before opening its listener.
Optional page warmups may time out even though worker health succeeds. A warning
from that stage is not itself a failed launch or evidence verification.

The app window now reports runtime preparation, worker index/total, time to
worker health, total worker preparation time, and core-listener startup.
It explicitly distinguishes these stages from outer readiness and page checks.
Messages contain no league, roster, player, token, database path or remote URL.

The six existing children now launch before serial health/cache preparation,
allowing process initialization to overlap. Each child is registered immediately
in the cleanup list. Health failure stops preparation; the public listener still
opens only after all six health checks pass. A partial launch failure leaves all
successfully started children owned by the existing finally cleanup.

Warmup routes, timeouts, preparation order, worker count, safety checks and
cleanup are unchanged. This removes serialization of child process startup;
it is not a universal latency guarantee. The offline pool acceptance fixture
checks bounded launch ordering, health-before-warmup, health failure and partial
launch failure without starting children or changing evidence.
