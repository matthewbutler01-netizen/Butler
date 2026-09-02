# Player Evidence Season Resolution

For leagues synced from a supported provider such as Sleeper, Butler persists the league season and uses it by default for player-evidence readiness.

```text
butler league player-evidence-readiness <league-id>
butler league player-evidence-readiness <league-id> --minimum-profile-as-of YYYY-MM-DD
```

An explicit season remains supported as an override and for manual or legacy leagues:

```text
butler league player-evidence-readiness <league-id> <season>
butler league player-evidence-readiness <league-id> <season> --minimum-profile-as-of YYYY-MM-DD
```

Butler does not infer season from the current calendar year. If a league has no persisted season and no explicit season is supplied, readiness fails explicitly and asks for a season or a provider re-sync.

The selected season controls which season-production snapshots are required. Profile freshness remains independent: `--minimum-profile-as-of` applies only to provider-reported profile facts. Exact canonical birth dates remain usable because age is derived from the birth date rather than from a stale reported age.

Player-evidence readiness still describes evidence availability only. It does not grade player quality, team competitiveness, or strategy.