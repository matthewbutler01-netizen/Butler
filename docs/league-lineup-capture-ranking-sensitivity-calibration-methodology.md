# Governed historical lineup-capture rank-sensitivity calibration methodology

Butler may evaluate whether the existing deterministic rank-sensitivity diagnostics have any empirically supportable relationship with later **out-of-window lineup-capture rank persistence**.

This methodology is deliberately narrower than a confidence model. It defines the historical evidence and validation protocol that would be required before Butler could even consider calibrated sensitivity/frequency categories in a later governed decision.

It does **not** authorize qualitative frequency thresholds, probabilities, confidence scores, manager grades, adjusted ranks, or predictive production behavior.

## Methodology status

This document is normative for the first historical calibration-audit phase.

The v1 decision is:

```text
HISTORICAL_CALIBRATION_AUDIT_AUTHORIZED
CALIBRATED_THRESHOLDS_NOT_AUTHORIZED
```

BF-517 authorizes construction and auditing of a historical temporal-holdout corpus only.

It does **not** authorize fitting or publishing LOW/MODERATE/HIGH frequency bands, a magnitude-frequency composite, a probability model, a confidence score, or any manager-level evaluation.

## Calibration question

The only question BF-517 permits is:

> when Butler computes the governed BF-504/BF-508/BF-512 sensitivity diagnostics from an earlier common-week window, how do those diagnostics relate to lineup-capture ordinal movement in a strictly later common-week holdout window?

The subject remains the persistence of a governed retrospective metric ordering.

The subject is **not**:

- the probability that the baseline rank is true or false;
- the probability that the rank will change in the future;
- manager consistency or reliability;
- manager skill or decision quality;
- coaching quality;
- causal responsibility; or
- a hidden estimate of historical startability.

## No “true rank” target

A later rank is not ground truth.

The future holdout rank reflects a different temporal window and may legitimately differ because team results, player performance, roster construction, injuries, transactions, or other observed conditions changed.

Therefore BF-517 permits only terms such as:

- **future holdout rank**;
- **out-of-window rank**;
- **temporal rank persistence**;
- **temporal ordinal displacement**; and
- **historical calibration audit**.

It must not call the later rank:

- true rank;
- correct rank;
- corrected rank;
- real rank;
- latent rank; or
- manager-quality rank.

## Required governed source stack

Historical calibration inputs must be reproducible from the same governed lineup-capture evidence stack used by production surfaces:

1. governed team-week points-gap evidence;
2. governed all-team common-universe evidence;
3. BF-500 common-universe lineup-capture ranking;
4. BF-504 leave-one-common-week-out ranking-stability evidence;
5. BF-508 maximum-movement sensitivity classification; and
6. BF-512 raw rank-change frequency evidence.

The audit must not substitute independently scoped season lineup-capture rates, pairwise contrasts, reconstructed standings, platform points, or external manager grades.

## Historical calibration unit

The fundamental calibration unit is one governed:

```text
league-season-cutoff
```

A calibration unit contains:

- one league identifier;
- one season;
- one deterministic cutoff between earlier and later observed common weeks;
- one baseline common-week window strictly before the cutoff;
- one future holdout common-week window strictly after the cutoff;
- the complete repository team universe for that league-season; and
- the governed evidence/provenance necessary to reproduce both windows.

Multiple cutoffs may exist for one league-season, but they are correlated observations and must never be treated as independent league-seasons in sample-count reporting or split logic.

## Baseline window requirements

The earlier baseline window must contain at least **5 common comparable weeks**.

This threshold is inherited directly from BF-504 because the baseline window must be capable of producing the full governed stability artifact and BF-512 frequency evidence.

For the baseline window Butler must calculate, without future-week leakage:

- BF-500 baseline lineup-capture rank;
- baseline six-decimal lineup-capture rate;
- BF-504 maximum absolute rank movement;
- BF-504 changed/unchanged perturbation scenario counts;
- BF-508 observed sensitivity class; and
- BF-512 six-decimal rank-change frequency.

The baseline diagnostic calculation must not inspect later holdout production, later holdout ranks, later holdout lineup-capture rates, or a full-season aggregate that includes holdout weeks.

## Future holdout window requirements

The later holdout window must contain at least **4 common comparable weeks**.

This threshold is inherited directly from the existing BF-500 ranking governance floor so the future-only holdout rank is itself a valid governed lineup-capture ranking artifact.

The holdout window must use only common comparable weeks strictly later than the calibration cutoff.

The holdout rank is calculated from those future weeks **alone**.

The baseline weeks must not be included in the holdout rank. This avoids creating an artificially persistent outcome through overlapping observations.

## Same team universe

The baseline and holdout windows must preserve the same complete repository team universe for the league-season.

A calibration unit is unavailable if Butler would need to:

- drop a team to make either window rankable;
- add a team only in one window;
- impute an unavailable team rate;
- assign an unavailable team last place; or
- create a partial rank in either window.

The historical audit must fail closed for that cutoff rather than silently narrow the universe.

## Temporal provenance rules

Historical calibration necessarily uses evidence observed at different dates. Exact evidence timestamps therefore do not need to match between baseline and holdout windows.

However, both windows must be governed by compatible policy semantics:

- same league and season;
- same repository team universe;
- compatible league scoring configuration for the compared period;
- compatible starting-slot configuration for the compared period;
- same governed scoring-policy version;
- same lineup-eligibility policy version;
- same solver semantics;
- same lineup-capture normalization policy; and
- evidence dates appropriate to their own temporal windows.

If a material league configuration change makes the windows semantically incomparable, the cutoff is unavailable for calibration rather than normalized around the change.

## Authorized historical outcomes

For each team in one available calibration unit, Butler may derive only deterministic temporal-persistence outcomes such as:

```text
baseline rank
future holdout rank
signed rank displacement = future holdout rank - baseline rank
absolute rank displacement = abs(future holdout rank - baseline rank)
exact numeric rank retained = baseline rank == future holdout rank
```

Competition-ranking ties remain governed by BF-500 in both windows.

A change from rank `1` to tied rank `1` is no numeric rank displacement.

BF-517 does not authorize inventing a secondary tie-distance metric.

## No cross-team outcome aggregation yet

The calibration audit may count and summarize available rows for data-quality and breadth purposes, but BF-517 does not authorize a fitted league-wide threshold based on those outcomes.

In particular, BF-517 does not authorize:

- choosing frequency cutoffs that minimize future displacement;
- choosing BF-508/BF-512 matrix cells that maximize holdout retention;
- fitting logistic regression or another classifier;
- fitting a probability of future rank retention;
- optimizing thresholds on the full historical corpus; or
- selecting categories after observing final validation outcomes.

Those steps require a later methodology revision after the audit establishes that the corpus is suitable.

## Denominator dependence must remain explicit

BF-516 established that rank-change frequency is denominator dependent.

Historical calibration must therefore retain at least:

- baseline common-week count;
- baseline perturbation scenario count;
- changed perturbation count;
- unchanged perturbation count; and
- six-decimal rank-change frequency.

The audit must not pool `1/5`, `2/10`, and similar observations into an apparently identical semantic class merely because the decimals match.

Any later threshold study must explicitly evaluate denominator dependence rather than erase it.

## League-size dependence must remain explicit

Ordinal rank movement has different practical ranges in leagues with different team counts.

The audit must retain repository team count and must report corpus breadth by team-count stratum.

BF-517 does not authorize a single universal displacement threshold across all league sizes.

It also does not authorize silently normalizing displacement by team count and then treating the normalized value as a calibrated outcome.

Any such normalization requires separate governance.

## Cutoff dependence must remain explicit

A calibration corpus can contain multiple valid cutoffs within the same league-season.

Those rows share teams, scoring configuration, and much of the same underlying production history.

Therefore the audit must report:

- unique leagues;
- unique league-seasons;
- unique league-season-cutoffs;
- unique team-season identities; and
- total team-cutoff rows.

It must not present the team-cutoff row count as the number of independent samples.

## Leakage controls

The historical calibration audit must enforce temporal leakage controls.

For a cutoff at week `C`:

- baseline diagnostics use only governed common weeks before `C`;
- holdout outcomes use only governed common weeks after `C`;
- no full-season lineup-capture total may feed a baseline feature;
- no future production may feed a baseline potential/started calculation;
- no later rank may be used to choose the cutoff itself; and
- no final-season category may be copied backward as a baseline label.

A cutoff that cannot be reproduced without future information is invalid for calibration.

## Development and validation grouping

BF-517 does not yet authorize model fitting, but the audit must preserve identifiers needed for future leakage-safe splitting.

At minimum every calibration unit must retain:

- league ID;
- season;
- cutoff;
- team ID; and
- repository team count.

Any future calibration fit must split at the **league-season or broader cluster level**, never randomly by team-cutoff row.

Rows from the same league-season must not be distributed across training and validation sets as if they were independent observations.

A later predictive methodology should prefer temporal holdout by season and should report whether the same league IDs appear across development and validation periods.

BF-517 does not authorize a specific train/validation/test percentage.

## Corpus breadth audit

Before any calibrated threshold can be proposed, Butler must produce a read-only corpus audit that reports at least:

- requested historical season range;
- available league count;
- available league-season count;
- league-seasons excluded and reason;
- available calibration-cutoff count;
- cutoffs excluded and reason;
- repository team-count distribution;
- baseline common-week-count distribution;
- holdout common-week-count distribution;
- baseline perturbation-denominator distribution;
- BF-508 sensitivity-class counts as descriptive source context;
- BF-512 raw frequency values/counts as descriptive source context;
- future holdout rank-displacement availability; and
- provenance/configuration incompatibility counts.

The audit must preserve denominators and exclusion reasons rather than reporting only one headline sample size.

## No arbitrary minimum corpus size in BF-517

BF-517 does **not** declare that `N=30`, `N=100`, or any other fixed number of league-seasons is automatically sufficient for calibration.

A numerical adequacy threshold chosen before seeing the actual corpus would be another arbitrary governance choice.

Instead BF-517 authorizes the corpus audit first.

A later methodology decision may set minimum breadth requirements after Butler knows the actual distribution across seasons, league sizes, cutoffs, and perturbation denominators.

Until that later decision, calibrated categories remain unavailable.

## Required exclusion states

A future corpus-audit implementation should preserve explicit cutoff-level exclusion reasons such as:

```text
AVAILABLE
EXCLUDED_BASELINE_BELOW_STABILITY_FLOOR
EXCLUDED_HOLDOUT_BELOW_RANKING_FLOOR
EXCLUDED_BASELINE_RANKING_UNAVAILABLE
EXCLUDED_BASELINE_STABILITY_UNAVAILABLE
EXCLUDED_HOLDOUT_RANKING_UNAVAILABLE
EXCLUDED_TEAM_UNIVERSE_MISMATCH
EXCLUDED_CONFIGURATION_INCOMPATIBLE
EXCLUDED_PROVENANCE_INCOMPATIBLE
```

The exact implementation enum may refine names, but it must remain fail closed and reason preserving.

A cutoff cannot be converted into an available row by dropping teams, widening evidence, reducing denominators, or reusing an independently scoped season rate.

## Historical corpus is read-only evidence

The calibration audit must not mutate production ranking artifacts, team records, roster history, league settings, or persisted source evidence.

It may materialize a derived report for inspection, but the report must identify its source policy versions and requested historical scope.

## No external manager labels

BF-517 does not authorize importing subjective manager grades, expert ratings, win-loss records, championships, standings finish, transaction activity, or user feedback as calibration truth.

Those outcomes answer different questions and would convert metric-sensitivity calibration into manager evaluation.

The historical target remains only future out-of-window movement of the same governed lineup-capture ordinal metric.

## No probability or confidence semantics

Even if the future audit eventually finds an empirical association, BF-517 does not authorize statements such as:

- `20% rank-change frequency means 80% confidence in the baseline rank`;
- `HIGH_SENSITIVITY implies a 60% chance of future movement`;
- `this rank is statistically reliable`;
- `this manager is predictable`; or
- `the true rank is probably 2`.

A later probability methodology would require explicit statistical assumptions, calibration testing, uncertainty evaluation, and out-of-sample validation that do not exist today.

## No threshold fitting in v1

The following remain prohibited after BF-517:

- qualitative BF-512 frequency bands;
- a magnitude-by-frequency matrix;
- a scalar stability or confidence score;
- probability of future rank retention;
- probability of future rank movement;
- expected future rank;
- confidence-adjusted rank;
- frequency-adjusted rank;
- manager consistency/reliability grades;
- sensitivity leaderboards;
- league-wide sensitivity scores;
- recommendations based on sensitivity; and
- cross-league manager comparison.

## What BF-517 can defend

Permitted statements include:

- `This league-season-cutoff had 6 baseline common weeks and 4 later holdout common weeks, so it was eligible for the historical calibration audit.`
- `The baseline rank was 2 and the future-only holdout rank was 3; the observed temporal ordinal displacement was +1.`
- `The baseline rank-change frequency was 1/6 = 0.166667; that value is retained with its denominator rather than converted into a qualitative frequency tier.`
- `The historical corpus contained 12 league-seasons but they were concentrated in one league size, so breadth remains visible rather than being declared sufficient.`
- `A cutoff was excluded because the future holdout contained fewer than four common comparable weeks.`

## What BF-517 cannot defend

BF-517 does not permit statements such as:

- `0.166667 is a low frequency because historical data proves it.`
- `The baseline rank had 83% confidence.`
- `A future rank change proves the earlier rank was wrong.`
- `This manager is inconsistent because the holdout rank moved.`
- `The historical sample is large enough because it has more than 30 rows.`
- `The best sensitivity threshold is 20%`.

## Policy identifier

A conforming BF-517 historical calibration-audit methodology should use:

```text
league-season-lineup-capture-ranking-sensitivity-calibration-audit-v1-temporal-disjoint-baseline-min5-holdout-min4-no-thresholds-no-confidence-no-manager-attribution
```

The metric scope should state that the artifact audits temporal out-of-window persistence of the governed lineup-capture ordinal metric and does not establish statistical confidence, probability, manager quality, or calibrated categories.

## Authorized next sequence

BF-517 authorizes only a historical corpus-audit implementation sequence:

1. **BF-518** — historical calibration-corpus audit analyzer/report;
2. **BF-519** — audit CLI exposing scope, inclusion/exclusion reasons, and breadth distributions;
3. **BF-520** — global help and durable documentation closeout; and
4. stop again before any threshold fitting, qualitative frequency category, magnitude-frequency composite, probability model, or confidence semantics.

If the repository cannot currently enumerate enough historical league-season evidence to build this audit faithfully, BF-518 should report that limitation rather than synthesize or import unsupported samples.

**Stop boundary:** a new governed methodology decision is required before fitting thresholds, selecting categories, estimating probabilities, converting sensitivity into confidence, adjusting BF-500 ranks, scoring managers, ranking teams by stability, issuing recommendations, or making cross-league manager comparisons.
