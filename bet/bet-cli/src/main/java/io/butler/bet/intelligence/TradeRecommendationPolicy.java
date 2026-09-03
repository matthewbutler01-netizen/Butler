package io.butler.bet.intelligence;

import java.util.Objects;

/**
 * Conservative, evidence-first package recommendation policy.
 * Supporting strategic dimensions are hard evidence gates, not weighted inputs.
 * This policy is package-relative and intentionally does not emit ACCEPT/REJECT without a team perspective.
 */
public final class TradeRecommendationPolicy {
    public static final String POLICY_ID = "trade-recommendation-v1-conservative-evidence-first";

    private TradeRecommendationPolicy() {}

    public enum Recommendation {
        SIDE_A_PACKAGE_PREFERRED,
        SIDE_B_PACKAGE_PREFERRED,
        HOLD,
        INCONCLUSIVE
    }

    public record EvidenceGate(boolean postureAvailable,
                               boolean futureCapitalAvailable,
                               boolean positionalPressureAvailable) {
        public boolean complete() {
            return postureAvailable && futureCapitalAvailable && positionalPressureAvailable;
        }
    }

    public static Recommendation classify(TradeMarketEdgePolicy.Direction marketEdge, EvidenceGate evidence) {
        Objects.requireNonNull(marketEdge, "marketEdge must not be null");
        Objects.requireNonNull(evidence, "evidence must not be null");

        if (!evidence.complete() || marketEdge == TradeMarketEdgePolicy.Direction.UNAVAILABLE) {
            return Recommendation.INCONCLUSIVE;
        }
        return switch (marketEdge) {
            case MARKET_FAIR -> Recommendation.HOLD;
            case SIDE_A_MARKET_EDGE -> Recommendation.SIDE_A_PACKAGE_PREFERRED;
            case SIDE_B_MARKET_EDGE -> Recommendation.SIDE_B_PACKAGE_PREFERRED;
            case UNAVAILABLE -> Recommendation.INCONCLUSIVE;
        };
    }
}
