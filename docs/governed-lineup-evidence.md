# Governed lineup evidence

Butler's lineup evidence surfaces are retrospective evidence tools. They recalculate what can be supported from persisted Sleeper roster/configuration observations and governed nflverse production evidence without turning the result into a manager grade.

## Commands

Team-week evidence:

```text
butler league team-week-potential-lineup <league-id> <team-id> <season> <week>
butler league team-week-started-lineup-evidence <league-id> <team-id> <season> <week>
butler league team-week-lineup-points-gap-evidence <league-id> <team-id> <season> <week>
butler league team-week-lineup-capture-evidence <league-id> <team-id> <season> <week>
```

Team-season evidence:

```text
butler league team-season-potential-lineup-evidence <league-id> <team-id> <season>
butler league team-season-lineup-points-gap-evidence <league-id> <team-id> <season>
butler league team-season-lineup-capture-evidence <league-id> <team-id> <season>
```

Pairwise team-season evidence:

```text
butler league team-pair-lineup-capture-contrast-evidence <league-id> <team-a-id> <team-b-id> <season>
```

League-season evidence:

```text
butler league season-potential-lineup-evidence <league-id> <season>
butler league season-lineup-points-gap-evidence <league-id> <season>
butler league season-lineup-capture-evidence <league-id> <season>
```

## Evidence chain

The lineup stack intentionally separates evidence collection, scoring, legal-lineup calculation, aggregation, normalization, comparison, and presentation.

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

## Lineup capture normalization

Lineup capture is the only v1 normalization approved for this evidence stack. It is derived from the governed points-gap evidence rather than independently rescoring players or rebuilding lineups.

For one complete team-week comparison:

```text
lineup capture rate = recalculated started points / retrospective potential points
```

For a team season:

```text
season lineup capture rate =
    comparable total recalculated started points
    /
    comparable total retrospective potential points
```

The season rate is the ratio of governed comparable totals, **not** the arithmetic mean of weekly percentages.

The normalized rate is unavailable when the applicable potential denominator is zero or when the v1 nonnegative-point requirements are not satisfied. Butler keeps the underlying raw points-gap evidence visible rather than fabricating a percentage.

Lineup capture remains descriptive retrospective evidence. It is not manager efficiency, coaching efficiency, decision quality, start/sit skill, or proof of fault or intent. The full normative method is in [`lineup-capture-methodology.md`](lineup-capture-methodology.md).

## Pairwise lineup-capture contrast

Butler does not subtract two independently scoped team-season capture rates because the teams may have different comparable week sets.

The governed pairwise command instead derives both teams from their team-season points-gap reports and forms one shared week universe:

```text
shared comparable weeks =
    Team A COMPARABLE_COMPLETE weeks
    INTERSECT
    Team B COMPARABLE_COMPLETE weeks
```

Both teams' started/potential totals and capture rates are recalculated over that exact shared week set. Only then may Butler expose a signed descriptive contrast:

```text
Team A minus Team B contrast =
    Team A shared-week capture rate
    -
    Team B shared-week capture rate
```

The output preserves:

- ordered shared comparable weeks;
- Team A-only comparable weeks;
- Team B-only comparable weeks;
- each team's observed and individually comparable counts;
- both teams' raw shared started/potential/gap totals;
- both shared-week capture rates when available; and
- the optional signed rate/percentage-point contrast.

The pairwise contrast is still retrospective evidence, not a winner or manager-quality judgment. Shared calendar weeks do not reconstruct historical player startability. The contrast must not be turned into a league ranking, manager grade, tier, recommendation, causal claim, skill estimate, or fault assignment.

The normative pairwise rules are in [`lineup-capture-comparability-methodology.md`](lineup-capture-comparability-methodology.md).

## Team-season week universe

Team-season evidence uses only persisted Sleeper roster weeks as its observed week universe. Butler does not fabricate unobserved weeks to make a season look complete.

Every observed roster week remains visible in one of four states:

- `COMPARABLE_COMPLETE` — both governed lineups are complete and the points gap is available;
- `POTENTIAL_INCOMPLETE` — the governed potential lineup cannot fill every required starting slot;
- `STARTED_INCOMPLETE` — the persisted observed starting lineup contains one or more empty slots;
- `BLOCKED` — required evidence is unavailable, inconsistent, moved across provenance boundaries, or otherwise unsafe to compare.

Only `COMPARABLE_COMPLETE` weeks contribute to team-season started-point, potential-point, points-gap, and lineup-capture source totals.

The denominator is always explicit. For example, `4 comparable complete observed weeks out of 7 observed weeks` means exactly that. Butler does not silently convert that into a seven-week estimate.

Coverage remains separate from lineup capture. Butler does not penalize, multiply, or blend missing coverage into the normalized rate.

## League-season presentation

League-season lineup evidence is a neutral wrapper around each team's governed team-season evidence.

Teams are presented in repository team-name order, not score or capture-rate order. Each team's observed/comparable denominator remains separate.

Butler does not compute a league-wide:

- started-points total;
- potential-points total;
- points-gap total;
- average points gap;
- average lineup capture rate;
- combined league lineup capture rate;
- comparison score;
- rank or tier.

Pairwise contrast does not change this league-season boundary. Butler does not generate every pairwise contrast and convert those differences into a ranking table.

## Identity-covered zero versus missing evidence

A player may legitimately have governed identity coverage for a week without a production row and therefore receive an authorized zero under the production-coverage policy. That is different from missing evidence and different from Sleeper starter `"0"`.

Butler preserves those distinctions so zero production is not confused with an empty slot or an evidence gap.

## What this evidence does not establish

The governed lineup evidence stack does not by itself establish:

- manager efficiency;
- manager quality or skill;
- intent or fault;
- historical startability beyond the evidence Butler actually persisted;
- a fair league-wide manager ranking;
- transitive manager quality from pairwise contrasts;
- statistical confidence or stability of a pairwise difference;
- a recommendation about how a manager should have acted.

The approved lineup-capture and pairwise-comparability methodologies define narrow descriptive evidence while preserving these attribution limits. Any future metric that turns pairwise contrast into a manager score, ranking, tier, recommendation, coverage-adjusted composite, statistical confidence claim, causal interpretation, or skill/fault claim requires a new governed methodology decision before implementation.
