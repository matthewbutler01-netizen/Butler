package io.butler.bet.cli;

import io.butler.bet.intelligence.NflversePlayerSeasonProductionImporter;
import io.butler.bet.sleeper.SleeperLiveWaiverProductionHydration;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerSleeperLiveWaiverProductionHydrationCliTest {
    @Test
    void rendersVerifiedHydrationAndExplicitRemainingGaps() {
        var report = new SleeperLiveWaiverProductionHydration.HydrationReport(
            SleeperLiveWaiverProductionHydration.POLICY_ID,
            "league-1",
            "market-1",
            "C:/tmp/butler-before-bf605.db",
            2,
            10,
            1,
            11,
            1,
            1,
            0,
            100,
            80,
            5000,
            1,
            2,
            1,
            1,
            1,
            "2026-09-07",
            List.of(new NflversePlayerSeasonProductionImporter.UnmatchedPlayer(
                "p2", "1002", "No Prior Row")),
            0,
            1,
            1,
            SleeperLiveWaiverProductionHydration.HydrationState.HYDRATED_VERIFIED);

        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes));
            ButlerSleeperLiveWaiverProductionHydrationCli.print(report);
        } finally {
            System.setOut(original);
        }

        String output = bytes.toString();
        assertTrue(output.contains("Pre-write backup retained:"));
        assertTrue(output.contains("Market-active target candidates: 2"));
        assertTrue(output.contains("Target canonical eligible / matched / unmatched / snapshots written: 2/1/1/1"));
        assertTrue(output.contains("Exact target identities with no 2025 nflverse production row:"));
        assertTrue(output.contains("Hydration state: HYDRATED_VERIFIED"));
        assertTrue(output.contains("does not rank waiver candidates"));
    }

    @Test
    void requiresExactlyOneLeagueId() {
        assertEquals("league-1", ButlerSleeperLiveWaiverProductionHydrationCli.parse(new String[]{" league-1 "}));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerSleeperLiveWaiverProductionHydrationCli.parse(new String[]{}));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerSleeperLiveWaiverProductionHydrationCli.parse(new String[]{"a", "b"}));
    }
}
