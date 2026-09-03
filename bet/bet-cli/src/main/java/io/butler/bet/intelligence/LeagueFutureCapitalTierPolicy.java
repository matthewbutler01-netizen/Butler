package io.butler.bet.intelligence;

/** Governed league-relative future draft-capital tier policy. */
public final class LeagueFutureCapitalTierPolicy {
    public static final String POLICY_ID = "future-capital-tier-v1-draft-value-quartiles";
    public static final int MINIMUM_LEAGUE_TEAMS = 4;
    public static final double TIER_FRACTION = 0.25;

    private LeagueFutureCapitalTierPolicy() {}

    public enum Tier {
        HIGH_FUTURE_CAPITAL,
        MIDDLE_FUTURE_CAPITAL,
        LOW_FUTURE_CAPITAL,
        INSUFFICIENT_EVIDENCE
    }

    public static int outerTierSize(int teamCount) {
        if (teamCount < MINIMUM_LEAGUE_TEAMS) {
            throw new IllegalArgumentException("teamCount must be at least " + MINIMUM_LEAGUE_TEAMS);
        }
        return Math.max(1, (int) Math.floor(teamCount * TIER_FRACTION));
    }
}
