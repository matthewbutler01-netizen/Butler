# Butler project lessons and completion standard

Reviewed with Matt on 2026-10-08. This records the agreed working standard;
it is not release certification or permission to publish a release.

## Product objective

Less Research. Better Decisions. Complete the weekly manager journey in under
five minutes: Dashboard, correct team/week, lineup review, waiver review,
outdated-evidence recovery, and a clear next action. Minimize PowerShell and
finish existing workflows before adding unrelated features. Keep v0.3.0 frozen;
current Start/Sit and Auto-Pilot work belongs to v0.4 development.

## Lessons we must apply

| Lesson | Required practice |
| --- | --- |
| A safe stop must be understandable | Explain the reason and provide a supported next step; preserve technical details underneath. |
| Refresh, capture, and deployment differ | State whether an operation updates evidence, saves a decision, or changes application code. Never imply one proves the others. |
| Valid evidence is not guaranteed football quality | Identify season, source, and methodology. Historical production is not a weekly projection; an attributed opinion is not consensus. |
| Missing data must remain visible | Do not invent projections, treat missing points as zero, or equate Questionable with Out. Preserve applicable holds. |
| Recovery must match the actual state | Inspect eligibility before suggesting commands. Do not weaken gates to make recovery succeed. |
| Manager control remains explicit | Sleeper access stays read-only. Butler-local writes must be identified; no automatic lineup, waiver, trade, or FAAB submissions. |
| Small checks do not certify the whole app | Use targeted checks during development, then validate the installed package and complete manager journey for the exact release commit. |
| Repeated manual handoffs are product debt | Reuse existing capabilities and complete app workflows instead of adding endless ticket-sized command sequences. |
| Data protection is separate from packaging | Keep runtime data private and external; retain backup and rollback requirements. A runtime ZIP is not a data backup. |

## Review findings

The reviewed Start/Sit and waiver work preserves holds, attribution, immutable
history, and read-only Sleeper behavior. Recent live output verifies successful
Butler audit capture and latest-decision checks. It does not establish a full
new data refresh, current backup/restore validation, or release readiness.

Usability remains incomplete: stale-decision recovery required multiple manual
CLI steps. The initial advice to use browser refresh for STALE_DO_NOT_ACT was
incorrect; the application correctly rejected that unsupported state.

Source inspection before the recovery change confirmed the gap: `scripts/butler-decision-refresh.ps1`
offers refresh for specific no-transaction and refresh-recommended states;
`scripts/sleeper-live-waiver-no-transaction-refresh.ps1` enforces its own
eligibility. The stale waiver presentation tells the manager to wait for a new
decision without completing that recovery journey. Audit capture already exists
as a separate capability, but must not be conflated with evidence refresh.

## Next completion objective

Complete state-aware recovery within the existing weekly manager journey.
Before implementation, distinguish superseded evidence from failed live
actionability, roster drift, pending/completed transactions, and unknown state.
Reuse established recovery capabilities only for their supported conditions.
Keep GET read-only and preserve protected POST handling and live revalidation.
Show what changed, verify the resulting decision, and return to Waiver Board.
On failure, retain the stop and provide an accurate supported next step.

Acceptance must exercise supported recovery and rejected/changed states, ensure
no Sleeper writes, and check the rendered result and navigation. Source-text
assertions alone are insufficient.

## Completion and release evidence

Follow `release-candidate.md`, `release-evidence.md`, and the README for the
authoritative exact-commit gates. Do not substitute older passing checkpoints.
Required evidence includes CI, the packaged Windows runtime, Fast Lane manager
journey, isolated fresh-profile onboarding, page verification, package hashes,
and the human under-five-minute task. Stop on failed gates; do not weaken them.
Keep publication separate from development and verification.

Report each outcome as verified, partially verified, or unverified. This review
did not rerun full acceptance or verify current remote CI/release state.

## Recovery implementation checkpoint

The development runner now admits STALE_DO_NOT_ACT only when live actionability
is LIVE_ACTIONABLE_VERIFIED and evidence lineage is exactly market superseded,
waiver superseded, or both. Dashboard refresh visibility uses the same conditions.
This is an explicit extension of recovery eligibility, not permission to act on
the old move. It runs the existing refresh, audit capture, and final-summary chain.
Pending/completed transactions and failed, unknown, missing, or duplicate evidence
remain rejected by this path. Existing POST token checks and roster probe remain.

Completion wording now says the refresh steps finished and directs the manager
to review the resulting decision and remaining holds, rather than claiming all
data is up to date.

Local PowerShell 7 checks passed: 30 state combinations at the stubbed external
runtime boundary, preflight-only behavior, stop on stage failure, duplicate-field
rejection, and rendered confirmation/result/navigation acceptance. These are
not live-provider or packaged Windows 5.1 tests. CI, packaged Windows acceptance,
and a live manager journey remain outstanding.
