package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeFlexibleCoverageMaterialLossAnalyzerTest {
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 1);
    private static final String LEAGUE = "l1";
    private static final String SOURCE = "source";
    private static final String TEAM_A = "a";
    private static final String TEAM_A_NAME = "Alpha";
    private static final String TEAM_B = "b";
    private static final String TEAM_B_NAME = "Bravo";

    @Test
    void crossPositionFlexReplacementUsesLegalPostTradeLineup() {
        var result = assess(
            LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_PRESSURE,
            side(player("wr2", "WR Two", "WR", TEAM_A, TEAM_A_NAME, 80.0)),
            side(player("rb3", "RB Three", "RB", TEAM_B, TEAM_B_NAME, 70.0)));

        assertEquals(TradeFlexibleCoverageMaterialLossAnalyzer.AssessmentState.WITHIN_TOLERANCE, result.state());
        assertEquals(80.0, result.preTradeCoverageValue());
        assertEquals(70.0, result.postTradeCoverageValue());
        assertEquals(0.125, result.lossFraction());
        assertTrue(result.available());
        assertTrue(result.protectedPressureArea());
        assertFalse(result.materialLoss());
    }

    @Test
    void flexibleCoverageLossGreaterThanTwentyFivePercentIsMaterial() {
        var result = assess(
            LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_PRESSURE,
            side(player("wr2", "WR Two", "WR", TEAM_A, TEAM_A_NAME, 80.0)),
            side(player("rb3", "RB Three", "RB", TEAM_B, TEAM_B_NAME, 50.0)));

        assertEquals(TradeFlexibleCoverageMaterialLossAnalyzer.AssessmentState.MATERIAL_LOSS, result.state());
        assertEquals(80.0, result.preTradeCoverageValue());
        assertEquals(50.0, result.postTradeCoverageValue());
        assertEquals(0.375, result.lossFraction());
        assertTrue(result.materialLoss());
    }

    @Test
    void exactlyTwentyFivePercentLossRemainsWithinTolerance() {
        var result = assess(
            LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_PRESSURE,
            side(player("wr2", "WR Two", "WR", TEAM_A, TEAM_A_NAME, 80.0)),
            side(player("rb3", "RB Three", "RB", TEAM_B, TEAM_B_NAME, 60.0)));

        assertEquals(TradeFlexibleCoverageMaterialLossAnalyzer.AssessmentState.WITHIN_TOLERANCE, result.state());
        assertEquals(0.25, result.lossFraction());
        assertFalse(result.materialLoss());
    }

    @Test
    void directStartersAreReselectedBeforeFlexibleCoverageIsMeasured() {
        var result = assess(
            LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_PRESSURE,
            side(player("wr1", "WR One", "WR", TEAM_A, TEAM_A_NAME, 100.0)),
            side(player("rb3", "RB Three", "RB", TEAM_B, TEAM_B_NAME, 50.0)));

        assertEquals(TradeFlexibleCoverageMaterialLossAnalyzer.AssessmentState.MATERIAL_LOSS, result.state());
        assertEquals(80.0, result.preTradeCoverageValue());
        assertEquals(50.0, result.postTradeCoverageValue());
        assertEquals(0.375, result.lossFraction());
    }

    @Test
    void balancedFlexibleAreaIsNotProtectedByMaterialLossRule() {
        var result = assess(
            LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_BALANCED,
            side(player("wr2", "WR Two", "WR", TEAM_A, TEAM_A_NAME, 80.0)),
            side(player("rb3", "RB Three", "RB", TEAM_B, TEAM_B_NAME, 10.0)));

        assertEquals(TradeFlexibleCoverageMaterialLossAnalyzer.AssessmentState.NOT_PROTECTED, result.state());
        assertTrue(result.available());
        assertFalse(result.protectedPressureArea());
        assertFalse(result.materialLoss());
        assertNull(result.preTradeCoverageValue());
        assertNull(result.postTradeCoverageValue());
        assertNull(result.lossFraction());
    }

    @Test
    void unavailableFlexiblePressureFailsClosedAsInsufficientEvidence() {
        var context = context(LeagueFlexibleSlotPressurePolicy.Tier.INSUFFICIENT_EVIDENCE,
            false, "Complete current value coverage is required.");
        var result = TradeFlexibleCoverageMaterialLossAnalyzer.assess(
            context,
            context.sideA(),
            lineup(),
            depth(),
            side(player("wr2", "WR Two", "WR", TEAM_A, TEAM_A_NAME, 80.0)),
            side(player("rb3", "RB Three", "RB", TEAM_B, TEAM_B_NAME, 50.0)));

        assertEquals(TradeFlexibleCoverageMaterialLossAnalyzer.AssessmentState.INSUFFICIENT_EVIDENCE, result.state());
        assertFalse(result.available());
        assertEquals("Complete current value coverage is required.", result.insufficiencyReason());
        assertNull(result.preTradeCoverageValue());
    }

    private static TradeFlexibleCoverageMaterialLossAnalyzer.Assessment assess(
        LeagueFlexibleSlotPressurePolicy.Tier tier,
        TradeAssetAnalyzer.TradeSide outgoing,
        TradeAssetAnalyzer.TradeSide incoming) {
        var context = context(tier, true, null);
        return TradeFlexibleCoverageMaterialLossAnalyzer.assess(
            context, context.sideA(), lineup(), depth(), outgoing, incoming);
    }

    private static TradeFlexibleSlotContextAnalyzer.TradeFlexibleContextReport context(
        LeagueFlexibleSlotPressurePolicy.Tier sideATier,
        boolean available,
        String reason) {
        var sideAIdentity = new TradeAssetStrategicContextAnalyzer.TeamIdentity(TEAM_A, TEAM_A_NAME);
        var sideBIdentity = new TradeAssetStrategicContextAnalyzer.TeamIdentity(TEAM_B, TEAM_B_NAME);
        var sideA = new TradeFlexibleSlotContextAnalyzer.TeamFlexibleContext(
            sideAIdentity,
            pressure(TEAM_A, TEAM_A_NAME, 80.0, sideATier));
        var sideB = new TradeFlexibleSlotContextAnalyzer.TeamFlexibleContext(
            sideBIdentity,
            pressure(TEAM_B, TEAM_B_NAME, available ? 70.0 : 0.0,
                available ? LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_BALANCED
                    : LeagueFlexibleSlotPressurePolicy.Tier.INSUFFICIENT_EVIDENCE));
        return new TradeFlexibleSlotContextAnalyzer.TradeFlexibleContextReport(
            LEAGUE,
            SOURCE,
            AS_OF,
            LeagueFlexibleSlotPressurePolicy.POLICY_ID,
            LeagueFlexibleSlotCoverageAnalyzer.POLICY_ID,
            1,
            0,
            available,
            reason,
            sideA,
            sideB);
    }

    private static LeagueFlexibleSlotPressureAnalyzer.TeamFlexiblePressure pressure(
        String teamId,
        String teamName,
        double value,
        LeagueFlexibleSlotPressurePolicy.Tier tier) {
        return new LeagueFlexibleSlotPressureAnalyzer.TeamFlexiblePressure(
            teamId, teamName, 1, tier == LeagueFlexibleSlotPressurePolicy.Tier.INSUFFICIENT_EVIDENCE ? 0 : 1,
            tier == LeagueFlexibleSlotPressurePolicy.Tier.INSUFFICIENT_EVIDENCE ? 1 : 0,
            value, tier);
    }

    private static LeagueLineupRequirementsAnalyzer.LineupRequirementsReport lineup() {
        return LeagueLineupRequirementsAnalyzer.interpret(LEAGUE, List.of("RB", "WR", "FLEX"));
    }

    private static LeaguePositionalDepthAnalyzer.DepthReport depth() {
        return new LeaguePositionalDepthAnalyzer.DepthReport(
            LEAGUE,
            SOURCE,
            AS_OF,
            List.of(teamDepth(
                playerDepth("rb1", "RB One", "RB", 90.0),
                playerDepth("rb2", "RB Two", "RB", 40.0),
                playerDepth("wr1", "WR One", "WR", 100.0),
                playerDepth("wr2", "WR Two", "WR", 80.0))));
    }

    private static LeaguePositionalDepthAnalyzer.TeamDepth teamDepth(
        LeaguePositionalDepthAnalyzer.PlayerDepthValue... players) {
        Map<String, List<LeaguePositionalDepthAnalyzer.PlayerDepthValue>> grouped = new LinkedHashMap<>();
        for (var player : players) {
            grouped.computeIfAbsent(player.position(), ignored -> new ArrayList<>()).add(player);
        }
        Comparator<LeaguePositionalDepthAnalyzer.PlayerDepthValue> order = Comparator
            .comparingDouble(LeaguePositionalDepthAnalyzer.PlayerDepthValue::value).reversed()
            .thenComparing(LeaguePositionalDepthAnalyzer.PlayerDepthValue::playerName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(LeaguePositionalDepthAnalyzer.PlayerDepthValue::playerId);
        Map<String, LeaguePositionalDepthAnalyzer.PositionDepth> positions = new LinkedHashMap<>();
        grouped.forEach((position, values) -> {
            values.sort(order);
            positions.put(position, new LeaguePositionalDepthAnalyzer.PositionDepth(
                position, values.size(), values.size(), 0, 0, List.copyOf(values)));
        });
        return new LeaguePositionalDepthAnalyzer.TeamDepth(TEAM_A, TEAM_A_NAME, Map.copyOf(positions));
    }

    private static LeaguePositionalDepthAnalyzer.PlayerDepthValue playerDepth(
        String id, String name, String position, double value) {
        return new LeaguePositionalDepthAnalyzer.PlayerDepthValue(id, name, position, "BN", value, AS_OF);
    }

    private static TradeAssetAnalyzer.TradePlayer player(
        String id,
        String name,
        String position,
        String teamId,
        String teamName,
        double value) {
        return new TradeAssetAnalyzer.TradePlayer(
            id, name, position, "NFL", teamId, teamName, value, AS_OF, false);
    }

    private static TradeAssetAnalyzer.TradeSide side(TradeAssetAnalyzer.TradePlayer... players) {
        List<TradeAssetAnalyzer.TradePlayer> list = List.of(players);
        double total = list.stream().mapToDouble(TradeAssetAnalyzer.TradePlayer::value).sum();
        return new TradeAssetAnalyzer.TradeSide(list, List.of(), total, list.size(), 0, 0, 0);
    }
}
