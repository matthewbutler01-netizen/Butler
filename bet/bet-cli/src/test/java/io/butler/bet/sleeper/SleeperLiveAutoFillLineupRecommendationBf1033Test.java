package io.butler.bet.sleeper;

import io.butler.bet.intelligence.AutoFillLineupOptimizer;
import org.junit.jupiter.api.Test;

import java.util.List;

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
    void nonLegalityFailureIsNotRewritten() {
        String reason = "Current weekly projection evidence is unavailable.";
        assertEquals(reason, SleeperLiveAutoFillLineupRecommendation.appendLegalityExclusionDiagnostics(
            reason, List.of(), List.of(), List.of()));
    }
}
