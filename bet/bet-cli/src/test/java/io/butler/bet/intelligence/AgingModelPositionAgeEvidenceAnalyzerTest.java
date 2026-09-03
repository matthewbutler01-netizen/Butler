package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgingModelPositionAgeEvidenceAnalyzerTest {
    @Test
    void bundlesOnlyPositionRelevantMetricsAndFailsClosedPerMetric() {
        var report = new AgingModelLocalSmootherAnalyzer.LocalSmootherReport(
            "profiles", "production", List.of(
                cell(AgingModelSampleAuditAnalyzer.Metric.PASSING_YARDS_PER_GAME, 5),
                cell(AgingModelSampleAuditAnalyzer.Metric.PASSING_TOUCHDOWNS_PER_GAME, 4),
                cell(AgingModelSampleAuditAnalyzer.Metric.RUSHING_YARDS_PER_GAME, 9),
                cell(AgingModelSampleAuditAnalyzer.Metric.RUSHING_TOUCHDOWNS_PER_GAME, 1)));

        var evidence = AgingModelPositionAgeEvidenceAnalyzer.resolve(report, " qb ", 25);

        assertEquals("QB", evidence.position());
        assertEquals(25, evidence.age());
        assertEquals(AgingModelSupportPolicy.POLICY_ID, evidence.supportPolicyId());
        assertEquals(5, evidence.minimumDistinctSeasonTransitions());
        assertEquals(5, evidence.metrics().size());
        assertEquals(2, evidence.publishedMetrics());
        assertEquals(2, evidence.belowSupportMetrics());
        assertEquals(1, evidence.notObservedMetrics());

        var passingYards = metric(evidence, AgingModelSampleAuditAnalyzer.Metric.PASSING_YARDS_PER_GAME);
        assertTrue(passingYards.available());
        assertEquals(5, passingYards.cell().distinctSeasonTransitions());

        var passingTouchdowns = metric(evidence, AgingModelSampleAuditAnalyzer.Metric.PASSING_TOUCHDOWNS_PER_GAME);
        assertEquals(AgingModelPublishedCellLookup.Status.BELOW_SUPPORT, passingTouchdowns.status());
        assertNull(passingTouchdowns.cell());

        var interceptions = metric(evidence, AgingModelSampleAuditAnalyzer.Metric.INTERCEPTIONS_PER_GAME);
        assertEquals(AgingModelPublishedCellLookup.Status.NOT_OBSERVED, interceptions.status());
        assertNull(interceptions.cell());
    }

    @Test
    void rejectsUnsupportedCoordinates() {
        var report = new AgingModelLocalSmootherAnalyzer.LocalSmootherReport("profiles", "production", List.of());
        assertThrows(IllegalArgumentException.class,
            () -> AgingModelPositionAgeEvidenceAnalyzer.resolve(report, "K", 25));
        assertThrows(IllegalArgumentException.class,
            () -> AgingModelPositionAgeEvidenceAnalyzer.resolve(report, "QB", -1));
    }

    private static AgingModelPositionAgeEvidenceAnalyzer.MetricEvidence metric(
        AgingModelPositionAgeEvidenceAnalyzer.PositionAgeEvidenceReport report,
        AgingModelSampleAuditAnalyzer.Metric metric) {
        return report.metrics().stream().filter(value -> value.metric() == metric).findFirst().orElseThrow();
    }

    private static AgingModelLocalSmootherAnalyzer.SmoothedCell cell(
        AgingModelSampleAuditAnalyzer.Metric metric, int transitions) {
        return new AgingModelLocalSmootherAnalyzer.SmoothedCell(
            "QB", metric, 25, 10, 20, 12, transitions, List.of(24, 25, 26),
            -1.0, 0.0, 1.0);
    }
}
