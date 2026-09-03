package io.butler.bet.intelligence;

/** Governed league-relative lineup-aware positional pressure policy. */
public final class LeaguePositionalPressurePolicy {
    public static final String POLICY_ID = "positional-pressure-v1-lineup-relative-quartiles";
    public static final int MINIMUM_LEAGUE_TEAMS = 4;

    private LeaguePositionalPressurePolicy() {}

    public enum Tier {
        POSITION_PRESSURE,
        POSITION_BALANCED,
        POSITION_STRENGTH,
        NO_DIRECT_REQUIREMENT,
        INSUFFICIENT_EVIDENCE
    }
}
