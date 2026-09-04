package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeRecommendationFlexibleMaterialLossPolicyTest {
    @Test
    void completeEvidenceAndClearVetoPreserveMarketDirection() {
        var result = TradeRecommendationFlexibleMaterialLossPolicy.classify(
            TradeMarketEdgePolicy.Direction.SIDE_A_MARKET_EDGE,
            completeEvidence(),
            TradeRecommendationVetoPolicy.VetoState.CLEAR);

        assertEquals(TradeRecommendationPolicy.Recommendation.SIDE_A_PACKAGE_PREFERRED, result);
    }

    @Test
    void flexibleMaterialLossVetoCanOnlyDowngradeDirectionToHold() {
        var result = TradeRecommendationFlexibleMaterialLossPolicy.classify(
            TradeMarketEdgePolicy.Direction.SIDE_B_MARKET_EDGE,
            completeEvidence(),
            TradeRecommendationVetoPolicy.VetoState.BLOCKED);

        assertEquals(TradeRecommendationPolicy.Recommendation.HOLD, result);
    }

    @Test
    void missingFlexiblePressureEvidenceIsInconclusive() {
        var evidence = new TradeRecommendationFlexibleMaterialLossPolicy.EvidenceGate(
            true, true, true, false);

        assertEquals(TradeRecommendationPolicy.Recommendation.INCONCLUSIVE,
            TradeRecommendationFlexibleMaterialLossPolicy.classify(
                TradeMarketEdgePolicy.Direction.SIDE_A_MARKET_EDGE,
                evidence,
                TradeRecommendationVetoPolicy.VetoState.CLEAR));
    }

    @Test
    void fairMarketRemainsHold() {
        assertEquals(TradeRecommendationPolicy.Recommendation.HOLD,
            TradeRecommendationFlexibleMaterialLossPolicy.classify(
                TradeMarketEdgePolicy.Direction.MARKET_FAIR,
                completeEvidence(),
                TradeRecommendationVetoPolicy.VetoState.CLEAR));
    }

    @Test
    void locksV4PolicyIdentifierAndEvidenceCompleteness() {
        assertEquals("trade-recommendation-v4-market-first-flexible-material-loss-veto",
            TradeRecommendationFlexibleMaterialLossPolicy.POLICY_ID);
        assertTrue(completeEvidence().complete());
    }

    private static TradeRecommendationFlexibleMaterialLossPolicy.EvidenceGate completeEvidence() {
        return new TradeRecommendationFlexibleMaterialLossPolicy.EvidenceGate(true, true, true, true);
    }
}
