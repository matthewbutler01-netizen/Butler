package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgingModelAgeOutlookPolicyTest {
    @Test
    void classifiesHigherIsFavorableMetricsOnlyWhenEntireIqrHasSameDirection() {
        assertEquals(AgingModelAgeOutlookPolicy.MetricOutlook.FAVORABLE,
            AgingModelAgeOutlookPolicy.classify(
                AgingModelSampleAuditAnalyzer.Metric.RECEIVING_YARDS_PER_GAME, 0.2, 1.5));
        assertEquals(AgingModelAgeOutlookPolicy.MetricOutlook.UNFAVORABLE,
            AgingModelAgeOutlookPolicy.classify(
                AgingModelSampleAuditAnalyzer.Metric.RECEIVING_YARDS_PER_GAME, -1.5, -0.2));
        assertEquals(AgingModelAgeOutlookPolicy.MetricOutlook.NEUTRAL_OR_MIXED,
            AgingModelAgeOutlookPolicy.classify(
                AgingModelSampleAuditAnalyzer.Metric.RECEIVING_YARDS_PER_GAME, -0.2, 0.4));
    }

    @Test
    void reversesDirectionForTurnoverMetrics() {
        assertEquals(AgingModelAgeOutlookPolicy.MetricOutlook.FAVORABLE,
            AgingModelAgeOutlookPolicy.classify(
                AgingModelSampleAuditAnalyzer.Metric.INTERCEPTIONS_PER_GAME, -0.5, -0.1));
        assertEquals(AgingModelAgeOutlookPolicy.MetricOutlook.UNFAVORABLE,
            AgingModelAgeOutlookPolicy.classify(
                AgingModelSampleAuditAnalyzer.Metric.FUMBLES_LOST_PER_GAME, 0.1, 0.3));
    }

    @Test
    void zeroInsideOrOnIntervalIsNeutralOrMixed() {
        assertEquals(AgingModelAgeOutlookPolicy.MetricOutlook.NEUTRAL_OR_MIXED,
            AgingModelAgeOutlookPolicy.classify(
                AgingModelSampleAuditAnalyzer.Metric.RUSHING_YARDS_PER_GAME, 0.0, 0.8));
        assertEquals(AgingModelAgeOutlookPolicy.MetricOutlook.NEUTRAL_OR_MIXED,
            AgingModelAgeOutlookPolicy.classify(
                AgingModelSampleAuditAnalyzer.Metric.INTERCEPTIONS_PER_GAME, -0.3, 0.0));
    }

    @Test
    void rejectsInvalidIntervals() {
        assertThrows(IllegalArgumentException.class,
            () -> AgingModelAgeOutlookPolicy.classify(
                AgingModelSampleAuditAnalyzer.Metric.RECEPTIONS_PER_GAME, 1.0, -1.0));
        assertThrows(IllegalArgumentException.class,
            () -> AgingModelAgeOutlookPolicy.classify(
                AgingModelSampleAuditAnalyzer.Metric.RECEPTIONS_PER_GAME, Double.NaN, 1.0));
    }

    @Test
    void exposesVersionedPolicyIdentifierAndDirections() {
        assertEquals("aging-outlook-v1-iqr-direction", AgingModelAgeOutlookPolicy.POLICY_ID);
        assertEquals(AgingModelAgeOutlookPolicy.Direction.LOWER_IS_FAVORABLE,
            AgingModelAgeOutlookPolicy.direction(AgingModelSampleAuditAnalyzer.Metric.INTERCEPTIONS_PER_GAME));
        assertEquals(AgingModelAgeOutlookPolicy.Direction.HIGHER_IS_FAVORABLE,
            AgingModelAgeOutlookPolicy.direction(AgingModelSampleAuditAnalyzer.Metric.PASSING_YARDS_PER_GAME));
    }
}
