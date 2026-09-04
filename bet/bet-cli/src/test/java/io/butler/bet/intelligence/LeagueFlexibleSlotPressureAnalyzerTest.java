package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeagueFlexibleSlotPressureAnalyzerTest {
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 1);

    @Test
    void classifiesCombinedFlexibleCoverageByLeagueRelativeOuterQuartiles() {
        var result = LeagueFlexibleSlotPressureAnalyzer.classify(coverage(true, null, 1, 1,
            team("a", "Alpha", 100),
            team("b", "Bravo", 80),
            team("c", "Charlie", 40),
            team("d", "Delta", 20)));

        assertTrue(result.available());
        assertNull(result.insufficiencyReason());
        assertEquals("flexible-slot-pressure-v1-combined-relative-quartiles", result.policyId());
        assertEquals("flexible-slot-coverage-v1-direct-reserved-max-value", result.coveragePolicyId());
        assertEquals(LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_STRENGTH, tier(result, "a"));
        assertEquals(LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_BALANCED, tier(result, "b"));
        assertEquals(LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_BALANCED, tier(result, "c"));
        assertEquals(LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_PRESSURE, tier(result, "d"));
    }

    @Test
    void boundaryTiesReceiveTheSameTierInsteadOfArbitraryTeamIdSplitting() {
        var result = LeagueFlexibleSlotPressureAnalyzer.classify(coverage(true, null, 1, 0,
            team("a", "Alpha", 100),
            team("b", "Bravo", 80),
            team("c", "Charlie", 20),
            team("d", "Delta", 20)));

        assertEquals(LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_PRESSURE, tier(result, "c"));
        assertEquals(LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_PRESSURE, tier(result, "d"));
    }

    @Test
    void completeTieIsBalancedRatherThanSimultaneouslyStrengthAndPressure() {
        var result = LeagueFlexibleSlotPressureAnalyzer.classify(coverage(true, null, 0, 1,
            team("a", "Alpha", 50),
            team("b", "Bravo", 50),
            team("c", "Charlie", 50),
            team("d", "Delta", 50)));

        result.teams().forEach(team ->
            assertEquals(LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_BALANCED, team.tier()));
    }

    @Test
    void activeFlexibleSlotsRequireAtLeastFourTeams() {
        var result = LeagueFlexibleSlotPressureAnalyzer.classify(coverage(true, null, 1, 0,
            team("a", "Alpha", 100),
            team("b", "Bravo", 80),
            team("c", "Charlie", 60)));

        assertFalse(result.available());
        assertEquals("At least four league teams are required for relative flexible-slot tiers.",
            result.insufficiencyReason());
        result.teams().forEach(team ->
            assertEquals(LeagueFlexibleSlotPressurePolicy.Tier.INSUFFICIENT_EVIDENCE, team.tier()));
    }

    @Test
    void unavailableCoverageFailsClosed() {
        var result = LeagueFlexibleSlotPressureAnalyzer.classify(coverage(false,
            "Complete current value coverage is required.", 1, 1,
            team("a", "Alpha", 0),
            team("b", "Bravo", 0),
            team("c", "Charlie", 0),
            team("d", "Delta", 0)));

        assertFalse(result.available());
        assertEquals("Complete current value coverage is required.", result.insufficiencyReason());
        result.teams().forEach(team ->
            assertEquals(LeagueFlexibleSlotPressurePolicy.Tier.INSUFFICIENT_EVIDENCE, team.tier()));
    }

    @Test
    void leagueWithoutFlexibleSlotsHasNoFlexibleRequirement() {
        var result = LeagueFlexibleSlotPressureAnalyzer.classify(coverage(true, null, 0, 0,
            teamWithSlots("a", "Alpha", 0, 0),
            teamWithSlots("b", "Bravo", 0, 0)));

        assertTrue(result.available());
        result.teams().forEach(team ->
            assertEquals(LeagueFlexibleSlotPressurePolicy.Tier.NO_FLEXIBLE_REQUIREMENT, team.tier()));
    }

    private static LeagueFlexibleSlotPressurePolicy.Tier tier(
        LeagueFlexibleSlotPressureAnalyzer.FlexiblePressureReport result, String teamId) {
        return result.teams().stream()
            .filter(team -> team.teamId().equals(teamId))
            .findFirst()
            .orElseThrow()
            .tier();
    }

    private static LeagueFlexibleSlotCoverageAnalyzer.FlexibleCoverageReport coverage(
        boolean available,
        String insufficiencyReason,
        int flexSlots,
        int superFlexSlots,
        LeagueFlexibleSlotCoverageAnalyzer.TeamFlexibleCoverage... teams) {
        return new LeagueFlexibleSlotCoverageAnalyzer.FlexibleCoverageReport(
            "l1",
            "source",
            AS_OF,
            LeagueFlexibleSlotCoverageAnalyzer.POLICY_ID,
            LeagueLineupRequirementsAnalyzer.POLICY_ID,
            TradeFlexibleSlotEligibilityPolicy.POLICY_ID,
            flexSlots,
            superFlexSlots,
            available,
            insufficiencyReason,
            List.of(teams));
    }

    private static LeagueFlexibleSlotCoverageAnalyzer.TeamFlexibleCoverage team(
        String id, String name, double value) {
        return teamWithSlots(id, name, 2, value);
    }

    private static LeagueFlexibleSlotCoverageAnalyzer.TeamFlexibleCoverage teamWithSlots(
        String id, String name, int flexibleSlots, double value) {
        return new LeagueFlexibleSlotCoverageAnalyzer.TeamFlexibleCoverage(
            id, name, 0, 0, 0.0,
            flexibleSlots, flexibleSlots, 0, value, value);
    }
}
