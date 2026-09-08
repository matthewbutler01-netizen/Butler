package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperLiveWaiverRecommendationEvidenceLineageRevalidationTest {

    @TempDir
    Path tempDir;

    @Test
    void exactLatestPersistedLineageIsVerified() throws Exception {
        var service = service(recommendationHistory(), frame("market-1", "waiver-1", "waiver-1"));

        var report = service.revalidate(target());

        assertEquals(
            SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.LATEST_EVIDENCE_LINEAGE_VERIFIED,
            report.state());
        assertEquals("market-1", report.latestMarketSnapshotId());
        assertEquals("waiver-1", report.latestWaiverSnapshotId());
        assertEquals("waiver-1", report.latestMarketReferencedWaiverSnapshotId());
    }

    @Test
    void newerMarketOnSameWaiverSupersedesOnlyMarketLineage() throws Exception {
        var service = service(recommendationHistory(), frame("market-2", "waiver-1", "waiver-1"));

        var report = service.revalidate(target());

        assertEquals(
            SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.MARKET_LINEAGE_SUPERSEDED,
            report.state());
    }

    @Test
    void newerWaiverAfterSameMarketSupersedesOnlyWaiverLineage() throws Exception {
        var service = service(recommendationHistory(), frame("market-1", "waiver-2", "waiver-1"));

        var report = service.revalidate(target());

        assertEquals(
            SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.WAIVER_LINEAGE_SUPERSEDED,
            report.state());
        assertEquals("waiver-1", report.latestMarketReferencedWaiverSnapshotId());
        assertEquals("waiver-2", report.latestWaiverSnapshotId());
    }

    @Test
    void newerMarketAndWaiverSupersedeBothAuditLineages() throws Exception {
        var service = service(recommendationHistory(), frame("market-2", "waiver-2", "waiver-2"));

        var report = service.revalidate(target());

        assertEquals(
            SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.MARKET_AND_WAIVER_LINEAGE_SUPERSEDED,
            report.state());
    }

    @Test
    void emptyHistoryIsValidAndDoesNotReadSnapshotLineage() throws Exception {
        var service = new SleeperLiveWaiverRecommendationEvidenceLineageRevalidation(
            ignored -> emptyHistory(),
            ignored -> { throw new AssertionError("snapshot lineage source must not be called"); });

        var report = service.revalidate(target());

        assertEquals(
            SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.NO_AUDITED_DECISION,
            report.state());
        assertNull(report.auditId());
        assertNull(report.latestMarketSnapshotId());
    }

    @Test
    void latestMarketReferencedWaiverMustReconcileToItsProviderFrame() {
        var badReferenced = new SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.WaiverFrame(
            "waiver-1", "league-butler", "league-sleeper", 2026, "in_season", 2,
            Instant.parse("2026-09-08T09:50:00Z"));
        var frame = new SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceFrame(
            market("market-1", "waiver-1", 1), waiver("waiver-1", 1), badReferenced);
        var service = service(recommendationHistory(), frame);

        var error = assertThrows(IllegalStateException.class, () -> service.revalidate(target()));

        assertTrue(error.getMessage().contains("provider frame does not reconcile"));
    }

    @Test
    void auditedMarketCannotResolveToDifferentWaiverLineage() {
        var frame = new SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceFrame(
            market("market-1", "waiver-other", 1),
            waiver("waiver-1", 1),
            waiver("waiver-other", 1));
        var service = service(recommendationHistory(), frame);

        var error = assertThrows(IllegalStateException.class, () -> service.revalidate(target()));

        assertTrue(error.getMessage().contains("audited BF-603 id now resolves to a different BF-602 lineage"));
    }

    @Test
    void nonMatchingLatestEvidenceIdentityFailsClosed() {
        var foreignWaiver = new SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.WaiverFrame(
            "waiver-1", "other-butler-league", "league-sleeper", 2026, "in_season", 1,
            Instant.parse("2026-09-08T09:50:00Z"));
        var frame = new SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceFrame(
            market("market-1", "waiver-1", 1), foreignWaiver, waiver("waiver-1", 1));
        var service = service(recommendationHistory(), frame);

        var error = assertThrows(IllegalStateException.class, () -> service.revalidate(target()));

        assertTrue(error.getMessage().contains("latest BF-602 identity/season/status"));
    }

    @Test
    void selectOnlyDatabaseReaderDoesNotCreateMissingEvidenceTables() throws Exception {
        Database database = new Database(tempDir.resolve("empty-bf631.db"));
        var source = new SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.ReadOnlyDbSnapshotLineageSource(database);

        var error = assertThrows(IllegalStateException.class, () -> source.latest("league-butler"));
        assertTrue(error.getMessage().contains("required persisted table is absent"));

        try (var connection = database.openConnection();
             var statement = connection.prepareStatement("""
                 SELECT COUNT(*)
                 FROM sqlite_master
                 WHERE type='table'
                   AND name IN ('live_waiver_snapshots','live_waiver_market_attention_snapshots')
                 """)) {
            try (var rs = statement.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1));
            }
        }
    }

    @Test
    void selectOnlyDatabaseReaderLoadsIndependentLatestWaiverAndLatestMarketReference() throws Exception {
        Database database = new Database(tempDir.resolve("lineage-bf631.db"));
        try (var connection = database.openConnection();
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE live_waiver_snapshots (
                    id TEXT PRIMARY KEY,
                    league_id TEXT NOT NULL,
                    sleeper_league_id TEXT NOT NULL,
                    season INTEGER NOT NULL,
                    provider_status TEXT NOT NULL,
                    provider_leg INTEGER,
                    observed_at_utc TEXT NOT NULL
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE live_waiver_market_attention_snapshots (
                    id TEXT PRIMARY KEY,
                    league_id TEXT NOT NULL,
                    waiver_snapshot_id TEXT NOT NULL,
                    sleeper_league_id TEXT NOT NULL,
                    season INTEGER NOT NULL,
                    provider_status TEXT NOT NULL,
                    provider_leg INTEGER,
                    observed_at_utc TEXT NOT NULL
                )
                """);
            statement.executeUpdate("""
                INSERT INTO live_waiver_snapshots
                    (id, league_id, sleeper_league_id, season, provider_status, provider_leg, observed_at_utc)
                VALUES
                    ('waiver-1','league-butler','league-sleeper',2026,'in_season',1,'2026-09-08T09:00:00Z'),
                    ('waiver-2','league-butler','league-sleeper',2026,'in_season',1,'2026-09-08T10:00:00Z')
                """);
            statement.executeUpdate("""
                INSERT INTO live_waiver_market_attention_snapshots
                    (id, league_id, waiver_snapshot_id, sleeper_league_id, season, provider_status, provider_leg, observed_at_utc)
                VALUES
                    ('market-1','league-butler','waiver-1','league-sleeper',2026,'in_season',1,'2026-09-08T09:10:00Z')
                """);
        }

        var source = new SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.ReadOnlyDbSnapshotLineageSource(database);
        var loaded = source.latest("league-butler");

        assertEquals("market-1", loaded.latestMarket().id());
        assertEquals("waiver-1", loaded.marketReferencedWaiver().id());
        assertEquals("waiver-2", loaded.latestWaiver().id());
    }

    private static SleeperLiveWaiverRecommendationEvidenceLineageRevalidation service(
        SleeperLiveWaiverRecommendationAuditHistory.HistoryReport history,
        SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceFrame frame) {
        return new SleeperLiveWaiverRecommendationEvidenceLineageRevalidation(
            ignored -> history,
            ignored -> frame);
    }

    private static SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceFrame frame(
        String marketId,
        String latestWaiverId,
        String marketReferencedWaiverId) {
        return new SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceFrame(
            market(marketId, marketReferencedWaiverId, 1),
            waiver(latestWaiverId, 1),
            waiver(marketReferencedWaiverId, 1));
    }

    private static SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.MarketFrame market(
        String id,
        String waiverId,
        Integer leg) {
        return new SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.MarketFrame(
            id, "league-butler", "league-sleeper", 2026, "in_season", leg,
            Instant.parse("2026-09-08T09:55:00Z"), waiverId);
    }

    private static SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.WaiverFrame waiver(
        String id,
        Integer leg) {
        return new SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.WaiverFrame(
            id, "league-butler", "league-sleeper", 2026, "in_season", leg,
            Instant.parse("2026-09-08T09:50:00Z"));
    }

    private static SleeperPersonalizedTargetService.VerifiedTarget target() {
        return new SleeperPersonalizedTargetService.VerifiedTarget(
            SleeperPersonalizedTargetService.BF623_POLICY_ID,
            "league-butler",
            "mbutler0624",
            "owner",
            "league-sleeper",
            "Hard(CORE)-Dynasty",
            "in_season",
            6,
            SleeperPersonalizedTargetService.MembershipRole.OWNER,
            "mbutler0624",
            "nuke the whales",
            SleeperPersonalizedTargetService.VerificationState.BOUND_TARGET_LIVE_VERIFIED);
    }

    private static SleeperLiveWaiverRecommendationAuditHistory.HistoryReport recommendationHistory() {
        return new SleeperLiveWaiverRecommendationAuditHistory.HistoryReport(
            SleeperLiveWaiverRecommendationAuditHistory.POLICY_ID,
            "league-butler", "owner", "league-sleeper", 6,
            List.of(new SleeperLiveWaiverRecommendationAuditHistory.HistoryEntry(
                "audit-1", "2026-09-08T09:53:19Z", "league-butler", "owner", "league-sleeper", 6,
                2026, "in_season", 1, "market-1", "waiver-1",
                "UNIQUE_ADD_DROP_SELECTED", "RECOMMEND_ADD_DROP", "7049", "12503",
                SleeperLiveWaiverRecommendationAuditHistory.IntegrityState.LINEAGE_AND_IDENTITY_VERIFIED)),
            SleeperLiveWaiverRecommendationAuditHistory.HistoryState.HISTORY_INTEGRITY_VERIFIED);
    }

    private static SleeperLiveWaiverRecommendationAuditHistory.HistoryReport emptyHistory() {
        return new SleeperLiveWaiverRecommendationAuditHistory.HistoryReport(
            SleeperLiveWaiverRecommendationAuditHistory.POLICY_ID,
            "league-butler", "owner", "league-sleeper", 6, List.of(),
            SleeperLiveWaiverRecommendationAuditHistory.HistoryState.EMPTY_HISTORY);
    }
}
