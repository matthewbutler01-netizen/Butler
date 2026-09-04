package io.butler.bet.intelligence;

import java.util.Objects;

/**
 * Versioned top-level recommendation contract that adds governed FLEX/SUPERFLEX pressure evidence
 * and legal post-trade flexible coverage material loss to the existing market-first veto model.
 * A strategic veto may only downgrade a directional market recommendation to HOLD.
 */
public final class TradeRecommendationFlexibleMaterialLossPolicy {
    public static final String POLICY_ID = "trade-recommendation-v4-market-first-flexible-material-loss-veto";

    private TradeRecommendationFlexibleMaterialLossPolicy() {}

    public record EvidenceGate(
        boolean postureAvailable,
        boolean futureCapitalAvailable,
        boolean positionalPressureAvailable,
        boolean flexiblePressureAvailable) {
        public boolean complete() {
            return postureAvailable && futureCapitalAvailable
                && positionalPressureAvailable && flexiblePressureAvailable;
        }

        TradeRecommendationPolicy.EvidenceGate legacyMaterialLossGate() {
            return new TradeRecommendationPolicy.EvidenceGate(
                postureAvailable, futureCapitalAvailable, positionalPressureAvailable);
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
        return TradeRecommendationMaterialLossPolicy.classify(
            marketEdge, evidence.legacyMaterialLossGate(), vetoState);
    }
}
