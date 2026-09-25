package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoFillLineupOptimizerBf942Test {
    private final AutoFillLineupOptimizer optimizer = new AutoFillLineupOptimizer();

    @Test
    void retainsExactCurrentRecommendedAndGainForSimpleSwap() {
        var result = optimizer.optimize(
            List.of("QB"),
            List.of(
                starter("qb-current", "Current QB", List.of("QB"), 0, "QB"),
                bench("qb-bench", "Bench QB", List.of("QB"))),
            Map.of(
                "qb-current", points("17.5"),
                "qb-bench", points("21.25")));

        assertTrue(result.ready());
        var slot = result.assignments().getFirst();
        assertEquals(points("17.5"), slot.currentProjectedPoints());
        assertEquals(points("21.25"), slot.projectedPoints());
        assertEquals(points("3.75"), slot.projectedGain());
        assertTrue(slot.changed());
    }

    @Test
    void slotDeltaCanBeNegativeInsideGloballyImprovedLineup() {
        var result = optimizer.optimize(
            List.of("FLEX", "RB"),
            List.of(
                starter("rb-a", "RB A", List.of("RB"), 0, "FLEX"),
                starter("rb-c", "RB C", List.of("RB"), 1, "RB"),
                bench("wr-b", "WR B", List.of("WR"))),
            Map.of(
                "rb-a", points("10"),
                "rb-c", points("8"),
                "wr-b", points("9")));

        assertTrue(result.ready());
        assertEquals(points("19"), result.projectedTotal());

        var flex = result.assignments().get(0);
        assertEquals("rb-a", flex.currentPlayerId());
        assertEquals("wr-b", flex.recommendedPlayerId());
        assertEquals(points("10"), flex.currentProjectedPoints());
        assertEquals(points("9"), flex.projectedPoints());
        assertEquals(points("-1"), flex.projectedGain());

        var rb = result.assignments().get(1);
        assertEquals("rb-c", rb.currentPlayerId());
        assertEquals("rb-a", rb.recommendedPlayerId());
        assertEquals(points("8"), rb.currentProjectedPoints());
        assertEquals(points("10"), rb.projectedPoints());
        assertEquals(points("2"), rb.projectedGain());
    }

    @Test
    void explicitlyUnavailableStarterKeepsProjectionDeltaUnavailable() {
        var result = optimizer.optimize(
            List.of("WR"),
            List.of(
                starter("wr-out", "Out Starter", List.of("WR"), 0, "WR"),
                bench("wr-bench", "Bench WR", List.of("WR"))),
            Map.of("wr-bench", points("15")),
            Set.of("wr-out"));

        assertTrue(result.ready());
        var slot = result.assignments().getFirst();
        assertNull(slot.currentProjectedPoints());
        assertEquals(points("15"), slot.projectedPoints());
        assertNull(slot.projectedGain());
        assertTrue(slot.changed());
    }

    private static AutoFillLineupOptimizer.RosterPlayer starter(
        String id,
        String name,
        List<String> positions,
        int ordinal,
        String slot) {
        return new AutoFillLineupOptimizer.RosterPlayer(
            id, name, positions, AutoFillLineupOptimizer.RosterSlot.STARTER, ordinal, slot);
    }

    private static AutoFillLineupOptimizer.RosterPlayer bench(
        String id,
        String name,
        List<String> positions) {
        return new AutoFillLineupOptimizer.RosterPlayer(
            id, name, positions, AutoFillLineupOptimizer.RosterSlot.BENCH, null, null);
    }

    private static BigDecimal points(String value) {
        return new BigDecimal(value);
    }
}
