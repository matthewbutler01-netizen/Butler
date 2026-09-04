package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeRecommendationFlexibleV4ContractTest {
    @Test
    void locksVersionedPolicyIdentifiers() {
        assertEquals("trade-recommendation-v4-market-first-flexible-material-loss-veto",
            TradeRecommendationFlexibleMaterialLossPolicy.POLICY_ID);
        assertEquals("trade-strategic-veto-v3-material-protected-value-plus-flexible-coverage-loss",
            TradeStrategicFlexibleMaterialLossVetoDetector.POLICY_ID);
        assertEquals("trade-flexible-coverage-loss-v1-post-trade-legal-lineup",
            TradeFlexibleCoverageMaterialLossAnalyzer.POLICY_ID);
        assertEquals("flexible-slot-pressure-v1-combined-relative-quartiles",
            LeagueFlexibleSlotPressurePolicy.POLICY_ID);
        assertEquals("flexible-slot-coverage-v1-direct-reserved-max-value",
            LeagueFlexibleSlotCoverageAnalyzer.POLICY_ID);
        assertEquals("trade-protected-value-materiality-v1-25-percent-loss",
            TradeProtectedValueMaterialityPolicy.POLICY_ID);
        assertEquals("trade-team-perspective-v1-explicit-owner",
            TradeTeamPerspectiveRecommendationPolicy.POLICY_ID);
    }

    @Test
    void preservesEarlierRecommendationContracts() {
        assertEquals("trade-recommendation-v1-conservative-evidence-first",
            TradeRecommendationPolicy.POLICY_ID);
        assertEquals("trade-recommendation-v2-market-first-strategic-veto",
            TradeRecommendationVetoPolicy.POLICY_ID);
        assertEquals("trade-recommendation-v3-market-first-material-loss-veto",
            TradeRecommendationMaterialLossPolicy.POLICY_ID);
        assertEquals("trade-strategic-veto-v1-explicit-weakness-protection",
            TradeStrategicVetoDetector.POLICY_ID);
        assertEquals("trade-strategic-veto-v2-material-protected-value-loss",
            TradeStrategicMaterialLossVetoDetector.POLICY_ID);
    }

    @Test
    void locksFlexibleVetoReasonVocabularyAndOrder() {
        assertEquals(List.of(
                "LOW_FUTURE_CAPITAL_MATERIAL_PICK_VALUE_LOSS",
                "POSITION_PRESSURE_MATERIAL_SAME_POSITION_VALUE_LOSS",
                "FLEXIBLE_PRESSURE_MATERIAL_POST_TRADE_COVERAGE_LOSS"),
            Arrays.stream(TradeStrategicFlexibleMaterialLossVetoDetector.ReasonCode.values())
                .map(Enum::name)
                .toList());
    }

    @Test
    void locksFlexibleLossAssessmentVocabulary() {
        assertEquals(List.of(
                "NOT_PROTECTED",
                "INSUFFICIENT_EVIDENCE",
                "WITHIN_TOLERANCE",
                "MATERIAL_LOSS"),
            Arrays.stream(TradeFlexibleCoverageMaterialLossAnalyzer.AssessmentState.values())
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
    void locksV4EvidenceGateCompleteness() {
        assertTrue(new TradeRecommendationFlexibleMaterialLossPolicy.EvidenceGate(
            true, true, true, true).complete());
        assertFalse(new TradeRecommendationFlexibleMaterialLossPolicy.EvidenceGate(
            false, true, true, true).complete());
        assertFalse(new TradeRecommendationFlexibleMaterialLossPolicy.EvidenceGate(
            true, false, true, true).complete());
        assertFalse(new TradeRecommendationFlexibleMaterialLossPolicy.EvidenceGate(
            true, true, false, true).complete());
        assertFalse(new TradeRecommendationFlexibleMaterialLossPolicy.EvidenceGate(
            true, true, true, false).complete());
    }

    @Test
    void locksDowngradeOnlyRecommendationSemantics() {
        var evidence = new TradeRecommendationFlexibleMaterialLossPolicy.EvidenceGate(
            true, true, true, true);
        assertEquals(TradeRecommendationPolicy.Recommendation.SIDE_A_PACKAGE_PREFERRED,
            TradeRecommendationFlexibleMaterialLossPolicy.classify(
                TradeMarketEdgePolicy.Direction.SIDE_A_MARKET_EDGE,
                evidence,
                TradeRecommendationVetoPolicy.VetoState.CLEAR));
        assertEquals(TradeRecommendationPolicy.Recommendation.HOLD,
            TradeRecommendationFlexibleMaterialLossPolicy.classify(
                TradeMarketEdgePolicy.Direction.SIDE_A_MARKET_EDGE,
                evidence,
                TradeRecommendationVetoPolicy.VetoState.BLOCKED));
        assertEquals(TradeRecommendationPolicy.Recommendation.HOLD,
            TradeRecommendationFlexibleMaterialLossPolicy.classify(
                TradeMarketEdgePolicy.Direction.SIDE_B_MARKET_EDGE,
                evidence,
                TradeRecommendationVetoPolicy.VetoState.BLOCKED));
    }

    @Test
    void locksActionVocabulary() {
        assertEquals(List.of("ACCEPT", "REJECT", "HOLD", "INCONCLUSIVE"),
            Arrays.stream(TradeTeamPerspectiveRecommendationPolicy.Action.values())
                .map(Enum::name)
                .toList());
    }
}
