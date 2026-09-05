# Governed lineup capture methodology

Butler may normalize governed lineup evidence into a **lineup capture rate** only as descriptive retrospective evidence. This methodology deliberately rejects the stronger label **manager efficiency** because the evidence stack does not reconstruct every historical availability condition and does not establish manager intent, fault, or skill.

## Methodology status

This document is normative for the first lineup-capture implementation.

The v1 methodology permits:

- a team-week lineup capture rate for a complete governed comparison;
- a team-season lineup capture rate over comparable complete observed roster weeks; and
- neutral league-season presentation of each team's independently scoped capture evidence.

The v1 methodology does **not** permit:

- a manager-efficiency score;
- a manager grade;
- a manager rank or tier;
- a cross-team lineup-capture ranking;
- a league-wide average or aggregate capture rate;
- a recommendation, intent inference, fault assignment, or skill estimate.

## Naming and interpretation boundary

The governed term is **lineup capture rate**.

It means only:

> the share of governed retrospective potential points represented by the recalculated observed started-lineup points inside the same comparable evidence boundary.

It must not be renamed or summarized as `manager efficiency`, `coaching efficiency`, `decision quality`, `start/sit skill`, or another phrase that attributes the result to a person's ability or intent.

Potential lineup evidence remains retrospective under the observed provider configuration. It is **not reconstructed historical startability**. Injuries, late scratches, transaction timing, platform locks, real-world availability, and other historical conditions are not assumed unless governed evidence explicitly represents them.

## Source evidence

Lineup capture must be derived from the existing governed points-gap reports rather than independently rescoring players or rebuilding lineups.

For a team-week rate, the source of truth is the governed complete team-week lineup points-gap evidence. The source report already proves that:

- potential and started lineups are complete;
- both use the same league/team/season/week identity;
- both use the same dated configuration, roster, production, scoring, solver, and eligibility boundary; and
- recalculated started points do not exceed retrospective potential points.

For a team-season rate, the source of truth is the governed team-season lineup points-gap evidence. Only nested weeks in `COMPARABLE_COMPLETE` state may contribute.

An implementation must not silently widen the source week universe or repair blocked/incomplete evidence to manufacture a rate.

## Team-week formula

A team-week lineup capture rate is available only when the governed team-week points-gap report exists and retrospective potential points are greater than zero.

The formula is:

```text
lineup capture rate = recalculated started points / retrospective potential points
```

The numerator and denominator are the authoritative evidence. The normalized rate is derived from them.

If retrospective potential points are exactly zero, the rate is **unavailable**. Butler must not report `100%`, `0%`, or another fabricated value for a `0 / 0` comparison.

Because the source comparison is complete, same-provenance, and governed by the maximizing potential-lineup solver, the valid v1 rate domain is:

```text
0 <= lineup capture rate <= 1
```

A result outside that range is an invariant failure and must fail closed.

## Team-season formula

A team-season lineup capture rate uses only `COMPARABLE_COMPLETE` observed roster weeks from the governed team-season points-gap report.

The formula is:

```text
season lineup capture rate =
    total recalculated started points over comparable complete weeks
    /
    total retrospective potential points over comparable complete weeks
```

The season rate is **not** the arithmetic mean of weekly percentages.

Using the ratio of governed totals preserves the actual point opportunity represented by each comparable week. A 40-point potential week therefore contributes more point opportunity than a 20-point potential week without introducing a separate hidden weighting model.

A season rate is available only when:

- there is at least one `COMPARABLE_COMPLETE` observed roster week; and
- comparable total retrospective potential points are greater than zero.

If either condition is not met, the normalized rate is unavailable. Butler retains the underlying week states and raw evidence instead of inventing a percentage.

## Coverage remains separate from the rate

Evidence coverage and lineup capture are different facts.

Every team-season capture report must preserve and expose, at minimum:

- observed roster weeks;
- blocked weeks;
- potential-incomplete weeks;
- started-incomplete weeks;
- comparable-complete weeks;
- comparable total recalculated started points;
- comparable total retrospective potential points;
- comparable total points gap; and
- the optional lineup capture rate.

V1 does **not** impose an arbitrary minimum comparable-week threshold before publishing descriptive capture evidence. A one-week rate may be mathematically valid but has only one week of coverage; the explicit denominator must remain visible so the output cannot masquerade as fuller-season evidence.

Coverage must not be folded into, multiplied by, penalized into, or otherwise blended with the lineup capture rate. Doing so would create a new composite score requiring a separate methodology.

## Precision and deterministic calculation

The authoritative values remain the governed started-point and potential-point totals.

When a normalized rate is materialized in an evidence report, v1 uses:

```text
scale: 6 decimal places
rounding: HALF_UP
```

For example, an exact fraction of `30 / 36` is materialized as `0.833333`.

CLI presentation may render that rate as a percentage with two decimal places using `HALF_UP`, for example `83.33%`, while retaining the raw numerator and denominator in the same output.

No implementation may compare or rank teams using the rounded CLI percentage.

## Relationship to points gap

Points gap remains the primary descriptive difference:

```text
points gap = retrospective potential points - recalculated started points
```

Lineup capture rate is a normalization of the same governed comparison:

```text
lineup capture rate = recalculated started points / retrospective potential points
```

V1 does not add a separate `miss rate`, `loss rate`, `efficiency loss`, or similar percentage. Such fields would be mathematically redundant and would encourage evaluative framing without adding evidence.

## League-season presentation

League-season lineup capture may expose each team's governed team-season capture evidence in the same neutral repository team-name order used by the existing league-season lineup evidence wrapper.

Each team's coverage denominator remains separate.

V1 explicitly prohibits league-season:

- sorting by lineup capture rate;
- assigning ordinal ranks;
- assigning capture tiers;
- averaging team capture rates;
- summing team numerators and denominators into a league capture rate; or
- declaring one manager better or worse from lineup capture evidence.

Even when two teams have the same observed/comparable week counts, the historical-startability limitation remains. Equal coverage counts do not prove equal real-world decision opportunity.

## Proposed policy identifiers

Implementations following this methodology should use stable policy identifiers that make the non-attribution boundary visible:

```text
team-week-lineup-capture-evidence-v1-complete-gap-source-started-over-potential-no-attribution
team-season-lineup-capture-evidence-v1-comparable-complete-total-ratio-no-attribution
league-season-lineup-capture-evidence-v1-team-name-order-no-ranking-no-cross-team-aggregate
```

The metric scope should describe retrospective lineup capture and must not contain `manager efficiency`, `manager performance`, `skill`, or equivalent attribution language.

## Required fail-closed behavior

A lineup-capture implementation must fail closed rather than publish a normalized rate when any of the following occurs:

- the governed source points-gap evidence is unavailable;
- a team-week source comparison is incomplete;
- a team-season has no comparable complete observed week;
- the applicable potential-point denominator is zero;
- started points are negative;
- potential points are negative;
- started points exceed potential points;
- the normalized rate would fall outside `[0, 1]`;
- source identity or provenance differs from the governed points-gap report; or
- an implementation would need to infer, repair, or reconstruct missing historical availability evidence.

The underlying governed source evidence remains inspectable when a normalized rate is unavailable.

## What v1 can defend

V1 can defend statements such as:

- `The recalculated started lineup captured 83.33% of the governed retrospective potential points for this complete observed week.`
- `Across 6 comparable complete observed roster weeks out of 9 observed weeks, the recalculated started lineups captured 91.20% of governed retrospective potential points.`

Those statements must remain accompanied by the historical-startability and coverage boundary where the broader context is presented.

## What v1 cannot defend

V1 cannot defend statements such as:

- `This manager is 91.20% efficient.`
- `This manager is better at setting lineups than another manager.`
- `The missed points were the manager's fault.`
- `The manager should have known to start the potential lineup.`
- `A higher lineup capture rate proves greater fantasy-football skill.`
- `This manager ranks third in the league at lineup management.`

Those claims require evidence and causal/interpretive assumptions that the governed lineup stack does not establish.

## Implementation sequence authorized by this methodology

After this specification is accepted, the defensible implementation path is:

1. team-week lineup capture evidence derived only from the governed team-week points-gap report;
2. CLI exposure of that team-week evidence;
3. team-season lineup capture evidence derived only from the governed team-season points-gap report;
4. CLI exposure with explicit week-state coverage;
5. neutral league-season wrapper with separate team denominators; and
6. neutral league-season CLI in repository team-name order.

The sequence must stop again before any manager-efficiency label, cross-team ranking, tier, recommendation, composite coverage adjustment, or skill/fault attribution is introduced.
