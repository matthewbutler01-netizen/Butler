package io.butler.bet.cli;

import io.butler.bet.sleeper.SleeperLiveWaiverUniverseAudit;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerSleeperLiveWaiverUniverseAuditCliTest {
    @Test
    void parsesExactLeagueArgument() {
        var options = ButlerSleeperLiveWaiverUniverseAuditCli.parse(new String[]{" league-1 "});
        assertEquals("league-1", options.leagueId());
        assertThrows(IllegalArgumentException.class,
            () -> ButlerSleeperLiveWaiverUniverseAuditCli.parse(new String[0]));
    }

    @Test
    void rendersDeterministicReadySummaryAndExamples() {
        var example = new SleeperLiveWaiverUniverseAudit.PlayerExample(
            "p3", "Free Three", "WR", "MIN", "Active", List.of("WR"));
        var report = new SleeperLiveWaiverUniverseAudit.AuditReport(
            SleeperLiveWaiverUniverseAudit.POLICY_ID,
            SleeperLiveWaiverUniverseAudit.ACTIVE_PLAYER_SOURCE,
            "l1",
            "League",
            "provider-1",
            2026,
            "in_season",
            1,
            2,
            2,
            3,
            2,
            List.of(),
            1,
            0,
            1,
            List.of(example),
            1,
            1,
            1,
            1,
            List.of(example),
            SleeperLiveWaiverUniverseAudit.AuditState.READY,
            List.of(),
            Instant.parse("2026-09-08T00:30:00Z"));

        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes));
            ButlerSleeperLiveWaiverUniverseAuditCli.print(report);
        } finally {
            System.setOut(original);
        }
        String output = bytes.toString();
        assertTrue(output.contains("Derived free-agent identities: 1"));
        assertTrue(output.contains("Unmapped free agents: 1"));
        assertTrue(output.contains("p3 | Free Three | pos=WR | fantasy=[WR] | team=MIN | status=Active"));
        assertTrue(output.contains("Waiver/free-agent universe: READY"));
        assertTrue(output.contains("downstream metadata/bootstrap work"));
    }
}
