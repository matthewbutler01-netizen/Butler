# Governed lineup-capture rank-change frequency classification methodology

Butler has already implemented two deterministic sensitivity dimensions around the governed BF-500 lineup-capture rank:

- **BF-508 movement magnitude** — maximum absolute ordinal movement, classified as `LOW_SENSITIVITY`, `MODERATE_SENSITIVITY`, or `HIGH_SENSITIVITY`; and
- **BF-512 change frequency** — the raw fraction of complete BF-504 leave-one-common-week-out perturbations whose rank differs from the BF-500 baseline rank.

BF-516 evaluates whether the BF-512 frequency should itself receive qualitative labels such as low, moderate, high, rare, occasional, frequent, stable, volatile, reliable, or unreliable.

## Methodology status

**Decision: qualitative rank-change-frequency classification is NOT AUTHORIZED in v1.**

The raw BF-512 changed-scenario numerator, complete perturbation denominator, governed six-decimal frequency, and display percentage are the furthest defensible frequency surface under the current evidence model.

BF-516 does not authorize an analyzer, classifier, CLI, tier, score, matrix, or ordering derived from qualitative frequency bands.

No BF-517 frequency-classification implementation should be created under this methodology.

## Question evaluated

BF-516 asks one narrow governance question:

> can Butler defend fixed qualitative labels for the observed fraction of required leave-one-common-week-out scenarios in which a team's governed lineup-capture rank changes?

The answer for v1 is **no**.

## Existing governed frequency

BF-512 defines:

```text
rank-change frequency(T) =
    changed perturbation scenarios(T)
    /
    complete required perturbation scenarios(T)
```

and complementary retention frequency:

```text
rank-retention frequency(T) =
    unchanged perturbation scenarios(T)
    /
    complete required perturbation scenarios(T)
```

Materialized values use six decimal places and `HALF_UP` rounding. The source BF-504 stability report must be `AVAILABLE`; otherwise no frequency row is published.

BF-516 does not alter any of those rules.

## Why fixed percentage tiers are not authorized

A threshold scheme such as:

```text
0%-20%   -> LOW_FREQUENCY
20%-50%  -> MODERATE_FREQUENCY
50%-100% -> HIGH_FREQUENCY
```

would add arbitrary semantics that are not present in the governed source evidence.

The principal problem is denominator dependence.

For example:

```text
1 changed scenario out of 5  = 0.200000
1 changed scenario out of 10 = 0.100000
```

Both observations contain exactly one changed perturbation scenario, but a fixed percentage classifier could place them in different qualitative bands solely because one league-season has more baseline common weeks and therefore more required perturbations.

That does not prove the 10-scenario artifact is categorically more stable, more reliable, higher-confidence, or better supported. It only means the same count of rank-changing omissions occurred in a larger deterministic perturbation set.

## Why fixed changed-count tiers are not authorized

Replacing percentage cutoffs with count cutoffs does not solve the problem.

For example:

```text
0 changed scenarios -> LOW
1-2 changed scenarios -> MODERATE
3+ changed scenarios -> HIGH
```

would ignore the perturbation denominator entirely. Three changed scenarios out of five and three changed scenarios out of twelve would receive the same label despite materially different raw frequencies.

V1 therefore preserves both numerator and denominator rather than privileging one arbitrary categorical interpretation.

## Why denominator-normalized tiers are still not justified

BF-516 also rejects the idea that percentage normalization alone makes cross-sample qualitative bands defensible.

The BF-504 perturbation scenarios are deterministic one-week omissions. They are not independent random draws from a probability distribution. A frequency of `0.200000` means only that 20% of the required governed one-week omissions changed the ordinal rank.

It does **not** establish:

- a 20% probability the baseline rank is wrong;
- a 20% probability the rank will change in the future;
- 80% confidence in the baseline rank;
- 80% manager reliability;
- a statistically calibrated instability rate; or
- a population parameter that supports standard probability bands.

Without a separate empirical calibration target or decision-theoretic use case, percentage cutoffs remain labels of convenience rather than governed evidence.

## The discrete denominator is visible evidence, not noise

BF-504 requires at least five baseline common comparable weeks, so the minimum frequency denominator is five. Larger common-week universes create larger deterministic perturbation sets.

Possible observed frequencies are therefore discrete and denominator-specific.

Examples include:

```text
n = 5  -> 0/5, 1/5, 2/5, 3/5, 4/5, 5/5
n = 6  -> 0/6, 1/6, 2/6, 3/6, 4/6, 5/6, 6/6
n = 10 -> 0/10 ... 10/10
```

A fixed band boundary can create artificial discontinuities between adjacent discrete observations. Butler should expose the actual count and denominator instead of hiding that structure behind a category.

## Magnitude and frequency already answer different questions

BF-508 and BF-512 intentionally remain separate:

```text
BF-508 magnitude:
    how far did the rank move at most?

BF-512 frequency:
    how often did the rank differ from baseline?
```

Examples that can legitimately coexist include:

- `HIGH_SENSITIVITY` magnitude with `1/7` changed scenarios;
- `MODERATE_SENSITIVITY` magnitude with `6/7` changed scenarios; or
- `LOW_SENSITIVITY` magnitude with `0/7` changed scenarios.

Those combinations are more informative when shown directly than when collapsed into a second qualitative frequency label or a combined category.

## No magnitude-frequency matrix in BF-516

BF-516 does not authorize a matrix such as:

```text
LOW magnitude + LOW frequency       -> ROBUST
HIGH magnitude + LOW frequency      -> FRAGILE_OUTLIER
LOW magnitude + HIGH frequency      -> CHRONICALLY_UNSTABLE
HIGH magnitude + HIGH frequency     -> VERY_UNSTABLE
```

Those labels would add evaluative semantics not supported by the governed evidence and could easily be misread as confidence, reliability, manager consistency, or quality.

Any future two-dimensional matrix requires a separate methodology with an explicit decision use case and calibrated semantics.

## No qualitative synonyms through presentation wording

Because formal frequency tiers are not authorized, presentation must also avoid backdoor qualitative classification.

The BF-512 artifact should not translate raw frequency into wording such as:

- rare rank changes;
- occasional rank changes;
- frequent rank changes;
- stable frequency;
- unstable frequency;
- robust frequency;
- volatile frequency;
- reliable rank;
- unreliable rank; or
- high/medium/low confidence.

Permitted wording remains descriptive and literal, for example:

- `rank changed in 1 of 5 required perturbations (0.200000)`; or
- `rank remained unchanged in all 7 required perturbations (change frequency 0.000000)`.

## No manager attribution

BF-516 does not authorize person-level interpretation.

Raw frequency and the absence of a qualitative tier do not establish:

- manager consistency;
- manager reliability;
- manager discipline;
- manager quality;
- manager skill;
- decision quality;
- blame or credit; or
- future managerial behavior.

The subject remains the deterministic sensitivity of a retrospective governed ordinal artifact.

## No statistical-confidence claim

BF-516 does not convert deterministic perturbation frequency into:

- a confidence level;
- probability;
- significance level;
- posterior belief;
- error bound;
- bootstrap estimate;
- prediction interval; or
- reliability estimate.

Qualitative labels would make that misinterpretation easier, not safer, under the present evidence model.

## Cross-league comparison remains prohibited

BF-512 frequencies are not authorized as cross-league manager-quality measures.

BF-516 does not create cross-league frequency classes, percentiles, benchmarks, or league-adjusted thresholds.

Different leagues may have different team counts, common-week universes, roster/scoring structures, and perturbation denominators. No current methodology calibrates those differences into a shared qualitative frequency scale.

## Baseline rank remains authoritative

The BF-500 lineup-capture rank over the full governed common-week universe remains authoritative.

BF-516 does not authorize:

- frequency-adjusted rank;
- confidence-weighted rank;
- sensitivity-adjusted rank;
- rank penalty or bonus;
- consensus rank;
- expected rank; or
- any replacement ordinal score.

## No frequency leaderboard

The BF-512 artifact remains contextual evidence and must preserve its governed source order.

BF-516 does not authorize sorting teams by raw frequency or by a qualitative frequency class to create:

- most/least stable standings;
- volatility standings;
- reliability rankings;
- frequency percentiles;
- league average frequency;
- league stability score; or
- a manager leaderboard.

## What Butler can defend after BF-516

Butler may continue to state:

- `Team Alpha changed rank in 1 of 5 required one-week-out perturbations, for observed rank-change frequency 0.200000.`
- `Team Beta changed rank in 4 of 7 required perturbations, for observed rank-change frequency 0.571429.`
- `Team Gamma is HIGH_SENSITIVITY by maximum movement and changed rank in 1 of 7 perturbations; magnitude and frequency are separate dimensions.`
- `Butler does not assign low/moderate/high frequency labels because no governed v1 threshold calibration exists.`

## What Butler cannot defend after BF-516

Butler must not state:

- `20% is low instability.`
- `57% is high instability.`
- `Team Alpha has high rank confidence.`
- `Team Beta is an unreliable manager.`
- `LOW frequency plus LOW sensitivity means robust.`
- `The lowest-frequency team has the most reliable manager.`
- `Frequency should alter the BF-500 baseline rank.`
- `These qualitative bands are comparable across leagues.`

## Governance decision

BF-516 closes with the following explicit decision:

```text
QUALITATIVE_RANK_CHANGE_FREQUENCY_CLASSIFICATION_NOT_AUTHORIZED_V1
```

Reason:

```text
current deterministic leave-one-week-out frequency has no calibrated,
non-arbitrary threshold scheme that can support categorical interpretation
without adding unsupported confidence, reliability, or cross-sample semantics
```

The existing BF-512 raw frequency artifact remains the authorized surface.

## Conditions for reconsideration

A future methodology may revisit qualitative frequency semantics only if it introduces a defensible calibration basis, for example:

1. an explicit product decision that requires categories rather than raw evidence;
2. a historical validation corpus large enough to evaluate candidate thresholds across varying denominators;
3. a clearly defined target outcome that the categories are intended to summarize or predict;
4. evidence that proposed boundaries are not merely arbitrary round percentages;
5. explicit treatment of league size, perturbation denominator, ties, and common-week count; and
6. preserved separation from manager skill, fault, quality, and probabilistic confidence unless separately governed.

Meeting those conditions would justify a new methodology review. It would not retroactively authorize v1 bands.

## Implementation boundary

**No implementation sequence is authorized by BF-516.**

Do not create a BF-517 qualitative frequency classifier, CLI, routing surface, or help entry under this methodology.

The next permitted work must either:

- stay within the already implemented raw BF-512 frequency surface; or
- begin a new governed methodology for a materially different question.

**Stop boundary:** no qualitative frequency tier, magnitude-frequency matrix, composite sensitivity score, manager consistency/reliability label, statistical confidence/probability claim, predictive model, adjusted rank, sensitivity leaderboard, league-wide sensitivity score, recommendation, causal interpretation, skill/fault attribution, coverage-adjusted composite, or cross-league manager comparison is authorized by BF-516.