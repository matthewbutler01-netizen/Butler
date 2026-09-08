package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.GovernedRecommendationAuditRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperLiveWaiverRecommendationAuditHistoryTest {
    private static final String LEAGUE = "butler-hardcore";
    private static final String OWNER = "1051699472830525440";
    private static final String SLEEPER_LEAGUE = "1312110516008677376";
    @TempDir Path tempDir;

    @Test
    void emptyHistoryIsValidAndDoesNotCreateAuditTable() throws Exception {
        Database database = database();
        assertFalse(tableExists(database));

        var report = new SleeperLiveWaiverRecommendationAuditHistory(database).inspect(target());

        assertEquals(SleeperLiveWaiverRecommendationAuditHistory.HistoryState.EMPTY_HISTORY, report.state());
        assertEquals(0, report.entries().size());
        assertFalse(tableExists(database));
    }

    @Test
    void capturedHistoryReconcilesIdentityAndLineageReadOnly() throws Exception {
        Database database = database();
        GovernedRecommendationAuditRepository repository = new GovernedRecommendationAuditRepository(database);
        var record = recommendationRecord("audit-1", "market-1", "waiver-1");
        repository.capture(record);
        int before = repository.countForLeague(LEAGUE);

        var report = new SleeperLiveWaiverRecommendationAuditHistory(database).inspect(target());

        assertEquals(SleeperLiveWaiverRecommendationAuditHistory.HistoryState.HISTORY_INTEGRITY_VERIFIED, report.state());
        assertEquals(1, report.entries().size());
        assertEquals("audit-1", report.entries().get(0).auditId());
        assertEquals(SleeperLiveWaiverRecommendationAuditHistory.IntegrityState.LINEAGE_AND_IDENTITY_VERIFIED,
            report.entries().get(0).integrityState());
        assertEquals(before, repository.countForLeague(LEAGUE));
    }

    @Test
    void tamperedStoredLineageFailsClosed() throws Exception {
        Database database = database();
        new GovernedRecommendationAuditRepository(database).capture(
            recommendationRecord("audit-1", "market-1", "waiver-1"));
        try (var connection = database.openConnection();
             var statement = connection.prepareStatement(
                 "UPDATE governed_recommendation_audits SET market_snapshot_id = ? WHERE id = ?")) {
            statement.setString(1, "market-tampered");
            statement.setString(2, "audit-1");
            statement.executeUpdate();
        }

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> new SleeperLiveWaiverRecommendationAuditHistory(database).inspect(target()));
        assertTrue(error.getMessage().contains("lineage key does not reproduce"));
    }

    @Test
    void storedIdentityMismatchFailsClosed() throws Exception {
        Database database = database();
        new GovernedRecommendationAuditRepository(database).capture(
            recommendationRecord("audit-1", "market-1", "waiver-1"));
        try (var connection = database.openConnection();
             var statement = connection.prepareStatement(
                 "UPDATE governed_recommendation_audits SET sleeper_owner_id = ? WHERE id = ?")) {
            statement.setString(1, "different-owner");
            statement.setString(2, "audit-1");
            statement.executeUpdate();
        }

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> new SleeperLiveWaiverRecommendationAuditHistory(database).inspect(target()));
        assertTrue(error.getMessage().contains("stored audit identity"));
    }

    private GovernedRecommendationAuditRepository.AuditRecord recommendationRecord(
        String id, String marketSnapshot, String waiverSnapshot) {
        var seed = new GovernedRecommendationAuditRepository.AuditRecord(
            id,
            "placeholder",
            SleeperLiveWaiverRecommendationAuditCapture.POLICY_ID,
            LEAGUE,
            OWNER,
            SLEEPER_LEAGUE,
            6,
            2026,
            "in_season",
            1,
            marketSnapshot,
            waiverSnapshot,
            SleeperLiveWaiverFinalRecommendationBundle.BF618_POLICY_ID,
            SleeperLiveWaiverFinalRecommendationBundle.BF619_POLICY_ID,
            SleeperLiveWaiverFinalRecommendationBundle.BF620_POLICY_ID,
            SleeperLiveWaiverFinalRecommendationBundle.BF624_POLICY_ID,
            "UNIQUE_ADD_DROP_SELECTED",
            "RECOMMEND_ADD_DROP",
            "7049",
            "12503",
            Instant.parse("2026-09-08T09:53:19.643012Z"));
        return new GovernedRecommendationAuditRepository.AuditRecord(
            seed.id(),
            SleeperLiveWaiverRecommendationAuditHistory.lineageKey(seed),
            seed.capturePolicyId(),
            seed.leagueId(),
            seed.sleeperOwnerId(),
            seed.sleeperLeagueId(),
            seed.rosterId(),
            seed.season(),
            seed.providerStatus(),
            seed.providerLeg(),
            seed.marketSnapshotId(),
            seed.waiverSnapshotId(),
            seed.bf618PolicyId(),
            seed.bf619PolicyId(),
            seed.bf620PolicyId(),
            seed.bf624PolicyId(),
            seed.selectionState(),
            seed.recommendationState(),
            seed.addSleeperPlayerId(),
            seed.dropSleeperPlayerId(),
            seed.capturedAtUtc());
    }

    private Database database() throws Exception {
        Database database = new Database(tempDir.resolve("test.db"));
        database.initialize();
        try (var connection = database.openConnection();
             var statement = connection.prepareStatement(
                 "INSERT INTO leagues(id, external_id, name, season) VALUES (?, ?, ?, ?)")) {
            statement.setString(1, LEAGUE);
            statement.setString(2, SLEEPER_LEAGUE);
            statement.setString(3, "Hard(CORE)-Dynasty");
            statement.setInt(4, 2026);
            statement.executeUpdate();
        }
        return database;
    }

    private static SleeperPersonalizedTargetService.VerifiedTarget target() {
        return new SleeperPersonalizedTargetService.VerifiedTarget(
            SleeperPersonalizedTargetService.BF623_POLICY_ID,
            LEAGUE,
            "mbutler0624",
            OWNER,
            SLEEPER_LEAGUE,
            "Hard(CORE)-Dynasty",
            "in_season",
            6,
            SleeperPersonalizedTargetService.MembershipRole.OWNER,
            "mbutler0624",
            "nuke the whales",
            SleeperPersonalizedTargetService.VerificationState.BOUND_TARGET_LIVE_VERIFIED);
    }

    private static boolean tableExists(Database database) throws Exception {
        try (var connection = database.openConnection();
             var statement = connection.prepareStatement(
                 "SELECT 1 FROM sqlite_master WHERE type='table' AND name='governed_recommendation_audits'");
             var rs = statement.executeQuery()) {
            return rs.next();
        }
    }
}
