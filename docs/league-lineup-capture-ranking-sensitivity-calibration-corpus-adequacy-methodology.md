# Governed lineup-capture rank-sensitivity calibration corpus adequacy methodology

BF-521 defines what Butler must observe in the historical BF-518 calibration corpus before a later governed methodology may even design or evaluate candidate calibration thresholds.

This methodology does **not** fit thresholds, declare statistical sufficiency, estimate confidence, adjust ranks, or score managers.

## Methodology status

The v1 decision is:

```text
STRUCTURAL_THRESHOLD_STUDY_READINESS_AUDIT_AUTHORIZED
THRESHOLD_FITTING_NOT_AUTHORIZED
STATISTICAL_ADEQUACY_NOT_ESTABLISHED
```

BF-521 authorizes a read-only structural-readiness assessment derived from the completed BF-518 historical calibration-corpus audit.

A structurally ready corpus may justify a later methodology for threshold-study design. Structural readiness by itself does **not** authorize choosing, fitting, publishing, or optimizing thresholds.

## Why BF-521 does not use a headline N

BF-517 deliberately refused to declare that a fixed number such as `N=30` or `N=100` makes a calibration corpus adequate.

That remains correct.

The BF-518 corpus contains correlated observations:

- multiple teams from the same league-season;
- multiple cutoffs from the same league-season;
- overlapping historical windows within one league-season; and
- repeated league identities across seasons when persisted.

Therefore `availableTeamCutoffRows` is not an independent sample count.

BF-521 does not turn row count into statistical adequacy.

## Source of truth

The only authorized source for BF-521 structural readiness is a valid BF-518:

`LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer.CorpusAuditReport`

BF-521 must not rebuild lineup-capture evidence, reconstruct historical ranks independently, substitute standings, import manager grades, or infer missing historical samples.

The readiness assessment must preserve the BF-518 requested season scope, league-season identities, available/excluded cutoffs, team-count distribution, perturbation denominators, source sensitivity classes, and future-only temporal displacement evidence.

## What “structural readiness” means

Structural readiness is a narrow governance concept.

It means the corpus contains the **minimum observable variation necessary to design a later threshold study without pretending one cluster, one season, one league size, one perturbation denominator, or one outcome state represents the broader problem**.

Structural readiness does **not** mean:

- enough data for statistical inference;
- enough data for a production calibration model;
- enough data to estimate probabilities;
- enough data to choose a threshold;
- enough data to generalize to every league format;
- enough data to score managers; or
- enough data to revise the BF-500 baseline rank.

## Core readiness gates

BF-521 defines six core structural gates.

The values below are **variation gates**, not sample-size sufficiency thresholds. They use the minimum count required for a dimension to exhibit more than one observed state.

### Gate 1 — multiple independent league identities

The available-cutoff corpus must contain at least **2 distinct league IDs**.

Reason: one league identity cannot demonstrate that observed sensitivity/outcome relationships are not unique to one league's participants, rules, or history.

This gate does not imply that two leagues are statistically sufficient.

### Gate 2 — multiple seasons

The available-cutoff corpus must contain at least **2 distinct seasons**.

Reason: one season cannot expose whether the observed relationship is specific to one NFL/fantasy environment.

This gate does not imply that two seasons are statistically sufficient.

### Gate 3 — multiple league-season clusters

The available-cutoff corpus must contain at least **2 distinct league-season identities with at least one AVAILABLE BF-518 cutoff**.

Rows from the same league-season remain one correlated cluster for this gate regardless of how many teams or cutoffs they contain.

A league-season with no available BF-518 cutoff does not satisfy the gate.

### Gate 4 — league-size variation

The available-cutoff corpus must contain at least **2 distinct repository team-count values**.

Reason: ordinal displacement has a different possible range in leagues of different sizes.

A future methodology that intentionally restricts calibration to one specific league size may replace this gate only through a separate explicit scope decision. BF-521 does not silently narrow the product scope.

### Gate 5 — perturbation-denominator variation

The available-cutoff corpus must contain at least **2 distinct baseline perturbation denominators**.

For BF-518, the perturbation denominator equals the baseline common-week count used for the BF-504 leave-one-week-out scenarios.

Reason: BF-516 established that `1/5`, `1/6`, `2/10`, and similar frequencies cannot be assumed semantically equivalent merely because a decimal representation looks close.

A corpus with only one denominator cannot support a study of denominator dependence.

### Gate 6 — future temporal-outcome variation

Across available BF-518 team-cutoff rows, the corpus must contain both:

- at least one row with `exactNumericRankRetained = true`; and
- at least one row with `absoluteTemporalRankDisplacement > 0`.

Reason: a corpus where every future-only holdout rank is retained, or every future-only holdout rank moves, cannot reveal whether a candidate sensitivity rule separates different temporal outcomes.

This is an outcome-variation gate, not a balanced-class requirement and not a probability statement.

## Source-feature coverage diagnostics

BF-521 also requires visibility into source-feature coverage, but does not make every feature category a hard core gate.

The readiness report must show whether available BF-518 rows include:

- `LOW_SENSITIVITY`;
- `MODERATE_SENSITIVITY`;
- `HIGH_SENSITIVITY`;
- multiple raw BF-512 rank-change-frequency values;
- multiple changed-scenario numerators; and
- multiple perturbation denominators.

Why BF-508 class coverage is not a core v1 gate:

A later study might examine BF-512 raw frequency without combining it with BF-508 magnitude class. Requiring all three BF-508 classes before even designing that study would silently force a magnitude-frequency model that BF-521 does not authorize.

However, any future methodology that proposes a magnitude-by-frequency interaction must explicitly require support for every interaction region it intends to interpret.

## Frequency-value variation

The readiness report must expose the number of distinct observed BF-512 six-decimal frequencies among available rows.

A single observed frequency value is a material limitation.

BF-521 does not set a universal minimum number of distinct frequency values beyond requiring denominator variation in the core gates, because distinct decimals may still be generated by highly correlated rows from the same league-season.

## Changed-scenario numerator variation

The readiness report must expose the distinct `baselineRankChangedScenarios` values represented in available rows.

This preserves the numerator behind BF-512 frequency rather than allowing a future threshold study to operate only on the decimal.

BF-521 does not authorize pooling equal decimals produced by different numerator/denominator pairs as interchangeable evidence.

## Exclusion burden remains visible

A structurally ready corpus may still have substantial exclusions.

The readiness report must retain BF-518 counts for:

- requested league-seasons;
- audited league-seasons;
- source-failure league-seasons;
- available cutoffs;
- excluded cutoffs; and
- every BF-518 cutoff exclusion state.

BF-521 does not define an arbitrary maximum exclusion percentage.

A future threshold-study methodology must review exclusion concentration before claiming broad applicability.

## Concentration diagnostics

The readiness report must expose concentration by at least:

- league ID;
- season;
- league-season identity;
- repository team count; and
- perturbation denominator.

The report may show counts and shares for inspection.

BF-521 does not authorize a concentration-adjusted weight, effective-sample-size estimate, or automatic pass/fail percentage cap.

## No random-row independence assumption

The following counts must never be described as independent observations:

- team-cutoff rows;
- cutoff rows from the same league-season; or
- teams from the same league-season-cutoff.

The primary clustering identity remains `league-id + season`.

A future model-fitting methodology must split or validate at the league-season or broader cluster level, never randomly by team-cutoff row.

## Readiness state

A BF-521 structural-readiness implementation should use a report-level state equivalent to:

```text
READY_FOR_THRESHOLD_STUDY_METHODOLOGY_DESIGN
NOT_READY_FOR_THRESHOLD_STUDY_METHODOLOGY_DESIGN
```

`READY_FOR_THRESHOLD_STUDY_METHODOLOGY_DESIGN` requires all six core gates.

If any core gate fails, the report state is `NOT_READY_FOR_THRESHOLD_STUDY_METHODOLOGY_DESIGN` and the failed gates remain explicit.

This state authorizes only the **design of a later governed threshold-study methodology**.

It does not authorize threshold fitting or production publication.

## Required gate evidence

For each core gate, a future BF-522 structural-readiness report should expose:

- gate identifier;
- observed distinct count or outcome presence;
- required structural condition;
- pass/fail result; and
- the concrete identities/values that produced the result where reasonably compact.

The gate identifiers should be stable and machine-readable.

Recommended v1 identifiers:

```text
MULTIPLE_LEAGUE_IDENTITIES
MULTIPLE_SEASONS
MULTIPLE_AVAILABLE_LEAGUE_SEASONS
MULTIPLE_TEAM_COUNT_STRATA
MULTIPLE_PERTURBATION_DENOMINATORS
TEMPORAL_OUTCOME_VARIATION
```

## Missing dimensions must not be imputed

If a requested historical scope contains only one league, one season, one team-count stratum, or one perturbation denominator, BF-521 must report that limitation.

It must not:

- synthesize additional league identities;
- import unsupported public leagues;
- clone one league into multiple pseudo-samples;
- split one league-season's cutoffs into fake independent clusters;
- invent team-count strata;
- perturb denominators solely to manufacture variation; or
- infer future outcomes for excluded BF-518 cutoffs.

## No historical outcome balancing

BF-521 does not authorize downsampling, oversampling, SMOTE-like synthesis, class weighting, or other balancing operations.

The readiness artifact reports the historical corpus as governed and observed.

If future retention and movement outcomes are extremely imbalanced, that remains visible for the next methodology decision.

## No threshold candidates yet

Even when all six core gates pass, BF-521 does **not** authorize examining candidate thresholds such as:

- `frequency < 0.20`;
- `frequency <= 1/5`;
- `maximum movement <= 1`;
- a magnitude-by-frequency lookup matrix; or
- a threshold chosen to maximize future rank retention.

Those are threshold-study decisions and require a later methodology after structural readiness has been observed.

## No “adequate sample” language

Permitted language includes:

- `The corpus passes the BF-521 structural variation gates.`
- `The corpus contains more than one league identity, season, team-count stratum, perturbation denominator, and temporal outcome state.`
- `The corpus is structurally ready for a later threshold-study methodology design.`
- `The corpus remains statistically unqualified; BF-521 does not establish sample-size adequacy.`

Prohibited language includes:

- `The sample is large enough.`
- `The corpus is statistically adequate.`
- `The threshold can now be trusted.`
- `This proves the calibration generalizes.`
- `N is sufficient because the readiness gates passed.`

## No confidence or probability semantics

BF-521 does not authorize:

- confidence intervals;
- p-values;
- standard errors;
- bootstrap confidence;
- posterior probability;
- probability of future rank retention;
- probability of future rank movement;
- a confidence score; or
- a reliability score.

The future holdout rank remains a deterministic out-of-window ordinal comparison, not ground truth.

## No manager attribution

No readiness result may be interpreted as:

- manager consistency;
- manager reliability;
- manager skill;
- coaching quality;
- decision quality;
- fault; or
- causal responsibility.

The subject remains the historical behavior of Butler's governed lineup-capture ordinal artifact.

## Relationship to BF-516 and BF-517

BF-516 rejected qualitative BF-512 frequency bands because fixed thresholds were uncalibrated and denominator dependent.

BF-517 authorized the historical temporal-holdout corpus audit needed to learn what evidence exists before any calibration study.

BF-521 is the next guardrail: it asks whether the BF-518 corpus contains enough **structural variation to justify designing a threshold study at all**.

It still does not make the threshold decision.

## Policy identifier

A conforming BF-521 methodology should use:

```text
league-lineup-capture-ranking-sensitivity-calibration-corpus-structural-readiness-v1-multi-cluster-multi-season-multi-size-multi-denominator-outcome-variation-no-thresholds-no-confidence
```

The metric scope should state that the artifact assesses structural diversity/readiness of the governed BF-518 historical corpus and does not establish statistical sufficiency, calibrated thresholds, probabilities, confidence, manager quality, or adjusted ranks.

## Authorized next sequence

If BF-521 is approved, it authorizes only:

1. **BF-522** — structural-readiness analyzer/report derived from BF-518 corpus audit;
2. **BF-523** — structural-readiness CLI exposing every core gate and diagnostic distribution;
3. **BF-524** — global help and durable documentation closeout; and
4. stop again before any threshold candidate generation, threshold optimization, probability model, confidence model, or production calibration category.

If the actual BF-518 corpus fails one or more BF-521 core gates, BF-522/BF-523 should report that result rather than weaken the gates or synthesize evidence.

**Stop boundary:** a new governed methodology decision is required after BF-524 before Butler may generate candidate thresholds, compare threshold performance, declare quantitative support requirements, fit a calibration model, estimate probabilities, publish confidence semantics, adjust BF-500 ranks, score managers, rank teams by sensitivity, issue recommendations, or make cross-league manager comparisons.
