package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeagueFlexibleSlotCoverageAnalyzerTest {
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 1);

    @Test
    void reservesDirectStartersBeforeMaximizingFlexibleCoverage() {
        var lineup = LeagueLineupRequirementsAnalyzer.interpret("l1",
            List.of("QB", "RB", "WR", "TE", "FLEX", "SUPER_FLEX"));
        var depth = report(team("a", "Team A", Map.of(
            "QB", position("QB", player("q1", "QB", 100), player("q2", "QB", 90)),
            "RB", position("RB", player("r1", "RB", 80), player("r2", "RB", 70)),
            "WR", position("WR", player("w1", "WR", 60), player("w2", "WR", 50)),
            "TE", position("TE", player("t1", "TE", 40), player("t2", "TE", 30)))));

        var result = LeagueFlexibleSlotCoverageAnalyzer.compose(lineup, depth);
        var team = result.teams().getFirst();

        assertTrue(result.available());
        assertEquals("flexible-slot-coverage-v1-direct-reserved-max-value", result.policyId());
        assertEquals(4, team.directRequiredSlots());
        assertEquals(4, team.directCoveredSlots());
        assertEquals(280.0, team.directReservedValue());
        assertEquals(2, team.flexibleSlots());
        assertEquals(2, team.flexibleCoveredSlots());
        assertEquals(0, team.flexibleUnfilledSlots());
        assertEquals(160.0, team.flexibleCoverageValue());
        assertEquals(240.0, team.eligibleRemainingValue());
    }

    @Test
    void superflexDoesNotForceQuarterbackWhenNonQuarterbacksCoverMoreValue() {
        var qbs = List.of(player("q", "QB", 10));
        var flex = List.of(player("r", "RB", 90), player("w", "WR", 80));

        var coverage = LeagueFlexibleSlotCoverageAnalyzer.maximizeCoverage(qbs, flex, 2, 1);

        assertEquals(2, coverage.coveredSlots());
        assertEquals(170.0, coverage.coveredValue());
    }

    @Test
    void directStarterCannotBeCountedAgainInFlex() {
        var lineup = LeagueLineupRequirementsAnalyzer.interpret("l1", List.of("RB", "FLEX"));
        var depth = report(team("a", "Team A", Map.of(
            "RB", position("RB", player("r1", "RB", 100), player("r2", "RB", 10)))));

        var result = LeagueFlexibleSlotCoverageAnalyzer.compose(lineup, depth);
        var team = result.teams().getFirst();

        assertTrue(result.available());
        assertEquals(100.0, team.directReservedValue());
        assertEquals(10.0, team.flexibleCoverageValue());
    }

    @Test
    void ordinaryFlexDoesNotRequireQuarterbackValueCoverage() {
        var lineup = LeagueLineupRequirementsAnalyzer.interpret("l1", List.of("QB", "RB", "FLEX"));
        var depth = report(team("a", "Team A", Map.of(
            "QB", incompletePosition("QB"),
            "RB", position("RB", player("r1", "RB", 100), player("r2", "RB", 50)))));

        var result = LeagueFlexibleSlotCoverageAnalyzer.compose(lineup, depth);

        assertTrue(result.available());
        assertEquals(50.0, result.teams().getFirst().flexibleCoverageValue());
    }

    @Test
    void superflexFailsClosedWhenQuarterbackValueCoverageIsIncomplete() {
        var lineup = LeagueLineupRequirementsAnalyzer.interpret("l1", List.of("QB", "SUPERFLEX"));
        var depth = report(team("a", "Team A", Map.of("QB", incompletePosition("QB"))));

        var result = LeagueFlexibleSlotCoverageAnalyzer.compose(lineup, depth);

        assertFalse(result.available());
        assertEquals(
            "Complete current value coverage is required for every rostered player eligible for an active flexible slot.",
            result.insufficiencyReason());
        assertEquals(0.0, result.teams().getFirst().flexibleCoverageValue());
    }

    @Test
    void unknownLineupSemanticsFailClosed() {
        var lineup = LeagueLineupRequirementsAnalyzer.interpret("l1", List.of("RB", "MYSTERY_FLEX"));
        var depth = report(team("a", "Team A", Map.of("RB", position("RB", player("r1", "RB", 100)))));

        var result = LeagueFlexibleSlotCoverageAnalyzer.compose(lineup, depth);

        assertFalse(result.available());
        assertEquals("Unknown lineup slot semantics prevent safe flexible-slot coverage measurement.",
            result.insufficiencyReason());
    }

    @Test
    void leagueWithoutFlexibleSlotsHasNeutralZeroCoverage() {
        var lineup = LeagueLineupRequirementsAnalyzer.interpret("l1", List.of("QB", "RB"));
        var depth = report(team("a", "Team A", Map.of(
            "QB", incompletePosition("QB"),
            "RB", incompletePosition("RB"))));

        var result = LeagueFlexibleSlotCoverageAnalyzer.compose(lineup, depth);

        assertTrue(result.available());
        assertEquals(0, result.flexSlots());
        assertEquals(0, result.superFlexSlots());
        assertEquals(0, result.teams().getFirst().flexibleSlots());
        assertEquals(0.0, result.teams().getFirst().flexibleCoverageValue());
    }

    private static LeaguePositionalDepthAnalyzer.DepthReport report(LeaguePositionalDepthAnalyzer.TeamDepth... teams) {
        return new LeaguePositionalDepthAnalyzer.DepthReport("l1", "source", AS_OF, List.of(teams));
    }

    private static LeaguePositionalDepthAnalyzer.TeamDepth team(
        String id, String name, Map<String, LeaguePositionalDepthAnalyzer.PositionDepth> positions) {
        return new LeaguePositionalDepthAnalyzer.TeamDepth(id, name, positions);
    }

    private static LeaguePositionalDepthAnalyzer.PositionDepth position(
        String position, LeaguePositionalDepthAnalyzer.PlayerDepthValue... players) {
        return new LeaguePositionalDepthAnalyzer.PositionDepth(
            position, players.length, players.length, 0, 0, List.of(players));
    }

    private static LeaguePositionalDepthAnalyzer.PositionDepth incompletePosition(String position) {
        return new LeaguePositionalDepthAnalyzer.PositionDepth(position, 1, 0, 0, 1, List.of());
    }

    private static LeaguePositionalDepthAnalyzer.PlayerDepthValue player(String id, String position, double value) {
        return new LeaguePositionalDepthAnalyzer.PlayerDepthValue(id, id, position, "BN", value, AS_OF);
    }
}
