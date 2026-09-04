package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeRecommendationFlexibleV5ContractTest {
    @Test
    void locksVersionedPolicyIdentifiers() {
        assertEquals("trade-recommendation-v5-market-first-flexible-transition-material-loss-veto",
            TradeRecommendationFlexibleTransitionMaterialLossPolicy.POLICY_ID);
        assertEquals("trade-strategic-veto-v4-material-protected-value-plus-flexible-transition-loss",
            TradeStrategicFlexibleTransitionMaterialLossVetoDetector.POLICY_ID);
        assertEquals("trade-flexible-pressure-transition-v1-post-trade-league-relative",
            TradeFlexiblePressureTransitionAnalyzer.POLICY_ID);
        assertEquals("trade-flexible-post-trade-depth-v1-two-team-exchange",
            TradeFlexiblePostTradeDepthAnalyzer.POLICY_ID);
        assertEquals("trade-flexible-coverage-loss-v1-post-trade-legal-lineup",
            TradeFlexibleCoverageMaterialLossAnalyzer.POLICY_ID);
        assertEquals("trade-protected-value-materiality-v1-25-percent-loss",
            TradeProtectedValueMaterialityPolicy.POLICY_ID);
        assertEquals("trade-team-perspective-v1-explicit-owner",
            TradeTeamPerspectiveRecommendationPolicy.POLICY_ID);
    }

    @Test
    void preservesEarlierRecommendationAndVetoContracts() {
        assertEquals("trade-recommendation-v1-conservative-evidence-first",
            TradeRecommendationPolicy.POLICY_ID);
        assertEquals("trade-recommendation-v2-market-first-strategic-veto",
            TradeRecommendationVetoPolicy.POLICY_ID);
        assertEquals("trade-recommendation-v3-market-first-material-loss-veto",
            TradeRecommendationMaterialLossPolicy.POLICY_ID);
        assertEquals("trade-recommendation-v4-market-first-flexible-material-loss-veto",
            TradeRecommendationFlexibleMaterialLossPolicy.POLICY_ID);
        assertEquals("trade-strategic-veto-v1-explicit-weakness-protection",
            TradeStrategicVetoDetector.POLICY_ID);
        assertEquals("trade-strategic-veto-v2-material-protected-value-loss",
            TradeStrategicMaterialLossVetoDetector.POLICY_ID);
        assertEquals("trade-strategic-veto-v3-material-protected-value-plus-flexible-coverage-loss",
            TradeStrategicFlexibleMaterialLossVetoDetector.POLICY_ID);
    }

    @Test
    void locksV5ReasonVocabularyAndOrder() {
        assertEquals(List.of(
                "LOW_FUTURE_CAPITAL_MATERIAL_PICK_VALUE_LOSS",
                "POSITION_PRESSURE_MATERIAL_SAME_POSITION_VALUE_LOSS",
                "FLEXIBLE_PRESSURE_MATERIAL_POST_TRADE_COVERAGE_LOSS",
                "FLEXIBLE_MATERIAL_LOSS_TRANSITION_TO_PRESSURE"),
            Arrays.stream(TradeStrategicFlexibleTransitionMaterialLossVetoDetector.ReasonCode.values())
                .map(Enum::name)
                .toList());
    }

    @Test
    void locksTransitionAssessmentVocabulary() {
        assertEquals(List.of(
                "NO_FLEXIBLE_REQUIREMENT",
                "INSUFFICIENT_EVIDENCE",
                "NO_TRANSITION",
                "TRANSITION_WITHIN_TOLERANCE",
                "MATERIAL_TRANSITION_TO_PRESSURE"),
            Arrays.stream(TradeFlexiblePressureTransitionAnalyzer.AssessmentState.values())
                .map(Enum::name)
                .toList());
    }

    @Test
    void locksTwentyFivePercentMaterialityBoundary() {
        assertEquals(0.25, TradeProtectedValueMaterialityPolicy.MAX_ALLOWED_LOSS_FRACTION);
        assertEquals(TradeProtectedValueMaterialityPolicy.Classification.WITHIN_TOLERANCE,
            TradeProtectedValueMaterialityPolicy.classify(
                new TradeProtectedValueFlowAnalyzer.ValueFlow(100.0, 75.0)));
        assertEquals(TradeProtectedValueMaterialityPolicy.Classification.MATERIAL_LOSS,
            TradeProtectedValueMaterialityPolicy.classify(
                new TradeProtectedValueFlowAnalyzer.ValueFlow(100.0, 74.0)));
    }

    @Test
    void locksV5EvidenceGateCompleteness() {
        assertTrue(new TradeRecommendationFlexibleTransitionMaterialLossPolicy.EvidenceGate(
            true, true, true, true).complete());
        assertFalse(new TradeRecommendationFlexibleTransitionMaterialLossPolicy.EvidenceGate(
            false, true, true, true).complete());
        assertFalse(new TradeRecommendationFlexibleTransitionMaterialLossPolicy.EvidenceGate(
            true, false, true, true).complete());
        assertFalse(new TradeRecommendationFlexibleTransitionMaterialLossPolicy.EvidenceGate(
            true, true, false, true).complete());
        assertFalse(new TradeRecommendationFlexibleTransitionMaterialLossPolicy.EvidenceGate(
            true, true, true, false).complete());
    }

    @Test
    void locksMarketFirstDowngradeOnlySemantics() {
        var evidence = new TradeRecommendationFlexibleTransitionMaterialLossPolicy.EvidenceGate(
            true, true, true, true);
        assertEquals(TradeRecommendationPolicy.Recommendation.SIDE_A_PACKAGE_PREFERRED,
            TradeRecommendationFlexibleTransitionMaterialLossPolicy.classify(
                TradeMarketEdgePolicy.Direction.SIDE_A_MARKET_EDGE,
                evidence,
                TradeRecommendationVetoPolicy.VetoState.CLEAR));
        assertEquals(TradeRecommendationPolicy.Recommendation.SIDE_B_PACKAGE_PREFERRED,
            TradeRecommendationFlexibleTransitionMaterialLossPolicy.classify(
                TradeMarketEdgePolicy.Direction.SIDE_B_MARKET_EDGE,
                evidence,
                TradeRecommendationVetoPolicy.VetoState.CLEAR));
        assertEquals(TradeRecommendationPolicy.Recommendation.HOLD,
            TradeRecommendationFlexibleTransitionMaterialLossPolicy.classify(
                TradeMarketEdgePolicy.Direction.SIDE_A_MARKET_EDGE,
                evidence,
                TradeRecommendationVetoPolicy.VetoState.BLOCKED));
        assertEquals(TradeRecommendationPolicy.Recommendation.HOLD,
            TradeRecommendationFlexibleTransitionMaterialLossPolicy.classify(
                TradeMarketEdgePolicy.Direction.SIDE_B_MARKET_EDGE,
                evidence,
                TradeRecommendationVetoPolicy.VetoState.BLOCKED));
        assertEquals(TradeRecommendationPolicy.Recommendation.INCONCLUSIVE,
            TradeRecommendationFlexibleTransitionMaterialLossPolicy.classify(
                TradeMarketEdgePolicy.Direction.UNAVAILABLE,
                evidence,
                TradeRecommendationVetoPolicy.VetoState.CLEAR));
    }

    @Test
    void locksPackageAndActionVocabulary() {
        assertEquals(List.of(
                "SIDE_A_PACKAGE_PREFERRED",
                "SIDE_B_PACKAGE_PREFERRED",
                "HOLD",
                "INCONCLUSIVE"),
            Arrays.stream(TradeRecommendationPolicy.Recommendation.values())
                .map(Enum::name)
                .toList());
        assertEquals(List.of("ACCEPT", "REJECT", "HOLD", "INCONCLUSIVE"),
            Arrays.stream(TradeTeamPerspectiveRecommendationPolicy.Action.values())
                .map(Enum::name)
                .toList());
    }
}
