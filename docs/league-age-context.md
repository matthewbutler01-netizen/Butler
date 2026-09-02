# League age context

Butler exposes neutral roster-age context without turning age into a player grade, career-arc label, team posture, or strategy recommendation.

## Command

```text
butler league age-context <league-id> [--age-as-of YYYY-MM-DD] [--minimum-profile-as-of YYYY-MM-DD]
```

`--age-as-of` controls the date used to derive age when Butler has an exact canonical birth date. When omitted, Butler uses the current UTC date.

`--minimum-profile-as-of` applies only to provider-reported profile snapshots. Provider-reported age that predates the cutoff remains identifiable as stale evidence and does not satisfy usable age coverage.

## Provenance rules

Butler preserves age provenance rather than flattening all age evidence into one field:

- `EXACT_BIRTH_DATE` means age was derived from an exact canonical birth date on the requested analysis date.
- `PROVIDER_REPORTED` means Butler displays the provider's reported age exactly as reported. It does not invent a birthday or extrapolate that reported age forward.
- `UNAVAILABLE` means the player has no usable age evidence under the current profile-source/freshness rules.

Exact birth-date evidence takes precedence when both exact and provider-reported evidence exist.

## Output

The command reports:

- league-wide age coverage;
- exact-birth-date and provider-reported provenance counts;
- team-level coverage, average age, minimum age, and maximum age;
- position-level coverage and the same neutral age summary fields;
- rostered players whose age is unavailable.

Missing age evidence stays visible instead of being silently removed from averages or coverage denominators.

## Interpretation boundary

Age context is descriptive evidence only. Butler does not define thresholds for "young," "old," "ascending," "declining," "win-now," "rebuilding," or similar strategy concepts here. Those labels would require an explicit governed interpretation layer and should not be inferred from age alone.
