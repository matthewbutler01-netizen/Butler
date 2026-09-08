package io.butler.bet.cli;

import io.butler.bet.sleeper.SleeperCurrentSeasonRosterBootstrap;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerSleeperCurrentSeasonRosterBootstrapCliTest {
    @Test
    void parsesLeagueAndExpectedProviderIds() {
        var options = ButlerSleeperCurrentSeasonRosterBootstrapCli.parse(
            new String[]{" l1 ", " provider-2026 "});
        assertEquals("l1", options.leagueId());
        assertEquals("provider-2026", options.expectedSleeperLeagueId());
        assertThrows(IllegalArgumentException.class,
            () -> ButlerSleeperCurrentSeasonRosterBootstrapCli.parse(new String[]{"l1"}));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerSleeperCurrentSeasonRosterBootstrapCli.parse(new String[]{"l1", "provider", "extra"}));
    }

    @Test
    void rendersBackupCountsAndVerifiedState() {
        var report = new SleeperCurrentSeasonRosterBootstrap.BootstrapReport(
            SleeperCurrentSeasonRosterBootstrap.POLICY_ID,
            "l1",
            "provider-2026",
            "butler-before-bf600.db",
            2,
            6,
            2,
            2,
            6,
            6,
            2,
            6,
            0,
            SleeperCurrentSeasonRosterBootstrap.BootstrapState.HYDRATED_VERIFIED);

        PrintStream original = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            ButlerSleeperCurrentSeasonRosterBootstrapCli.print(report);
        } finally {
            System.setOut(original);
        }

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("Pre-write backup retained: butler-before-bf600.db"));
        assertTrue(rendered.contains("BF-599 current player identities needing bootstrap: 2"));
        assertTrue(rendered.contains("Post-import exact mapped/unmapped current players: 6/0"));
        assertTrue(rendered.contains("Bootstrap state: HYDRATED_VERIFIED"));
    }
}
