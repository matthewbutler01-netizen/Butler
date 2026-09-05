# Governed lineup evidence

Butler's lineup evidence surfaces are retrospective evidence tools. They recalculate what can be supported from persisted Sleeper roster/configuration observations and governed nflverse production evidence without turning the result into a manager grade or skill claim.

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
butler league season-lineup-capture-common-universe-evidence <league-id> <season>
butler league season-lineup-capture-ranking-evidence <league-id> <season>
butler league season-lineup-capture-ranking-stability-evidence <league-id> <season>
butler league season-lineup-capture-ranking-sensitivity-classification-evidence <league-id> <season>
butler league season-lineup-capture-ranking-change-frequency-evidence <league-id> <season>
```

## Evidence chain

The lineup stack intentionally separates evidence collection, scoring, legal-lineup calculation, aggregation, normalization, comparison, ranking, deterministic sensitivity analysis, qualitative movement classification, change-frequency measurement, and presentation.

For a team-week comparison, Butler requires the same governed evidence boundary for both sides of the comparison:

- the persisted Sleeper league configuration observation used for starting slots and scoring settings;
- the exact persisted Sleeper team-week roster snapshot;
- dated Sleeper fantasy-position eligibility evidence;
- dated nflverse week-production coverage and production evidence; and
- the same scoring and lineup-eligibility policies.

If governed evidence moves while a season comparison is being assembled, Butler blocks that week instead of combining evidence from different snapshots.

## Started lineup semantics

The started-lineup analyzer uses the exact ordered Sleeper starter snapshot. It does not reconstruct a lineup from final scores.

Sleeper's literal starter value `"0"` is an explicit empty starting slot. Butler preserves it as empty. It is never treated as a player identifier, a zero-point player, or an identity-covered player with no production row.

A nonzero starter must belong to the exact persisted team-week roster and must be eligible for the corresponding ordered starting slot. Duplicate starters, starter/slot-count mismatches, and invalid slot eligibility fail closed rather than being guessed around.

## Potential lineup semantics

Potential-lineup evidence solves for the highest-scoring legal lineup supported by observed provider configuration and governed production evidence.

This is a retrospective calculation under the configuration Butler observed. It is **not reconstructed historical startability**. Butler does not claim that every real-world status, injury designation, transaction timing detail, platform lock, or other historical availability condition has been recreated unless that condition is explicitly represented by governed evidence.

## Team-week points gap

A team-week points gap is available only when both the governed potential lineup and observed started lineup are complete under the same evidence boundary.

```text
points gap = retrospective potential points - recalculated started points
```

The gap is descriptive evidence. It is not a manager-efficiency percentage, manager score, fault assignment, or skill estimate. Incomplete lineups do not receive a partial, normalized, or extrapolated gap.

## Lineup capture normalization

Lineup capture is the v1 normalization approved for this evidence stack. It is derived from governed points-gap evidence rather than independently rescoring players or rebuilding lineups.

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

The normalized rate is unavailable when the applicable potential denominator is zero or when v1 nonnegative-point requirements are not satisfied. Butler keeps the underlying raw points-gap evidence visible rather than fabricating a percentage.

Lineup capture remains descriptive retrospective evidence. It is not manager efficiency, coaching efficiency, decision quality, start/sit skill, or proof of fault or intent. The full normative method is in [`lineup-capture-methodology.md`](lineup-capture-methodology.md).

## Pairwise lineup-capture contrast

Butler does not subtract two independently scoped team-season capture rates because teams may have different comparable week sets.

The governed pairwise command derives both teams from their team-season points-gap reports and forms one shared week universe:

```text
shared comparable weeks =
    Team A COMPARABLE_COMPLETE weeks
    INTERSECT
    Team B COMPARABLE_COMPLETE weeks
```

Both teams' started/potential totals and capture rates are recalculated over that exact shared week set. Only then may Butler expose a signed descriptive contrast.

The pairwise contrast remains retrospective evidence, not a winner or manager-quality judgment. Shared calendar weeks do not reconstruct historical player startability. Pairwise evidence is not used as a secondary ranking tie-breaker.

The normative pairwise rules are in [`lineup-capture-comparability-methodology.md`](lineup-capture-comparability-methodology.md).

## League common-universe lineup-capture table

The governed league common-universe command removes the mismatched-week-set problem from a neutral league-wide presentation.

Butler derives every row directly from each repository team's governed team-season points-gap report and forms one all-team week universe:

```text
league common comparable weeks =
    Team 1 COMPARABLE_COMPLETE weeks
    INTERSECT
    Team 2 COMPARABLE_COMPLETE weeks
    INTERSECT
    ...
    INTERSECT
    every repository team's COMPARABLE_COMPLETE weeks
```

A team is never dropped merely to widen that intersection. A week comparable for all but one repository team contributes to none of the normalized common-universe rows.

For each team, Butler recalculates raw started points, retrospective potential points, points gap, and optional lineup-capture rate over the exact same common week list. The row also preserves broader observed count, individually comparable count, and individually comparable weeks excluded from the league common universe.

If fewer than two repository teams exist, or if the all-team intersection contains no comparable complete week, normalized league comparison is unavailable. Butler does not fall back to independently scoped season rates.

The **common-universe table itself remains neutral**: rows stay in repository team-name order and it contains no rank, tier, percentile, winner, league average/median, benchmark difference, pairwise matrix, Elo-like score, or manager grade.

The normative all-team rules are in [`league-lineup-capture-common-universe-methodology.md`](league-lineup-capture-common-universe-methodology.md).

## League common-universe lineup-capture ranking

The separate ranking surface may assign **lineup-capture ranks** only from the governed common-universe report. It does not rebuild evidence or rank independently scoped season rates.

A ranking is published only when at least two repository teams exist, the common-universe source is available, at least **4 common comparable weeks** exist, and every repository team has an available normalized common-universe lineup-capture rate.

If any repository team is unavailable, Butler withholds the **entire** ranking. It does not drop that team, assign it last place, impute a rate, or publish a partial leaderboard.

Rank assignment uses only the governed six-decimal common-universe lineup-capture rate. Higher rate receives the better ordinal position. Exact ties at that governed precision use standard competition ranking (`1, 2, 2, 4`). No secondary metric breaks a tie.

Every ranked row retains the common started/potential/gap totals, common-week denominator, observed coverage, individually comparable coverage, and individually comparable weeks excluded from common. The nested common-universe source remains inspectable in neutral repository team-name order.

The four-week minimum is a **governance floor**, not a statistical-confidence or significance claim. A lineup-capture rank is a rank of the governed retrospective metric, **not a manager rank** and not evidence of manager efficiency, quality, skill, fault, intent, or decision quality.

The normative ranking rules are in [`league-lineup-capture-ranking-methodology.md`](league-lineup-capture-ranking-methodology.md).

## League lineup-capture ranking stability

The separate ranking-stability surface evaluates deterministic sensitivity of an already-governed available lineup-capture ranking to removing one contributing common comparable week at a time.

V1 uses leave-one-common-week-out sensitivity only. It requires at least **5 baseline common comparable weeks** so every perturbation still retains the BF-500 four-week ranking floor.

For baseline common weeks `W = [w1 ... wn]`, Butler creates exactly `n` scenarios. Scenario `i` deliberately omits `wi`, retains every other baseline common week, recalculates every repository team's started/potential/gap totals and six-decimal lineup-capture rate from nested governed weekly source evidence, and reapplies the same competition-ranking policy.

The baseline common-universe report is not mutated or fabricated to make the omission look like an evidence gap. The omitted week is a sensitivity perturbation, not a reclassification of source comparability.

Every perturbation keeps the exact baseline team universe. Butler does not drop an unavailable team or skip a difficult omitted-week scenario. If any required perturbation cannot produce an available normalized rate for every baseline team, the **entire stability summary is unavailable**.

When all perturbations are available, Butler may expose deterministic team-level summaries including baseline rank/rate, distinct observed perturbation ranks, best and worst perturbation ranks, rank-range width, maximum absolute movement from baseline, unchanged/changed scenario counts, perturbation rate range, and maximum absolute rate movement.

These are sensitivity observations, **not confidence intervals or probability statements**. BF-504 does not itself create qualitative classes and does not create a stability-adjusted replacement rank, average perturbation rank, league stability score, manager-consistency score, or stability leaderboard.

The baseline BF-500 lineup-capture rank remains authoritative for the full common-week set. Stability evidence answers only how that governed metric ordering responds to specified one-week omissions.

Leave-one-week-out sensitivity does not repair historical-startability limitations and does not establish manager consistency, reliability, skill, quality, fault, intent, or causal decision quality.

The normative stability rules are in [`league-lineup-capture-ranking-stability-methodology.md`](league-lineup-capture-ranking-stability-methodology.md).

## League lineup-capture rank sensitivity classification

The separate BF-508 classification surface may attach one deterministic qualitative label to each team only when the complete BF-504 ranking-stability artifact is `AVAILABLE`.

The classification input is **only** BF-504's maximum absolute rank movement from the BF-500 baseline rank across every required leave-one-common-week-out scenario:

```text
maximum absolute rank movement = 0  -> LOW_SENSITIVITY
maximum absolute rank movement = 1  -> MODERATE_SENSITIVITY
maximum absolute rank movement >= 2 -> HIGH_SENSITIVITY
```

These are **observed rank-sensitivity classes**. They summarize how much the governed ordinal artifact moved in the completed perturbation set. They are not confidence levels, probability estimates, predictions, manager-consistency labels, manager-reliability grades, or manager-quality tiers.

The BF-508 classifier does not use movement frequency, rate movement, raw points, coverage, team-count normalization, baseline rank position, pairwise contrast, or manager identity. Those fields may remain visible as evidence context but cannot upgrade or downgrade the class.

Competition-ranking ties remain exactly as governed by BF-500/BF-504. BF-508 does not compress ranks, switch to dense ranking, or reinterpret movement caused by a tie appearing or disappearing.

If source stability is unavailable, Butler withholds the **entire** qualitative classification. It does not classify only teams or perturbations that happen to be available.

The BF-500 baseline rank remains authoritative. Sensitivity class cannot create a rank penalty, rank bonus, confidence-weighted rank, consensus rank, or other stability-adjusted replacement rank.

Sensitivity class is not an independent ranking key. Butler does not sort teams into a sensitivity leaderboard, calculate a league sensitivity score or percentile, or claim that a low-sensitivity team is better managed than a high-sensitivity team.

The normative classification rules are in [`league-lineup-capture-ranking-sensitivity-classification-methodology.md`](league-lineup-capture-ranking-sensitivity-classification-methodology.md).

## League lineup-capture rank-change frequency

BF-512 adds a separate deterministic frequency dimension derived from the **same complete BF-504 stability artifact**. It answers how often a team's governed lineup-capture rank differed from its BF-500 baseline rank across the required leave-one-common-week-out perturbations.

For team `T`:

```text
rank-change frequency(T) =
    baseline-rank changed scenarios(T)
    /
    complete perturbation scenario count(T)

rank-retention frequency(T) =
    baseline-rank unchanged scenarios(T)
    /
    complete perturbation scenario count(T)
```

Both frequencies are materialized at six decimal places using `HALF_UP`; the two values must sum to `1.000000`. The CLI may show two-decimal percentages, but the governed six-decimal values remain authoritative.

The denominator is the complete required BF-504 perturbation count. Butler does not drop or skip scenarios to make the frequency look better or worse. If source ranking-stability evidence is unavailable, the entire BF-512 frequency surface is unavailable and no partial team rows are published.

BF-508 magnitude and BF-512 frequency remain **separate evidence dimensions**. For example, a team may be `HIGH_SENSITIVITY` because one omission moved its rank by two positions while its change frequency is only `0.200000` because that happened in 1 of 5 scenarios. The frequency does not alter the BF-508 class, and the magnitude class does not alter the frequency.

Observed change frequency is **not probability or confidence**. `1 of 5 = 0.200000` means only one of five governed deterministic omissions changed the rank. It does not mean a 20% probability the baseline rank is wrong, 80% confidence in that rank, or a 20% chance the rank changes in the future.

BF-512 defines no qualitative frequency tier and no combined magnitude-frequency score or matrix. Frequency is not an independent ranking key and cannot produce a frequency-adjusted rank, sensitivity leaderboard, manager-consistency label, manager-reliability label, league-wide sensitivity score, or person-level quality judgment.

The BF-500 baseline lineup-capture rank remains authoritative.

The normative frequency rules are in [`league-lineup-capture-ranking-change-frequency-methodology.md`](league-lineup-capture-ranking-change-frequency-methodology.md).

## Team-season week universe

Team-season evidence uses only persisted Sleeper roster weeks as its observed week universe. Butler does not fabricate unobserved weeks to make a season look complete.

Every observed roster week remains visible in one of four states:

- `COMPARABLE_COMPLETE` — both governed lineups are complete and the points gap is available;
- `POTENTIAL_INCOMPLETE` — the governed potential lineup cannot fill every required starting slot;
- `STARTED_INCOMPLETE` — the persisted observed starting lineup contains one or more empty slots; and
- `BLOCKED` — required evidence is unavailable, inconsistent, moved across provenance boundaries, or otherwise unsafe to compare.

Only `COMPARABLE_COMPLETE` weeks contribute to team-season started-point, potential-point, points-gap, and lineup-capture source totals.

Coverage remains separate from lineup capture. Butler does not penalize, multiply, or blend missing coverage into the normalized rate.

## League-season presentation

League-season lineup evidence now has six distinct presentation layers:

1. independently scoped team evidence in repository team-name order;
2. the neutral all-team common-universe table in repository team-name order;
3. the separate governed lineup-capture ranking surface, available only under BF-500 prerequisites;
4. deterministic leave-one-common-week-out ranking-stability evidence, available only under BF-504 prerequisites;
5. observed maximum-movement rank-sensitivity classification derived from the complete BF-504 artifact under BF-508 rules; and
6. observed changed-over-total rank-change frequency derived from the complete BF-504 artifact under BF-512 rules.

The ranking surface changes presentation order only for the authorized common-universe lineup-capture metric. Stability, magnitude classification, and change frequency do not replace or revise the BF-500 baseline rank.

Butler still does not compute a league-wide average/median lineup-capture benchmark, combined league capture rate, percentile, pairwise matrix, Elo-like score, manager grade, composite manager score, league-wide stability/sensitivity score, probabilistic rank confidence, frequency-adjusted rank, or combined magnitude-frequency score.

## Identity-covered zero versus missing evidence

A player may legitimately have governed identity coverage for a week without a production row and therefore receive an authorized zero under the production-coverage policy. That is different from missing evidence and different from Sleeper starter `"0"`.

Butler preserves those distinctions so zero production is not confused with an empty slot or evidence gap.

## What this evidence does not establish

The governed lineup evidence stack does not establish:

- manager efficiency;
- manager quality, consistency, reliability, or skill;
- intent or fault;
- historical startability beyond the evidence Butler actually persisted;
- a causal manager ranking;
- that rank 1 identifies the best manager;
- that adjacent lineup-capture ranks represent a meaningful skill difference;
- statistical confidence, significance, probability, or predictive stability of the ordinal positions;
- that observed rank-change frequency estimates future rank-change probability;
- a manager grade, manager tier, manager percentile, or manager-stability label;
- a league benchmark-relative manager score or league stability/sensitivity score;
- a stability-adjusted, frequency-adjusted, or confidence-adjusted replacement rank; or
- a recommendation about how a manager should have acted.

The governed lineup-capture rank is an ordinal presentation of one governed retrospective metric over one common evidence universe. BF-504 adds deterministic sensitivity evidence around that rank. BF-508 may classify **observed maximum ordinal movement** as low, moderate, or high sensitivity. BF-512 separately reports **how often** the rank changed across the complete deterministic perturbation set. Neither dimension is statistical confidence or person-level evaluation, and v1 does not combine them.

Any future step that adds qualitative frequency tiers, combined magnitude-frequency scores or matrices, manager consistency/reliability labels, confidence intervals or probability claims, predictive modeling, frequency/stability-adjusted ranks, sensitivity leaderboards, league-wide sensitivity scores, recommendations, causal interpretation, skill/fault attribution, coverage-adjusted composites, or cross-league manager comparison requires a new governed methodology decision before implementation.
