package io.butler.bet.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TeamSeasonPerformanceTest {
    @Test
    void exposesObservedRecordWithoutStrategyLabel() {
        var performance = new TeamSeasonPerformance(
            "l1", "t1", 2026, 6, 3, 1, 1012.5, 930.0, "sleeper", LocalDate.of(2026, 11, 15));

        assertEquals(10, performance.gamesPlayed());
        assertEquals(0.65, performance.winPercentage(), 0.000001);
        assertEquals(82.5, performance.pointDifferential(), 0.000001);
    }

    @Test
    void scorelessZeroGameSnapshotRemainsValidObservedEvidence() {
        var performance = new TeamSeasonPerformance(
            "l1", "t1", 2026, 0, 0, 0, 0.0, 0.0, "sleeper", LocalDate.of(2026, 9, 1));

        assertEquals(0, performance.gamesPlayed());
        assertEquals(0.0, performance.winPercentage());
        assertEquals(0.0, performance.pointDifferential());
    }

    @Test
    void rejectsMalformedOrImpossibleEvidence() {
        assertThrows(IllegalArgumentException.class, () -> new TeamSeasonPerformance(
            "", "t1", 2026, 1, 0, 0, 100, 90, "sleeper", LocalDate.now()));
        assertThrows(IllegalArgumentException.class, () -> new TeamSeasonPerformance(
            "l1", "t1", 2026, -1, 0, 0, 100, 90, "sleeper", LocalDate.now()));
        assertThrows(IllegalArgumentException.class, () -> new TeamSeasonPerformance(
            "l1", "t1", 2026, 1, 0, 0, Double.NaN, 90, "sleeper", LocalDate.now()));
        assertThrows(IllegalArgumentException.class, () -> new TeamSeasonPerformance(
            "l1", "t1", 2026, 1, 0, 0, 100, 90, "sleeper", null));
    }
}
