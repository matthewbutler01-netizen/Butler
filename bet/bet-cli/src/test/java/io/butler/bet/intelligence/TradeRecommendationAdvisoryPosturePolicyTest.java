package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeRecommendationAdvisoryPosturePolicyTest {

    @Test
    void postureIsNotPartOfTheV6DecisionGate() {
        var complete = new TradeRecommendationAdvisoryPosturePolicy.EvidenceGate(true, true, true);

        assertTrue(complete.complete());
        assertEquals(
            TradeRecommendationPolicy.Recommendation.SIDE_A_PACKAGE_PREFERRED,
            TradeRecommendationAdvisoryPosturePolicy.classify(
                TradeMarketEdgePolicy.Direction.SIDE_A_MARKET_EDGE,
                complete,
                TradeRecommendationVetoPolicy.VetoState.CLEAR));
    }

    @Test
    void remainingGovernedEvidenceStillFailsClosed() {
        assertFalse(new TradeRecommendationAdvisoryPosturePolicy.EvidenceGate(false, true, true).complete());
        assertFalse(new TradeRecommendationAdvisoryPosturePolicy.EvidenceGate(true, false, true).complete());
        assertFalse(new TradeRecommendationAdvisoryPosturePolicy.EvidenceGate(true, true, false).complete());

        for (var evidence : new TradeRecommendationAdvisoryPosturePolicy.EvidenceGate[] {
            new TradeRecommendationAdvisoryPosturePolicy.EvidenceGate(false, true, true),
            new TradeRecommendationAdvisoryPosturePolicy.EvidenceGate(true, false, true),
            new TradeRecommendationAdvisoryPosturePolicy.EvidenceGate(true, true, false)
        }) {
            assertEquals(
                TradeRecommendationPolicy.Recommendation.INCONCLUSIVE,
                TradeRecommendationAdvisoryPosturePolicy.classify(
                    TradeMarketEdgePolicy.Direction.SIDE_A_MARKET_EDGE,
                    evidence,
                    TradeRecommendationVetoPolicy.VetoState.CLEAR));
        }
    }

    @Test
    void marketFairAndStrategicVetoRemainDowngradeOnly() {
        var complete = new TradeRecommendationAdvisoryPosturePolicy.EvidenceGate(true, true, true);

        assertEquals(
            TradeRecommendationPolicy.Recommendation.HOLD,
            TradeRecommendationAdvisoryPosturePolicy.classify(
                TradeMarketEdgePolicy.Direction.MARKET_FAIR,
                complete,
                TradeRecommendationVetoPolicy.VetoState.CLEAR));
        assertEquals(
            TradeRecommendationPolicy.Recommendation.HOLD,
            TradeRecommendationAdvisoryPosturePolicy.classify(
                TradeMarketEdgePolicy.Direction.SIDE_B_MARKET_EDGE,
                complete,
                TradeRecommendationVetoPolicy.VetoState.BLOCKED));
        assertEquals(
            TradeRecommendationPolicy.Recommendation.INCONCLUSIVE,
            TradeRecommendationAdvisoryPosturePolicy.classify(
                TradeMarketEdgePolicy.Direction.UNAVAILABLE,
                complete,
                TradeRecommendationVetoPolicy.VetoState.CLEAR));
    }
}
