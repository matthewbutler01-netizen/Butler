package io.butler.bet.cli;

import io.butler.bet.intelligence.TradeCounterSingleAssetCandidateAnalyzer;
import io.butler.bet.intelligence.TradeCounterStrategicCandidateVettingAnalyzer;
import io.butler.bet.intelligence.TradeCounterValueTargetAnalyzer;
import io.butler.bet.intelligence.TradeFairnessPolicy;
import io.butler.bet.intelligence.TradeRecommendationVetoPolicy;
import io.butler.bet.intelligence.TradeStrategicFlexibleTransitionMaterialLossVetoDetector;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerTradeCounterStrategicCliTest {
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 1);

    @Test
    void parsesSeasonMixedAssetsSourceAndFreshnessBoundary() {
        var options = ButlerTradeCounterStrategicCli.parse(new String[]{
            "trade", "counter-strategic", "l1", "2026",
            "player:p1,pick:k1", "p2,pick:k2",
            "dynastyprocess", "--minimum-as-of", "2026-09-01"});

        assertEquals("l1", options.leagueId());
        assertEquals(2026, options.season());
        assertEquals(List.of("p1"), options.sideA().playerIds());
        assertEquals(List.of("k1"), options.sideA().draftPickIds());
        assertEquals("dynastyprocess", options.source());
        assertEquals(AS_OF, options.minimumAsOf());
    }

    @Test
    void printsBilateralStrategicLabelsWithoutCounterAction() {
        String output = capture(report());

        assertTrue(output.contains("Trade counter strategic vetting (season-aware bilateral v5 veto)"));
        assertTrue(output.contains("Season: 2026"));
        assertTrue(output.contains("Strategically vetted market-fair candidates: 1"));
        assertTrue(output.contains("strategic=BLOCKED"));
        assertTrue(output.contains("SIDE_A Team A [A] veto=CLEAR"));
        assertTrue(output.contains("SIDE_B Team B [B] veto=BLOCKED"));
        assertTrue(output.contains("Veto reason:"));
        assertTrue(output.contains("No candidate is selected and no COUNTER action or recommendation is emitted."));
        assertFalse(output.contains("Action:"));
        assertFalse(output.contains("Package recommendation:"));
    }

    private static TradeCounterStrategicCandidateVettingAnalyzer.StrategicCandidateReport report() {
        var candidate = new TradeCounterSingleAssetCandidateAnalyzer.Candidate(
            TradeCounterSingleAssetCandidateAnalyzer.AdjustmentType.ADD_ASSET_TO_LOWER_PACKAGE,
            TradeCounterValueTargetAnalyzer.Side.SIDE_B,
            TradeCounterSingleAssetCandidateAnalyzer.AssetType.PLAYER,
            "p3", "Player Three", "B", "Team B", 5.0, AS_OF,
            4.0, 1.0, 105.0, 100.0, 4.878,
            TradeFairnessPolicy.Classification.MARKET_FAIR);
        var sideA = new TradeCounterStrategicCandidateVettingAnalyzer.SideVetting(
            TradeCounterStrategicCandidateVettingAnalyzer.Side.SIDE_A,
            "A", "Team A", TradeRecommendationVetoPolicy.VetoState.CLEAR, List.of());
        var reason = new TradeStrategicFlexibleTransitionMaterialLossVetoDetector.VetoReason(
            TradeStrategicFlexibleTransitionMaterialLossVetoDetector.ReasonCode
                .LOW_FUTURE_CAPITAL_MATERIAL_PICK_VALUE_LOSS,
            null, 100.0, 70.0, 0.30);
        var sideB = new TradeCounterStrategicCandidateVettingAnalyzer.SideVetting(
            TradeCounterStrategicCandidateVettingAnalyzer.Side.SIDE_B,
            "B", "Team B", TradeRecommendationVetoPolicy.VetoState.BLOCKED, List.of(reason));
        var vetted = new TradeCounterStrategicCandidateVettingAnalyzer.StrategicCandidate(
            1, candidate, TradeCounterStrategicCandidateVettingAnalyzer.VettingState.BLOCKED, sideA, sideB);
        return new TradeCounterStrategicCandidateVettingAnalyzer.StrategicCandidateReport(
            TradeCounterStrategicCandidateVettingAnalyzer.POLICY_ID,
            TradeCounterSingleAssetCandidateAnalyzer.POLICY_ID,
            TradeStrategicFlexibleTransitionMaterialLossVetoDetector.POLICY_ID,
            "l1", 2026, "source", AS_OF, true, null, List.of(vetted));
    }

    private static String capture(
        TradeCounterStrategicCandidateVettingAnalyzer.StrategicCandidateReport report) {
        PrintStream original = System.out;
        var bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes));
            ButlerTradeCounterStrategicCli.print(report);
        } finally {
            System.setOut(original);
        }
        return bytes.toString();
    }
}
