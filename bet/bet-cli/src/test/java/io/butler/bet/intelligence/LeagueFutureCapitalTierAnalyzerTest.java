package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeagueFutureCapitalTierAnalyzerTest {
    @Test
    void classifiesLeagueRelativeQuartilesAndAllowsZeroPickTeam() {
        var report = LeagueFutureCapitalTierAnalyzer.classify(report(List.of(
            team("t1", "One", 400.0, 4, 0, 0, 4),
            team("t2", "Two", 300.0, 3, 0, 0, 3),
            team("t3", "Three", 200.0, 2, 0, 0, 2),
            team("t4", "Four", 0.0, 0, 0, 0, 0))));

        assertTrue(report.available());
        assertEquals(LeagueFutureCapitalTierPolicy.Tier.HIGH_FUTURE_CAPITAL, tier(report, "t1"));
        assertEquals(LeagueFutureCapitalTierPolicy.Tier.MIDDLE_FUTURE_CAPITAL, tier(report, "t2"));
        assertEquals(LeagueFutureCapitalTierPolicy.Tier.MIDDLE_FUTURE_CAPITAL, tier(report, "t3"));
        assertEquals(LeagueFutureCapitalTierPolicy.Tier.LOW_FUTURE_CAPITAL, tier(report, "t4"));
    }

    @Test
    void preservesTiesAtQuartileBoundary() {
        var report = LeagueFutureCapitalTierAnalyzer.classify(report(List.of(
            team("t1", "One", 400.0, 1, 0, 0, 1),
            team("t2", "Two", 400.0, 1, 0, 0, 1),
            team("t3", "Three", 200.0, 1, 0, 0, 1),
            team("t4", "Four", 100.0, 1, 0, 0, 1),
            team("t5", "Five", 100.0, 1, 0, 0, 1))));

        assertEquals(LeagueFutureCapitalTierPolicy.Tier.HIGH_FUTURE_CAPITAL, tier(report, "t1"));
        assertEquals(LeagueFutureCapitalTierPolicy.Tier.HIGH_FUTURE_CAPITAL, tier(report, "t2"));
        assertEquals(LeagueFutureCapitalTierPolicy.Tier.LOW_FUTURE_CAPITAL, tier(report, "t4"));
        assertEquals(LeagueFutureCapitalTierPolicy.Tier.LOW_FUTURE_CAPITAL, tier(report, "t5"));
    }

    @Test
    void allEqualValuesCollapseToMiddle() {
        var report = LeagueFutureCapitalTierAnalyzer.classify(report(List.of(
            team("t1", "One", 100.0, 1, 0, 0, 1),
            team("t2", "Two", 100.0, 1, 0, 0, 1),
            team("t3", "Three", 100.0, 1, 0, 0, 1),
            team("t4", "Four", 100.0, 1, 0, 0, 1))));

        assertTrue(report.available());
        report.teams().forEach(team -> assertEquals(
            LeagueFutureCapitalTierPolicy.Tier.MIDDLE_FUTURE_CAPITAL, team.tier()));
    }

    @Test
    void incompleteOrStalePickCoverageFailsClosed() {
        var missing = LeagueFutureCapitalTierAnalyzer.classify(report(List.of(
            team("t1", "One", 100.0, 1, 0, 1, 2),
            team("t2", "Two", 100.0, 1, 0, 0, 1),
            team("t3", "Three", 100.0, 1, 0, 0, 1),
            team("t4", "Four", 100.0, 1, 0, 0, 1))));
        assertFalse(missing.available());
        missing.teams().forEach(team -> assertEquals(
            LeagueFutureCapitalTierPolicy.Tier.INSUFFICIENT_EVIDENCE, team.tier()));

        var stale = LeagueFutureCapitalTierAnalyzer.classify(report(List.of(
            team("t1", "One", 100.0, 1, 1, 0, 2),
            team("t2", "Two", 100.0, 1, 0, 0, 1),
            team("t3", "Three", 100.0, 1, 0, 0, 1),
            team("t4", "Four", 100.0, 1, 0, 0, 1))));
        assertFalse(stale.available());
    }

    @Test
    void emptyLeaguePickInventoryIsInsufficient() {
        var report = LeagueFutureCapitalTierAnalyzer.classify(report(List.of(
            team("t1", "One", 0.0, 0, 0, 0, 0),
            team("t2", "Two", 0.0, 0, 0, 0, 0),
            team("t3", "Three", 0.0, 0, 0, 0, 0),
            team("t4", "Four", 0.0, 0, 0, 0, 0))));
        assertFalse(report.available());
    }

    private static LeagueFutureCapitalTierPolicy.Tier tier(
        LeagueFutureCapitalTierAnalyzer.FutureCapitalReport report, String teamId) {
        return report.teams().stream().filter(team -> team.teamId().equals(teamId)).findFirst().orElseThrow().tier();
    }

    private static LeagueDraftCapitalTimelineAnalyzer.DraftCapitalReport report(
        List<LeagueDraftCapitalTimelineAnalyzer.TeamDraftCapital> teams) {
        return new LeagueDraftCapitalTimelineAnalyzer.DraftCapitalReport("l1", "source", null, teams);
    }

    private static LeagueDraftCapitalTimelineAnalyzer.TeamDraftCapital team(
        String id, String name, double value, int valued, int stale, int missing, int total) {
        return new LeagueDraftCapitalTimelineAnalyzer.TeamDraftCapital(
            id, name, value, valued, stale, missing, total, List.of());
    }
}
