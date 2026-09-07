package io.butler.bet.cli;

import io.butler.bet.sleeper.SleeperCurrentSeasonSuccessorDiscovery;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerSleeperCurrentSeasonSuccessorDiscoveryCliTest {
    @Test
    void parsesExactlyOneLeagueId() {
        var options = ButlerSleeperCurrentSeasonSuccessorDiscoveryCli.parse(new String[]{" l1 "});
        assertEquals("l1", options.leagueId());
        assertThrows(IllegalArgumentException.class,
            () -> ButlerSleeperCurrentSeasonSuccessorDiscoveryCli.parse(new String[]{}));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerSleeperCurrentSeasonSuccessorDiscoveryCli.parse(new String[]{"l1", "extra"}));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerSleeperCurrentSeasonSuccessorDiscoveryCli.parse(new String[]{"   "}));
    }

    @Test
    void rendersLineageProofStateAndReadOnlyBoundary() {
        var candidate = new SleeperCurrentSeasonSuccessorDiscovery.CandidateObservation(
            "successor",
            "Best",
            2026,
            "in_season",
            List.of("u1", "u2"),
            List.of("successor", "provider-2025"),
            true);
        var report = new SleeperCurrentSeasonSuccessorDiscovery.DiscoveryReport(
            SleeperCurrentSeasonSuccessorDiscovery.POLICY_ID,
            "l1",
            "Best",
            "provider-2025",
            "Best",
            2025,
            "complete",
            2026,
            2,
            0,
            List.of(candidate),
            SleeperCurrentSeasonSuccessorDiscovery.DiscoveryState.UNIQUE_SUCCESSOR);

        PrintStream original = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            ButlerSleeperCurrentSeasonSuccessorDiscoveryCli.print(report);
        } finally {
            System.setOut(original);
        }

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("MATCH | league=successor"));
        assertTrue(rendered.contains("lineage newest->oldest=[successor, provider-2025]"));
        assertTrue(rendered.contains("Discovery state: UNIQUE_SUCCESSOR"));
        assertTrue(rendered.contains("Unique successor Sleeper league: successor"));
        assertTrue(rendered.contains("does not relink or import a league"));
    }
}
