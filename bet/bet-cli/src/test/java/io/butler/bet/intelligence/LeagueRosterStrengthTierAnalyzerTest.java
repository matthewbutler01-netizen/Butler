package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeagueRosterStrengthTierAnalyzerTest {
    @Test
    void ranksStarterValueThenTotalPlayerValueIntoRelativeQuartiles() {
        var report = LeagueRosterStrengthTierAnalyzer.classify("l1", "market", null, List.of(
            team("a", 100, 150), team("b", 90, 200), team("c", 90, 180), team("d", 80, 170),
            team("e", 70, 160), team("f", 60, 150), team("g", 50, 140), team("h", 40, 130)));

        assertTrue(report.available());
        assertEquals(LeagueRosterStrengthTierPolicy.Tier.FRONT_ROSTER_TIER, tier(report, "a"));
        assertEquals(LeagueRosterStrengthTierPolicy.Tier.FRONT_ROSTER_TIER, tier(report, "b"));
        assertEquals(LeagueRosterStrengthTierPolicy.Tier.MIDDLE_ROSTER_TIER, tier(report, "c"));
        assertEquals(LeagueRosterStrengthTierPolicy.Tier.BACK_ROSTER_TIER, tier(report, "g"));
        assertEquals(LeagueRosterStrengthTierPolicy.Tier.BACK_ROSTER_TIER, tier(report, "h"));
    }

    @Test
    void preservesTiesAtQuartileBoundary() {
        var report = LeagueRosterStrengthTierAnalyzer.classify("l1", "market", null, List.of(
            team("a", 100, 150), team("b", 90, 180), team("c", 90, 180), team("d", 80, 160),
            team("e", 70, 150), team("f", 60, 140), team("g", 50, 130), team("h", 40, 120)));

        assertEquals(LeagueRosterStrengthTierPolicy.Tier.FRONT_ROSTER_TIER, tier(report, "b"));
        assertEquals(LeagueRosterStrengthTierPolicy.Tier.FRONT_ROSTER_TIER, tier(report, "c"));
    }

    @Test
    void incompleteCoverageFailsClosedForWholeLeague() {
        var incomplete = new LeagueRosterStrengthTierAnalyzer.TeamRosterStrength("a", "A", 100, 150,
            10, 9, 0, 1, LeagueRosterStrengthTierPolicy.Tier.MIDDLE_ROSTER_TIER);
        var report = LeagueRosterStrengthTierAnalyzer.classify("l1", "market", null, List.of(
            incomplete, team("b", 90, 140), team("c", 80, 130), team("d", 70, 120)));

        assertFalse(report.available());
        assertTrue(report.teams().stream().allMatch(t -> t.tier() == LeagueRosterStrengthTierPolicy.Tier.INSUFFICIENT_EVIDENCE));
    }

    @Test
    void allEqualTeamsStayMiddleInsteadOfReceivingContradictoryOuterTiers() {
        var report = LeagueRosterStrengthTierAnalyzer.classify("l1", "market", null, List.of(
            team("a", 100, 150), team("b", 100, 150), team("c", 100, 150), team("d", 100, 150)));
        assertTrue(report.teams().stream().allMatch(t -> t.tier() == LeagueRosterStrengthTierPolicy.Tier.MIDDLE_ROSTER_TIER));
    }

    private static LeagueRosterStrengthTierAnalyzer.TeamRosterStrength team(String id, double starters, double total) {
        return new LeagueRosterStrengthTierAnalyzer.TeamRosterStrength(id, id.toUpperCase(), starters, total,
            10, 10, 0, 0, LeagueRosterStrengthTierPolicy.Tier.INSUFFICIENT_EVIDENCE);
    }

    private static LeagueRosterStrengthTierPolicy.Tier tier(LeagueRosterStrengthTierAnalyzer.RosterStrengthReport report, String id) {
        return report.teams().stream().filter(t -> t.teamId().equals(id)).findFirst().orElseThrow().tier();
    }
}
