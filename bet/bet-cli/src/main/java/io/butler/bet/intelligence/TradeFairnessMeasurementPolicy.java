package io.butler.bet.intelligence;

/**
 * Governs the market-value measurement used by future trade-fairness interpretation.
 *
 * The policy intentionally defines no FAIR / NOT_FAIR tolerance. A tolerance is a separate
 * product-governance decision. Supporting evidence does not enter this calculation.
 */
public final class TradeFairnessMeasurementPolicy {
    public static final String POLICY_ID = "trade-fairness-measure-v1-midpoint-percent";

    private TradeFairnessMeasurementPolicy() {}

    /**
     * Returns the absolute market-value gap as a symmetric percentage of the two-side midpoint.
     * Example: 105 vs 95 => 10 / 100 = 10%.
     * Both-zero totals are treated as a 0% gap.
     */
    public static double symmetricGapPercent(double sideAValue, double sideBValue) {
        requireFiniteNonNegative(sideAValue, "sideAValue");
        requireFiniteNonNegative(sideBValue, "sideBValue");
        double midpoint = (sideAValue + sideBValue) / 2.0;
        if (midpoint == 0.0) return 0.0;
        return Math.abs(sideAValue - sideBValue) * 100.0 / midpoint;
    }

    private static void requireFiniteNonNegative(double value, String field) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(field + " must be finite and non-negative");
        }
    }
}
