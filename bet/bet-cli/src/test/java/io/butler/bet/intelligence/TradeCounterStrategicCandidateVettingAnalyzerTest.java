package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TradeCounterStrategicCandidateVettingAnalyzerTest {
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 1);

    @Test
    void addCandidateProducesModifiedPackageWithoutChangingOppositeSide() {
        var trade = trade(
            side(List.of(player("a1", "A", 105.0))),
            side(List.of(player("b1", "B", 95.0))));
        var candidate = candidate(
            TradeCounterSingleAssetCandidateAnalyzer.AdjustmentType.ADD_ASSET_TO_LOWER_PACKAGE,
            TradeCounterValueTargetAnalyzer.Side.SIDE_B,
            "b2");

        var modified = TradeCounterStrategicCandidateVettingAnalyzer.applyCandidate(trade, candidate);

        assertEquals(List.of("a1"), modified.sideA().playerIds());
        assertEquals(List.of("b1", "b2"), modified.sideB().playerIds());
    }

    @Test
    void removalCandidateMustRemainAValidNonEmptyTradePackage() {
        var trade = trade(
            side(List.of(player("a1", "A", 105.0))),
            side(List.of(player("b1", "B", 95.0))));
        var candidate = candidate(
            TradeCounterSingleAssetCandidateAnalyzer.AdjustmentType.REMOVE_ASSET_FROM_HIGHER_PACKAGE,
            TradeCounterValueTargetAnalyzer.Side.SIDE_A,
            "a1");

        assertThrows(IllegalStateException.class, () ->
            TradeCounterStrategicCandidateVettingAnalyzer.applyCandidate(trade, candidate));
    }

    @Test
    void removalCandidateRemovesOnlyTheNamedAsset() {
        var trade = trade(
            side(List.of(player("a1", "A", 99.5), player("a2", "A", 5.5))),
            side(List.of(player("b1", "B", 95.0))));
        var candidate = candidate(
            TradeCounterSingleAssetCandidateAnalyzer.AdjustmentType.REMOVE_ASSET_FROM_HIGHER_PACKAGE,
            TradeCounterValueTargetAnalyzer.Side.SIDE_A,
            "a2");

        var modified = TradeCounterStrategicCandidateVettingAnalyzer.applyCandidate(trade, candidate);

        assertEquals(List.of("a1"), modified.sideA().playerIds());
        assertEquals(List.of("b1"), modified.sideB().playerIds());
    }

    @Test
    void bilateralStateIsBlockedWhenEitherSideHasGovernedVeto() {
        var clearA = clear(TradeCounterStrategicCandidateVettingAnalyzer.Side.SIDE_A, "A");
        var clearB = clear(TradeCounterStrategicCandidateVettingAnalyzer.Side.SIDE_B, "B");
        var blockedB = blocked(TradeCounterStrategicCandidateVettingAnalyzer.Side.SIDE_B, "B");

        assertEquals(TradeCounterStrategicCandidateVettingAnalyzer.VettingState.CLEAR,
            TradeCounterStrategicCandidateVettingAnalyzer.combine(clearA, clearB));
        assertEquals(TradeCounterStrategicCandidateVettingAnalyzer.VettingState.BLOCKED,
            TradeCounterStrategicCandidateVettingAnalyzer.combine(clearA, blockedB));
    }

    @Test
    void locksStrategicCounterPolicyIdentifier() {
        assertEquals("trade-counter-strategic-candidate-v1-bilateral-v5-veto",
            TradeCounterStrategicCandidateVettingAnalyzer.POLICY_ID);
    }

    private static TradeCounterStrategicCandidateVettingAnalyzer.SideVetting clear(
        TradeCounterStrategicCandidateVettingAnalyzer.Side side,
        String teamId) {
        return new TradeCounterStrategicCandidateVettingAnalyzer.SideVetting(
            side, teamId, "Team " + teamId,
            TradeRecommendationVetoPolicy.VetoState.CLEAR, List.of());
    }

    private static TradeCounterStrategicCandidateVettingAnalyzer.SideVetting blocked(
        TradeCounterStrategicCandidateVettingAnalyzer.Side side,
        String teamId) {
        var reason = new TradeStrategicFlexibleTransitionMaterialLossVetoDetector.VetoReason(
            TradeStrategicFlexibleTransitionMaterialLossVetoDetector.ReasonCode
                .LOW_FUTURE_CAPITAL_MATERIAL_PICK_VALUE_LOSS,
            null,
            100.0,
            70.0,
            0.30);
        return new TradeCounterStrategicCandidateVettingAnalyzer.SideVetting(
            side, teamId, "Team " + teamId,
            TradeRecommendationVetoPolicy.VetoState.BLOCKED, List.of(reason));
    }

    private static TradeCounterSingleAssetCandidateAnalyzer.Candidate candidate(
        TradeCounterSingleAssetCandidateAnalyzer.AdjustmentType adjustment,
        TradeCounterValueTargetAnalyzer.Side side,
        String id) {
        return new TradeCounterSingleAssetCandidateAnalyzer.Candidate(
            adjustment,
            side,
            TradeCounterSingleAssetCandidateAnalyzer.AssetType.PLAYER,
            id,
            id,
            side == TradeCounterValueTargetAnalyzer.Side.SIDE_A ? "A" : "B",
            side == TradeCounterValueTargetAnalyzer.Side.SIDE_A ? "Team A" : "Team B",
            5.0,
            AS_OF,
            4.0,
            1.0,
            100.0,
            100.0,
            0.0,
            TradeFairnessPolicy.Classification.MARKET_FAIR);
    }

    private static TradeAssetAnalyzer.TradeReport trade(
        TradeAssetAnalyzer.TradeSide sideA,
        TradeAssetAnalyzer.TradeSide sideB) {
        return new TradeAssetAnalyzer.TradeReport("l1", "source", AS_OF, sideA, sideB);
    }

    private static TradeAssetAnalyzer.TradeSide side(List<TradeAssetAnalyzer.TradePlayer> players) {
        double total = players.stream().mapToDouble(TradeAssetAnalyzer.TradePlayer::value).sum();
        return new TradeAssetAnalyzer.TradeSide(
            players, List.of(), total, players.size(), 0, 0, 0);
    }

    private static TradeAssetAnalyzer.TradePlayer player(String id, String teamId, double value) {
        return new TradeAssetAnalyzer.TradePlayer(
            id, id, "WR", "NFL", teamId, "Team " + teamId,
            value, AS_OF, false);
    }
}
