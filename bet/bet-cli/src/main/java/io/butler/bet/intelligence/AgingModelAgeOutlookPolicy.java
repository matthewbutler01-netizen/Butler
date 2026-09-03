package io.butler.bet.intelligence;

import java.util.Objects;

/**
 * Governs descriptive per-metric age-outlook labels from validated aging-model uncertainty.
 * This policy does not aggregate metrics, change dynasty value, or issue player recommendations.
 */
public final class AgingModelAgeOutlookPolicy {
    public static final String POLICY_ID = "aging-outlook-v1-iqr-direction";

    private AgingModelAgeOutlookPolicy() {}

    public static MetricOutlook classify(AgingModelSampleAuditAnalyzer.Metric metric,
                                         double deltaP25,
                                         double deltaP75) {
        Objects.requireNonNull(metric, "metric must not be null");
        if (!Double.isFinite(deltaP25) || !Double.isFinite(deltaP75)) {
            throw new IllegalArgumentException("outlook interval must be finite");
        }
        if (deltaP25 > deltaP75) {
            throw new IllegalArgumentException("outlook interval must not be inverted");
        }

        Direction direction = direction(metric);
        double favorableLow = direction == Direction.HIGHER_IS_FAVORABLE ? deltaP25 : -deltaP75;
        double favorableHigh = direction == Direction.HIGHER_IS_FAVORABLE ? deltaP75 : -deltaP25;

        if (favorableLow > 0.0) return MetricOutlook.FAVORABLE;
        if (favorableHigh < 0.0) return MetricOutlook.UNFAVORABLE;
        return MetricOutlook.NEUTRAL_OR_MIXED;
    }

    public static Direction direction(AgingModelSampleAuditAnalyzer.Metric metric) {
        Objects.requireNonNull(metric, "metric must not be null");
        return switch (metric) {
            case INTERCEPTIONS_PER_GAME, FUMBLES_LOST_PER_GAME -> Direction.LOWER_IS_FAVORABLE;
            default -> Direction.HIGHER_IS_FAVORABLE;
        };
    }

    public enum Direction { HIGHER_IS_FAVORABLE, LOWER_IS_FAVORABLE }

    public enum MetricOutlook { FAVORABLE, NEUTRAL_OR_MIXED, UNFAVORABLE }
}
