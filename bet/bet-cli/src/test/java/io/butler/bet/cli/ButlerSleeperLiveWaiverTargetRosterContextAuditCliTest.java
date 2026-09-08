package io.butler.bet.cli;

import io.butler.bet.sleeper.SleeperLiveWaiverTargetRosterContextAudit;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerSleeperLiveWaiverTargetRosterContextAuditCliTest {
    @Test
    void rendersExactRosterContextWithoutRecommendationSemantics() {
        var player = new SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer(
            "p1", "STARTER", 0, "QB", "b1", "Example QB", "QB", "CHI", "EXACT_CANONICAL");
        var report = new SleeperLiveWaiverTargetRosterContextAudit.AuditReport(
            SleeperLiveWaiverTargetRosterContextAudit.POLICY_ID,
            "L", "M", "W", "S", 2026, "in_season", 1,
            "owner-1", "Owner", "Team", 1, "T1", "Team",
            List.of("QB", "BN"), List.of("QB"), 51, 42,
            1, 1, 0, 0, 0, 1, 0, List.of(player));

        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes));
            ButlerSleeperLiveWaiverTargetRosterContextAuditCli.print(report);
        } finally {
            System.setOut(original);
        }

        String output = bytes.toString();
        assertTrue(output.contains("Target-roster context state: READY_CONTEXT_ONLY"));
        assertTrue(output.contains("BF-609 candidates / evidence-reviewable: 51/42"));
        assertTrue(output.contains("rosterSlot=STARTER starterOrdinal=0 lineupSlot=QB"));
        assertTrue(output.contains("does not score roster needs"));
        assertTrue(output.contains("does not"));
        assertTrue(output.contains("rank waiver candidates"));
    }
}
