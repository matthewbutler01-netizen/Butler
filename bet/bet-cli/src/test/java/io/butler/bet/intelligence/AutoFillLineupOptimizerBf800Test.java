package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoFillLineupOptimizerBf800Test {
    private final AutoFillLineupOptimizer optimizer = new AutoFillLineupOptimizer();

    @Test
    void recommendsHighestProjectedLegalNormalStarter() {
        var result = optimizer.optimize(
            List.of("QB", "BN"),
            List.of(
                starter("qb-current", "Current QB", List.of("QB"), 0, "QB"),
                bench("qb-bench", "Bench QB", List.of("QB"))),
            Map.of(
                "qb-current", points("17.5"),
                "qb-bench", points("21.25")));

        assertTrue(result.ready());
        assertEquals(points("21.25"), result.projectedTotal());
        assertEquals("qb-bench", result.assignments().getFirst().recommendedPlayerId());
        assertTrue(result.assignments().getFirst().changed());
        assertEquals(List.of("qb-current"), result.movesToBench().stream().map(AutoFillLineupOptimizer.RosterPlayer::playerId).toList());
        assertEquals(List.of("qb-bench"), result.promotions().stream().map(AutoFillLineupOptimizer.RosterPlayer::playerId).toList());
    }

    @Test
    void globallySolvesFlexWithoutSpendingBestRunningBackInWrongSlot() {
        var result = optimizer.optimize(
            List.of("FLEX", "RB"),
            List.of(
                starter("rb-a", "RB A", List.of("RB"), 0, "FLEX"),
                starter("rb-c", "RB C", List.of("RB"), 1, "RB"),
                bench("wr-b", "WR B", List.of("WR"))),
            Map.of("rb-a", points("10"), "wr-b", points("9"), "rb-c", points("8")));

        assertTrue(result.ready());
        assertEquals(points("19"), result.projectedTotal());
        assertEquals("wr-b", result.assignments().get(0).recommendedPlayerId());
        assertEquals("rb-a", result.assignments().get(1).recommendedPlayerId());
    }

    @Test
    void fillsSuperFlexWithSecondQuarterbackWhenThatMaximizesWeeklyPoints() {
        var result = optimizer.optimize(
            List.of("QB", "SUPER_FLEX"),
            List.of(
                starter("qb-one", "QB One", List.of("QB"), 0, "QB"),
                starter("rb-one", "RB One", List.of("RB"), 1, "SUPER_FLEX"),
                bench("qb-two", "QB Two", List.of("QB"))),
            Map.of("qb-one", points("22"), "rb-one", points("14"), "qb-two", points("20")));

        assertTrue(result.ready());
        assertEquals(points("42"), result.projectedTotal());
        assertEquals("qb-one", result.assignments().get(0).recommendedPlayerId());
        assertEquals("qb-two", result.assignments().get(1).recommendedPlayerId());
        assertEquals(List.of("rb-one"), result.movesToBench().stream().map(AutoFillLineupOptimizer.RosterPlayer::playerId).toList());
    }

    @Test
    void reserveAndTaxiPlayersNeverEnterCandidatePoolEvenWithHugeProjection() {
        var result = optimizer.optimize(
            List.of("WR"),
            List.of(
                starter("wr-start", "Starter", List.of("WR"), 0, "WR"),
                bench("wr-bench", "Bench", List.of("WR")),
                reserve("wr-ir", "Reserve", List.of("WR")),
                taxi("wr-taxi", "Taxi", List.of("WR"))),
            Map.of(
                "wr-start", points("10"),
                "wr-bench", points("9"),
                "wr-ir", points("100"),
                "wr-taxi", points("200")));

        assertTrue(result.ready());
        assertEquals("wr-start", result.assignments().getFirst().recommendedPlayerId());
        assertFalse(result.assignments().getFirst().changed());
    }

    @Test
    void missingProjectionForAnyActivePlayerFailsClosed() {
        var result = optimizer.optimize(
            List.of("WR"),
            List.of(
                starter("wr-start", "Starter", List.of("WR"), 0, "WR"),
                bench("wr-bench", "Bench", List.of("WR"))),
            Map.of("wr-start", points("10")));

        assertFalse(result.ready());
        assertTrue(result.reason().contains("Bench"));
        assertTrue(result.assignments().isEmpty());
    }

    @Test
    void exactProjectionTieRemainsDeterministicByProviderPlayerId() {
        var result = optimizer.optimize(
            List.of("WR"),
            List.of(
                starter("z-player", "Z Player", List.of("WR"), 0, "WR"),
                bench("a-player", "A Player", List.of("WR"))),
            Map.of("z-player", points("10.25"), "a-player", points("10.25")));

        assertTrue(result.ready());
        assertEquals("a-player", result.assignments().getFirst().recommendedPlayerId());
        assertEquals(List.of("z-player"), result.movesToBench().stream().map(AutoFillLineupOptimizer.RosterPlayer::playerId).toList());
        assertEquals(List.of("a-player"), result.promotions().stream().map(AutoFillLineupOptimizer.RosterPlayer::playerId).toList());
    }

    private static AutoFillLineupOptimizer.RosterPlayer starter(
        String id, String name, List<String> positions, int ordinal, String slot) {
        return new AutoFillLineupOptimizer.RosterPlayer(
            id, name, positions, AutoFillLineupOptimizer.RosterSlot.STARTER, ordinal, slot);
    }

    private static AutoFillLineupOptimizer.RosterPlayer bench(String id, String name, List<String> positions) {
        return new AutoFillLineupOptimizer.RosterPlayer(
            id, name, positions, AutoFillLineupOptimizer.RosterSlot.BENCH, null, null);
    }

    private static AutoFillLineupOptimizer.RosterPlayer reserve(String id, String name, List<String> positions) {
        return new AutoFillLineupOptimizer.RosterPlayer(
            id, name, positions, AutoFillLineupOptimizer.RosterSlot.RESERVE, null, null);
    }

    private static AutoFillLineupOptimizer.RosterPlayer taxi(String id, String name, List<String> positions) {
        return new AutoFillLineupOptimizer.RosterPlayer(
            id, name, positions, AutoFillLineupOptimizer.RosterSlot.TAXI, null, null);
    }

    private static BigDecimal points(String value) {
        return new BigDecimal(value);
    }
}
