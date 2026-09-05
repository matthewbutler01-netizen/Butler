package io.butler.bet.integration.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.RosterRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.data.TradeCounterAuthorizationGrantRepository;
import io.butler.bet.data.TradeCounterExecutionAttemptRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.Player;
import io.butler.bet.domain.Roster;
import io.butler.bet.domain.Team;
import io.butler.bet.execution.TradeCounterManualHandoffCoordinator;
import io.butler.bet.intelligence.TradeAssetAnalyzer;
import io.butler.bet.intelligence.TradeCounterAuthorizationPolicy;
import io.butler.bet.intelligence.TradeCounterCandidateSelectionPolicy;
import io.butler.bet.intelligence.TradeCounterExecutionReadinessPolicy;
import io.butler.bet.intelligence.TradeCounterMaterializedPackagePolicy;
import io.butler.bet.intelligence.TradeCounterNegotiationMessagePolicy;
import io.butler.bet.intelligence.TradeCounterOpportunityPolicy;
import io.butler.bet.intelligence.TradeCounterProposalEnvelopePolicy;
import io.butler.bet.intelligence.TradeCounterProposalIdentityPolicy;
import io.butler.bet.intelligence.TradeCounterProposalPolicy;
import io.butler.bet.intelligence.TradeCounterSingleAssetCandidateAnalyzer;
import io.butler.bet.intelligence.TradeCounterValueTargetAnalyzer;
import io.butler.bet.intelligence.TradeFairnessPolicy;
import io.butler.bet.intelligence.TradeTeamPerspectiveRecommendationPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperCounterTradeNoActionResolutionRepositoryTest {
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 1);
    private static final Instant PRESENTED_AT = Instant.parse("2026-09-05T01:10:00Z");
    private static final Instant SNAPSHOTTED_AT = PRESENTED_AT.plusSeconds(1);
    private static final Instant ACKNOWLEDGED_AT = PRESENTED_AT.plusSeconds(10);
    private static final Instant RECORDED_AT = ACKNOWLEDGED_AT.plusSeconds(1);
    private static final Instant APPLIED_AT = PRESENTED_AT.plusSeconds(60);

    @TempDir
    Path tempDir;

    @Test
    void exactCompletedReadbackSupersedesUnfinalizedNoActionAndFinalizesSuccessAtomically() throws Exception {
        Fixture fixture = fixture("supersede");
        recordNoAction(fixture);
        var tradeDecision = completeDecision(fixture);

        var result = new SleeperCounterTradeOutcomeCoordinator(fixture.database())
            .apply(tradeDecision, APPLIED_AT);

        assertEquals(SleeperCounterTradeOutcomeCoordinator.ApplyState.APPLIED, result.state());
        assertTrue(result.reason().contains("superseded"));
        assertEquals(TradeCounterExecutionAttemptRepository.State.SUCCEEDED,
            new TradeCounterExecutionAttemptRepository(fixture.database())
                .findByAttemptId(fixture.attemptId()).orElseThrow().state());
        assertTrue(new TradeCounterAuthorizationGrantRepository(fixture.database())
            .findById(fixture.grantId()).orElseThrow().consumed());

        var acknowledgment = new SleeperManualCounterNoActionAcknowledgmentRepository(fixture.database())
            .findByClaimId(fixture.claimId()).orElseThrow();
        assertEquals(SleeperManualCounterNoActionAcknowledgmentPolicy.REQUIRED_CONFIRMATION,
            acknowledgment.confirmation());
        assertTrue(new SleeperManualCounterNoActionOutcomeCoordinator(fixture.database())
            .findByClaimId(fixture.claimId()).isEmpty());

        var resolution = new SleeperCounterTradeNoActionResolutionRepository(fixture.database())
            .findByClaimId(fixture.claimId()).orElseThrow();
        assertEquals(
            SleeperCounterTradeNoActionResolutionRepository.ResolutionType.SUPERSEDED_BY_CONFIRMED_TRADE,
            resolution.resolutionType());
        assertEquals("tx-complete", resolution.sleeperTransactionId());
        assertEquals(7, resolution.sleeperWeek());
        assertEquals(acknowledgment.acknowledgmentId(), resolution.acknowledgmentId());
        assertEquals(result.outcome().movementSha256(), resolution.movementSha256());
    }

    @Test
    void exactRepeatAfterSupersededSuccessIsIdempotent() throws Exception {
        Fixture fixture = fixture("repeat");
        recordNoAction(fixture);
        var coordinator = new SleeperCounterTradeOutcomeCoordinator(fixture.database());
        var decision = completeDecision(fixture);

        var first = coordinator.apply(decision, APPLIED_AT);
        var second = coordinator.apply(decision, APPLIED_AT.plusSeconds(30));

        assertEquals(SleeperCounterTradeOutcomeCoordinator.ApplyState.APPLIED, first.state());
        assertEquals(SleeperCounterTradeOutcomeCoordinator.ApplyState.ALREADY_APPLIED, second.state());
        assertEquals(first.outcome().outcomeId(), second.outcome().outcomeId());
        var resolution = new SleeperCounterTradeNoActionResolutionRepository(fixture.database())
            .findByClaimId(fixture.claimId()).orElseThrow();
        assertEquals(APPLIED_AT, resolution.resolvedAt());
    }

    @Test
    void completedReadbackAfterNoActionClosureRecordsDiscrepancyWithoutRewritingHistory() throws Exception {
        Fixture fixture = fixture("post-close");
        recordNoAction(fixture);
        var noActionFinalizer = new SleeperManualCounterNoActionOutcomeCoordinator(fixture.database());
        var closed = noActionFinalizer.apply(fixture.claimId(), APPLIED_AT.minusSeconds(5));
        assertEquals(SleeperManualCounterNoActionOutcomeCoordinator.ApplyState.APPLIED, closed.state());

        var grantBefore = new TradeCounterAuthorizationGrantRepository(fixture.database())
            .findById(fixture.grantId()).orElseThrow();
        var result = new SleeperCounterTradeOutcomeCoordinator(fixture.database())
            .apply(completeDecision(fixture), APPLIED_AT);

        assertEquals(
            SleeperCounterTradeOutcomeCoordinator.ApplyState.POST_CLOSURE_DISCREPANCY,
            result.state());
        assertTrue(result.outcome() == null);
        assertTrue(result.reason().contains("not rewritten"));
        var attempt = new TradeCounterExecutionAttemptRepository(fixture.database())
            .findByAttemptId(fixture.attemptId()).orElseThrow();
        assertEquals(TradeCounterExecutionAttemptRepository.State.FAILED, attempt.state());
        var grantAfter = new TradeCounterAuthorizationGrantRepository(fixture.database())
            .findById(fixture.grantId()).orElseThrow();
        assertTrue(grantAfter.consumed());
        assertEquals(grantBefore.consumedAt(), grantAfter.consumedAt());
        assertTrue(new SleeperCounterTradeOutcomeCoordinator(fixture.database())
            .findByClaimId(fixture.claimId()).isEmpty());

        var resolution = new SleeperCounterTradeNoActionResolutionRepository(fixture.database())
            .findByClaimId(fixture.claimId()).orElseThrow();
        assertEquals(
            SleeperCounterTradeNoActionResolutionRepository.ResolutionType.POST_CLOSURE_EXTERNAL_ACTION,
            resolution.resolutionType());
        assertEquals(closed.outcome().outcomeId(), resolution.noActionTerminalOutcomeId());
        assertEquals("tx-complete", resolution.sleeperTransactionId());
    }

    @Test
    void postClosureDiscrepancyRepeatPreservesFirstResolution() throws Exception {
        Fixture fixture = fixture("post-close-repeat");
        recordNoAction(fixture);
        new SleeperManualCounterNoActionOutcomeCoordinator(fixture.database())
            .apply(fixture.claimId(), APPLIED_AT.minusSeconds(5));
        var coordinator = new SleeperCounterTradeOutcomeCoordinator(fixture.database());
        var decision = completeDecision(fixture);

        var first = coordinator.apply(decision, APPLIED_AT);
        var second = coordinator.apply(decision, APPLIED_AT.plusSeconds(30));

        assertEquals(SleeperCounterTradeOutcomeCoordinator.ApplyState.POST_CLOSURE_DISCREPANCY,
            first.state());
        assertEquals(SleeperCounterTradeOutcomeCoordinator.ApplyState.POST_CLOSURE_DISCREPANCY,
            second.state());
        var resolution = new SleeperCounterTradeNoActionResolutionRepository(fixture.database())
            .findByClaimId(fixture.claimId()).orElseThrow();
        assertEquals(APPLIED_AT, resolution.resolvedAt());
    }

    @Test
    void durableResolutionRejectsUpdateAndDelete() throws Exception {
        Fixture fixture = fixture("immutable");
        recordNoAction(fixture);
        new SleeperCounterTradeOutcomeCoordinator(fixture.database())
            .apply(completeDecision(fixture), APPLIED_AT);

        try (var connection = fixture.database().openConnection(); var statement = connection.createStatement()) {
            assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate(
                "UPDATE sleeper_counter_trade_no_action_resolutions SET sleeper_transaction_id='other'"
                    + " WHERE claim_id='" + fixture.claimId() + "'"));
            assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate(
                "DELETE FROM sleeper_counter_trade_no_action_resolutions"
                    + " WHERE claim_id='" + fixture.claimId() + "'"));
        }

        var stored = new SleeperCounterTradeNoActionResolutionRepository(fixture.database())
            .findByClaimId(fixture.claimId()).orElseThrow();
        assertEquals("tx-complete", stored.sleeperTransactionId());
        assertEquals(
            SleeperCounterTradeNoActionResolutionRepository.ResolutionType.SUPERSEDED_BY_CONFIRMED_TRADE,
            stored.resolutionType());
    }

    @Test
    void supersededNoActionCannotLaterBeFinalizedFailed() throws Exception {
        Fixture fixture = fixture("no-rewrite");
        recordNoAction(fixture);
        new SleeperCounterTradeOutcomeCoordinator(fixture.database())
            .apply(completeDecision(fixture), APPLIED_AT);

        var noAction = new SleeperManualCounterNoActionOutcomeCoordinator(fixture.database())
            .apply(fixture.claimId(), APPLIED_AT.plusSeconds(10));

        assertEquals(SleeperManualCounterNoActionOutcomeCoordinator.ApplyState.INVALID_STATE,
            noAction.state());
        assertTrue(new SleeperManualCounterNoActionOutcomeCoordinator(fixture.database())
            .findByClaimId(fixture.claimId()).isEmpty());
        assertEquals(TradeCounterExecutionAttemptRepository.State.SUCCEEDED,
            new TradeCounterExecutionAttemptRepository(fixture.database())
                .findByAttemptId(fixture.attemptId()).orElseThrow().state());
    }

    @Test
    void databaseRejectsDirectTradeSuccessWhileNoActionAcknowledgmentIsUnresolved() throws Exception {
        Fixture fixture = fixture("unresolved-success-bypass");
        recordNoAction(fixture);
        new SleeperCounterTradeOutcomeCoordinator(fixture.database()).initialize();

        try (var connection = fixture.database().openConnection();
             var statement = connection.prepareStatement("""
                 INSERT INTO sleeper_counter_trade_terminal_outcomes(
                     outcome_id, coordinator_policy_id, evidence_policy_id,
                     reconciliation_service_id, reconciliation_policy_id,
                     claim_id, handoff_id, attempt_id, grant_id, movement_sha256,
                     sleeper_week, sleeper_transaction_id, terminal_state,
                     grant_disposition, evidence_reason, applied_at)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                 """)) {
            statement.setString(1, "bypass-success");
            statement.setString(2, SleeperCounterTradeOutcomeCoordinator.COORDINATOR_POLICY_ID);
            statement.setString(3, SleeperCounterTradeReconciliationOutcomePolicy.POLICY_ID);
            statement.setString(4, SleeperCounterTradeSnapshotReconciliationService.SERVICE_ID);
            statement.setString(5, SleeperTradeReconciliationPolicy.POLICY_ID);
            statement.setString(6, fixture.claimId());
            statement.setString(7, fixture.handoffId());
            statement.setString(8, fixture.attemptId());
            statement.setString(9, fixture.grantId());
            statement.setString(10, fixture.snapshot().movementSha256());
            statement.setInt(11, 7);
            statement.setString(12, "tx-complete");
            statement.setString(13, "SUCCEEDED");
            statement.setString(14, "CONSUME");
            statement.setString(15, "Attempted direct success bypass without supersession resolution.");
            statement.setString(16, APPLIED_AT.toString());

            assertThrows(java.sql.SQLException.class, statement::executeUpdate);
        }

        assertEquals(TradeCounterExecutionAttemptRepository.State.IN_FLIGHT,
            new TradeCounterExecutionAttemptRepository(fixture.database())
                .findByAttemptId(fixture.attemptId()).orElseThrow().state());
        assertFalse(new TradeCounterAuthorizationGrantRepository(fixture.database())
            .findById(fixture.grantId()).orElseThrow().consumed());
        assertTrue(new SleeperCounterTradeOutcomeCoordinator(fixture.database())
            .findByClaimId(fixture.claimId()).isEmpty());
        assertTrue(new SleeperCounterTradeNoActionResolutionRepository(fixture.database())
            .findByClaimId(fixture.claimId()).isEmpty());
    }

    @Test
    void databaseRejectsNoActionTerminalizationAfterSupersessionResolution() throws Exception {
        Fixture fixture = fixture("superseded-no-action-bypass");
        recordNoAction(fixture);
        new SleeperCounterTradeOutcomeCoordinator(fixture.database()).initialize();
        var acknowledgment = new SleeperManualCounterNoActionAcknowledgmentRepository(fixture.database())
            .findByClaimId(fixture.claimId()).orElseThrow();

        insertSupersessionResolutionOnly(fixture, acknowledgment);

        try (var connection = fixture.database().openConnection();
             var statement = connection.prepareStatement("""
                 INSERT INTO sleeper_manual_counter_no_action_terminal_outcomes(
                     outcome_id, coordinator_policy_id, acknowledgment_journal_policy_id,
                     acknowledgment_policy_id, acknowledgment_id, claim_id, attempt_id, grant_id,
                     handoff_id, payload_sha256, action, destination_type, destination_id,
                     confirmation, acknowledged_at, terminal_state, grant_disposition,
                     evidence_reason, applied_at)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                 """)) {
            statement.setString(1, "bypass-no-action");
            statement.setString(2, SleeperManualCounterNoActionOutcomeCoordinator.COORDINATOR_POLICY_ID);
            statement.setString(3, SleeperManualCounterNoActionAcknowledgmentRepository.JOURNAL_POLICY_ID);
            statement.setString(4, SleeperManualCounterNoActionAcknowledgmentPolicy.POLICY_ID);
            statement.setString(5, acknowledgment.acknowledgmentId());
            statement.setString(6, fixture.claimId());
            statement.setString(7, fixture.attemptId());
            statement.setString(8, fixture.grantId());
            statement.setString(9, fixture.handoffId());
            statement.setString(10, acknowledgment.payloadSha256());
            statement.setString(11, TradeCounterAuthorizationPolicy.Action.SUBMIT_COUNTER_TRADE.name());
            statement.setString(12, TradeCounterAuthorizationPolicy.DestinationType.LEAGUE.name());
            statement.setString(13, acknowledgment.destination().id());
            statement.setString(14, acknowledgment.confirmation());
            statement.setString(15, acknowledgment.acknowledgedAt().toString());
            statement.setString(16, TradeCounterExecutionAttemptRepository.State.FAILED.name());
            statement.setString(17, "CONSUME");
            statement.setString(18, "Attempted direct FAILED bypass after supersession resolution.");
            statement.setString(19, APPLIED_AT.plusSeconds(1).toString());

            assertThrows(java.sql.SQLException.class, statement::executeUpdate);
        }

        assertEquals(TradeCounterExecutionAttemptRepository.State.IN_FLIGHT,
            new TradeCounterExecutionAttemptRepository(fixture.database())
                .findByAttemptId(fixture.attemptId()).orElseThrow().state());
        assertFalse(new TradeCounterAuthorizationGrantRepository(fixture.database())
            .findById(fixture.grantId()).orElseThrow().consumed());
        assertTrue(new SleeperManualCounterNoActionOutcomeCoordinator(fixture.database())
            .findByClaimId(fixture.claimId()).isEmpty());
        var resolution = new SleeperCounterTradeNoActionResolutionRepository(fixture.database())
            .findByClaimId(fixture.claimId()).orElseThrow();
        assertEquals(
            SleeperCounterTradeNoActionResolutionRepository.ResolutionType.SUPERSEDED_BY_CONFIRMED_TRADE,
            resolution.resolutionType());
    }

    private void insertSupersessionResolutionOnly(
        Fixture fixture,
        SleeperManualCounterNoActionAcknowledgmentRepository.StoredAcknowledgment acknowledgment)
        throws Exception {
        try (var connection = fixture.database().openConnection();
             var statement = connection.prepareStatement("""
                 INSERT INTO sleeper_counter_trade_no_action_resolutions(
                     resolution_id, policy_id, acknowledgment_id, no_action_terminal_outcome_id,
                     claim_id, attempt_id, grant_id, handoff_id, payload_sha256, movement_sha256,
                     evidence_policy_id, reconciliation_service_id, reconciliation_policy_id,
                     sleeper_week, sleeper_transaction_id, resolution_type, reason, resolved_at)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                 """)) {
            statement.setString(1, "resolution-only");
            statement.setString(2, SleeperCounterTradeNoActionResolutionRepository.POLICY_ID);
            statement.setString(3, acknowledgment.acknowledgmentId());
            statement.setNull(4, java.sql.Types.VARCHAR);
            statement.setString(5, fixture.claimId());
            statement.setString(6, fixture.attemptId());
            statement.setString(7, fixture.grantId());
            statement.setString(8, fixture.handoffId());
            statement.setString(9, acknowledgment.payloadSha256());
            statement.setString(10, fixture.snapshot().movementSha256());
            statement.setString(11, SleeperCounterTradeReconciliationOutcomePolicy.POLICY_ID);
            statement.setString(12, SleeperCounterTradeSnapshotReconciliationService.SERVICE_ID);
            statement.setString(13, SleeperTradeReconciliationPolicy.POLICY_ID);
            statement.setInt(14, 7);
            statement.setString(15, "tx-complete");
            statement.setString(16,
                SleeperCounterTradeNoActionResolutionRepository.ResolutionType
                    .SUPERSEDED_BY_CONFIRMED_TRADE.name());
            statement.setString(17,
                "Exact completed trade evidence superseded the unfinalized no-action acknowledgment.");
            statement.setString(18, APPLIED_AT.toString());
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void recordNoAction(Fixture fixture) throws Exception {
        var handoff = new SleeperManualCounterHandoffRepository(fixture.database())
            .findByClaimId(fixture.claimId()).orElseThrow();
        var request = new SleeperManualCounterNoActionAcknowledgmentPolicy.AcknowledgmentRequest(
            handoff.grantId(), handoff.handoffId(), handoff.payloadSha256(),
            SleeperManualCounterNoActionAcknowledgmentPolicy.REQUIRED_CONFIRMATION,
            ACKNOWLEDGED_AT);
        var decision = SleeperManualCounterNoActionAcknowledgmentPolicy.acknowledge(handoff, request);
        var recorded = new SleeperManualCounterNoActionAcknowledgmentRepository(fixture.database())
            .record(decision, RECORDED_AT);
        assertTrue(recorded.state() == SleeperManualCounterNoActionAcknowledgmentRepository.RecordState.RECORDED
            || recorded.state() == SleeperManualCounterNoActionAcknowledgmentRepository.RecordState.ALREADY_RECORDED);
        assertEquals(TradeCounterExecutionAttemptRepository.State.IN_FLIGHT,
            new TradeCounterExecutionAttemptRepository(fixture.database())
                .findByAttemptId(fixture.attemptId()).orElseThrow().state());
        assertFalse(new TradeCounterAuthorizationGrantRepository(fixture.database())
            .findById(fixture.grantId()).orElseThrow().consumed());
    }

    private Fixture fixture(String suffix) throws Exception {
        Database database = new Database(tempDir.resolve("bf432-" + suffix + ".db"));
        database.initialize();
        seedSleeperIdentity(database);
        Artifacts artifacts = artifacts();

        var destination = new TradeCounterAuthorizationPolicy.Destination(
            TradeCounterAuthorizationPolicy.DestinationType.LEAGUE, "l1");
        var authorizationRequest = TradeCounterAuthorizationPolicy.request(
            artifacts.identity(), TradeCounterAuthorizationPolicy.Action.SUBMIT_COUNTER_TRADE, destination);
        var grant = TradeCounterAuthorizationPolicy.authorize(
            authorizationRequest, authorizationRequest.requiredConfirmation()).grant();
        var grants = new TradeCounterAuthorizationGrantRepository(database);
        grants.initialize();
        grants.save(grant);

        var readiness = TradeCounterExecutionReadinessPolicy.assess(
            grant, false, true, artifacts.identity());
        var coordinated = new TradeCounterManualHandoffCoordinator(database).coordinate(
            grant, readiness, artifacts.identity(), artifacts.materialized(), artifacts.message(), PRESENTED_AT);
        assertTrue(coordinated.state() == TradeCounterManualHandoffCoordinator.State.HANDOFF_PRESENTED
            || coordinated.state() == TradeCounterManualHandoffCoordinator.State.HANDOFF_ALREADY_PRESENTED);

        var snapshotResult = new SleeperCounterTradeExpectationSnapshotRepository(database).snapshot(
            coordinated.claimId(), "l1", "team-a", "team-b",
            artifacts.materialized().revisedSideA(), artifacts.materialized().revisedSideB(), SNAPSHOTTED_AT);
        assertTrue(snapshotResult.state() == SleeperCounterTradeExpectationSnapshotRepository.State.SNAPSHOTTED
            || snapshotResult.state() == SleeperCounterTradeExpectationSnapshotRepository.State.ALREADY_SNAPSHOTTED);

        return new Fixture(database, grant.grantId(), coordinated.claimId(), coordinated.attemptId(),
            coordinated.handoffId(), snapshotResult.snapshot());
    }

    private static SleeperCounterTradeReconciliationOutcomePolicy.Decision completeDecision(Fixture fixture) {
        var expected = fixture.snapshot().expectedTrade(7, PRESENTED_AT.toEpochMilli());
        var reconciliation = new SleeperTradeReconciliationPolicy.Result(
            SleeperTradeReconciliationPolicy.POLICY_ID,
            SleeperTradeReconciliationPolicy.State.MATCH_COMPLETE,
            expected,
            List.of("tx-complete"),
            false,
            "Exact completed trade matched frozen movement.");
        var report = new SleeperCounterTradeSnapshotReconciliationService.Report(
            SleeperCounterTradeSnapshotReconciliationService.SERVICE_ID,
            SleeperCounterTradeSnapshotReconciliationService.State.RECONCILED,
            fixture.grantId(), fixture.claimId(), fixture.handoffId(),
            fixture.snapshot().movementSha256(), 7, PRESENTED_AT.toEpochMilli(),
            reconciliation, List.of(), "Read evidence evaluated.");
        return SleeperCounterTradeReconciliationOutcomePolicy.classify(report);
    }

    private static void seedSleeperIdentity(Database database) throws Exception {
        new LeagueRepository(database).save(new League("l1", "289646328504385536", "League", 2026));
        var teams = new TeamRepository(database);
        teams.save(new Team("team-a", "1", "l1", "Team A"));
        teams.save(new Team("team-b", "2", "l1", "Team B"));
        var players = new PlayerRepository(database);
        players.save(new Player("p1", "101", "P1", "WR", "CHI"));
        players.save(new Player("p2", "202", "P2", "RB", "DET"));
        players.save(new Player("p3", "303", "P3", "WR", "GB"));
        var rosters = new RosterRepository(database);
        rosters.save(new Roster("r1", null, "team-a", "p1", "STARTER"));
        rosters.save(new Roster("r2", null, "team-b", "p2", "STARTER"));
        rosters.save(new Roster("r3", null, "team-b", "p3", "BENCH"));
    }

    private static Artifacts artifacts() {
        var proposal = new TradeCounterProposalPolicy.Proposal(
            1,
            TradeCounterSingleAssetCandidateAnalyzer.AdjustmentType.ADD_ASSET_TO_LOWER_PACKAGE,
            TradeCounterValueTargetAnalyzer.Side.SIDE_B,
            TradeCounterSingleAssetCandidateAnalyzer.AssetType.PLAYER,
            "p3", "P3", "team-b", "Team B",
            5.0, AS_OF, 4.0, 1.0, 100.0, 104.0, 3.921568627,
            TradeFairnessPolicy.Classification.MARKET_FAIR);
        var proposalResult = new TradeCounterProposalPolicy.Result(
            TradeCounterProposalPolicy.POLICY_ID,
            TradeCounterOpportunityPolicy.POLICY_ID,
            TradeCounterCandidateSelectionPolicy.POLICY_ID,
            "l1", 2026, "source", AS_OF,
            TradeCounterProposalPolicy.Action.COUNTER,
            TradeCounterProposalPolicy.ReasonCode.UNIQUE_SELECTED_CANDIDATE,
            proposal);
        var envelope = TradeCounterProposalEnvelopePolicy.bind(
            proposalResult,
            TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM,
            new TradeAssetAnalyzer.TradePackage(List.of("p1"), List.of()),
            new TradeAssetAnalyzer.TradePackage(List.of("p2"), List.of()));
        var materialized = TradeCounterMaterializedPackagePolicy.materialize(envelope);
        var identity = TradeCounterProposalIdentityPolicy.identify(envelope, materialized);
        var message = TradeCounterNegotiationMessagePolicy.compose(envelope);
        return new Artifacts(materialized, identity, message);
    }

    private record Artifacts(
        TradeCounterMaterializedPackagePolicy.MaterializedCounter materialized,
        TradeCounterProposalIdentityPolicy.Identity identity,
        TradeCounterNegotiationMessagePolicy.MessageResult message) {}

    private record Fixture(
        Database database,
        String grantId,
        String claimId,
        String attemptId,
        String handoffId,
        SleeperCounterTradeExpectationSnapshotRepository.Snapshot snapshot) {}
}
