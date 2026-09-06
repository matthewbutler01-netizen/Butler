# Governed validation-phase methodology

BF-533 records the post-BF-532 methodology decision for Butler's lineup-capture rank-sensitivity work.

The decision is deliberately conservative:

```text
SCALAR_CANDIDATE_SELECTION_OBJECTIVE_NOT_AUTHORIZED_V1
AUTOMATIC_CANDIDATE_SELECTION_NOT_AUTHORIZED_V1
END_TO_END_VALIDATION_PHASE_AUTHORIZED
PRODUCTION_THRESHOLD_NOT_AUTHORIZED
CONFIDENCE_OR_PROBABILITY_NOT_AUTHORIZED
```

## Why Butler does not choose a threshold yet

BF-526 and BF-530 expose useful held-out persistence evidence, but that evidence does not justify a defensible scalar objective for selecting one candidate.

The unresolved problems are structural rather than implementation gaps:

- future-only ordinal rank movement is descriptive persistence evidence, not ground truth;
- league-season clusters remain few and internally correlated;
- candidate availability differs by development fold;
- some candidate/fold pairs remain unevaluable;
- held-out rule-side row counts can be imbalanced;
- team-count and perturbation-denominator context differs by candidate; and
- normalizing raw displacement into a score would introduce new assumptions and a model-selection objective that has not been validated.

BF-533 therefore rejects shortcuts such as choosing the most frequently lower-displacement candidate, the widest-support candidate, the most conservative candidate, or the candidate with the largest apparent held-out separation.

## Authorized next phase: validation and testing

The next governed step is deterministic acceptance testing of the behavior that already exists.

Testing is allowed to verify that Butler faithfully implements the governed evidence pipeline from persisted inputs through public CLI evidence surfaces.

The primary acceptance path is:

```text
persisted SQLite evidence
  -> historical calibration corpus audit
  -> structural readiness
  -> BF-526 candidate threshold study
  -> BF-530 candidate cross-fold support audit
  -> BF-531 CLI rendering
```

## Required acceptance scenarios

A conforming acceptance suite should cover at least:

1. **Available multi-cluster path** — persisted evidence produces an available BF-526 study and BF-530 support audit.
2. **Fail-closed path** — insufficient or unavailable source evidence publishes no partial candidate support output.
3. **Candidate identity/order preservation** — BF-526 candidate identities and family/value order survive through BF-530/BF-531 unchanged.
4. **Leakage boundary** — held-out values never expand their own development-fold candidate vocabulary.
5. **Cluster accounting** — league-season fold counts remain distinct from team-cutoff row counts and are never presented as independent sample N.
6. **Support semantics** — all four BF-529 support states remain structural breadth labels only.
7. **Direction semantics** — raw displacement direction is shown beside source side row counts and distributions and is never normalized into a hidden score.
8. **No-selection boundary** — output contains no best/winner/recommended threshold, performance ranking, objective score, tie-breaker, or production authorization.
9. **No-confidence boundary** — output contains no probability, statistical significance, confidence score, confidence interval, or reliability semantics.
10. **No manager attribution** — output contains no manager skill, fault, quality, reliability, or consistency grade.
11. **Determinism** — identical persisted fixtures produce identical report and rendered evidence.
12. **Router/CLI reachability** — the governed command remains reachable through the central Butler router with the documented season-range interface.

## Test fixtures

Acceptance fixtures may use deterministic synthetic persisted evidence specifically designed to exercise the governed states.

Synthetic fixtures are test inputs only. They must not be described as real-world calibration evidence and must never produce a production threshold.

Fixtures should preserve real repository behavior wherever practical:

- SQLite repositories and schema initialization;
- persisted league/team/player/configuration evidence;
- persisted roster and production coverage evidence;
- deterministic historical week/cutoff windows; and
- the production analyzers and CLI renderers under test.

The acceptance suite should avoid mocking the candidate-study or support-audit outputs when the purpose of the test is end-to-end pipeline validation.

## Acceptance test failure semantics

A failing acceptance scenario is a defect signal, not evidence that the methodology should be weakened.

Tests must not be made green by:

- lowering BF-521 readiness gates;
- synthesizing missing candidate identities;
- forcing held-out splits;
- pooling team-cutoff rows as independent samples;
- changing candidate order based on apparent performance;
- adding a hidden scalar score; or
- silently selecting a threshold.

Fix the implementation or fixture when it violates the existing governed contract.

## Testing does not authorize calibration

Passing acceptance tests proves only that Butler behaves according to the current governed specification on the tested fixtures.

It does not prove:

- statistical adequacy;
- real-world predictive validity;
- threshold optimality;
- probability calibration;
- rank correctness;
- manager quality; or
- production readiness of an automatic threshold.

## UX guardrail during validation

Where acceptance testing touches user-facing output, it should preserve the product guardrail recorded in GitHub issue #534:

- decision-relevant information remains understandable;
- advanced evidence is available without implying false confidence;
- no gambling-style urgency or engagement pressure is introduced;
- desktop/web output remains usable; and
- peak-load testing is treated as a later explicit production-readiness requirement rather than assumed from unit-test success.

## Authorized next sequence

BF-533 authorizes only:

1. **BF-534** — implement a deterministic end-to-end acceptance-test fixture/harness for the governed BF-518/BF-522/BF-526/BF-530/BF-531 path;
2. run that acceptance suite in CI and begin defect-driven testing; and
3. stop before any automatic candidate selection, scalar objective, production threshold, confidence/probability model, adjusted rank, manager score, leaderboard, or recommendation.

**Testing start boundary:** once BF-534 is running its end-to-end acceptance scenarios in CI, Butler has entered the validation/testing phase. Further work should be driven by observed test failures, missing acceptance coverage, and product-readiness requirements rather than by inventing a candidate winner.
