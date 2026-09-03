package io.butler.bet.intelligence;

/**
 * Governed league-relative competitive tier policy.
 *
 * <p>The policy is intentionally descriptive. It does not create contender/rebuilder labels,
 * roster strategy, trade recommendations, or value adjustments.</p>
 */
public final class LeagueCompetitiveTierPolicy {
    public static final String POLICY_ID = "league-competitive-tier-v1-relative-quartiles";
    public static final int MIN_GAMES = 4;
    public static final double TIER_FRACTION = 0.25;

    private LeagueCompetitiveTierPolicy() {}

    public enum Tier {
        FRONT_TIER,
        MIDDLE_TIER,
        BACK_TIER,
        INSUFFICIENT_EVIDENCE
    }

    /**
     * Number of teams used for each outer tier. Floor preserves the rule that an outer tier never
     * represents more than 25% of the ranked league. At least one team is selected for leagues with
     * four or more teams.
     */
    public static int outerTierSize(int teamCount) {
        if (teamCount < 4) throw new IllegalArgumentException("teamCount must be at least 4");
        return Math.max(1, (int) Math.floor(teamCount * TIER_FRACTION));
    }
}
