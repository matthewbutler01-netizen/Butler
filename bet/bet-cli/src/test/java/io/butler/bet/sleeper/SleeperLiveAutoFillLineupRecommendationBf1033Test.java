package io.butler.bet.sleeper;

import io.butler.bet.intelligence.AutoFillLineupOptimizer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperLiveAutoFillLineupRecommendationBf1033Test {

    @Test
    void legalityFailureIncludesHeldAndUnavailableCandidateReasons() {
        var roster = List.of(
            new AutoFillLineupOptimizer.RosterPlayer(
                "rb-held", "Held RB", List.of("RB"),
                AutoFillLineupOptimizer.RosterSlot.BENCH, null, null),
            new AutoFillLineupOptimizer.RosterPlayer(
                "rb-out", "Out RB", List.of("RB"),
                AutoFillLineupOptimizer.RosterSlot.BENCH, null, null));

        var holds = List.of(new SleeperLiveAutoFillLineupRecommendation.ProjectionHold(
            "rb-held", "Held RB", "BENCH", null, "Active", "Questionable",
            "Current weekly projection evidence has no exact Sleeper player-id row; pending clearance."));

        var exclusions = List.of(new SleeperLiveAutoFillLineupRecommendation.UnavailablePlayerExclusion(
            "rb-out", "Out RB", "Active", "Out",
            "Excluded from startable candidates because exact current Sleeper availability confirms unavailable status."));

        String result = SleeperLiveAutoFillLineupRecommendation.appendLegalityExclusionDiagnostics(
            "A complete legal lineup cannot be built for the scoreable open slots from current eligibility evidence."
                + " Unfilled slots: RB@2.",
            roster, holds, exclusions);

        assertTrue(result.contains("Projection-held players: Held RB [rb-held]=RB"));
        assertTrue(result.contains("pending clearance"));
        assertTrue(result.contains("Explicitly unavailable players: Out RB [rb-out]=RB"));
        assertTrue(result.contains("confirms unavailable status"));
    }

    @Test
    void projectedSwapRequiresExactCurrentStatusForBothPlayerIds() {
        var swap = new AutoFillLineupOptimizer.SlotRecommendation(
            1, "WR", "starter", "Current WR", "bench", "Bench WR",
            new BigDecimal("8"), new BigDecimal("18"), new BigDecimal("10"));
        var starter = new SleeperPlayerAvailabilityProvider.PlayerAvailability(
            "starter", "Active", "Healthy");
        var bench = new SleeperPlayerAvailabilityProvider.PlayerAvailability(
            "bench", "Active", null);
        assertTrue(SleeperLiveAutoFillLineupRecommendation.hasExactSwapAvailability(
            swap, Map.of("starter", starter, "bench", bench)));
        assertTrue(!SleeperLiveAutoFillLineupRecommendation.hasExactSwapAvailability(
            swap, Map.of("bench", bench)), "A missing current starter must hold.");
        assertTrue(!SleeperLiveAutoFillLineupRecommendation.hasExactSwapAvailability(
            swap, Map.of("starter", starter)), "A missing proposed player must hold.");
        assertTrue(!SleeperLiveAutoFillLineupRecommendation.hasExactSwapAvailability(
            swap, Map.of("starter", starter, "bench",
                new SleeperPlayerAvailabilityProvider.PlayerAvailability("bench", null, "Healthy"))),
            "An unverified status is not health clearance.");
        assertTrue(!SleeperLiveAutoFillLineupRecommendation.hasExactSwapAvailability(
            swap, Map.of("starter", starter, "bench",
                new SleeperPlayerAvailabilityProvider.PlayerAvailability("different", "Active", null))),
            "A map key cannot override contradictory exact player identity.");
    }

    @Test
    void anEmptyStartingSlotNeedsOnlyExactProposedPlayerStatus() {
        var fill = new AutoFillLineupOptimizer.SlotRecommendation(
            1, "WR", "0", "Empty WR", "bench", "Bench WR",
            null, new BigDecimal("18"), null);
        var bench = new SleeperPlayerAvailabilityProvider.PlayerAvailability(
            "bench", "Active", "Healthy");
        assertTrue(SleeperLiveAutoFillLineupRecommendation.hasExactSwapAvailability(
            fill, Map.of("bench", bench)));
        assertTrue(!SleeperLiveAutoFillLineupRecommendation.hasExactSwapAvailability(
            fill, Map.of()));
    }

    @Test
    void nonLegalityFailureIsNotRewritten() {
        String reason = "Current weekly projection evidence is unavailable.";
        assertEquals(reason, SleeperLiveAutoFillLineupRecommendation.appendLegalityExclusionDiagnostics(
            reason, List.of(), List.of(), List.of()));
    }
}
