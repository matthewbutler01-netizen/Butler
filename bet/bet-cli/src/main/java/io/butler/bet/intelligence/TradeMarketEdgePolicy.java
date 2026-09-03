package io.butler.bet.intelligence;

/**
 * Governs directional market-value interpretation after the trade fairness band is applied.
 * This is not a winner, accept/reject, or strategy recommendation.
 */
public final class TradeMarketEdgePolicy {
    public static final String POLICY_ID = "trade-market-edge-v1-outside-fairness-band";

    private TradeMarketEdgePolicy() {}

    public enum Direction {
        MARKET_FAIR,
        SIDE_A_MARKET_EDGE,
        SIDE_B_MARKET_EDGE,
        UNAVAILABLE
    }

    public static Direction classify(
        TradeFairnessPolicy.Classification fairnessClassification,
        Double signedValueDifference) {
        if (fairnessClassification == null) {
            throw new IllegalArgumentException("fairnessClassification must not be null");
        }
        if (fairnessClassification == TradeFairnessPolicy.Classification.UNAVAILABLE) {
            if (signedValueDifference != null) {
                throw new IllegalArgumentException("unavailable fairness must not have a signed value difference");
            }
            return Direction.UNAVAILABLE;
        }
        if (signedValueDifference == null || !Double.isFinite(signedValueDifference)) {
            throw new IllegalArgumentException("available fairness requires a finite signed value difference");
        }
        if (fairnessClassification == TradeFairnessPolicy.Classification.MARKET_FAIR) {
            return Direction.MARKET_FAIR;
        }
        if (signedValueDifference > 0.0) return Direction.SIDE_A_MARKET_EDGE;
        if (signedValueDifference < 0.0) return Direction.SIDE_B_MARKET_EDGE;
        throw new IllegalArgumentException("outside fairness band cannot have zero signed value difference");
    }
}
