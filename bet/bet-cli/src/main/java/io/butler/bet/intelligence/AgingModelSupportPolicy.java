package io.butler.bet.intelligence;

/**
 * Governed publication-support policy for empirical aging-model cells.
 *
 * <p>The minimum support threshold was selected after empirical calibration through BF-191..BF-205.
 * It is intentionally global and based on distinct season transitions, not raw observation count.
 */
public final class AgingModelSupportPolicy {
    public static final int MINIMUM_DISTINCT_SEASON_TRANSITIONS = 5;
    public static final String POLICY_ID = "aging-support-v1-min-transitions-5";

    private AgingModelSupportPolicy() {}

    public static boolean isPublicationEligible(int distinctSeasonTransitions) {
        if (distinctSeasonTransitions < 0) {
            throw new IllegalArgumentException("distinctSeasonTransitions must not be negative");
        }
        return distinctSeasonTransitions >= MINIMUM_DISTINCT_SEASON_TRANSITIONS;
    }
}
