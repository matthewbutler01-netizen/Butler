package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgingModelPublishedSmootherAnalyzerTest {
    @Test
    void publishesOnlyCellsMeetingGovernedTransitionSupport() {
        var below = cell(26, 4);
        var atThreshold = cell(27, 5);
        var above = cell(28, 9);
        var diagnostic = new AgingModelLocalSmootherAnalyzer.LocalSmootherReport(
            "profiles-source", "production-source", List.of(below, atThreshold, above));

        var published = AgingModelPublishedSmootherAnalyzer.applyPolicy(diagnostic);

        assertEquals(AgingModelSupportPolicy.POLICY_ID, published.supportPolicyId());
        assertEquals(5, published.minimumDistinctSeasonTransitions());
        assertEquals(3, published.diagnosticCells());
        assertEquals(1, published.excludedCells());
        assertEquals(2, published.publishedCells());
        assertEquals(List.of(atThreshold, above), published.cells());
    }

    private static AgingModelLocalSmootherAnalyzer.SmoothedCell cell(int age, int transitions) {
        return new AgingModelLocalSmootherAnalyzer.SmoothedCell(
            "WR", AgingModelSampleAuditAnalyzer.Metric.RECEIVING_YARDS_PER_GAME, age,
            10, 20, 12, transitions, List.of(age - 1, age, age + 1),
            -1.0, 0.0, 1.0);
    }
}
