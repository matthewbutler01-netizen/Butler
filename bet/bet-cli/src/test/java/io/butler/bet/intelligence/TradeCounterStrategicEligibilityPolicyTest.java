package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeCounterStrategicEligibilityPolicyTest {
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 1);

    @Test
    void keepsOnlyClearCandidatesAndPreservesMarketRank() {
        var clearThird = candidate(3, TradeCounterStrategicCandidateVettingAnalyzer.VettingState.CLEAR);
        var blockedFourth = candidate(4, TradeCounterStrategicCandidateVettingAnalyzer.VettingState.BLOCKED);
        var clearSeventh = candidate(7, TradeCounterStrategicCandidateVettingAnalyzer.VettingState.CLEAR);
        var report = report(true, null, List.of(clearThird, blockedFourth, clearSeventh));

        var eligibility = TradeCounterStrategicEligibilityPolicy.classify(report);

        assertTrue(eligibility.available());
        assertEquals(List.of(3, 7), eligibility.eligibleCandidates().stream()
            .map(TradeCounterStrategicCandidateVettingAnalyzer.StrategicCandidate::marketRank).toList());
        assertEquals(List.of(4), eligibility.blockedCandidates().stream()
            .map(TradeCounterStrategicCandidateVettingAnalyzer.StrategicCandidate::marketRank).toList());
    }

    @Test
    void unavailableVettingFailsEligibilityClosed() {
        var eligibility = TradeCounterStrategicEligibilityPolicy.classify(
            report(false, "missing evidence", List.of()));

        assertFalse(eligibility.available());
        assertEquals("missing evidence", eligibility.insufficiencyReason());
        assertTrue(eligibility.eligibleCandidates().isEmpty());
        assertTrue(eligibility.blockedCandidates().isEmpty());
    }

    @Test
    void locksEligibilityPolicyIdentifier() {
        assertEquals("trade-counter-strategic-eligibility-v1-clear-only-preserve-market-rank",
            TradeCounterStrategicEligibilityPolicy.POLICY_ID);
    }

    private static TradeCounterStrategicCandidateVettingAnalyzer.StrategicCandidateReport report(
        boolean available,
        String reason,
        List<TradeCounterStrategicCandidateVettingAnalyzer.StrategicCandidate> candidates) {
        return new TradeCounterStrategicCandidateVettingAnalyzer.StrategicCandidateReport(
            TradeCounterStrategicCandidateVettingAnalyzer.POLICY_ID,
            TradeCounterSingleAssetCandidateAnalyzer.POLICY_ID,
            TradeStrategicFlexibleTransitionMaterialLossVetoDetector.POLICY_ID,
            "l1", 2026, "source", AS_OF, available, reason, candidates);
    }

    private static TradeCounterStrategicCandidateVettingAnalyzer.StrategicCandidate candidate(
        int rank,
        TradeCounterStrategicCandidateVettingAnalyzer.VettingState state) {
        var market = new TradeCounterSingleAssetCandidateAnalyzer.Candidate(
            TradeCounterSingleAssetCandidateAnalyzer.AdjustmentType.ADD_ASSET_TO_LOWER_PACKAGE,
            TradeCounterValueTargetAnalyzer.Side.SIDE_B,
            TradeCounterSingleAssetCandidateAnalyzer.AssetType.PLAYER,
            "p" + rank, "Player " + rank, "B", "Team B", 5.0, AS_OF,
            4.0, 1.0, 105.0, 100.0, 4.878,
            TradeFairnessPolicy.Classification.MARKET_FAIR);
        var sideA = new TradeCounterStrategicCandidateVettingAnalyzer.SideVetting(
            TradeCounterStrategicCandidateVettingAnalyzer.Side.SIDE_A,
            "A", "Team A", TradeRecommendationVetoPolicy.VetoState.CLEAR, List.of());
        var sideB = state == TradeCounterStrategicCandidateVettingAnalyzer.VettingState.CLEAR
            ? new TradeCounterStrategicCandidateVettingAnalyzer.SideVetting(
                TradeCounterStrategicCandidateVettingAnalyzer.Side.SIDE_B,
                "B", "Team B", TradeRecommendationVetoPolicy.VetoState.CLEAR, List.of())
            : blockedSideB();
        return new TradeCounterStrategicCandidateVettingAnalyzer.StrategicCandidate(
            rank, market, state, sideA, sideB);
    }

    private static TradeCounterStrategicCandidateVettingAnalyzer.SideVetting blockedSideB() {
        var reason = new TradeStrategicFlexibleTransitionMaterialLossVetoDetector.VetoReason(
            TradeStrategicFlexibleTransitionMaterialLossVetoDetector.ReasonCode
                .LOW_FUTURE_CAPITAL_MATERIAL_PICK_VALUE_LOSS,
            null, 100.0, 70.0, 0.30);
        return new TradeCounterStrategicCandidateVettingAnalyzer.SideVetting(
            TradeCounterStrategicCandidateVettingAnalyzer.Side.SIDE_B,
            "B", "Team B", TradeRecommendationVetoPolicy.VetoState.BLOCKED, List.of(reason));
    }
}
