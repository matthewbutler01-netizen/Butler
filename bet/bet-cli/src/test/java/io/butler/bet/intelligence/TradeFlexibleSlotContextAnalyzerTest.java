package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeFlexibleSlotContextAnalyzerTest {
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 1);

    @Test
    void attachesCombinedFlexiblePressureToBothExplicitTradeTeams() {
        var report = TradeFlexibleSlotContextAnalyzer.attach(
            "l1",
            "source",
            AS_OF,
            identity("a", "Alpha"),
            identity("b", "Bravo"),
            1,
            1,
            pressure(true, null,
                team("a", "Alpha", 2, 100, LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_STRENGTH),
                team("b", "Bravo", 2, 20, LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_PRESSURE),
                team("c", "Charlie", 2, 60, LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_BALANCED),
                team("d", "Delta", 2, 50, LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_BALANCED)));

        assertTrue(report.flexiblePressureAvailable());
        assertEquals("flexible-slot-pressure-v1-combined-relative-quartiles", report.flexiblePressurePolicyId());
        assertEquals("flexible-slot-coverage-v1-direct-reserved-max-value", report.flexibleCoveragePolicyId());
        assertEquals(1, report.flexSlots());
        assertEquals(1, report.superFlexSlots());
        assertEquals(LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_STRENGTH, report.sideA().pressure().tier());
        assertEquals(LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_PRESSURE, report.sideB().pressure().tier());
    }

    @Test
    void preservesUnavailableFlexibleEvidenceWithoutCreatingARecommendation() {
        var report = TradeFlexibleSlotContextAnalyzer.attach(
            "l1",
            "source",
            AS_OF,
            identity("a", "Alpha"),
            identity("b", "Bravo"),
            1,
            0,
            pressure(false, "Flexible evidence is incomplete.",
                team("a", "Alpha", 1, 0, LeagueFlexibleSlotPressurePolicy.Tier.INSUFFICIENT_EVIDENCE),
                team("b", "Bravo", 1, 0, LeagueFlexibleSlotPressurePolicy.Tier.INSUFFICIENT_EVIDENCE)));

        assertFalse(report.flexiblePressureAvailable());
        assertEquals("Flexible evidence is incomplete.", report.flexiblePressureInsufficiencyReason());
    }

    @Test
    void rejectsMissingTradeTeamPressure() {
        var error = assertThrows(IllegalStateException.class, () ->
            TradeFlexibleSlotContextAnalyzer.attach(
                "l1", "source", AS_OF,
                identity("a", "Alpha"), identity("b", "Bravo"),
                1, 0,
                pressure(true, null,
                    team("a", "Alpha", 1, 100, LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_STRENGTH))));

        assertEquals("flexible pressure missing for trade team: b", error.getMessage());
    }

    @Test
    void rejectsTradeTeamNameMismatch() {
        var error = assertThrows(IllegalStateException.class, () ->
            TradeFlexibleSlotContextAnalyzer.attach(
                "l1", "source", AS_OF,
                identity("a", "Alpha"), identity("b", "Bravo"),
                1, 0,
                pressure(true, null,
                    team("a", "Wrong Alpha", 1, 100, LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_STRENGTH),
                    team("b", "Bravo", 1, 20, LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_PRESSURE))));

        assertEquals("trade and flexible-pressure team names differ: a", error.getMessage());
    }

    @Test
    void rejectsFlexibleSlotCountThatDiffersFromTradeExposure() {
        var error = assertThrows(IllegalStateException.class, () ->
            TradeFlexibleSlotContextAnalyzer.attach(
                "l1", "source", AS_OF,
                identity("a", "Alpha"), identity("b", "Bravo"),
                1, 1,
                pressure(true, null,
                    team("a", "Alpha", 1, 100, LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_STRENGTH),
                    team("b", "Bravo", 1, 20, LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_PRESSURE))));

        assertEquals("flexible-pressure team slot count differs from trade lineup exposure: a", error.getMessage());
    }

    @Test
    void rejectsSourceOrFreshnessDrift() {
        var sourceError = assertThrows(IllegalStateException.class, () ->
            TradeFlexibleSlotContextAnalyzer.attach(
                "l1", "other-source", AS_OF,
                identity("a", "Alpha"), identity("b", "Bravo"),
                1, 0,
                pressure(true, null,
                    team("a", "Alpha", 1, 100, LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_STRENGTH),
                    team("b", "Bravo", 1, 20, LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_PRESSURE))));
        assertEquals("trade and flexible-pressure reports use different value sources", sourceError.getMessage());

        var freshnessError = assertThrows(IllegalStateException.class, () ->
            TradeFlexibleSlotContextAnalyzer.attach(
                "l1", "source", AS_OF.minusDays(1),
                identity("a", "Alpha"), identity("b", "Bravo"),
                1, 0,
                pressure(true, null,
                    team("a", "Alpha", 1, 100, LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_STRENGTH),
                    team("b", "Bravo", 1, 20, LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_PRESSURE))));
        assertEquals("trade and flexible-pressure reports use different freshness boundaries", freshnessError.getMessage());
    }

    private static TradeAssetStrategicContextAnalyzer.TeamIdentity identity(String id, String name) {
        return new TradeAssetStrategicContextAnalyzer.TeamIdentity(id, name);
    }

    private static LeagueFlexibleSlotPressureAnalyzer.FlexiblePressureReport pressure(
        boolean available,
        String insufficiencyReason,
        LeagueFlexibleSlotPressureAnalyzer.TeamFlexiblePressure... teams) {
        return new LeagueFlexibleSlotPressureAnalyzer.FlexiblePressureReport(
            "l1",
            "source",
            AS_OF,
            LeagueFlexibleSlotPressurePolicy.POLICY_ID,
            LeagueFlexibleSlotCoverageAnalyzer.POLICY_ID,
            available,
            insufficiencyReason,
            List.of(teams));
    }

    private static LeagueFlexibleSlotPressureAnalyzer.TeamFlexiblePressure team(
        String id,
        String name,
        int slots,
        double value,
        LeagueFlexibleSlotPressurePolicy.Tier tier) {
        return new LeagueFlexibleSlotPressureAnalyzer.TeamFlexiblePressure(
            id, name, slots, slots, 0, value, tier);
    }
}
