# Governed lineup-capture rank-sensitivity calibration corpus adequacy methodology

BF-521 defines what Butler must observe in the historical BF-518 calibration corpus before a later governed methodology may design or evaluate candidate calibration thresholds.

This methodology does **not** fit thresholds, declare statistical sufficiency, estimate confidence, adjust ranks, or score managers.

## Methodology status

BF-521 through BF-524 are implemented for v1 structural readiness.

The v1 decision is:

```text
STRUCTURAL_THRESHOLD_STUDY_READINESS_AUDIT_AUTHORIZED
THRESHOLD_FITTING_NOT_AUTHORIZED
STATISTICAL_ADEQUACY_NOT_ESTABLISHED
```

Implemented production surfaces:

- BF-522 analyzer/report: `LeagueLineupCaptureRankingSensitivityCalibrationCorpusReadinessAnalyzer`
- BF-523 CLI: `butler league lineup-capture-ranking-sensitivity-calibration-corpus-readiness <start-season> <end-season>`
- BF-524 global help and durable documentation closeout

The implementation is a read-only structural-readiness assessment derived from the completed BF-518 historical calibration-corpus audit.

A structurally ready corpus may justify a later methodology for threshold-study design. Structural readiness by itself does **not** authorize choosing, generating, fitting, publishing, or optimizing thresholds.

## Why BF-521 does not use a headline N

BF-517 deliberately refused to declare that a fixed number such as `N=30` or `N=100` makes a calibration corpus adequate. That remains correct.

The BF-518 corpus contains correlated observations:

- multiple teams from the same league-season;
- multiple cutoffs from the same league-season;
- overlapping historical windows within one league-season; and
- repeated league identities across seasons when persisted.

Therefore `availableTeamCutoffRows` is not an independent sample count. BF-521 does not turn row count into statistical adequacy.

## Source of truth

The only authorized source for structural readiness is a valid BF-518 `LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer.CorpusAuditReport`.

BF-522 must not rebuild lineup-capture evidence, reconstruct historical ranks independently, substitute standings, import manager grades, or infer missing historical samples.

The readiness assessment preserves the BF-518 requested season scope, league-season identities, available/excluded cutoffs, team-count distribution, perturbation denominators, source sensitivity classes, and future-only temporal displacement evidence.

## What structural readiness means

Structural readiness is a narrow governance concept. It means the corpus contains the minimum observable variation necessary to design a later threshold study without pretending one cluster, one season, one league size, one perturbation denominator, or one outcome state represents the broader problem.

Structural readiness does **not** mean:

- enough data for statistical inference;
- enough data for a production calibration model;
- enough data to estimate probabilities;
- enough data to choose a threshold;
- enough data to generalize to every league format;
- enough data to score managers; or
- enough data to revise the BF-500 baseline rank.

## Core readiness gates

BF-522 implements six core structural gates. They are **variation gates**, not sample-size sufficiency thresholds. Each uses the minimum count necessary for a dimension to exhibit more than one observed state.

### Gate 1 — `MULTIPLE_LEAGUE_IDENTITIES`

The available-cutoff corpus must contain at least **2 distinct league IDs**.

One league identity cannot show that observed sensitivity/outcome behavior is not unique to one league's participants, rules, or history. Two leagues are not thereby declared statistically sufficient.

### Gate 2 — `MULTIPLE_SEASONS`

The available-cutoff corpus must contain at least **2 distinct seasons**.

One season cannot expose whether the observed relationship is specific to one NFL/fantasy environment. Two seasons are not thereby declared statistically sufficient.

### Gate 3 — `MULTIPLE_AVAILABLE_LEAGUE_SEASONS`

The available-cutoff corpus must contain at least **2 distinct league-season identities with at least one `AVAILABLE` BF-518 cutoff**.

Rows from the same league-season remain one correlated cluster regardless of how many teams or cutoffs they contain. A league-season with no available BF-518 cutoff does not satisfy this gate.

### Gate 4 — `MULTIPLE_TEAM_COUNT_STRATA`

The available-cutoff corpus must contain at least **2 distinct repository team-count values**.

Ordinal displacement has a different possible range in leagues of different sizes. A future methodology that intentionally restricts calibration to one league size may replace this gate only through a separate explicit scope decision.

### Gate 5 — `MULTIPLE_PERTURBATION_DENOMINATORS`

The available-cutoff corpus must contain at least **2 distinct baseline perturbation denominators**.

For BF-518, the denominator equals the baseline common-week count used for BF-504 leave-one-week-out scenarios. BF-516 established that values such as `1/5`, `1/6`, and `2/10` cannot be assumed semantically equivalent merely because decimal values look similar.

### Gate 6 — `TEMPORAL_OUTCOME_VARIATION`

Across available BF-518 team-cutoff rows, the corpus must contain both:

- at least one row with `exactNumericRankRetained = true`; and
- at least one row with `absoluteTemporalRankDisplacement > 0`.

A corpus where every future-only holdout rank is retained, or every rank moves, cannot reveal whether a later candidate sensitivity rule separates different temporal outcomes. This is an outcome-variation gate, not a balanced-class requirement or probability claim.

## Readiness state

BF-522 publishes exactly one report-level state:

```text
READY_FOR_THRESHOLD_STUDY_METHODOLOGY_DESIGN
NOT_READY_FOR_THRESHOLD_STUDY_METHODOLOGY_DESIGN
```

`READY_FOR_THRESHOLD_STUDY_METHODOLOGY_DESIGN` requires all six core gates.

If any gate fails, the state is `NOT_READY_FOR_THRESHOLD_STUDY_METHODOLOGY_DESIGN` and every failed gate remains explicit.

The report constructor recomputes the readiness state, gate evidence, and diagnostics from the nested BF-518 source and rejects fabricated readiness fields.

A `READY` result authorizes only the **design of a later governed threshold-study methodology**. It does not authorize threshold generation, fitting, production publication, or confidence semantics.

## Gate evidence

For each core gate, BF-522 exposes:

- the stable machine-readable gate identifier;
- observed distinct count or outcome-state count;
- required structural condition;
- pass/fail result; and
- the concrete observed identities/values where reasonably compact.

BF-523 renders every gate before any aggregate interpretation.

## Source-feature coverage diagnostics

BF-522 also exposes descriptive source-feature diagnostics without turning every feature category into a hard gate.

The report preserves:

- BF-508 sensitivity-class counts;
- BF-512 six-decimal rank-change-frequency distribution;
- changed-scenario numerator distribution; and
- perturbation-denominator values.

BF-508 class coverage is intentionally **not** a v1 core readiness gate. A later study may investigate raw BF-512 frequency without creating a magnitude-frequency interaction. Requiring all three BF-508 classes would silently force a model BF-521 did not authorize.

Any future magnitude-by-frequency methodology must separately require support for every interaction region it intends to interpret.

## Frequency and numerator variation

The readiness diagnostics retain distinct BF-512 six-decimal frequencies and distinct `baselineRankChangedScenarios` values.

Equal decimals produced by different numerator/denominator pairs are not automatically interchangeable evidence. A single observed frequency value is a visible limitation, but BF-521 sets no universal pass/fail count for distinct frequency decimals beyond the core denominator-variation gate.

## Exclusion burden remains visible

A structurally ready corpus may still have substantial exclusions.

BF-522/BF-523 retain BF-518 counts for requested league-seasons, audited league-seasons, source-failure league-seasons, available cutoffs, excluded cutoffs, and underlying cutoff-state evidence.

BF-521 defines no arbitrary maximum exclusion percentage. A later threshold-study methodology must review exclusion concentration before making broader applicability claims.

## Concentration diagnostics

BF-522 exposes available-cutoff concentration by:

- league ID;
- season;
- league-season identity;
- repository team count; and
- perturbation denominator.

BF-523 renders these distributions directly.

BF-521 does not authorize concentration-adjusted weighting, effective-sample-size estimation, or an automatic pass/fail concentration cap.

## No random-row independence assumption

The following counts must never be described as independent observations:

- team-cutoff rows;
- cutoff rows from the same league-season; or
- teams from the same league-season-cutoff.

The primary clustering identity remains `league-id + season`.

A future fitting methodology must split or validate at the league-season or broader cluster level, never randomly by team-cutoff row.

## Missing dimensions must not be imputed

If the requested historical scope contains only one league, one season, one team-count stratum, one denominator, or only one temporal outcome state, BF-522 reports the failed gate.

It must not synthesize league identities, import unsupported public leagues, clone a league into pseudo-samples, split one league-season into fake independent clusters, invent team-count strata, perturb denominators to manufacture variation, infer outcomes for excluded cutoffs, or weaken the gates.

## No historical outcome balancing

BF-521 does not authorize downsampling, oversampling, synthetic observations, class weighting, or other balancing operations.

The readiness artifact reports the historical corpus as governed and observed. Severe outcome imbalance remains visible for the next methodology decision.

## No threshold candidates yet

Even when all six gates pass, BF-521 through BF-524 do **not** authorize examining or generating candidate thresholds such as:

- `frequency < 0.20`;
- `frequency <= 1/5`;
- `maximum movement <= 1`;
- a magnitude-by-frequency lookup matrix; or
- a threshold selected to maximize future rank retention.

Those are threshold-study decisions and require a new methodology after structural readiness has been observed.

## No adequate-sample language

Permitted statements include:

- `The corpus passes the BF-521 structural variation gates.`
- `The corpus contains more than one league identity, season, team-count stratum, perturbation denominator, and temporal outcome state.`
- `The corpus is structurally ready for a later threshold-study methodology design.`
- `The corpus remains statistically unqualified; BF-521 does not establish sample-size adequacy.`

Prohibited statements include:

- `The sample is large enough.`
- `The corpus is statistically adequate.`
- `The threshold can now be trusted.`
- `This proves the calibration generalizes.`
- `N is sufficient because the readiness gates passed.`

## No confidence or probability semantics

BF-521 through BF-524 do not authorize confidence intervals, p-values, standard errors, bootstrap confidence, posterior probability, probability of future rank retention/movement, confidence scores, or reliability scores.

The future holdout rank remains a deterministic out-of-window ordinal comparison, not ground truth.

## No manager attribution

No readiness result may be interpreted as manager consistency, manager reliability, manager skill, coaching quality, decision quality, fault, or causal responsibility.

The subject remains the historical behavior of Butler's governed lineup-capture ordinal artifact.

## Relationship to BF-516 and BF-517

BF-516 rejected qualitative BF-512 frequency bands because fixed thresholds were uncalibrated and denominator dependent.

BF-517 through BF-520 implemented the historical temporal-holdout corpus audit needed to learn what evidence exists before any calibration study.

BF-521 through BF-524 implement the next guardrail: whether that BF-518 corpus contains enough **structural variation to justify designing a threshold study at all**.

They still do not make the threshold decision.

## Policy identifier

The implemented BF-521/BF-522 policy identifier is:

```text
league-lineup-capture-ranking-sensitivity-calibration-corpus-structural-readiness-v1-multi-cluster-multi-season-multi-size-multi-denominator-outcome-variation-no-thresholds-no-confidence
```

The implemented metric scope is:

```text
STRUCTURAL_DIVERSITY_READINESS_OF_GOVERNED_BF518_HISTORICAL_CALIBRATION_CORPUS_NO_STATISTICAL_SUFFICIENCY_NO_THRESHOLDS_NO_CONFIDENCE_NO_MANAGER_ATTRIBUTION
```

## Implementation closeout

Completed sequence:

1. **BF-521** — structural-readiness methodology;
2. **BF-522** — `LeagueLineupCaptureRankingSensitivityCalibrationCorpusReadinessAnalyzer`;
3. **BF-523** — `butler league lineup-capture-ranking-sensitivity-calibration-corpus-readiness <start-season> <end-season>`;
4. **BF-524** — global help and durable documentation closeout.

**Stop boundary:** a new governed methodology decision is required before Butler may generate candidate thresholds, compare threshold performance, declare quantitative support requirements, fit a calibration model, estimate probabilities, publish confidence semantics, adjust BF-500 ranks, score managers, rank teams by sensitivity, issue recommendations, or make cross-league manager comparisons.
