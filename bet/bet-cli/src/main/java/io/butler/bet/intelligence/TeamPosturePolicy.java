package io.butler.bet.intelligence;

import java.util.Objects;

/**
 * Governs team posture from agreement between independent competitive-performance and roster-strength tiers.
 * No weighted score, trade recommendation, or value adjustment is produced here.
 */
public final class TeamPosturePolicy {
    public static final String POLICY_ID = "team-posture-v1-tier-agreement";

    private TeamPosturePolicy() {}

    public enum Posture {
        CONTENDER,
        MIDDLE_OR_MIXED,
        REBUILDER,
        INSUFFICIENT_EVIDENCE
    }

    public static Posture classify(LeagueCompetitiveTierPolicy.Tier competitive,
                                   LeagueRosterStrengthTierPolicy.Tier roster) {
        Objects.requireNonNull(competitive, "competitive tier must not be null");
        Objects.requireNonNull(roster, "roster tier must not be null");

        if (competitive == LeagueCompetitiveTierPolicy.Tier.INSUFFICIENT_EVIDENCE
            || roster == LeagueRosterStrengthTierPolicy.Tier.INSUFFICIENT_EVIDENCE) {
            return Posture.INSUFFICIENT_EVIDENCE;
        }
        if (competitive == LeagueCompetitiveTierPolicy.Tier.FRONT_TIER
            && roster == LeagueRosterStrengthTierPolicy.Tier.FRONT_ROSTER_TIER) {
            return Posture.CONTENDER;
        }
        if (competitive == LeagueCompetitiveTierPolicy.Tier.BACK_TIER
            && roster == LeagueRosterStrengthTierPolicy.Tier.BACK_ROSTER_TIER) {
            return Posture.REBUILDER;
        }
        return Posture.MIDDLE_OR_MIXED;
    }
}
