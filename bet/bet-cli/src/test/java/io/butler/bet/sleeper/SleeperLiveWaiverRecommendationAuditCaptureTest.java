package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.GovernedRecommendationAuditRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperLiveWaiverRecommendationAuditCaptureTest {
    @TempDir Path tempDir;

    @Test
    void recommendationCaptureIsVerifiedAndExactRepeatIsIdempotent() throws Exception {
        Database database = database();
        var repository = new GovernedRecommendationAuditRepository(database);
        var recommendation = recommendation("market-1", "waiver-1", "7049", "12503");
        var service = service(repository, recommendation);

        var first = service.capture(target());
        var second = service.capture(target());

        assertEquals(GovernedRecommendationAuditRepository.CaptureState.CAPTURED_VERIFIED, first.captureState());
        assertEquals(GovernedRecommendationAuditRepository.CaptureState.ALREADY_CAPTURED_EXACT, second.captureState());
        assertEquals(first.auditId(), second.auditId());
        assertEquals("RECOMMEND_ADD_DROP", first.recommendationState());
        assertEquals("7049", first.addSleeperPlayerId());
        assertEquals("12503", first.dropSleeperPlayerId());
        assertEquals(1, repository.countForLeague("butler-hardcore"));
    }

    @Test
    void noGovernedTransactionIsPersistedWithoutInventingAddDrop() throws Exception {
        Database database = database();
        var repository = new GovernedRecommendationAuditRepository(database);
        var service = service(repository, noTransaction("market-2", "waiver-2"));

        var result = service.capture(target());

        assertEquals(GovernedRecommendationAuditRepository.CaptureState.CAPTURED_VERIFIED, result.captureState());
        assertEquals("NO_GOVERNED_TRANSACTION", result.recommendationState());
        assertEquals(null, result.addSleeperPlayerId());
        assertEquals(null, result.dropSleeperPlayerId());
        assertEquals(1, repository.countForLeague("butler-hardcore"));
    }

    @Test
    void bf623IdentityMismatchFailsClosedBeforePersistence() throws Exception {
        Database database = database();
        var repository = new GovernedRecommendationAuditRepository(database);
        var wrongOwner = recommendation("market-3", "waiver-3", "7049", "12503", "wrong-owner", 6);
        var service = service(repository, wrongOwner);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.capture(target()));

        assertTrue(error.getMessage().contains("identity does not reconcile"));
        assertEquals(0, repository.countForLeague("butler-hardcore"));
    }

    @Test
    void internalMarketLineageMismatchFailsClosedBeforePersistence() throws Exception {
        Database database = database();
        var repository = new GovernedRecommendationAuditRepository(database);
        var base = recommendation("market-4", "waiver-4", "7049", "12503");
        var badMethodology = new SleeperLiveWaiverFinalRecommendationBundle.MethodologyReport(
            SleeperLiveWaiverFinalRecommendationBundle.BF618_POLICY_ID,
            "different-market",
            base.methodology().historicalFinalists(),
            base.methodology().newcomerFinalists(),
            base.methodology().historicalFinalistPositions(),
            base.methodology().addWinnerRule(),
            base.methodology().evidenceRule(),
            base.methodology().crossPositionRule(),
            base.methodology().newcomerRule(),
            base.methodology().dropRule(),
            base.methodology().protectedTargetRule(),
            base.methodology().state());
        var mismatch = new SleeperLiveWaiverFinalRecommendationBundle.RecommendationReport(
            base.policyId(), base.leagueId(), base.sleeperOwnerId(), base.marketSnapshotId(), base.waiverSnapshotId(),
            base.sleeperLeagueId(), base.rosterId(), badMethodology, base.selection(), base.providerSeason(),
            base.providerStatus(), base.providerLeg(), base.recommendedAdd(), base.recommendedDrop(),
            base.newcomerReviewAlternatives(), base.state());
        var service = service(repository, mismatch);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.capture(target()));

        assertTrue(error.getMessage().contains("market snapshot lineage"));
        assertEquals(0, repository.countForLeague("butler-hardcore"));
    }

    @Test
    void sameImmutableLineageWithChangedDecisionPayloadIsRejected() throws Exception {
        Database database = database();
        var repository = new GovernedRecommendationAuditRepository(database);
        AtomicReference<SleeperLiveWaiverFinalRecommendationBundle.RecommendationReport> current =
            new AtomicReference<>(recommendation("market-5", "waiver-5", "7049", "12503"));
        var service = new SleeperLiveWaiverRecommendationAuditCapture(
            (leagueId, ownerId) -> current.get(),
            repository,
            Clock.fixed(Instant.parse("2026-09-08T09:40:00Z"), ZoneOffset.UTC));

        service.capture(target());
        current.set(recommendation("market-5", "waiver-5", "3214", "12493"));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.capture(target()));
        assertTrue(error.getMessage().contains("immutable recommendation audit lineage"));
        assertEquals(1, repository.countForLeague("butler-hardcore"));
    }

    private SleeperLiveWaiverRecommendationAuditCapture service(
        GovernedRecommendationAuditRepository repository,
        SleeperLiveWaiverFinalRecommendationBundle.RecommendationReport recommendation) {
        return new SleeperLiveWaiverRecommendationAuditCapture(
            (leagueId, ownerId) -> recommendation,
            repository,
            Clock.fixed(Instant.parse("2026-09-08T09:40:00Z"), ZoneOffset.UTC));
    }

    private Database database() throws Exception {
        Database database = new Database(tempDir.resolve("test.db"));
        database.initialize();
        try (var connection = database.openConnection();
             var statement = connection.prepareStatement(
                 "INSERT INTO leagues(id, external_id, name, season) VALUES (?, ?, ?, ?)")) {
            statement.setString(1, "butler-hardcore");
            statement.setString(2, "1312110516008677376");
            statement.setString(3, "Hard(CORE)-Dynasty");
            statement.setInt(4, 2026);
            statement.executeUpdate();
        }
        return database;
    }

    private static SleeperPersonalizedTargetService.VerifiedTarget target() {
        return new SleeperPersonalizedTargetService.VerifiedTarget(
            SleeperPersonalizedTargetService.BF623_POLICY_ID,
            "butler-hardcore",
            "mbutler0624",
            "1051699472830525440",
            "1312110516008677376",
            "Hard(CORE)-Dynasty",
            "in_season",
            6,
            SleeperPersonalizedTargetService.MembershipRole.OWNER,
            "mbutler0624",
            "nuke the whales",
            SleeperPersonalizedTargetService.VerificationState.BOUND_TARGET_LIVE_VERIFIED);
    }

    private static SleeperLiveWaiverFinalRecommendationBundle.RecommendationReport recommendation(
        String market, String waiver, String addId, String dropId) {
        return recommendation(market, waiver, addId, dropId, "1051699472830525440", 6);
    }

    private static SleeperLiveWaiverFinalRecommendationBundle.RecommendationReport recommendation(
        String market, String waiver, String addId, String dropId, String owner, int rosterId) {
        var add = player(addId, "Add Player", "WAIVER_CANDIDATE");
        var drop = player(dropId, "Drop Player", "BENCH");
        var methodology = methodology(market);
        var selection = new SleeperLiveWaiverFinalRecommendationBundle.SelectionReport(
            SleeperLiveWaiverFinalRecommendationBundle.BF619_POLICY_ID,
            market,
            List.of(addId, dropId),
            List.of(),
            add,
            drop,
            SleeperLiveWaiverFinalRecommendationBundle.SelectionState.UNIQUE_ADD_DROP_SELECTED);
        return new SleeperLiveWaiverFinalRecommendationBundle.RecommendationReport(
            SleeperLiveWaiverFinalRecommendationBundle.BF620_POLICY_ID,
            "butler-hardcore",
            owner,
            market,
            waiver,
            "1312110516008677376",
            rosterId,
            methodology,
            selection,
            2026,
            "in_season",
            1,
            add,
            drop,
            List.of(),
            SleeperLiveWaiverFinalRecommendationBundle.RecommendationState.RECOMMEND_ADD_DROP);
    }

    private static SleeperLiveWaiverFinalRecommendationBundle.RecommendationReport noTransaction(
        String market, String waiver) {
        var methodology = methodology(market);
        var selection = new SleeperLiveWaiverFinalRecommendationBundle.SelectionReport(
            SleeperLiveWaiverFinalRecommendationBundle.BF619_POLICY_ID,
            market,
            List.of(),
            List.of(),
            null,
            null,
            SleeperLiveWaiverFinalRecommendationBundle.SelectionState.NO_UNIQUE_HISTORICAL_ADD);
        return new SleeperLiveWaiverFinalRecommendationBundle.RecommendationReport(
            SleeperLiveWaiverFinalRecommendationBundle.BF620_POLICY_ID,
            "butler-hardcore",
            "1051699472830525440",
            market,
            waiver,
            "1312110516008677376",
            6,
            methodology,
            selection,
            2026,
            "in_season",
            1,
            null,
            null,
            List.of(),
            SleeperLiveWaiverFinalRecommendationBundle.RecommendationState.NO_GOVERNED_TRANSACTION);
    }

    private static SleeperLiveWaiverFinalRecommendationBundle.MethodologyReport methodology(String market) {
        return new SleeperLiveWaiverFinalRecommendationBundle.MethodologyReport(
            SleeperLiveWaiverFinalRecommendationBundle.BF618_POLICY_ID,
            market,
            1,
            0,
            List.of("WR"),
            "HISTORICAL_FINALISTS_DIRECT_ALL_OPPONENTS_DOMINANCE_WITHIN_POSITION",
            "LATEST_2025_COMMON_SOURCE_SUPPORTED_SUBTOTAL_PER_GAME_SCHEMA_EQUALITY",
            "BF624_COMPLETE_TRANSACTION_DELTA_STRICT_ALL_COMPATIBLE_COMMON_SOURCE_DOMINANCE",
            "NEWCOMERS_NONNUMERIC_NOT_ELIGIBLE_FOR_FINAL_WINNER",
            "DROP_ONLY_FROM_SELECTED_ADD_BF615_CANDIDATE_SUPPORTED_BENCH_RESERVE_COMPARATORS",
            "PROTECTED_MISSING_PRODUCTION_TARGET_NEVER_DROPPABLE",
            SleeperLiveWaiverFinalRecommendationBundle.MethodologyState.FINAL_SELECTION_METHOD_FROZEN);
    }

    private static SleeperLiveWaiverFinalRecommendationBundle.SelectedPlayer player(
        String id, String name, String role) {
        return new SleeperLiveWaiverFinalRecommendationBundle.SelectedPlayer(
            id, name, "WR", role, null, null, null, null, null);
    }
}
