# Player Evidence Profile

Butler's neutral player-evidence profile composes age context, raw season-production context, and governed supporting-evidence flags without reducing any dimension to a blended score, grade, roster posture, strategy label, dynasty adjustment, or recommendation.

## CLI

```text
butler league player-evidence-profile <league-id> [season] [--age-as-of YYYY-MM-DD] [--minimum-profile-as-of YYYY-MM-DD]
```

If `season` is omitted, Butler uses the league season persisted by supported league imports. If no league season is available, the command refuses to guess and requires an explicit season.

`--age-as-of` controls the date used for the neutral age-context dimension. Provider-reported ages are preserved as reported and are not extrapolated into invented birthdays or future ages.

`--minimum-profile-as-of` applies only to provider-reported profile snapshots. Exact canonical birth dates remain usable because age is derived from the exact date rather than from a stale reported age.

The aging-model supporting-evidence dimension uses a separate governed coordinate: exact-DOB age on September 1 of the requested season. The CLI prints this aging-model as-of date explicitly so it is not confused with `--age-as-of`.

## Output contract

The profile keeps age, production, and supporting evidence independent. At league and team level Butler reports separate age coverage, production coverage, supporting-flag counts, and directional supporting-flag counts. One dimension never fills gaps in another.

Age context includes coverage, average/minimum/maximum age where available, and exact-birth-date versus provider-reported provenance. Production context remains raw and scoring-neutral: games played, passing/rushing/receiving production, interceptions, fumbles lost, source, and snapshot dates.

Supporting evidence currently includes governed aging-model outlook flags. Each flag carries a subject, category, metric dimension, signal, summary, policy ID, and evidence source. The signal is one of `FAVORABLE`, `UNFAVORABLE`, or `INCONCLUSIVE`. Neutral or mixed historical aging evidence maps to `INCONCLUSIVE`; Butler does not force direction.

Supporting flags intentionally have no numeric weight or score contribution. Empty supporting evidence is allowed and does not block the age or production profile. When supporting evidence is present, league, season, and team coverage are checked for consistency and mismatches fail closed rather than being silently joined.

The current aging-model outlook remains deliberately conservative. The governed policy uses the full interquartile interval and metric directionality, and most validated model cells are inconclusive. A directional flag is therefore supporting context, not a standalone player judgment.

Butler does not convert this profile into a universal fantasy score, player grade, career-arc classification, contender/rebuilder label, buyer/seller posture, dynasty-value adjustment, or recommendation. Those are later interpretation concerns and must be separately governed.

## Related commands

```text
butler league age-context <league-id> [--age-as-of YYYY-MM-DD] [--minimum-profile-as-of YYYY-MM-DD]
butler league production-context <league-id> [season]
butler league age-outlook <league-id> <season>
butler league supporting-evidence <league-id> <season>
butler league player-evidence-readiness <league-id> [season] [--minimum-profile-as-of YYYY-MM-DD]
butler league evidence-overview <league-id> [season] [--minimum-value-as-of YYYY-MM-DD] [--minimum-profile-as-of YYYY-MM-DD]
```

Use `age-context` or `production-context` when inspecting one neutral evidence dimension in detail. Use `age-outlook` to inspect the governed per-metric historical aging interpretation and `supporting-evidence` to inspect the generic decision-support flag boundary directly. Use `player-evidence-readiness` when the question is whether required evidence is complete. Use `player-evidence-profile` when the question is what the canonical player-evidence package contains without interpreting it strategically.
