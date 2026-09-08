package io.butler.bet.cli;

import io.butler.bet.sleeper.SleeperLiveWaiverSnapshotSync;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerSleeperLiveWaiverSnapshotSyncCliTest {
    @Test
    void rendersVerifiedSnapshotAndBoundary() {
        var report = new SleeperLiveWaiverSnapshotSync.SyncReport(
            SleeperLiveWaiverSnapshotSync.POLICY_ID,
            SleeperLiveWaiverSnapshotSync.ELIGIBILITY_POLICY_ID,
            "snap", "L", "S", "2026-09-08T00:30:00Z",
            List.of("QB", "FLEX", "BN"), List.of("QB", "RB", "WR", "TE"),
            5, 2, 2, 0, 3, 1,
            Map.of("ROSTERED", 2, "LEAGUE_ELIGIBLE_POSITION_MATCH", 1,
                "NO_LEAGUE_ELIGIBLE_POSITION_MATCH", 1, "NO_SUPPORTED_FANTASY_POSITION", 1),
            List.of("p3 | Free Receiver | pos=WR | fantasy=[WR] | team=MIN | status=Active"),
            2, 2, 1);

        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes));
            ButlerSleeperLiveWaiverSnapshotSyncCli.print(report);
        } finally {
            System.setOut(original);
        }
        String output = bytes.toString();
        assertTrue(output.contains("Snapshot state: PERSISTED_VERIFIED"));
        assertTrue(output.contains("Persisted active-source entries: 5"));
        assertTrue(output.contains("League-eligible free-agent entries: 1"));
        assertTrue(output.contains("Canonical Butler players before/after: 2/2"));
        assertTrue(output.contains("does not rank waiver targets"));
    }
}
