package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptimalLegalLineupSolverTest {
    private final OptimalLegalLineupSolver solver = new OptimalLegalLineupSolver();

    @Test
    void findsGlobalOptimumInsteadOfGreedilySpendingConstrainedPlayerInFlex() {
        var result = solver.solve(
            List.of("FLEX", "RB"),
            List.of(
                player("rb-a", List.of("RB"), "10"),
                player("wr-b", List.of("WR"), "9"),
                player("rb-c", List.of("RB"), "8")));

        assertEquals(2, result.filledSlots());
        assertEquals(new BigDecimal("19"), result.totalPoints());
        assertEquals("wr-b", result.assignments().get(0).playerId());
        assertEquals("rb-a", result.assignments().get(1).playerId());
    }

    @Test
    void globallyOptimizesQuarterbackAndSuperFlexTogether() {
        var result = solver.solve(
            List.of("QB", "SUPER_FLEX"),
            List.of(
                player("q1", List.of("QB"), "12"),
                player("q2", List.of("QB"), "11"),
                player("r1", List.of("RB"), "10")));

        assertEquals(2, result.filledSlots());
        assertEquals(new BigDecimal("23"), result.totalPoints());
        assertEquals("q1", result.assignments().get(0).playerId());
        assertEquals("q2", result.assignments().get(1).playerId());
    }

    @Test
    void maximizesFilledSlotsBeforePointsEvenWhenEligibleStarterScoresNegative() {
        var result = solver.solve(
            List.of("QB", "FLEX"),
            List.of(
                player("qb-negative", List.of("QB"), "-5"),
                player("wr-positive", List.of("WR"), "1")));

        assertTrue(result.complete());
        assertEquals(2, result.filledSlots());
        assertEquals(new BigDecimal("-4"), result.totalPoints());
        assertEquals("qb-negative", result.assignments().get(0).playerId());
        assertEquals("wr-positive", result.assignments().get(1).playerId());
    }

    @Test
    void leavesUnfillableStartingSlotExplicitlyEmptyWithoutFabricatingZeroPointPlayer() {
        var result = solver.solve(
            List.of("QB", "WR"),
            List.of(player("qb", List.of("QB"), "7.5")));

        assertFalse(result.complete());
        assertEquals(1, result.filledSlots());
        assertEquals(new BigDecimal("7.5"), result.totalPoints());
        assertTrue(result.assignments().get(0).filled());
        assertFalse(result.assignments().get(1).filled());
        assertEquals(null, result.assignments().get(1).playerId());
        assertEquals(null, result.assignments().get(1).fantasyPoints());
    }

    @Test
    void excludesExplicitNonStartingSlotsButPreservesOriginalStarterOrdinals() {
        var result = solver.solve(
            List.of("QB", "BN", "FLEX", "IR", "TAXI"),
            List.of(
                player("qb", List.of("QB"), "10"),
                player("wr", List.of("WR"), "8")));

        assertEquals(2, result.startingSlots());
        assertEquals(0, result.assignments().get(0).slotOrdinal());
        assertEquals("QB", result.assignments().get(0).slot());
        assertEquals(2, result.assignments().get(1).slotOrdinal());
        assertEquals("FLEX", result.assignments().get(1).slot());
    }

    @Test
    void unsupportedSlotFailsClosedEvenWithoutSeparateCoverageGate() {
        assertThrows(IllegalStateException.class,
            () -> solver.solve(
                List.of("QB", "REC_FLEX"),
                List.of(player("qb", List.of("QB"), "10"))));
    }

    @Test
    void exactPointTieIsDeterministicByPlayerId() {
        var result = solver.solve(
            List.of("WR"),
            List.of(
                player("z-player", List.of("WR"), "10.25"),
                player("a-player", List.of("WR"), "10.25")));

        assertEquals("a-player", result.assignments().getFirst().playerId());
        assertEquals(new BigDecimal("10.25"), result.totalPoints());
    }

    @Test
    void rejectsDuplicatePlayerCandidatesAndNeverInfersFromMissingFantasyPositions() {
        assertThrows(IllegalArgumentException.class,
            () -> solver.solve(
                List.of("WR"),
                List.of(
                    player("same", List.of("WR"), "4"),
                    player("same", List.of("WR"), "5"))));

        var emptyEligibility = solver.solve(
            List.of("WR"),
            List.of(player("unknown", List.of(), "20")));
        assertEquals(0, emptyEligibility.filledSlots());
        assertEquals(BigDecimal.ZERO, emptyEligibility.totalPoints());
        assertFalse(emptyEligibility.assignments().getFirst().filled());
    }

    @Test
    void refusesConfigurationWithNoSupportedStartingSlots() {
        assertThrows(IllegalStateException.class,
            () -> solver.solve(
                List.of("BN", "IR", "TAXI"),
                List.of(player("qb", List.of("QB"), "10"))));
    }

    private static OptimalLegalLineupSolver.ScoredPlayerCandidate player(
        String playerId, List<String> positions, String points) {
        return new OptimalLegalLineupSolver.ScoredPlayerCandidate(
            playerId, positions, new BigDecimal(points));
    }
}
