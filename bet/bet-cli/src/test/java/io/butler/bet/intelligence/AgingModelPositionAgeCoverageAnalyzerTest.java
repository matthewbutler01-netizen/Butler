package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgingModelPositionAgeCoverageAnalyzerTest {
    @Test
    void classifiesFullPartialBelowSupportAndUnobservedAgesWithoutSelectingACutoff() {
        List<AgingModelLocalSmootherAnalyzer.SmoothedCell> cells = new ArrayList<>();
        for (var metric : List.of(
            AgingModelSampleAuditAnalyzer.Metric.PASSING_YARDS_PER_GAME,
            AgingModelSampleAuditAnalyzer.Metric.PASSING_TOUCHDOWNS_PER_GAME,
            AgingModelSampleAuditAnalyzer.Metric.INTERCEPTIONS_PER_GAME,
            AgingModelSampleAuditAnalyzer.Metric.RUSHING_YARDS_PER_GAME,
            AgingModelSampleAuditAnalyzer.Metric.RUSHING_TOUCHDOWNS_PER_GAME)) {
            cells.add(cell(24, metric, 5));
        }
        cells.add(cell(26, AgingModelSampleAuditAnalyzer.Metric.PASSING_YARDS_PER_GAME, 4));
        cells.add(cell(27, AgingModelSampleAuditAnalyzer.Metric.PASSING_YARDS_PER_GAME, 5));
        cells.add(cell(27, AgingModelSampleAuditAnalyzer.Metric.PASSING_TOUCHDOWNS_PER_GAME, 4));

        var report = AgingModelPositionAgeCoverageAnalyzer.summarize(
            new AgingModelLocalSmootherAnalyzer.LocalSmootherReport("profiles", "production", cells));
        var qb = report.positions().stream().filter(position -> position.position().equals("QB")).findFirst().orElseThrow();

        assertEquals(24, qb.minimumObservedAge());
        assertEquals(27, qb.maximumObservedAge());
        assertEquals(4, qb.ages().size());
        assertEquals(AgingModelPositionAgeCoverageAnalyzer.Status.FULL, age(qb, 24).status());
        assertEquals(AgingModelPositionAgeCoverageAnalyzer.Status.NOT_OBSERVED, age(qb, 25).status());
        assertEquals(AgingModelPositionAgeCoverageAnalyzer.Status.BELOW_SUPPORT, age(qb, 26).status());
        assertEquals(AgingModelPositionAgeCoverageAnalyzer.Status.PARTIAL, age(qb, 27).status());
        assertEquals(1, qb.fullAges());
        assertEquals(1, qb.partialAges());
        assertEquals(1, qb.belowSupportAges());
        assertEquals(1, qb.notObservedAges());

        var rb = report.positions().stream().filter(position -> position.position().equals("RB")).findFirst().orElseThrow();
        assertEquals(null, rb.minimumObservedAge());
        assertEquals(0, rb.ages().size());
    }

    private static AgingModelPositionAgeCoverageAnalyzer.AgeCoverage age(
        AgingModelPositionAgeCoverageAnalyzer.PositionCoverage position, int age) {
        return position.ages().stream().filter(value -> value.age() == age).findFirst().orElseThrow();
    }

    private static AgingModelLocalSmootherAnalyzer.SmoothedCell cell(
        int age, AgingModelSampleAuditAnalyzer.Metric metric, int transitions) {
        return new AgingModelLocalSmootherAnalyzer.SmoothedCell(
            "QB", metric, age, 10, 20, 12, transitions, List.of(age - 1, age, age + 1),
            -1.0, 0.0, 1.0);
    }
}
