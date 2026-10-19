# Fresh-profile initial waiver audit

Packaged onboarding previously imported and hydrated waiver evidence but never
recorded its initial governed recommendation. A real isolated fresh profile
passed the seven base manager-page checks, yet Dashboard reported no waiver
decision and the v0.4 checker reported an unverified audit.

Setup now invokes the existing BF-627 audit capture after hydration and before
committing its staged database or league selection. That capability verifies
live manager identity and reconciles the existing recommendation lineage. It
records a result, including a held result; it does not promise actionable advice,
refresh provider evidence, clear holds or submit a Sleeper transaction.

Only one recognized capture state and one nonempty UUID audit id allow setup
to proceed. Capture errors, missing or ambiguous proof and unknown states stop
before database/selection installation. Existing-profile refusal remains.

Windows fixtures run the actual setup entry with a fake Java boundary, checking
successful initial capture and failed-capture containment. The isolated live
investigation also ran the existing BF-627 CLI and confirmed Dashboard changed
from no recorded decision to a recorded review result. That manual isolated
probe is not end-to-end certification of the updated setup package: the new
commit still needs exact-head CI and packaged fresh-profile acceptance.
