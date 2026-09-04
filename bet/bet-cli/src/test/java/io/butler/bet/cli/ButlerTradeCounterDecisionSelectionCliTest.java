package io.butler.bet.cli;

import io.butler.bet.intelligence.TradeCounterCandidateSelectionPolicy;
import io.butler.bet.intelligence.TradeCounterOpportunityPolicy;
import io.butler.bet.intelligence.TradeCounterSingleAssetCandidateAnalyzer;
import io.butler.bet.intelligence.TradeCounterStrategicCandidateVettingAnalyzer;
import io.butler.bet.intelligence.TradeCounterStrategicEligibilityPolicy;
import io.butler.bet.intelligence.TradeCounterValueTargetAnalyzer;
import io.butler.bet.intelligence.TradeFairnessPolicy;
import io.butler.bet.intelligence.TradeRecommendationPolicy;
import io.butler.bet.intelligence.TradeRecommendationVetoPolicy;
import io.butler.bet.intelligence.TradeTeamPerspectiveRecommendationPolicy;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerTradeCounterDecisionSelectionCliTest {
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 1);

    @Test
    void printsSelectedAssetAndGovernedCriteriaWithoutEmittingCounterAction() throws Exception {
        var best = candidate(4, 5.0, 1.0, "p4", TradeCounterSingleAssetCandidateAnalyzer.AssetType.PLAYER);
        var other = candidate(1, 7.0, 2.0, "p1", TradeCounterSingleAssetCandidateAnalyzer.AssetType.PLAYER);
        var eligibility = eligibility(List.of(other, best));
        var opportunity = opportunity(eligibility);
        var selection = TradeCounterCandidateSelectionPolicy.classify(opportunity, eligibility);

        String output = capture(opportunity, selection);

        assertTrue(output.contains("Counter candidate selection: SELECTED"));
        assertTrue(output.contains("Selected market rank: 4"));
        assertTrue(output.contains("Selected asset: Asset 4 [p4] PLAYER"));
        assertTrue(output.contains("Selected market criteria: excess=1.00 asset-value=5.00"));
        assertTrue(output.contains("no COUNTER action is emitted"));
        assertFalse(output.contains("Action: COUNTER"));
    }

    @Test
    void printsAmbiguityAndDoesNotChooseDeterministicTailWinner() throws Exception {
        var player = candidate(2, 5.0, 1.0, "aaa", TradeCounterSingleAssetCandidateAnalyzer.AssetType.PLAYER);
        var pick = candidate(8, 5.0, 1.0, "zzz", TradeCounterSingleAssetCandidateAnalyzer.AssetType.DRAFT_PICK);
        var eligibility = eligibility(List.of(player, pick));
        var opportunity = opportunity(eligibility);
        var selection = TradeCounterCandidateSelectionPolicy.classify(opportunity, eligibility);

        String output = capture(opportunity, selection);

        assertTrue(output.contains("Counter candidate selection: AMBIGUOUS"));
        assertTrue(output.contains("Ambiguous top market ranks: [2, 8]"));
        assertTrue(output.contains("No asset is selected because the top eligible candidates tie"));
        assertFalse(output.contains("Selected asset:"));
        assertFalse(output.contains("Action: COUNTER"));
    }

    private static String capture(
        TradeCounterOpportunityPolicy.Decision opportunity,
        TradeCounterCandidateSelectionPolicy.Selection selection) throws Exception {
        Method printSelection = ButlerTradeCounterDecisionCli.class.getDeclaredMethod(
            "printSelection",
            TradeCounterOpportunityPolicy.Decision.class,
            TradeCounterCandidateSelectionPolicy.Selection.class);
        printSelection.setAccessible(true);

        PrintStream original = System.out;
        var bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes));
            printSelection.invoke(null, opportunity, selection);
        } finally {
            System.setOut(original);
        }
        return bytes.toString();
    }

    private static TradeCounterOpportunityPolicy.Decision opportunity(
        TradeCounterStrategicEligibilityPolicy.EligibilityReport eligibility) {
        return TradeCounterOpportunityPolicy.classify(
            TradeRecommendationPolicy.Recommendation.SIDE_A_PACKAGE_PREFERRED,
            TradeTeamPerspectiveRecommendationPolicy.Action.REJECT,
            TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM,
            true,
            eligibility);
    }

    private static TradeCounterStrategicEligibilityPolicy.EligibilityReport eligibility(
        List<TradeCounterStrategicCandidateVettingAnalyzer.StrategicCandidate> candidates) {
        return new TradeCounterStrategicEligibilityPolicy.EligibilityReport(
            TradeCounterStrategicEligibilityPolicy.POLICY_ID,
            TradeCounterStrategicCandidateVettingAnalyzer.POLICY_ID,
            "l1", 2026, "source", AS_OF, true, null, candidates, List.of());
    }

    private static TradeCounterStrategicCandidateVettingAnalyzer.StrategicCandidate candidate(
        int rank,
        double assetValue,
        double excessValue,
        String assetId,
        TradeCounterSingleAssetCandidateAnalyzer.AssetType assetType) {
        var market = new TradeCounterSingleAssetCandidateAnalyzer.Candidate(
            TradeCounterSingleAssetCandidateAnalyzer.AdjustmentType.ADD_ASSET_TO_LOWER_PACKAGE,
            TradeCounterValueTargetAnalyzer.Side.SIDE_B,
            assetType,
            assetId,
            "Asset " + rank,
            "B",
            "Team B",
            assetValue,
            AS_OF,
            assetValue - excessValue,
            excessValue,
            105.0,
            100.0,
            4.878,
            TradeFairnessPolicy.Classification.MARKET_FAIR);
        var sideA = new TradeCounterStrategicCandidateVettingAnalyzer.SideVetting(
            TradeCounterStrategicCandidateVettingAnalyzer.Side.SIDE_A,
            "A", "Team A", TradeRecommendationVetoPolicy.VetoState.CLEAR, List.of());
        var sideB = new TradeCounterStrategicCandidateVettingAnalyzer.SideVetting(
            TradeCounterStrategicCandidateVettingAnalyzer.Side.SIDE_B,
            "B", "Team B", TradeRecommendationVetoPolicy.VetoState.CLEAR, List.of());
        return new TradeCounterStrategicCandidateVettingAnalyzer.StrategicCandidate(
            rank, market, TradeCounterStrategicCandidateVettingAnalyzer.VettingState.CLEAR, sideA, sideB);
    }
}
