# Player Evidence Profile

Butler's neutral player-evidence profile composes age context and raw season-production context without reducing either dimension to a score, grade, roster posture, or strategy label.

## CLI

```text
butler league player-evidence-profile <league-id> [season] [--age-as-of YYYY-MM-DD] [--minimum-profile-as-of YYYY-MM-DD]
```

If `season` is omitted, Butler uses the league season persisted by supported league imports. If no league season is available, the command refuses to guess and requires an explicit season.

`--age-as-of` controls the date used to derive age from exact birth dates. Provider-reported ages are preserved as reported and are not extrapolated into invented birthdays or future ages.

`--minimum-profile-as-of` applies only to provider-reported profile snapshots. Exact canonical birth dates remain usable because age is derived from the exact date rather than from a stale reported age.

## Output contract

The profile keeps age and production evidence independent. At league and team level Butler reports separate age coverage and production coverage. A roster can therefore have complete age evidence and partial production evidence, or the reverse, without one dimension masking the other.

Age context includes coverage, average/minimum/maximum age where available, and exact-birth-date versus provider-reported provenance. Production context remains raw and scoring-neutral: games played, passing/rushing/receiving production, interceptions, fumbles lost, source, and snapshot dates.

Butler does not convert this profile into a universal fantasy score, player grade, career-arc classification, contender/rebuilder label, buyer/seller posture, or recommendation. Those are later interpretation concerns and must be separately governed.

## Related commands

```text
butler league age-context <league-id> [--age-as-of YYYY-MM-DD] [--minimum-profile-as-of YYYY-MM-DD]
butler league production-context <league-id> [season]
butler league player-evidence-readiness <league-id> [season] [--minimum-profile-as-of YYYY-MM-DD]
butler league evidence-overview <league-id> [season] [--minimum-value-as-of YYYY-MM-DD] [--minimum-profile-as-of YYYY-MM-DD]
```

Use `age-context` or `production-context` when inspecting one evidence dimension in detail. Use `player-evidence-readiness` when the question is whether required evidence is complete. Use `player-evidence-profile` when the question is what the two player-evidence dimensions look like together without interpreting them strategically.
