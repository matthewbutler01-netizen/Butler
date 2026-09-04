package io.butler.bet.intelligence;

/** Governed league-relative combined FLEX/SUPERFLEX pressure policy. */
public final class LeagueFlexibleSlotPressurePolicy {
    public static final String POLICY_ID = "flexible-slot-pressure-v1-combined-relative-quartiles";
    public static final int MINIMUM_LEAGUE_TEAMS = 4;

    private LeagueFlexibleSlotPressurePolicy() {}

    public enum Tier {
        FLEXIBLE_PRESSURE,
        FLEXIBLE_BALANCED,
        FLEXIBLE_STRENGTH,
        NO_FLEXIBLE_REQUIREMENT,
        INSUFFICIENT_EVIDENCE
    }
}
