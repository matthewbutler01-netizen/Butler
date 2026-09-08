package io.butler.bet.cli;

import io.butler.bet.sleeper.SleeperLiveWaiverTargetRosterProductionHydration;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerSleeperLiveWaiverTargetRosterProductionHydrationCliTest {

    @Test
    void parsesExactLeagueAndOwner() {
        var parsed = ButlerSleeperLiveWaiverTargetRosterProductionHydrationCli.parse(
            new String[] {" league-1 ", " owner-1 "});
        assertEquals("league-1", parsed.leagueId());
        assertEquals("owner-1", parsed.ownerId());
        assertThrows(IllegalArgumentException.class,
            () -> ButlerSleeperLiveWaiverTargetRosterProductionHydrationCli.parse(new String[] {"league-1"}));
    }

    @Test
    void outputKeepsEvidenceOnlyBoundaryAndExplicitUnmatchedMeaning() {
        var report = new SleeperLiveWaiverTargetRosterProductionHydration.HydrationReport(
            SleeperLiveWaiverTargetRosterProductionHydration.POLICY_ID,
            "league-1", "owner-1", "market-1", "waiver-1", "sleeper-league-1", 1,
            "backup.db", 2, 12, 3,
            100, 100, 5000, 1, 2, 1, 1,
            "2026-09-08", List.of(), 13, 2,
            SleeperLiveWaiverTargetRosterProductionHydration.HydrationState.HYDRATED_VERIFIED);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(bytes, true, StandardCharsets.UTF_8));
            ButlerSleeperLiveWaiverTargetRosterProductionHydrationCli.print(report);
        } finally {
            System.setOut(original);
        }
        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Target-roster production hydration state: HYDRATED_VERIFIED"));
        assertTrue(output.contains("evidence gap"));
        assertTrue(output.contains("not zero production"));
        String lower = output.toLowerCase();
        assertFalse(lower.contains("recommended add:"));
        assertFalse(lower.contains("recommended drop:"));
        assertFalse(lower.contains("faab bid:"));
        assertFalse(lower.contains("confidence:"));
        assertFalse(lower.contains("probability:"));
    }
}
