# Local runtime lifetime observations

The supervisor and its existing watchdog now share one best-effort local receipt
per launch in `%LOCALAPPDATA%\Butler\diagnostics\runtime-lifetime-*.txt`.
It contains a version header and at most seven fixed event observations with UTC
timestamps. An observed guard exit includes its integer exit code. No league,
roster, player, token, command line, raw error or provider payload is recorded.

`GUARD_EXITED` means the supervisor observed that exit code.
`SUPERVISOR_DISAPPEARED` means the watchdog could no longer verify the exact
supervisor process identity. `GUARD_DISAPPEARED` means it could no longer verify
the guard while the supervisor was still present. These observations do not
identify who closed a window, establish a crash, or certify continuous health.
Missing final events remain unknown; power loss and simultaneous process loss
can prevent a terminal observation. The earlier unexplained stop has no receipt
and cannot be retrospectively attributed by this change.

Receipts are local diagnostics, not release evidence or source-freshness proof.
Missing/unwritable storage must not stop launch or change guard exit results.
Receipt append paths must remain in the diagnostics directory with the generated
filename shape. There is no automatic restart or change to watchdog ownership,
cleanup, loopback binding, evidence holds or Sleeper write capability.

The Windows acceptance fixture runs the actual supervisor and watchdog around
an inert guard: successful and nonzero guard exits, abrupt owned-supervisor loss,
watchdog cleanup of that owned guard, blocked diagnostic storage and refusal to
append to an unrelated file. It starts no Butler runtime or provider request.

A separate guard-loss fixture keeps an inert supervisor alive while stopping
its inert guard. It runs the actual watchdog and requires the matching core
and its descendant to exit, while an unrelated guard child and the supervisor
remain alive. The receipt must say `GUARD_DISAPPEARED`, without inventing an
observed exit code or reporting supervisor loss. This verifies cleanup scope
and observation accuracy; it adds no automatic restart or production behavior.
