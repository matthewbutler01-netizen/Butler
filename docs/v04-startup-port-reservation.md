# Startup endpoint reservation

The outer app and core pool reserve their loopback listener before preparing
children. Previously they selected an endpoint, released its probe, and bound
only after preparation. Another socket could acquire that endpoint meanwhile.
Exclusive binding now fails before expensive preparation if the endpoint is
already occupied. Both existing finally blocks still stop their owned listener.

A listening socket is not verified readiness. Request acceptance remains after
the existing child health checks and request-pool initialization. Launchers must
continue to require Butler's exact health response; a successful TCP connection
does not clear startup, evidence freshness or transaction eligibility.

`scripts/butler-startup-port-reservation-acceptance.ps1` runs the production
startup prefixes with inert preparation boundaries. A competing exclusive bind
must fail, an HTTP health request must time out before readiness, and the port
must be reusable after fixture cleanup. The previous delayed-bind source fails
this regression. No JVM, database or provider is used by the fixture.

This race was identified while investigating a local acceptance failure whose
private startup diagnostic recorded a Windows socket access-permissions error.
That observation establishes the failed bind, but does not identify its exact
competing socket or prove the cause of an earlier timeout. Failed acceptance
records remain retained; startup limits are unchanged.
