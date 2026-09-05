# Governed historical lineup-capture rank-sensitivity calibration methodology

Butler may audit whether existing deterministic rank-sensitivity diagnostics have any empirically supportable relationship with later **out-of-window lineup-capture rank persistence**.

This methodology is deliberately narrower than a confidence model. It governs a historical corpus audit only. It does **not** authorize qualitative frequency thresholds, probabilities, confidence scores, manager grades, adjusted ranks, or predictive manager evaluation.

## Methodology status

**Implemented v1.**

The governed decision remains:

```text
HISTORICAL_CALIBRATION_AUDIT_AUTHORIZED
CALIBRATED_THRESHOLDS_NOT_AUTHORIZED
```

Implemented surfaces:

- **BF-518** — `LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer`
- **BF-519** — `butler league lineup-capture-ranking-sensitivity-calibration-corpus-audit <start-season> <end-season>`
- **BF-520** — global help and durable documentation closeout

The implementation audits historical temporal-holdout evidence and corpus breadth. It does not fit or publish LOW/MODERATE/HIGH frequency bands, a magnitude-frequency composite, a probability model, a confidence score, or any manager-level evaluation.

## Policy identifier

The implemented policy identifier is:

```text
league-season-lineup-capture-ranking-sensitivity-calibration-audit-v1-temporal-disjoint-baseline-min5-holdout-min4-no-thresholds-no-confidence-no-manager-attribution
```

The metric scope is historical temporal-holdout audit of the governed lineup-capture ordinal metric, with no threshold fitting, statistical-confidence claim, probability claim, or manager attribution.

## Calibration question

The only authorized question is:

> when Butler computes governed BF-504/BF-508/BF-512 sensitivity diagnostics from an earlier common-week window, how do those diagnostics relate to lineup-capture ordinal movement in a strictly later common-week holdout window?

The subject is persistence of a governed retrospective metric ordering. It is **not** the probability that a rank is true or false, manager consistency or reliability, manager skill, coaching quality, causal responsibility, or reconstructed historical startability.

## No “true rank” target

A later rank is not ground truth.

The future holdout rank reflects a different temporal window and may legitimately differ because team results, player performance, roster construction, injuries, transactions, or other observed conditions changed.

Permitted terms include:

- **future holdout rank**;
- **future-only holdout rank**;
- **out-of-window rank**;
- **temporal rank persistence**;
- **temporal ordinal displacement**; and
- **historical calibration audit**.

The later rank must not be called true rank, correct rank, corrected rank, real rank, latent rank, or manager-quality rank. A future rank change does not prove the earlier governed rank was wrong.

## Required governed source stack

Historical audit inputs are derived from the same governed lineup-capture evidence stack used by production surfaces:

1. governed team-week points-gap evidence;
2. governed all-team common-universe evidence;
3. BF-500 common-universe lineup-capture ranking semantics;
4. BF-504 leave-one-common-week-out stability semantics;
5. BF-508 maximum-movement sensitivity classification; and
6. BF-512 raw rank-change frequency semantics.

The implementation does not substitute independently scoped season rates, pairwise contrasts, reconstructed standings, platform points, or external manager grades.

## Historical calibration unit

The fundamental audit unit is one governed:

```text
league-season-cutoff
```

A unit contains one persisted league-season, one deterministic cutoff between earlier and later common weeks, one baseline common-week window, one strictly later future-only holdout window, the complete repository team universe, and the governed evidence required to reproduce both windows.

Multiple cutoffs from one league-season are correlated observations. Team-cutoff rows are not presented as independent samples.

## Implemented historical scope

BF-518 accepts a requested inclusive season range and enumerates persisted leagues through the repository. Persisted leagues without season metadata are counted separately rather than assigned a guessed season.

For each persisted league whose season is in the requested range, Butler derives the governed all-team common comparable-week universe and audits every boundary between ordered common weeks.

Source evidence that cannot safely produce the governed league-season source is preserved as a source failure instead of becoming a synthetic calibration row.

## Baseline window requirements

The earlier baseline window requires at least **5 common comparable weeks**.

This inherits BF-504's stability floor. The baseline window must be capable of producing:

- baseline competition rank under BF-500 semantics;
- baseline six-decimal lineup-capture rate;
- BF-504-style maximum absolute rank movement;
- changed and unchanged leave-one-week-out scenario counts;
- BF-508 sensitivity class; and
- BF-512 six-decimal rank-change frequency.

All baseline diagnostics are recomputed from baseline weeks only. Future holdout production, rates, or ranks do not feed baseline features.

## Future-only holdout requirements

The later holdout window requires at least **4 common comparable weeks**.

This inherits the BF-500 ranking floor. Holdout rank and rate are recalculated from the strictly later common weeks alone. Baseline weeks do not overlap the holdout window.

The implementation therefore does not manufacture persistence by allowing baseline observations to remain in the future outcome.

## Same team universe

Baseline and holdout windows preserve the same complete repository team universe inherited from the governed all-team source.

Butler does not drop a team, impute a rate, assign an unavailable team last place, or publish a partial team result to make a cutoff usable.

An excluded cutoff publishes no partial team calibration rows.

## Temporal provenance and configuration compatibility

Historical audit evidence naturally has dated roster and production observations. Dates need not be identical across earlier and later weeks.

However, a cutoff must preserve compatible governed semantics across the evaluated baseline and holdout period. The implemented compatibility guard requires consistent:

- league-configuration observation identity for the evaluated common weeks;
- governed scoring-policy version;
- governed solver-policy version;
- governed eligibility-policy version; and
- starting-slot count.

A material configuration incompatibility excludes the cutoff rather than being normalized away.

Within each common week, the pre-existing common-universe source still enforces the governed cross-team evidence boundary.

## Implemented cutoff states

BF-518 preserves explicit cutoff states:

```text
AVAILABLE
EXCLUDED_BASELINE_BELOW_STABILITY_FLOOR
EXCLUDED_HOLDOUT_BELOW_RANKING_FLOOR
EXCLUDED_CONFIGURATION_INCOMPATIBLE
EXCLUDED_BASELINE_RANKING_UNAVAILABLE
EXCLUDED_BASELINE_STABILITY_UNAVAILABLE
EXCLUDED_HOLDOUT_RANKING_UNAVAILABLE
```

League-season audit states are:

```text
AVAILABLE_CALIBRATION_CUTOFFS
AUDITED_NO_AVAILABLE_CUTOFFS
EXCLUDED_COMMON_UNIVERSE_UNAVAILABLE
```

Source analysis failures are preserved separately as `SOURCE_EVIDENCE_UNAVAILABLE`.

The audit does not widen evidence, reduce a denominator, drop a team, or reuse independently scoped season evidence to convert an exclusion into an available cutoff.

## Authorized team-level historical outcomes

For each team in an available cutoff, BF-518 materializes:

- baseline rank;
- baseline six-decimal lineup-capture rate;
- baseline common-week count;
- baseline maximum absolute rank movement;
- baseline unchanged perturbation count;
- baseline changed perturbation count;
- baseline six-decimal rank-change frequency;
- baseline BF-508 sensitivity class;
- future-only holdout rank;
- future-only holdout six-decimal lineup-capture rate;
- future-only holdout common-week count;
- signed temporal rank displacement;
- absolute temporal rank displacement; and
- exact numeric rank-retained boolean.

The displacement equations are:

```text
signed temporal rank displacement = future holdout rank - baseline rank
absolute temporal rank displacement = abs(signed temporal rank displacement)
exact numeric rank retained = baseline rank == future holdout rank
```

Competition-ranking ties remain governed by BF-500 semantics. A team at rank `1` in both windows has zero numeric displacement even if tie composition changes. BF-517 does not authorize a secondary tie-distance metric.

## Critical observed distinction

The BF-518 test corpus deliberately demonstrates that:

```text
baseline BF-508 class = LOW_SENSITIVITY
baseline changed scenarios = 0 of 5
baseline BF-512 frequency = 0.000000
future-only holdout absolute rank displacement = 1
```

This is an important governance boundary. Deterministic leave-one-week-out stability does not become statistical confidence merely because historical holdout evidence is available.

A baseline that does not move under its required one-week omissions can still have a different ordinal position in a later disjoint window.

## Corpus breadth audit

BF-518 exposes breadth rather than one misleading headline sample size. The report includes:

- requested league-season count;
- audited league-season count;
- source-failure league-season count;
- available cutoff count;
- excluded cutoff count;
- available team-cutoff row count;
- repository team-count distribution;
- baseline common-week-count distribution;
- future holdout common-week-count distribution;
- baseline perturbation-denominator distribution;
- BF-508 sensitivity-class counts; and
- cutoff-state counts.

BF-519 renders these distributions and the underlying league-season/cutoff states.

## Denominator dependence remains explicit

BF-516 established that raw rank-change frequency is denominator dependent. BF-518 therefore retains baseline common-week count, changed count, unchanged count, and six-decimal frequency together.

Values such as `1/5` and `2/10` are not silently promoted into one qualitative semantic class merely because both equal `0.200000`.

No qualitative frequency threshold is fitted in v1.

## League-size and cutoff dependence remain explicit

Ordinal movement has different possible ranges in different league sizes. BF-518 retains repository team-count breadth and does not introduce a universal normalized displacement score.

Multiple cutoffs in one league-season share teams and underlying history. BF-519 explicitly warns that team-cutoff rows are correlated and are not automatically an independent sample size.

## Leakage controls

The implemented audit keeps baseline and holdout windows temporally disjoint:

- baseline features use earlier common weeks only;
- future outcomes use later common weeks only;
- full-season aggregates do not feed baseline features;
- future production does not feed baseline lineup calculations; and
- the later holdout rank is not copied backward as a baseline label.

Any future model-fitting workflow must preserve league-season or broader clustering and may not randomly split correlated team-cutoff rows across development and validation sets as if independent.

BF-517 does not authorize a train/validation/test percentage.

## No arbitrary minimum corpus size

V1 deliberately declares no rule such as `N=30`, `N=100`, or a minimum team-cutoff row count as sufficient for calibration.

The corpus audit exists to reveal actual historical breadth first. A later governed methodology may decide what season diversity, league diversity, league-size breadth, denominator breadth, cutoff breadth, and holdout coverage are required before a threshold study is even permitted.

Until that decision is made, calibrated categories remain unavailable.

## Historical corpus remains read-only evidence

BF-518 and BF-519 do not mutate production ranking artifacts, teams, roster history, league settings, or persisted source evidence.

The audit is a derived read-only evidence surface.

## No external manager labels

The audit does not import subjective manager grades, expert ratings, win-loss records, championships, standings finish, transaction activity, or user feedback as calibration truth.

The historical outcome remains only later out-of-window movement of the same governed lineup-capture ordinal metric.

## No probability or confidence semantics

The implemented audit does not authorize statements such as:

- `20% rank-change frequency means 80% confidence in the baseline rank`;
- `HIGH_SENSITIVITY implies a 60% chance of future movement`;
- `this rank is statistically reliable`;
- `this manager is predictable`; or
- `the true rank is probably 2`.

A probability methodology would require additional statistical assumptions, calibration tests, uncertainty evaluation, and genuinely out-of-sample validation that are not authorized here.

## No threshold fitting in v1

The following remain prohibited after BF-520:

- qualitative BF-512 frequency bands;
- a magnitude-by-frequency matrix or score;
- a scalar stability or confidence score;
- probability of future rank retention or movement;
- expected future rank;
- confidence-adjusted or frequency-adjusted rank;
- manager consistency/reliability grades;
- sensitivity leaderboards;
- league-wide sensitivity scores;
- recommendations based on sensitivity;
- causal interpretation;
- skill/fault attribution; and
- cross-league manager comparison.

## What v1 can defend

Permitted statements include:

- `This cutoff had 5 baseline common weeks and 4 strictly later holdout common weeks, so it was available for the historical audit.`
- `The baseline rank was 2 and the future-only holdout rank was 1; observed signed temporal displacement was -1.`
- `The baseline rank-change frequency was 0/5 = 0.000000, while the future-only holdout rank still differed by one position.`
- `This cutoff was excluded because the future-only holdout had fewer than four common comparable weeks.`
- `The corpus has a particular team-count and perturbation-denominator distribution; the audit does not declare that breadth sufficient for calibration.`

## What v1 cannot defend

V1 does not permit statements such as:

- `0.166667 is a calibrated low frequency.`
- `The baseline rank had 83% confidence.`
- `A future rank change proves the earlier rank was wrong.`
- `This manager is inconsistent because the holdout rank moved.`
- `The historical sample is large enough because it has more than 30 rows.`
- `The best sensitivity threshold is 20%.`

## Implemented command

```text
butler league lineup-capture-ranking-sensitivity-calibration-corpus-audit <start-season> <end-season>
```

The CLI exposes historical scope, governance floors, source failures, cutoff inclusion/exclusion states, corpus breadth distributions, available temporal team rows, and the no-calibration boundary.

## Stop boundary

BF-517 through BF-520 complete the authorized historical corpus-audit phase.

A **new governed methodology decision is required** before any work may:

- declare the corpus sufficient for calibration;
- fit or optimize thresholds;
- select qualitative frequency categories;
- create a magnitude-frequency classifier;
- estimate probability or confidence;
- produce expected or adjusted ranks;
- score manager consistency/reliability;
- rank teams or managers by sensitivity;
- issue recommendations from sensitivity evidence; or
- make cross-league manager comparisons.

The recommended next decision surface is corpus **adequacy methodology**: decide what empirical breadth and validation prerequisites would have to be satisfied before a threshold-fitting study could even be authorized. That next methodology should assess evidence sufficiency; it should not fit thresholds itself.
