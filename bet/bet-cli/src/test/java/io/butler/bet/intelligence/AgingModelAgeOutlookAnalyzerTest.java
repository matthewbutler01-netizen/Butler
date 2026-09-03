package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgingModelAgeOutlookAnalyzerTest {
    @Test
    void appliesOutlookOnlyToValidationCompletePublishedCells() {
        var favorable = validatedCell(
            AgingModelSampleAuditAnalyzer.Metric.RECEIVING_YARDS_PER_GAME, 28, 0.2, 0.7, 1.1, true);
        var incomplete = validatedCell(
            AgingModelSampleAuditAnalyzer.Metric.RECEPTIONS_PER_GAME, 29, -0.2, 0.0, 0.3, false);

        var report = new AgingModelPublicationValidationAnalyzer.ValidationReport(
            "nflverse-players", "nflverse", AgingModelSupportPolicy.POLICY_ID,
            AgingModelSupportPolicy.MINIMUM_DISTINCT_SEASON_TRANSITIONS,
            2, 1, List.of(favorable, incomplete));

        var outlook = AgingModelAgeOutlookAnalyzer.apply(report);

        assertEquals(AgingModelAgeOutlookPolicy.POLICY_ID, outlook.outlookPolicyId());
        assertEquals(2, outlook.publishedCells());
        assertEquals(1, outlook.outlookAvailableCells());
        assertEquals(1, outlook.outlookUnavailableCells());
        assertEquals(1, outlook.favorableCells());
        assertEquals(0, outlook.neutralOrMixedCells());
        assertEquals(0, outlook.unfavorableCells());

        var available = outlook.cells().stream().filter(AgingModelAgeOutlookAnalyzer.OutlookCell::outlookAvailable).findFirst().orElseThrow();
        assertEquals(AgingModelAgeOutlookPolicy.Direction.HIGHER_IS_FAVORABLE, available.direction());
        assertEquals(AgingModelAgeOutlookPolicy.MetricOutlook.FAVORABLE, available.outlook());

        var unavailable = outlook.cells().stream().filter(cell -> !cell.outlookAvailable()).findFirst().orElseThrow();
        assertFalse(unavailable.validation().validationComplete());
        assertNull(unavailable.direction());
        assertNull(unavailable.outlook());
    }

    @Test
    void preservesTurnoverDirectionAndNeutralIntervals() {
        var favorableTurnover = validatedCell(
            AgingModelSampleAuditAnalyzer.Metric.INTERCEPTIONS_PER_GAME, 31, -0.4, -0.2, -0.1, true);
        var neutral = validatedCell(
            AgingModelSampleAuditAnalyzer.Metric.RUSHING_YARDS_PER_GAME, 31, -0.5, 0.0, 0.6, true);

        var report = new AgingModelPublicationValidationAnalyzer.ValidationReport(
            "nflverse-players", "nflverse", AgingModelSupportPolicy.POLICY_ID,
            AgingModelSupportPolicy.MINIMUM_DISTINCT_SEASON_TRANSITIONS,
            2, 2, List.of(favorableTurnover, neutral));

        var outlook = AgingModelAgeOutlookAnalyzer.apply(report);
        assertEquals(1, outlook.favorableCells());
        assertEquals(1, outlook.neutralOrMixedCells());
        assertEquals(0, outlook.unfavorableCells());
        assertTrue(outlook.cells().stream().allMatch(AgingModelAgeOutlookAnalyzer.OutlookCell::outlookAvailable));
    }

    private static AgingModelPublicationValidationAnalyzer.ValidatedCell validatedCell(
        AgingModelSampleAuditAnalyzer.Metric metric,
        int age,
        double p25,
        double median,
        double p75,
        boolean complete) {
        var cell = new AgingModelLocalSmootherAnalyzer.SmoothedCell(
            "WR", metric, age, 10, 25, 20, 8, List.of(age - 1, age, age + 1), p25, median, p75);
        if (!complete) {
            return new AgingModelPublicationValidationAnalyzer.ValidatedCell(cell, null, null, null, false);
        }
        var span = new AgingModelPublicationValidationAnalyzer.TrainingSpan(2005, 2025, 25);
        var holdout = new AgingModelTemporalHoldoutAnalyzer.DimensionDiagnostic(
            "WR", metric, 100, 0.0, 1.0, 0.8, 1.2);
        var stability = new AgingModelNormalizedStabilityAnalyzer.NormalizedCell(
            "WR", metric, age, 25, 8, 8, 0, median,
            0.05, 0.08, 0.10, 1.0, 0.05, 0.08, 0.10, 2012, 2013);
        return new AgingModelPublicationValidationAnalyzer.ValidatedCell(cell, span, holdout, stability, true);
    }
}
