# Governed lineup evidence

Butler's lineup evidence surfaces are retrospective evidence tools. They recalculate what can be supported from persisted Sleeper roster/configuration observations and governed nflverse production evidence without turning the result into a manager grade.

## Commands

Team-week evidence:

```text
butler league team-week-potential-lineup <league-id> <team-id> <season> <week>
butler league team-week-started-lineup-evidence <league-id> <team-id> <season> <week>
butler league team-week-lineup-points-gap-evidence <league-id> <team-id> <season> <week>
```

Team-season evidence:

```text
butler league team-season-potential-lineup-evidence <league-id> <team-id> <season>
butler league team-season-lineup-points-gap-evidence <league-id> <team-id> <season>
```

League-season evidence:

```text
butler league season-potential-lineup-evidence <league-id> <season>
butler league season-lineup-points-gap-evidence <league-id> <season>
```

## Evidence chain

The lineup stack intentionally separates evidence collection, scoring, legal-lineup calculation, aggregation, and presentation.

For a team-week comparison, Butler requires the same governed evidence boundary for both sides of the comparison:

- the persisted Sleeper league configuration observation used for starting slots and scoring settings;
- the exact persisted Sleeper team-week roster snapshot;
- dated Sleeper fantasy-position eligibility evidence;
- dated nflverse week-production coverage and production evidence;
- the same scoring and lineup-eligibility policies.

If the governed evidence moves while a season comparison is being assembled, Butler blocks that week instead of combining evidence from different snapshots.

## Started lineup semantics

The started-lineup analyzer uses the exact ordered Sleeper starter snapshot. It does not reconstruct a lineup from final scores.

Sleeper's literal starter value `"0"` is an explicit empty starting slot. Butler preserves it as empty. It is never treated as:

- a player identifier;
- a zero-point player;
- an identity-covered player with no production row.

Those cases remain distinct in the evidence model.

A nonzero starter must belong to the exact persisted team-week roster and must be eligible for the corresponding ordered starting slot. Duplicate starters, starter/slot-count mismatches, and invalid slot eligibility fail closed rather than being guessed around.

## Potential lineup semantics

Potential-lineup evidence solves for the highest-scoring legal lineup supported by the observed provider configuration and governed production evidence.

This is a retrospective calculation under the configuration Butler observed. It is **not reconstructed historical startability**. Butler does not claim that every real-world status, injury designation, transaction timing detail, platform lock, or other historical availability condition has been recreated unless that condition is explicitly represented by governed evidence.

## Team-week points gap

A team-week points gap is available only when both the governed potential lineup and the observed started lineup are complete under the same evidence boundary.

The calculation is exactly:

```text
points gap = retrospective potential points - recalculated started points
```

The gap is descriptive evidence. It is not a manager-efficiency percentage, manager score, fault assignment, or skill estimate.

Incomplete lineups do not receive a partial, normalized, or extrapolated gap.

## Team-season week universe

Team-season evidence uses only persisted Sleeper roster weeks as its observed week universe. Butler does not fabricate unobserved weeks to make a season look complete.

Every observed roster week remains visible in one of four states:

- `COMPARABLE_COMPLETE` — both governed lineups are complete and the points gap is available;
- `POTENTIAL_INCOMPLETE` — the governed potential lineup cannot fill every required starting slot;
- `STARTED_INCOMPLETE` — the persisted observed starting lineup contains one or more empty slots;
- `BLOCKED` — required evidence is unavailable, inconsistent, moved across provenance boundaries, or otherwise unsafe to compare.

Only `COMPARABLE_COMPLETE` weeks contribute to team-season started-point, potential-point, and points-gap totals.

The denominator is always explicit. For example, `4 comparable complete observed weeks out of 7 observed weeks` means exactly that. Butler does not silently convert that into a seven-week estimate.

No average gap or normalized efficiency percentage is produced by this evidence layer.

## League-season presentation

League-season lineup evidence is a neutral wrapper around each team's governed team-season evidence.

Teams are presented in repository team-name order, not score order. Each team's observed/comparable denominator remains separate.

Butler does not compute a league-wide:

- started-points total;
- potential-points total;
- points-gap total;
- average gap;
- normalized percentage;
- comparison score;
- rank or tier.

This prevents teams with different evidence coverage from being collapsed into an apparently comparable league table without an explicit methodology decision.

## Identity-covered zero versus missing evidence

A player may legitimately have governed identity coverage for a week without a production row and therefore receive an authorized zero under the production-coverage policy. That is different from missing evidence and different from Sleeper starter `"0"`.

Butler preserves those distinctions so zero production is not confused with an empty slot or an evidence gap.

## What this evidence does not establish

The governed lineup evidence stack does not by itself establish:

- manager efficiency;
- manager quality or skill;
- intent or fault;
- historical startability beyond the evidence Butler actually persisted;
- a fair cross-team ranking when coverage denominators differ;
- a recommendation about how a manager should have acted.

Any future metric that turns these descriptive points gaps into a manager score, percentage, ranking, or decision recommendation requires a separate governed methodology. That methodology must explicitly define its denominator, coverage requirements, treatment of blocked/incomplete weeks, historical-availability assumptions, cross-team comparability rules, and interpretation boundary before implementation.
