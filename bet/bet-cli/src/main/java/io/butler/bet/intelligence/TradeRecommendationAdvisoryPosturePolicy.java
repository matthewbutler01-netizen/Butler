package io.butler.bet.intelligence;

import java.util.Objects;

/**
 * Versioned market-first trade recommendation contract that keeps team posture visible as
 * descriptive advisory context instead of requiring it to be available before a decision can
 * be made. Exact market direction, future capital, positional pressure, and flexible pressure
 * remain governed decision evidence. Strategic vetoes remain downgrade-only.
 */
public final class TradeRecommendationAdvisoryPosturePolicy {
    public static final String POLICY_ID =
        "trade-recommendation-v6-market-first-advisory-posture-flexible-transition-material-loss-veto";

    private TradeRecommendationAdvisoryPosturePolicy() {}

    public record EvidenceGate(
        boolean futureCapitalAvailable,
        boolean positionalPressureAvailable,
        boolean flexiblePressureAvailable) {
        public boolean complete() {
            return futureCapitalAvailable && positionalPressureAvailable && flexiblePressureAvailable;
        }
    }

    public static TradeRecommendationPolicy.Recommendation classify(
        TradeMarketEdgePolicy.Direction marketEdge,
        EvidenceGate evidence,
        TradeRecommendationVetoPolicy.VetoState vetoState) {
        Objects.requireNonNull(marketEdge, "marketEdge must not be null");
        Objects.requireNonNull(evidence, "evidence must not be null");
        Objects.requireNonNull(vetoState, "vetoState must not be null");

        if (!evidence.complete() || marketEdge == TradeMarketEdgePolicy.Direction.UNAVAILABLE) {
            return TradeRecommendationPolicy.Recommendation.INCONCLUSIVE;
        }
        if (marketEdge == TradeMarketEdgePolicy.Direction.MARKET_FAIR
            || vetoState == TradeRecommendationVetoPolicy.VetoState.BLOCKED) {
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
