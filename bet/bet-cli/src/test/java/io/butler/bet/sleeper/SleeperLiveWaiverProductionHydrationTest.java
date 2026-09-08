package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.domain.Player;
import io.butler.bet.intelligence.NflversePlayerSeasonProductionImporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperLiveWaiverProductionHydrationTest {
    @TempDir Path tempDir;

    @Test
    void bootstrapsExactMissingIdentityHydratesTargetAndVerifiesPostCoverage() throws Exception {
        Path dbPath = tempDir.resolve("butler.db");
        Database database = initialized(dbPath);
        new PlayerRepository(database).save(new Player("p2", "1002", "Known Receiver", "WR", "KC"));
        var pre = report("market-1", 1, 1, 0, List.of(
            candidate("1001", "New Runner", "RB", null,
                SleeperLiveWaiverProductionCoverageAudit.CoverageState.UNMAPPED_CANONICAL),
            candidate("1002", "Known Receiver", "WR", "p2",
                SleeperLiveWaiverProductionCoverageAudit.CoverageState.MAPPED_NO_2025_PRODUCTION)));
        var post = report("market-1", 0, 1, 1, List.of(
            candidate("1001", "New Runner", "RB", "p1-created",
                SleeperLiveWaiverProductionCoverageAudit.CoverageState.MAPPED_WITH_2025_PRODUCTION),
            candidate("1002", "Known Receiver", "WR", "p2",
                SleeperLiveWaiverProductionCoverageAudit.CoverageState.MAPPED_NO_2025_PRODUCTION)));
        AtomicInteger auditCalls = new AtomicInteger();
        SleeperLiveWaiverProductionHydration.CoverageSource coverage = ignored ->
            auditCalls.getAndIncrement() == 0 ? pre : post;
        SleeperLiveWaiverProductionHydration.HydrationAction hydration = ids -> {
            assertEquals(Set.of("1001", "1002"), ids);
            return new NflversePlayerSeasonProductionImporter.ImportResult(
                2025, LocalDate.of(2026, 9, 7), true,
                100, 100, 5000, 1, 2, 1, 1, 1,
                List.of(new NflversePlayerSeasonProductionImporter.UnmatchedPlayer(
                    "p2", "1002", "Known Receiver")));
        };
        var backup = new SleeperLiveWaiverProductionHydration.FileBackupStore(
            dbPath, Clock.fixed(Instant.parse("2026-09-08T02:00:00Z"), ZoneOffset.UTC));
        var subject = new SleeperLiveWaiverProductionHydration(database, coverage, hydration, backup);

        var result = subject.hydrate("league-1");

        assertEquals(SleeperLiveWaiverProductionHydration.HydrationState.HYDRATED_VERIFIED, result.state());
        assertEquals(2, result.targetCandidates());
        assertEquals(1, result.canonicalPlayersCreated());
        assertEquals(0, result.postUnmapped());
        assertEquals(1, result.postMappedWithProduction());
        assertTrue(new PlayerRepository(database).findByExternalId("1001").isPresent());
        assertTrue(Files.isRegularFile(Path.of(result.backupPath())));
    }

    @Test
    void restoresDatabaseWhenTargetedHydrationFailsAfterCanonicalBootstrap() throws Exception {
        Path dbPath = tempDir.resolve("butler.db");
        Database database = initialized(dbPath);
        new PlayerRepository(database).save(new Player("p2", "1002", "Known Receiver", "WR", "KC"));
        var pre = report("market-1", 1, 1, 0, List.of(
            candidate("1001", "New Runner", "RB", null,
                SleeperLiveWaiverProductionCoverageAudit.CoverageState.UNMAPPED_CANONICAL),
            candidate("1002", "Known Receiver", "WR", "p2",
                SleeperLiveWaiverProductionCoverageAudit.CoverageState.MAPPED_NO_2025_PRODUCTION)));
        SleeperLiveWaiverProductionHydration.HydrationAction hydration = ids -> {
            assertTrue(new PlayerRepository(database).findByExternalId("1001").isPresent());
            throw new IllegalArgumentException("ambiguous GSIS-to-Sleeper mapping");
        };
        var backup = new SleeperLiveWaiverProductionHydration.FileBackupStore(
            dbPath, Clock.fixed(Instant.parse("2026-09-08T02:01:00Z"), ZoneOffset.UTC));
        var subject = new SleeperLiveWaiverProductionHydration(database, ignored -> pre, hydration, backup);

        var error = assertThrows(
            SleeperLiveWaiverProductionHydration.HydrationRollbackException.class,
            () -> subject.hydrate("league-1"));

        assertTrue(error.restored());
        assertTrue(Files.isRegularFile(Path.of(error.backupPath())));
        assertFalse(new PlayerRepository(database).findByExternalId("1001").isPresent());
        assertTrue(new PlayerRepository(database).findByExternalId("1002").isPresent());
    }

    @Test
    void restoresDatabaseWhenBf603FrameChangesDuringHydration() throws Exception {
        Path dbPath = tempDir.resolve("butler.db");
        Database database = initialized(dbPath);
        new PlayerRepository(database).save(new Player("p2", "1002", "Known Receiver", "WR", "KC"));
        var pre = report("market-1", 0, 1, 0, List.of(
            candidate("1002", "Known Receiver", "WR", "p2",
                SleeperLiveWaiverProductionCoverageAudit.CoverageState.MAPPED_NO_2025_PRODUCTION)));
        var post = report("market-2", 0, 0, 1, List.of(
            candidate("1002", "Known Receiver", "WR", "p2",
                SleeperLiveWaiverProductionCoverageAudit.CoverageState.MAPPED_WITH_2025_PRODUCTION)));
        AtomicInteger auditCalls = new AtomicInteger();
        var subject = new SleeperLiveWaiverProductionHydration(
            database,
            ignored -> auditCalls.getAndIncrement() == 0 ? pre : post,
            ids -> new NflversePlayerSeasonProductionImporter.ImportResult(
                2025, LocalDate.of(2026, 9, 7), true, 10, 10, 100, 1, 1, 1, 0, 1, List.of()),
            new SleeperLiveWaiverProductionHydration.FileBackupStore(
                dbPath, Clock.fixed(Instant.parse("2026-09-08T02:02:00Z"), ZoneOffset.UTC)));

        var error = assertThrows(
            SleeperLiveWaiverProductionHydration.HydrationRollbackException.class,
            () -> subject.hydrate("league-1"));

        assertTrue(error.restored());
        assertTrue(error.getCause().getMessage().contains("market-attention frame changed"));
    }

    private Database initialized(Path dbPath) throws Exception {
        Database database = new Database(dbPath);
        database.initialize();
        return database;
    }

    private static SleeperLiveWaiverProductionCoverageAudit.AuditReport report(
        String marketSnapshotId,
        int unmapped,
        int noProduction,
        int withProduction,
        List<SleeperLiveWaiverProductionCoverageAudit.CandidateCoverage> candidates) {
        return new SleeperLiveWaiverProductionCoverageAudit.AuditReport(
            SleeperLiveWaiverProductionCoverageAudit.POLICY_ID,
            "league-1",
            marketSnapshotId,
            "2026-09-08T01:42:16Z",
            2025,
            candidates.size(),
            unmapped,
            noProduction,
            withProduction,
            Map.of(),
            candidates);
    }

    private static SleeperLiveWaiverProductionCoverageAudit.CandidateCoverage candidate(
        String sleeperId,
        String name,
        String position,
        String butlerPlayerId,
        SleeperLiveWaiverProductionCoverageAudit.CoverageState state) {
        return new SleeperLiveWaiverProductionCoverageAudit.CandidateCoverage(
            new SleeperLiveWaiverProductionCoverageAudit.MarketCandidate(
                sleeperId, name, position, "KC", "Active", 10, 0, 10, "ADD_ONLY"),
            butlerPlayerId,
            state,
            List.of());
    }
}
