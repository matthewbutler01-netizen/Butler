# Longitudinal aging evidence coverage

Butler measures whether its stored player evidence can support later empirical aging analysis before fitting or applying any aging curve.

## Command

```text
butler league longitudinal-evidence <league-id>
```

The command reports historical evidence for the league's current rostered players. It is an evidence-coverage surface, not a player evaluation or model output.

## What Butler counts

Butler reports, at league, team, position, and player levels:

- players with canonical exact birth dates;
- stored production player-seasons;
- rate-eligible player-seasons, where a production snapshot exists and games played is greater than zero;
- consecutive rate-eligible season pairs;
- consecutive season pairs that also have exact age evidence from a canonical birth date.

Repeated refreshes do not inflate the history: Butler selects the latest stored production snapshot for each player, season, and source.

A zero-game season remains historical production evidence, but it is not rate-eligible because per-game rates cannot be calculated without fabricating a denominator.

## Why exact birth dates matter

Provider-reported current age is useful for current-age context, but Butler does not backdate that reported age into historical seasons. Historical aging analysis therefore requires a canonical exact birth date so age can be derived for the relevant season dates.

This prevents Butler from inventing birthdays or historical ages from an approximate or provider-reported current age.

## Interpretation boundary

Longitudinal evidence coverage does not define a minimum sample size and does not declare the data sufficient for a model. It does not fit an aging curve, normalize production, calculate an age-adjusted score, label a player as ascending or declining, classify a team's lifecycle, or recommend a strategy.

The output exists so a later governed modeling layer can make its sample requirements and methodology explicit instead of assuming that any amount of historical data is adequate.

## Recommended workflow

```text
butler nflverse production-history-preview <start-season> <end-season>
butler nflverse production-history-refresh <start-season> <end-season>
butler league longitudinal-evidence <league-id>
```

Inspect failed seasons and exact-birth-date coverage before treating the stored history as a candidate modeling dataset.
