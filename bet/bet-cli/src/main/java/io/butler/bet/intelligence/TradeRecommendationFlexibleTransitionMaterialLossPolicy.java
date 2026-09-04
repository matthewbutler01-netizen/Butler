package io.butler.bet.intelligence;

import java.util.Objects;

/**
 * Versioned top-level recommendation contract that preserves the v4 evidence gate and market-first
 * downgrade-only behavior while allowing governed material transitions into FLEXIBLE_PRESSURE to
 * participate in the strategic veto. No weighting, side flipping, or score blending is applied.
 */
public final class TradeRecommendationFlexibleTransitionMaterialLossPolicy {
    public static final String POLICY_ID =
        "trade-recommendation-v5-market-first-flexible-transition-material-loss-veto";

    private TradeRecommendationFlexibleTransitionMaterialLossPolicy() {}

    public record EvidenceGate(
        boolean postureAvailable,
        boolean futureCapitalAvailable,
        boolean positionalPressureAvailable,
        boolean flexiblePressureAvailable) {
        public boolean complete() {
            return postureAvailable && futureCapitalAvailable
                && positionalPressureAvailable && flexiblePressureAvailable;
        }

        TradeRecommendationFlexibleMaterialLossPolicy.EvidenceGate v4Gate() {
            return new TradeRecommendationFlexibleMaterialLossPolicy.EvidenceGate(
                postureAvailable,
                futureCapitalAvailable,
                positionalPressureAvailable,
                flexiblePressureAvailable);
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
        return TradeRecommendationFlexibleMaterialLossPolicy.classify(
            marketEdge, evidence.v4Gate(), vetoState);
    }
}
