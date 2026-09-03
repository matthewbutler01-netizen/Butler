package io.butler.bet.intelligence;

/**
 * Governs the first market-value-only fairness interpretation for player trades.
 * Supporting evidence remains explanatory context and never changes the fairness result.
 */
public final class TradeFairnessPolicy {
    public static final String POLICY_ID = "trade-fairness-v1-midpoint-gap-5pct";
    public static final double MAXIMUM_FAIR_GAP_PERCENT = 5.0;

    private TradeFairnessPolicy() {}

    public enum Classification {
        MARKET_FAIR,
        OUTSIDE_FAIRNESS_BAND,
        UNAVAILABLE
    }

    public static Classification classify(Double symmetricGapPercent) {
        if (symmetricGapPercent == null) return Classification.UNAVAILABLE;
        if (!Double.isFinite(symmetricGapPercent) || symmetricGapPercent < 0.0) {
            throw new IllegalArgumentException("symmetricGapPercent must be finite and non-negative");
        }
        return symmetricGapPercent <= MAXIMUM_FAIR_GAP_PERCENT
            ? Classification.MARKET_FAIR
            : Classification.OUTSIDE_FAIRNESS_BAND;
    }
}
