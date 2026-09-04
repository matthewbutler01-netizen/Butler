package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TradeRecommendationMaterialLossPolicyTest {
    private static final TradeRecommendationPolicy.EvidenceGate COMPLETE =
        new TradeRecommendationPolicy.EvidenceGate(true, true, true);

    @Test
    void preservesDirectionalMarketRecommendationWhenVetoClear() {
        assertEquals(TradeRecommendationPolicy.Recommendation.SIDE_A_PACKAGE_PREFERRED,
            TradeRecommendationMaterialLossPolicy.classify(
                TradeMarketEdgePolicy.Direction.SIDE_A_MARKET_EDGE,
                COMPLETE,
                TradeRecommendationVetoPolicy.VetoState.CLEAR));
    }

    @Test
    void materialLossVetoDowngradesDirectionalRecommendationToHold() {
        assertEquals(TradeRecommendationPolicy.Recommendation.HOLD,
            TradeRecommendationMaterialLossPolicy.classify(
                TradeMarketEdgePolicy.Direction.SIDE_B_MARKET_EDGE,
                COMPLETE,
                TradeRecommendationVetoPolicy.VetoState.BLOCKED));
    }

    @Test
    void incompleteEvidenceAndUnavailableMarketStillFailClosed() {
        assertEquals(TradeRecommendationPolicy.Recommendation.INCONCLUSIVE,
            TradeRecommendationMaterialLossPolicy.classify(
                TradeMarketEdgePolicy.Direction.SIDE_A_MARKET_EDGE,
                new TradeRecommendationPolicy.EvidenceGate(true, false, true),
                TradeRecommendationVetoPolicy.VetoState.CLEAR));
        assertEquals(TradeRecommendationPolicy.Recommendation.INCONCLUSIVE,
            TradeRecommendationMaterialLossPolicy.classify(
                TradeMarketEdgePolicy.Direction.UNAVAILABLE,
                COMPLETE,
                TradeRecommendationVetoPolicy.VetoState.BLOCKED));
    }

    @Test
    void marketFairRemainsHold() {
        assertEquals(TradeRecommendationPolicy.Recommendation.HOLD,
            TradeRecommendationMaterialLossPolicy.classify(
                TradeMarketEdgePolicy.Direction.MARKET_FAIR,
                COMPLETE,
                TradeRecommendationVetoPolicy.VetoState.CLEAR));
    }

    @Test
    void locksVersionedPolicyId() {
        assertEquals("trade-recommendation-v3-market-first-material-loss-veto",
            TradeRecommendationMaterialLossPolicy.POLICY_ID);
    }
}
