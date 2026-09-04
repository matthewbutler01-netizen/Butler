package io.butler.bet.intelligence;

import java.util.Objects;

/**
 * Versioned top-level recommendation contract for the 25% protected-value material-loss veto.
 * Market direction remains primary; a strategic material-loss veto may only downgrade direction to HOLD.
 */
public final class TradeRecommendationMaterialLossPolicy {
    public static final String POLICY_ID = "trade-recommendation-v3-market-first-material-loss-veto";

    private TradeRecommendationMaterialLossPolicy() {}

    public static TradeRecommendationPolicy.Recommendation classify(
        TradeMarketEdgePolicy.Direction marketEdge,
        TradeRecommendationPolicy.EvidenceGate evidence,
        TradeRecommendationVetoPolicy.VetoState vetoState) {
        Objects.requireNonNull(marketEdge, "marketEdge must not be null");
        Objects.requireNonNull(evidence, "evidence must not be null");
        Objects.requireNonNull(vetoState, "vetoState must not be null");
        return TradeRecommendationVetoPolicy.classify(marketEdge, evidence, vetoState);
    }
}
