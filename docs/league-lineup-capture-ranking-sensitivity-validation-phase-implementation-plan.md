# Validation phase implementation plan

BF-533 redirects the lineup-capture rank-sensitivity sequence from candidate selection into deterministic acceptance testing.

## BF-534 target

BF-534 will implement the first end-to-end acceptance test for the governed path:

```text
persisted SQLite evidence
  -> calibration corpus audit
  -> readiness
  -> candidate threshold study
  -> candidate cross-fold support audit
  -> CLI rendering
```

The test should seed deterministic synthetic repository evidence rather than constructing BF-526/BF-530 reports by hand.

## Minimum BF-534 acceptance cases

- available multi-league-season fixture produces available candidate-study/support-audit output;
- empty/insufficient fixture fails closed without candidate evidence;
- candidate identities and order remain unchanged through the pipeline;
- cluster/fold counts stay separate from row counts;
- raw direction evidence retains both side row counts/distributions;
- CLI output preserves no-selection, no-confidence, and no-manager-attribution boundaries;
- identical fixture inputs produce identical reports/rendered output.

## Implementation preference

Keep the acceptance harness under the existing JUnit/Gradle test runtime so the normal repository build executes it automatically. Prefer a dedicated acceptance-test class/package and reusable deterministic test fixture helper over a second build system.

Do not add live provider writes, network dependencies, production data, or a selected threshold to make the acceptance suite work.

## Entry criterion for the testing phase

Butler is considered to have entered the validation/testing phase when the BF-534 end-to-end acceptance test is executing in GitHub Actions as part of the repository build.

At that point, follow-on work is defect/coverage driven. A failing acceptance test should block merge until the implementation or fixture is corrected without weakening the governed analytical boundaries.
