package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TradeRecommendationPolicyTest {
    private static final TradeRecommendationPolicy.EvidenceGate COMPLETE =
        new TradeRecommendationPolicy.EvidenceGate(true, true, true);

    @Test
    void prefersSideAPackageOnlyWithCompleteEvidenceAndMarketEdge() {
        assertEquals(TradeRecommendationPolicy.Recommendation.SIDE_A_PACKAGE_PREFERRED,
            TradeRecommendationPolicy.classify(TradeMarketEdgePolicy.Direction.SIDE_A_MARKET_EDGE, COMPLETE));
    }

    @Test
    void prefersSideBPackageOnlyWithCompleteEvidenceAndMarketEdge() {
        assertEquals(TradeRecommendationPolicy.Recommendation.SIDE_B_PACKAGE_PREFERRED,
            TradeRecommendationPolicy.classify(TradeMarketEdgePolicy.Direction.SIDE_B_MARKET_EDGE, COMPLETE));
    }

    @Test
    void holdsWhenMarketIsFair() {
        assertEquals(TradeRecommendationPolicy.Recommendation.HOLD,
            TradeRecommendationPolicy.classify(TradeMarketEdgePolicy.Direction.MARKET_FAIR, COMPLETE));
    }

    @Test
    void abstainsWhenMarketEdgeIsUnavailable() {
        assertEquals(TradeRecommendationPolicy.Recommendation.INCONCLUSIVE,
            TradeRecommendationPolicy.classify(TradeMarketEdgePolicy.Direction.UNAVAILABLE, COMPLETE));
    }

    @Test
    void abstainsWhenAnySupportingEvidenceIsUnavailable() {
        assertEquals(TradeRecommendationPolicy.Recommendation.INCONCLUSIVE,
            TradeRecommendationPolicy.classify(TradeMarketEdgePolicy.Direction.SIDE_A_MARKET_EDGE,
                new TradeRecommendationPolicy.EvidenceGate(false, true, true)));
        assertEquals(TradeRecommendationPolicy.Recommendation.INCONCLUSIVE,
            TradeRecommendationPolicy.classify(TradeMarketEdgePolicy.Direction.SIDE_A_MARKET_EDGE,
                new TradeRecommendationPolicy.EvidenceGate(true, false, true)));
        assertEquals(TradeRecommendationPolicy.Recommendation.INCONCLUSIVE,
            TradeRecommendationPolicy.classify(TradeMarketEdgePolicy.Direction.SIDE_A_MARKET_EDGE,
                new TradeRecommendationPolicy.EvidenceGate(true, true, false)));
    }
}
