package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoFillLineupOptimizerBf825Test {
    private final AutoFillLineupOptimizer optimizer = new AutoFillLineupOptimizer();

    @Test
    void explicitlyUnavailableBenchPlayerNeedsNoProjectionAndCannotStart() {
        var result = optimizer.optimize(
            List.of("WR"),
            List.of(
                starter("wr-start", "Starter", 0),
                bench("wr-out", "Unavailable Bench")),
            Map.of("wr-start", points("10")),
            Set.of("wr-out"));

        assertTrue(result.ready());
        assertEquals("wr-start", result.assignments().getFirst().recommendedPlayerId());
        assertTrue(result.promotions().isEmpty());
    }

    @Test
    void explicitlyUnavailableStarterVacatesSlotForProjectedBenchPlayer() {
        var result = optimizer.optimize(
            List.of("WR"),
            List.of(
                starter("wr-out", "Unavailable Starter", 0),
                bench("wr-bench", "Projected Bench")),
            Map.of("wr-bench", points("14")),
            Set.of("wr-out"));

        assertTrue(result.ready());
        assertEquals(points("14"), result.projectedTotal());
        assertEquals("wr-bench", result.assignments().getFirst().recommendedPlayerId());
        assertTrue(result.assignments().getFirst().changed());
        assertEquals(List.of("wr-out"), result.movesToBench().stream()
            .map(AutoFillLineupOptimizer.RosterPlayer::playerId).toList());
        assertEquals(List.of("wr-bench"), result.promotions().stream()
            .map(AutoFillLineupOptimizer.RosterPlayer::playerId).toList());
    }

    @Test
    void missingProjectionStillFailsForPlayerNotProvedUnavailable() {
        var result = optimizer.optimize(
            List.of("WR"),
            List.of(
                starter("wr-start", "Starter", 0),
                bench("wr-missing", "Missing Projection")),
            Map.of("wr-start", points("10")),
            Set.of());

        assertFalse(result.ready());
        assertTrue(result.reason().contains("Missing Projection"));
    }

    @Test
    void unavailableIdMustExactlyMatchActiveRosterEvidence() {
        assertThrows(IllegalArgumentException.class, () -> optimizer.optimize(
            List.of("WR"),
            List.of(starter("wr-start", "Starter", 0)),
            Map.of("wr-start", points("10")),
            Set.of("not-on-roster")));
    }

    private static AutoFillLineupOptimizer.RosterPlayer starter(String id, String name, int ordinal) {
        return new AutoFillLineupOptimizer.RosterPlayer(
            id, name, List.of("WR"), AutoFillLineupOptimizer.RosterSlot.STARTER, ordinal, "WR");
    }

    private static AutoFillLineupOptimizer.RosterPlayer bench(String id, String name) {
        return new AutoFillLineupOptimizer.RosterPlayer(
            id, name, List.of("WR"), AutoFillLineupOptimizer.RosterSlot.BENCH, null, null);
    }

    private static BigDecimal points(String value) {
        return new BigDecimal(value);
    }
}
