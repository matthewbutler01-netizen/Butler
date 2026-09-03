package io.butler.bet.intelligence;

/** Governed league-relative current-roster strength tier policy. */
public final class LeagueRosterStrengthTierPolicy {
    public static final String POLICY_ID = "roster-strength-tier-v1-starter-total-quartiles";
    public static final int MINIMUM_LEAGUE_TEAMS = 4;

    private LeagueRosterStrengthTierPolicy() {}

    public enum Tier {
        FRONT_ROSTER_TIER,
        MIDDLE_ROSTER_TIER,
        BACK_ROSTER_TIER,
        INSUFFICIENT_EVIDENCE
    }
}
