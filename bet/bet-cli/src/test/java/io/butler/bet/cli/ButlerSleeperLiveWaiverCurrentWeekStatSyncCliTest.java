package io.butler.bet.cli;

import io.butler.bet.data.LiveWaiverCurrentWeekStatRepository;
import io.butler.bet.sleeper.SleeperLiveWaiverCurrentWeekStatSync;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerSleeperLiveWaiverCurrentWeekStatSyncCliTest {
    @Test
    void rendersPartialEvidenceAndExplicitFinalityBoundary() {
        var entry = new LiveWaiverCurrentWeekStatRepository.Entry(
            "p1", "Example", "WR", 12, 3, 9, "BOTH", "SOURCE_PRESENT",
            "{\"rec\":5,\"rec_tgt\":7,\"rec_yd\":61}",
            null, null, null, null, null,
            null, null, null, 7.0, 5.0, 61.0, null, null);
        var report = new SleeperLiveWaiverCurrentWeekStatSync.SyncReport(
            SleeperLiveWaiverCurrentWeekStatSync.POLICY_ID,
            "stats/nfl/regular/2026/1",
            SleeperLiveWaiverCurrentWeekStatSync.OBSERVATION_STATE,
            "Q", "M", "A", "L", "S", 2026, "in_season", 1,
            2026, 1, "regular", Instant.parse("2026-09-08T03:00:00Z"),
            1, 1, 0, 0, 0, 1, 1, List.of(entry), 1);

        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes));
            ButlerSleeperLiveWaiverCurrentWeekStatSyncCli.print(report);
        } finally {
            System.setOut(original);
        }

        String output = bytes.toString();
        assertTrue(output.contains("Observation state: CURRENT_WEEK_FINALITY_UNPROVEN"));
        assertTrue(output.contains("Weekly stat source PRESENT / ABSENT: 1/0"));
        assertTrue(output.contains("targets=7.0"));
        assertTrue(output.contains("rec=5.0"));
        assertTrue(output.contains("Current-week stat state: PERSISTED_VERIFIED"));
        assertTrue(output.contains("missing player row or missing stat key is not interpreted as zero"));
        assertTrue(output.contains("not a projection"));
    }
}
