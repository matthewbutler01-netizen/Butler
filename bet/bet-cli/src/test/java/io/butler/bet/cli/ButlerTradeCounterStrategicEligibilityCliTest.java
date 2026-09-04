package io.butler.bet.cli;

import io.butler.bet.intelligence.TradeCounterSingleAssetCandidateAnalyzer;
import io.butler.bet.intelligence.TradeCounterStrategicCandidateVettingAnalyzer;
import io.butler.bet.intelligence.TradeCounterStrategicEligibilityPolicy;
import io.butler.bet.intelligence.TradeCounterValueTargetAnalyzer;
import io.butler.bet.intelligence.TradeFairnessPolicy;
import io.butler.bet.intelligence.TradeRecommendationVetoPolicy;
import io.butler.bet.intelligence.TradeStrategicFlexibleTransitionMaterialLossVetoDetector;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerTradeCounterStrategicEligibilityCliTest {
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 1);

    @Test
    void printsClearOnlyEligibilityWithoutSelectingCandidate() {
        var clear = candidate(2, true);
        var blocked = candidate(5, false);
        var report = new TradeCounterStrategicCandidateVettingAnalyzer.StrategicCandidateReport(
            TradeCounterStrategicCandidateVettingAnalyzer.POLICY_ID,
            TradeCounterSingleAssetCandidateAnalyzer.POLICY_ID,
            TradeStrategicFlexibleTransitionMaterialLossVetoDetector.POLICY_ID,
            "l1", 2026, "source", AS_OF, true, null, List.of(clear, blocked));
        var eligibility = TradeCounterStrategicEligibilityPolicy.classify(report);

        String output = capture(report, eligibility);

        assertTrue(output.contains("Strategic eligibility policy: " + TradeCounterStrategicEligibilityPolicy.POLICY_ID));
        assertTrue(output.contains("Strategically eligible candidates: 1"));
        assertTrue(output.contains("ELIGIBLE #2"));
        assertTrue(output.contains("Strategically blocked candidates excluded: 1"));
        assertTrue(output.contains("Eligibility preserves market rank and does not select or re-rank a candidate."));
        assertFalse(output.contains("ELIGIBLE #5"));
        assertFalse(output.contains("Action:"));
    }

    private static TradeCounterStrategicCandidateVettingAnalyzer.StrategicCandidate candidate(
        int rank,
        boolean clear) {
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
        var sideB = clear
            ? new TradeCounterStrategicCandidateVettingAnalyzer.SideVetting(
                TradeCounterStrategicCandidateVettingAnalyzer.Side.SIDE_B,
                "B", "Team B", TradeRecommendationVetoPolicy.VetoState.CLEAR, List.of())
            : blockedSideB();
        return new TradeCounterStrategicCandidateVettingAnalyzer.StrategicCandidate(
            rank, market,
            clear ? TradeCounterStrategicCandidateVettingAnalyzer.VettingState.CLEAR
                : TradeCounterStrategicCandidateVettingAnalyzer.VettingState.BLOCKED,
            sideA, sideB);
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

    private static String capture(
        TradeCounterStrategicCandidateVettingAnalyzer.StrategicCandidateReport report,
        TradeCounterStrategicEligibilityPolicy.EligibilityReport eligibility) {
        PrintStream original = System.out;
        var bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes));
            ButlerTradeCounterStrategicCli.print(report, eligibility);
        } finally {
            System.setOut(original);
        }
        return bytes.toString();
    }
}
