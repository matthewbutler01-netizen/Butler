package io.butler.bet.cli;

import io.butler.bet.data.LiveWaiverAvailabilityRepository;
import io.butler.bet.sleeper.SleeperLiveWaiverAvailabilitySync;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerSleeperLiveWaiverAvailabilitySyncCliTest {
    @Test
    void rendersRawEvidenceAndExplicitGovernanceBoundary() {
        var entry = new LiveWaiverAvailabilityRepository.Entry(
            "p1", "Example", "WR", 12, 3, 9, "BOTH", "SOURCE_PRESENT",
            "CHI", "Active", "Questionable", "2026-09-06", "Limited", "2", 2);
        var report = new SleeperLiveWaiverAvailabilitySync.SyncReport(
            SleeperLiveWaiverAvailabilitySync.POLICY_ID,
            SleeperLiveWaiverAvailabilitySync.SOURCE,
            "A", "M", "L", "S", 2026, "in_season", 1,
            Instant.parse("2026-09-08T02:00:00Z"),
            1, 1, 0, 1, 1, 1, 1, 1, 1, List.of(entry), 1);

        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes));
            ButlerSleeperLiveWaiverAvailabilitySyncCli.print(report);
        } finally {
            System.setOut(original);
        }

        String output = bytes.toString();
        assertTrue(output.contains("Current player source PRESENT / ABSENT: 1/0"));
        assertTrue(output.contains("injury=Questionable"));
        assertTrue(output.contains("practice=Limited"));
        assertTrue(output.contains("depth_order=2"));
        assertTrue(output.contains("Availability state: PERSISTED_VERIFIED"));
        assertTrue(output.contains("Missing fields are not interpreted as healthy"));
        assertTrue(output.contains("No FAAB"));
    }
}
