package io.butler.bet.intelligence;

import java.util.Objects;

/**
 * Conservative market-first recommendation policy with an explicit strategic veto.
 * A veto may only downgrade a directional market recommendation to HOLD.
 * It cannot create direction, flip the preferred side, or override incomplete evidence.
 */
public final class TradeRecommendationVetoPolicy {
    public static final String POLICY_ID = "trade-recommendation-v2-market-first-strategic-veto";

    private TradeRecommendationVetoPolicy() {}

    public enum VetoState {
        CLEAR,
        BLOCKED
    }

    public static TradeRecommendationPolicy.Recommendation classify(
        TradeMarketEdgePolicy.Direction marketEdge,
        TradeRecommendationPolicy.EvidenceGate evidence,
        VetoState vetoState) {
        Objects.requireNonNull(marketEdge, "marketEdge must not be null");
        Objects.requireNonNull(evidence, "evidence must not be null");
        Objects.requireNonNull(vetoState, "vetoState must not be null");

        if (!evidence.complete() || marketEdge == TradeMarketEdgePolicy.Direction.UNAVAILABLE) {
            return TradeRecommendationPolicy.Recommendation.INCONCLUSIVE;
        }
        if (marketEdge == TradeMarketEdgePolicy.Direction.MARKET_FAIR) {
            return TradeRecommendationPolicy.Recommendation.HOLD;
        }
        if (vetoState == VetoState.BLOCKED) {
            return TradeRecommendationPolicy.Recommendation.HOLD;
        }
        return switch (marketEdge) {
            case SIDE_A_MARKET_EDGE -> TradeRecommendationPolicy.Recommendation.SIDE_A_PACKAGE_PREFERRED;
            case SIDE_B_MARKET_EDGE -> TradeRecommendationPolicy.Recommendation.SIDE_B_PACKAGE_PREFERRED;
            case MARKET_FAIR -> TradeRecommendationPolicy.Recommendation.HOLD;
            case UNAVAILABLE -> TradeRecommendationPolicy.Recommendation.INCONCLUSIVE;
        };
    }
}
