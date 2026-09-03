package io.butler.bet.intelligence;

import java.util.Objects;

/**
 * Derives governed market-edge direction from an existing trade evidence package.
 * Supporting evidence remains descriptive and cannot alter the direction.
 */
public final class TradeMarketEdgeAnalyzer {
    public MarketEdgeReport analyze(TradeSupportingEvidenceAnalyzer.TradeEvidencePackage trade) {
        Objects.requireNonNull(trade, "trade must not be null");
        TradeMarketEdgePolicy.Direction direction = TradeMarketEdgePolicy.classify(
            trade.fairnessClassification(), trade.valueDifference());
        return new MarketEdgeReport(
            TradeMarketEdgePolicy.POLICY_ID,
            trade.fairnessPolicyId(),
            trade.fairnessClassification(),
            trade.valueDifference(),
            direction);
    }

    public record MarketEdgeReport(
        String policyId,
        String fairnessPolicyId,
        TradeFairnessPolicy.Classification fairnessClassification,
        Double signedValueDifference,
        TradeMarketEdgePolicy.Direction direction) {
        public MarketEdgeReport {
            Objects.requireNonNull(policyId, "policyId must not be null");
            Objects.requireNonNull(fairnessPolicyId, "fairnessPolicyId must not be null");
            Objects.requireNonNull(fairnessClassification, "fairnessClassification must not be null");
            Objects.requireNonNull(direction, "direction must not be null");
            if (direction == TradeMarketEdgePolicy.Direction.UNAVAILABLE && signedValueDifference != null) {
                throw new IllegalArgumentException("unavailable market edge must not have signed value difference");
            }
            if (direction != TradeMarketEdgePolicy.Direction.UNAVAILABLE && signedValueDifference == null) {
                throw new IllegalArgumentException("available market edge requires signed value difference");
            }
        }
    }
}
