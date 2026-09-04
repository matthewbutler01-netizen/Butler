# Trade Counter Strategic Vetting

Butler keeps market-only counter evidence and season-aware strategic vetting as separate governed surfaces.

The market-only command remains:

`butler trade counter-value <league-id> <side-a-assets> <side-b-assets> [source] [--minimum-as-of YYYY-MM-DD]`

The season-aware strategic surface is:

`butler trade counter-strategic <league-id> <season> <side-a-assets> <side-b-assets> [source] [--minimum-as-of YYYY-MM-DD]`

Strategic vetting policy: `trade-counter-strategic-candidate-v1-bilateral-v5-veto`

Strategic eligibility policy: `trade-counter-strategic-eligibility-v1-clear-only-preserve-market-rank`

Market candidate policy: `trade-counter-single-asset-candidate-v1-market-fair-minimum-excess`

Strategic veto policy: `trade-strategic-veto-v4-material-protected-value-plus-flexible-transition-loss`

## Purpose

`trade counter-strategic` starts with the existing ranked single-asset market-fair candidates. For each candidate, Butler reconstructs the modified trade package and reruns the full season-aware `TradeFlexibleRecommendationContextAnalyzer` evidence stack.

The candidate is then evaluated bilaterally through the same governed material-loss protection used by live Trade Recommendation v5:

- future-capital material protected-value loss;
- direct positional-pressure material same-position loss;
- existing FLEX/SUPERFLEX pressure material legal-coverage loss;
- material legal-coverage loss when the team newly transitions into FLEXIBLE_PRESSURE.

Both trade teams are evaluated. A candidate is `BLOCKED` when either side receives a governed strategic veto; otherwise it is `CLEAR`.

This bilateral status is a safety annotation, not a recommendation. Market candidate rank is preserved exactly and is not re-sorted by strategic state.

## Strategic eligibility

After bilateral vetting, Butler derives a strategically eligible subset without creating a new score or ranking:

- `CLEAR` candidates remain strategically eligible;
- `BLOCKED` candidates are excluded from the eligible subset;
- eligible candidates retain their original market rank, including gaps caused by excluded candidates;
- the first eligible candidate is not automatically selected or preferred by policy.

The CLI prints both the original vetted candidate evidence and the derived eligible subset so the filtering step remains auditable.

## Evidence availability

Strategic candidate vetting fails closed before labeling candidates unless the reconstructed candidate trade has complete required evidence for:

1. market direction;
2. team posture;
3. future capital;
4. direct positional pressure;
5. combined flexible pressure.

No missing strategic dimension is interpreted as `CLEAR`. If strategic vetting is unavailable, strategic eligibility is also unavailable and exposes no candidates.

The season is explicit because team posture and related strategic context are season-aware. Butler does not infer or default the season from the current date.

## Candidate reconstruction

A strategic candidate must reconstruct into a valid Butler trade package.

- add-to-lower candidates add exactly the named player or draft pick to the governed target side;
- remove-from-higher candidates remove exactly the named asset from that package;
- removing an asset may not leave a trade side empty;
- all analysis retains the original league, value source, and minimum-as-of boundary.

Every strategic candidate originates from the existing market candidate policy, so its market result has already been verified as `MARKET_FAIR` under the governed fairness engine.

## Interpretation boundary

These policies and the CLI do **not**:

- choose the first or any other candidate;
- change market candidate ranking;
- infer a user/team perspective;
- emit `COUNTER`, `ACCEPT`, `REJECT`, `HOLD`, or `INCONCLUSIVE`;
- create multi-asset counter packages;
- modify Trade Recommendation v5;
- blend market value and strategic evidence into a score;
- weaken or reinterpret any existing strategic veto policy.

A future policy may use the strategically eligible subset to construct a team-perspective counter recommendation, but that requires a separately versioned decision contract.
