package io.butler.bet.sleeper;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.time.Instant;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NflverseRosterUsageProviderTest {
    private static final String IDS = "gsis_id,sleeper_id,pfr_id\ng1,s1,p1\n";
    private static final Instant NOW = Instant.parse("2026-10-01T06:00:00Z");
    private static final String STATS = "player_id,season,season_type,week,carries,targets\n";
    private static final String SNAPS = "pfr_player_id,season,game_type,week,offense_snaps,offense_pct\n";

    @Test void sharpConcurrentDropsHoldWithoutInventingRoleCause() {
        var result = parse(STATS + "g1,2026,REG,2,8,2\ng1,2026,REG,3,3,1\n",
            SNAPS + "p1,2026,REG,2,50,0.8\np1,2026,REG,3,20,0.4\n");
        assertTrue(result.get("s1").reviewHold());
        assertTrue(result.get("s1").detail().contains("targets=1"));
        assertTrue(result.get("s1").detail().contains("snap share=40.0%"));
        assertTrue(result.get("s1").detail().contains("https://github.com/nflverse"));
        assertTrue(result.get("s1").detail().contains("do not establish the cause"));
    }

    @Test void decliningVolumeWithStableSnapsDoesNotFabricateRoleHold() {
        var result = parse(STATS + "g1,2026,REG,2,8,2\ng1,2026,REG,3,3,1\n",
            SNAPS + "p1,2026,REG,2,50,0.8\np1,2026,REG,3,48,0.8\n");
        assertFalse(result.get("s1").reviewHold());
    }

    @Test void missingLatestWeekByeAndUnknownIdentityRemainMissingNotZero() {
        assertTrue(parse(STATS + "g1,2026,REG,2,8,2\n",
            SNAPS + "p1,2026,REG,2,50,0.8\n").isEmpty());
        assertTrue(parse(STATS + "other,2026,REG,2,8,2\nother,2026,REG,3,0,0\n",
            SNAPS + "p1,2026,REG,2,50,0.8\np1,2026,REG,3,20,0.4\n").isEmpty());
    }

    @Test void wrongSeasonPostseasonAndCurrentWeekCannotSupplyRoleEvidence() {
        assertTrue(parse(STATS + "g1,2025,REG,2,8,2\ng1,2026,POST,3,1,1\ng1,2026,REG,4,0,0\n",
            SNAPS + "p1,2026,REG,2,50,0.8\np1,2026,REG,3,20,0.4\n").isEmpty());
    }

    @Test void duplicateRowsAndConflictingCrosswalkAreRejected() {
        assertThrows(IllegalStateException.class, () -> parse(
            STATS + "g1,2026,REG,2,8,2\ng1,2026,REG,2,8,2\n", SNAPS));
        assertTrue(NflverseRosterUsageProvider.parse(
            IDS + "g1,other,p2\n", STATS + "g1,2026,REG,2,8,2\ng1,2026,REG,3,3,1\n",
            SNAPS + "p1,2026,REG,2,50,0.8\np1,2026,REG,3,20,0.4\n",
            2026, 4, Set.of("s1"), NOW).isEmpty());
    }

    @Test void missingNumericFieldsAndImpossibleShareAreRejected() {
        assertThrows(IllegalStateException.class, () -> parse(
            STATS + "g1,2026,REG,2,,2\n", SNAPS));
        assertThrows(IllegalStateException.class, () -> parse(STATS,
            SNAPS + "p1,2026,REG,2,50,NaN\n"));
    }

    @Test void passingAttemptsAreSeparateFromRushingReceivingHoldPolicy() {
        var result = parse(STATS.stripTrailing() + ",attempts\n"
            + "g1,2026,REG,2,4,0,32\ng1,2026,REG,3,4,0,41\n",
            SNAPS + "p1,2026,REG,2,55,1.0\np1,2026,REG,3,51,0.81\n");
        var evidence = result.get("s1");
        assertEquals(32, evidence.weeks().get(0).attempts());
        assertEquals(4, evidence.weeks().get(0).opportunities());
        assertTrue(evidence.summary().contains("passing attempts 41"));
        assertFalse(evidence.reviewHold());
    }

    @Test void missingPassingAttemptsRemainUnavailableAndExplicitZeroRemainsZero() {
        var result = parse(STATS.stripTrailing() + ",attempts\n"
            + "g1,2026,REG,2,4,0,\ng1,2026,REG,3,4,0,0\n",
            SNAPS + "p1,2026,REG,2,55,1.0\np1,2026,REG,3,51,0.81\n");
        assertNull(result.get("s1").weeks().get(0).attempts());
        assertTrue(result.get("s1").summary().contains("passing attempts unavailable"));
        assertEquals(0, result.get("s1").weeks().get(1).attempts());
        assertThrows(IllegalStateException.class, () -> parse(STATS.stripTrailing() + ",attempts\n"
            + "g1,2026,REG,2,4,0,-1\n", SNAPS));
    }

    @Test void retriesOnlyTransientSourceStatuses() {
        assertTrue(NflverseRosterUsageProvider.shouldRetryStatus(404));
        assertTrue(NflverseRosterUsageProvider.shouldRetryStatus(408));
        assertTrue(NflverseRosterUsageProvider.shouldRetryStatus(429));
        assertTrue(NflverseRosterUsageProvider.shouldRetryStatus(500));
        assertTrue(NflverseRosterUsageProvider.shouldRetryStatus(503));
        assertFalse(NflverseRosterUsageProvider.shouldRetryStatus(400));
        assertFalse(NflverseRosterUsageProvider.shouldRetryStatus(401));
        assertFalse(NflverseRosterUsageProvider.shouldRetryStatus(403));
    }

    @Test void decodesGzipReleaseAssetsAndLeavesPlainCsvUntouched() throws Exception {
        String csv = "season,week,gameday,gametime\n2026,4,2026-10-04,13:00\n";
        assertEquals(csv, NflverseRosterUsageProvider.decodeText(
            URI.create("https://example.test/games.csv"), csv.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        assertEquals(csv, NflverseRosterUsageProvider.decodeText(
            URI.create("https://example.test/games.csv.gz"), bytes.toByteArray()));
    }

    @Test void scheduleSourceUsesCurrentNflverseGzipAsset() {
        assertEquals(
            "https://github.com/nflverse/nflverse-data/releases/download/schedules/games.csv.gz",
            NflverseDefensiveMatchupProvider.SCHEDULE_URI.toString());
    }

    private static java.util.Map<String, NflverseRosterUsageProvider.UsageEvidence> parse(String stats, String snaps) {
        return NflverseRosterUsageProvider.parse(IDS, stats, snaps, 2026, 4, Set.of("s1"), NOW);
    }
}
