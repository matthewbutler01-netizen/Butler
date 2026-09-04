package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TradeRecommendationVetoPolicyTest {
    private static final TradeRecommendationPolicy.EvidenceGate COMPLETE =
        new TradeRecommendationPolicy.EvidenceGate(true, true, true);

    @Test
    void preservesMarketDirectionWhenStrategicVetoIsClear() {
        assertEquals(TradeRecommendationPolicy.Recommendation.SIDE_A_PACKAGE_PREFERRED,
            TradeRecommendationVetoPolicy.classify(
                TradeMarketEdgePolicy.Direction.SIDE_A_MARKET_EDGE,
                COMPLETE,
                TradeRecommendationVetoPolicy.VetoState.CLEAR));
        assertEquals(TradeRecommendationPolicy.Recommendation.SIDE_B_PACKAGE_PREFERRED,
            TradeRecommendationVetoPolicy.classify(
                TradeMarketEdgePolicy.Direction.SIDE_B_MARKET_EDGE,
                COMPLETE,
                TradeRecommendationVetoPolicy.VetoState.CLEAR));
    }

    @Test
    void strategicVetoCanOnlyDowngradeDirectionToHold() {
        assertEquals(TradeRecommendationPolicy.Recommendation.HOLD,
            TradeRecommendationVetoPolicy.classify(
                TradeMarketEdgePolicy.Direction.SIDE_A_MARKET_EDGE,
                COMPLETE,
                TradeRecommendationVetoPolicy.VetoState.BLOCKED));
        assertEquals(TradeRecommendationPolicy.Recommendation.HOLD,
            TradeRecommendationVetoPolicy.classify(
                TradeMarketEdgePolicy.Direction.SIDE_B_MARKET_EDGE,
                COMPLETE,
                TradeRecommendationVetoPolicy.VetoState.BLOCKED));
    }

    @Test
    void marketFairStaysHoldRegardlessOfVetoState() {
        assertEquals(TradeRecommendationPolicy.Recommendation.HOLD,
            TradeRecommendationVetoPolicy.classify(
                TradeMarketEdgePolicy.Direction.MARKET_FAIR,
                COMPLETE,
                TradeRecommendationVetoPolicy.VetoState.CLEAR));
        assertEquals(TradeRecommendationPolicy.Recommendation.HOLD,
            TradeRecommendationVetoPolicy.classify(
                TradeMarketEdgePolicy.Direction.MARKET_FAIR,
                COMPLETE,
                TradeRecommendationVetoPolicy.VetoState.BLOCKED));
    }

    @Test
    void incompleteEvidenceStillFailsClosedBeforeVeto() {
        assertEquals(TradeRecommendationPolicy.Recommendation.INCONCLUSIVE,
            TradeRecommendationVetoPolicy.classify(
                TradeMarketEdgePolicy.Direction.SIDE_A_MARKET_EDGE,
                new TradeRecommendationPolicy.EvidenceGate(false, true, true),
                TradeRecommendationVetoPolicy.VetoState.CLEAR));
        assertEquals(TradeRecommendationPolicy.Recommendation.INCONCLUSIVE,
            TradeRecommendationVetoPolicy.classify(
                TradeMarketEdgePolicy.Direction.SIDE_A_MARKET_EDGE,
                new TradeRecommendationPolicy.EvidenceGate(true, true, false),
                TradeRecommendationVetoPolicy.VetoState.BLOCKED));
    }

    @Test
    void unavailableMarketDirectionRemainsInconclusive() {
        assertEquals(TradeRecommendationPolicy.Recommendation.INCONCLUSIVE,
            TradeRecommendationVetoPolicy.classify(
                TradeMarketEdgePolicy.Direction.UNAVAILABLE,
                COMPLETE,
                TradeRecommendationVetoPolicy.VetoState.BLOCKED));
    }
}
