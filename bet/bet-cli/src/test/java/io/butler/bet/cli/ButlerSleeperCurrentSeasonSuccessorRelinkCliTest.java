package io.butler.bet.cli;

import io.butler.bet.sleeper.SleeperCurrentSeasonSuccessorRelink;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerSleeperCurrentSeasonSuccessorRelinkCliTest {
    @Test
    void parsesLeagueAndExpectedSuccessorExactly() {
        var options = ButlerSleeperCurrentSeasonSuccessorRelinkCli.parse(
            new String[]{" l1 ", " successor-2026 "});
        assertEquals("l1", options.leagueId());
        assertEquals("successor-2026", options.expectedSuccessorSleeperLeagueId());

        assertThrows(IllegalArgumentException.class,
            () -> ButlerSleeperCurrentSeasonSuccessorRelinkCli.parse(new String[]{"l1"}));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerSleeperCurrentSeasonSuccessorRelinkCli.parse(
                new String[]{"l1", "successor-2026", "extra"}));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerSleeperCurrentSeasonSuccessorRelinkCli.parse(new String[]{" ", "successor-2026"}));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerSleeperCurrentSeasonSuccessorRelinkCli.parse(new String[]{"l1", " "}));
    }

    @Test
    void rendersVerifiedRelinkAndMandatoryReadinessReauditBoundary() {
        var report = new SleeperCurrentSeasonSuccessorRelink.RelinkReport(
            SleeperCurrentSeasonSuccessorRelink.POLICY_ID,
            "l1",
            "Best",
            "provider-2025",
            "successor-2026",
            2026,
            "drafting",
            List.of("successor-2026", "provider-2025", "root-2024"),
            SleeperCurrentSeasonSuccessorRelink.RelinkState.RELINKED_VERIFIED);

        PrintStream original = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            ButlerSleeperCurrentSeasonSuccessorRelinkCli.print(report);
        } finally {
            System.setOut(original);
        }

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("Previous Sleeper league: provider-2025"));
        assertTrue(rendered.contains("New Sleeper league: successor-2026"));
        assertTrue(rendered.contains("Persisted Butler season: 2026"));
        assertTrue(rendered.contains("Lineage proof newest->oldest: [successor-2026, provider-2025, root-2024]"));
        assertTrue(rendered.contains("Relink state: RELINKED_VERIFIED"));
        assertTrue(rendered.contains("Rerun BF-595"));
    }
}
