package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgingModelPublicationValidationAnalyzerTest {
    @Test
    void enrichesPublishedCellWithTrainingHoldoutAndStabilityDiagnostics() {
        var metric = AgingModelSampleAuditAnalyzer.Metric.PASSING_YARDS_PER_GAME;
        var cell = new AgingModelLocalSmootherAnalyzer.SmoothedCell(
            "QB", metric, 25, 12, 30, 20, 5, List.of(24, 25, 26), -3.0, 1.0, 4.0);
        var published = new AgingModelPublishedSmootherAnalyzer.PublishedSmootherReport(
            "profiles", "production", AgingModelSupportPolicy.POLICY_ID,
            AgingModelSupportPolicy.MINIMUM_DISTINCT_SEASON_TRANSITIONS, 1, 0, List.of(cell));
        var observations = List.of(
            observation("a", metric, 24, 2018, 2019),
            observation("b", metric, 25, 2021, 2022),
            observation("c", metric, 26, 2023, 2024));
        var holdout = new AgingModelTemporalHoldoutAnalyzer.DimensionDiagnostic(
            "QB", metric, 100, 0.2, 10.0, 8.0, 14.0);
        var stability = new AgingModelNormalizedStabilityAnalyzer.NormalizedCell(
            "QB", metric, 25, 30, 5, 5, 0, 1.0,
            0.5, 0.8, 1.2, 10.0, 0.05, 0.08, 0.12, 2021, 2022);

        var report = AgingModelPublicationValidationAnalyzer.enrich(
            published, observations, List.of(holdout), List.of(stability));

        assertEquals(1, report.publishedCells());
        assertEquals(1, report.validationCompleteCells());
        assertTrue(report.allPublishedCellsValidationComplete());
        var validated = report.cells().getFirst();
        assertTrue(validated.validationComplete());
        assertEquals(2018, validated.trainingSpan().minimumStartSeason());
        assertEquals(2024, validated.trainingSpan().maximumEndSeason());
        assertEquals(3, validated.trainingSpan().observations());
        assertEquals(10.0, validated.holdout().meanAbsoluteError());
        assertEquals(0.12, validated.stability().maximumShiftToHoldoutMae());
        assertEquals(-3.0, validated.deltaP25());
        assertEquals(1.0, validated.medianDelta());
        assertEquals(4.0, validated.deltaP75());
    }

    @Test
    void retainsPublishedCellAsValidationIncompleteWhenDiagnosticsAreMissing() {
        var metric = AgingModelSampleAuditAnalyzer.Metric.PASSING_YARDS_PER_GAME;
        var cell = new AgingModelLocalSmootherAnalyzer.SmoothedCell(
            "QB", metric, 25, 12, 30, 20, 5, List.of(24, 25, 26), -3.0, 1.0, 4.0);
        var published = new AgingModelPublishedSmootherAnalyzer.PublishedSmootherReport(
            "profiles", "production", AgingModelSupportPolicy.POLICY_ID,
            AgingModelSupportPolicy.MINIMUM_DISTINCT_SEASON_TRANSITIONS, 1, 0, List.of(cell));

        var report = AgingModelPublicationValidationAnalyzer.enrich(
            published, List.of(observation("a", metric, 25, 2021, 2022)), List.of(), List.of());

        assertEquals(1, report.publishedCells());
        assertEquals(0, report.validationCompleteCells());
        assertEquals(1, report.validationIncompleteCells());
        assertFalse(report.allPublishedCellsValidationComplete());
        assertFalse(report.cells().getFirst().validationComplete());
    }

    private static AgingModelSampleAuditAnalyzer.AgingObservation observation(
        String id, AgingModelSampleAuditAnalyzer.Metric metric, int age, int start, int end) {
        return new AgingModelSampleAuditAnalyzer.AgingObservation(
            id, id, "QB", metric, age, start, end, 100.0, 101.0, 1.0);
    }
}
