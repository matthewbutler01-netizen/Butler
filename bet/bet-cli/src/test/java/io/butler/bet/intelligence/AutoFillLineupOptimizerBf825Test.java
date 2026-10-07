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
    void emptySlotUsesBenchWithoutSyntheticCurrentProjectionOrBenchMove() {
        var result = optimizer.optimize(List.of("WR", "WR"),
            List.of(starter("wr-start", "Starter", 1), bench("wr-bench", "Bench")),
            Map.of("wr-start", points("10"), "wr-bench", points("8")), Set.of(), Set.of(), Set.of(0));
        assertTrue(result.ready());
        assertEquals("wr-bench", result.assignments().get(0).recommendedPlayerId());
        assertEquals("0", result.assignments().get(0).currentPlayerId());
        assertEquals(null, result.assignments().get(0).currentProjectedPoints());
        assertEquals(null, result.assignments().get(0).projectedGain());
        assertEquals("wr-start", result.assignments().get(1).recommendedPlayerId());
        assertTrue(result.movesToBench().isEmpty());
        assertEquals(List.of("wr-bench"), result.promotions().stream().map(p -> p.playerId()).toList());
        assertEquals(points("18"), result.projectedTotal());
    }

    @Test
    void multipleEmptySlotsRequireDistinctEligibleCandidatesAndPreserveHolds() {
        var result = optimizer.optimize(List.of("WR", "WR", "WR"),
            List.of(starter("held", "Held", 1), bench("a", "A"), bench("b", "B")),
            Map.of("a", points("7"), "b", points("6")), Set.of(), Set.of("held"), Set.of(0, 2));
        assertTrue(result.ready());
        assertEquals(List.of(0, 2), result.assignments().stream().map(a -> a.starterOrdinal()).toList());
        assertEquals(2, result.assignments().stream().map(a -> a.recommendedPlayerId()).distinct().count());
        assertTrue(result.movesToBench().isEmpty());
        var insufficient = optimizer.optimize(List.of("WR", "WR"), List.of(bench("a", "A")),
            Map.of("a", points("7")), Set.of(), Set.of(), Set.of(0, 1));
        assertFalse(insufficient.ready());
        var ineligible = optimizer.optimize(List.of("QB"), List.of(bench("a", "A")),
            Map.of("a", points("7")), Set.of(), Set.of(), Set.of(0));
        assertFalse(ineligible.ready());
        assertTrue(ineligible.reason().contains("Unfilled slots: QB@0"));
        assertTrue(ineligible.reason().contains("Scoreable candidate eligibility: A [a]=WR"));
        assertTrue(ineligible.reason().contains("Projection holds=0; explicitly unavailable=0"));
    }

    @Test
    void explicitEmptySlotCanBeFilledByEligibleNegativeProjection() {
        var result = optimizer.optimize(List.of("WR"), List.of(bench("a", "A")),
            Map.of("a", points("-1")), Set.of(), Set.of(), Set.of(0));
        assertTrue(result.ready());
        assertEquals(points("-1"), result.projectedTotal());
        assertEquals(null, result.assignments().getFirst().projectedGain());
    }

    @Test
    void missingStarterWithoutExplicitEmptyOrdinalAndOccupiedEmptyOrdinalFailClosed() {
        assertThrows(IllegalStateException.class, () -> optimizer.optimize(List.of("WR"), List.of(bench("a", "A")), Map.of("a", points("7"))));
        assertThrows(IllegalStateException.class, () -> optimizer.optimize(List.of("WR"), List.of(starter("a", "A", 0)), Map.of("a", points("7")), Set.of(), Set.of(), Set.of(0)));
    }

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
    void projectionHeldStarterKeepsItsSlotWhileRemainingScoreableSlotsOptimize() {
        var result = optimizer.optimize(
            List.of("WR", "WR"),
            List.of(
                starter("wr-held", "Held Starter", 0),
                starter("wr-start", "Scoreable Starter", 1),
                bench("wr-bench", "Better Bench")),
            Map.of(
                "wr-start", points("10"),
                "wr-bench", points("14")),
            Set.of(),
            Set.of("wr-held"));

        assertTrue(result.ready());
        assertEquals(1, result.assignments().size());
        assertEquals(1, result.assignments().getFirst().starterOrdinal());
        assertEquals("wr-bench", result.assignments().getFirst().recommendedPlayerId());
        assertTrue(result.assignments().getFirst().changed());
        assertEquals(List.of("wr-start"), result.movesToBench().stream()
            .map(AutoFillLineupOptimizer.RosterPlayer::playerId).toList());
        assertEquals(List.of("wr-bench"), result.promotions().stream()
            .map(AutoFillLineupOptimizer.RosterPlayer::playerId).toList());
    }

    @Test
    void projectionHeldBenchPlayerIsNotEligibleForPromotion() {
        var result = optimizer.optimize(
            List.of("WR"),
            List.of(
                starter("wr-start", "Starter", 0),
                bench("wr-held", "Held Bench")),
            Map.of("wr-start", points("10")),
            Set.of(),
            Set.of("wr-held"));

        assertTrue(result.ready());
        assertEquals("wr-start", result.assignments().getFirst().recommendedPlayerId());
        assertTrue(result.promotions().isEmpty());
    }

    @Test
    void repeatedEquivalentSlotsPreserveCurrentOrderWhenStarterSetIsUnchanged() {
        var result = optimizer.optimize(
            List.of("WR", "WR"),
            List.of(
                starter("z-wr", "Current WR One", 0),
                starter("a-wr", "Current WR Two", 1)),
            Map.of(
                "z-wr", points("10"),
                "a-wr", points("12")),
            Set.of());

        assertTrue(result.ready());
        assertEquals("z-wr", result.assignments().get(0).recommendedPlayerId());
        assertEquals("a-wr", result.assignments().get(1).recommendedPlayerId());
        assertFalse(result.assignments().get(0).changed());
        assertFalse(result.assignments().get(1).changed());
        assertTrue(result.movesToBench().isEmpty());
        assertTrue(result.promotions().isEmpty());
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
