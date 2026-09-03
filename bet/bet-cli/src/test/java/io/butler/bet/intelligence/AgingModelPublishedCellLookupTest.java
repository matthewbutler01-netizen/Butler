package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgingModelPublishedCellLookupTest {
    @Test
    void publishesEligibleCellAndHidesBelowSupportCell() {
        var below = cell(39, 3);
        var published = cell(38, 5);
        var report = new AgingModelLocalSmootherAnalyzer.LocalSmootherReport(
            "profiles", "production", List.of(below, published));

        var good = AgingModelPublishedCellLookup.resolve(report, "wr",
            AgingModelSampleAuditAnalyzer.Metric.RECEIVING_YARDS_PER_GAME, 38);
        assertEquals(AgingModelPublishedCellLookup.Status.PUBLISHED, good.status());
        assertTrue(good.available());
        assertSame(published, good.cell());
        assertEquals(AgingModelSupportPolicy.POLICY_ID, good.supportPolicyId());

        var blocked = AgingModelPublishedCellLookup.resolve(report, "WR",
            AgingModelSampleAuditAnalyzer.Metric.RECEIVING_YARDS_PER_GAME, 39);
        assertEquals(AgingModelPublishedCellLookup.Status.BELOW_SUPPORT, blocked.status());
        assertFalse(blocked.available());
        assertNull(blocked.cell());
    }

    @Test
    void distinguishesMissingEvidenceFromBelowSupport() {
        var report = new AgingModelLocalSmootherAnalyzer.LocalSmootherReport(
            "profiles", "production", List.of(cell(38, 5)));

        var missing = AgingModelPublishedCellLookup.resolve(report, "WR",
            AgingModelSampleAuditAnalyzer.Metric.RECEIVING_YARDS_PER_GAME, 50);
        assertEquals(AgingModelPublishedCellLookup.Status.NOT_OBSERVED, missing.status());
        assertFalse(missing.available());
        assertNull(missing.cell());
    }

    @Test
    void rejectsInvalidLookupCoordinates() {
        var report = new AgingModelLocalSmootherAnalyzer.LocalSmootherReport(
            "profiles", "production", List.of());
        assertThrows(IllegalArgumentException.class,
            () -> AgingModelPublishedCellLookup.resolve(report, " ",
                AgingModelSampleAuditAnalyzer.Metric.RECEIVING_YARDS_PER_GAME, 30));
        assertThrows(IllegalArgumentException.class,
            () -> AgingModelPublishedCellLookup.resolve(report, "WR",
                AgingModelSampleAuditAnalyzer.Metric.RECEIVING_YARDS_PER_GAME, -1));
    }

    private static AgingModelLocalSmootherAnalyzer.SmoothedCell cell(int age, int transitions) {
        return new AgingModelLocalSmootherAnalyzer.SmoothedCell(
            "WR", AgingModelSampleAuditAnalyzer.Metric.RECEIVING_YARDS_PER_GAME, age,
            8, 18, 11, transitions, List.of(age - 1, age, age + 1),
            -2.0, -0.5, 1.5);
    }
}
