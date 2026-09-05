package io.butler.bet.integration.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.RosterRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.data.TradeCounterAuthorizationGrantRepository;
import io.butler.bet.data.TradeCounterExecutionAttemptRepository;
import io.butler.bet.data.TradeCounterExecutionOutcomeCoordinator;
import io.butler.bet.data.TradeCounterExecutionRequestRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.Player;
import io.butler.bet.domain.Roster;
import io.butler.bet.domain.Team;
import io.butler.bet.execution.TradeCounterActionExecutor;
import io.butler.bet.execution.TradeCounterExecutionOutcomePolicy;
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

class SleeperCounterTradeOutcomeCoordinatorTest {
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 1);
    private static final Instant PRESENTED_AT = Instant.parse("2026-09-04T21:20:00Z");
    private static final Instant SNAPSHOTTED_AT = PRESENTED_AT.plusSeconds(1);
    private static final Instant APPLIED_AT = PRESENTED_AT.plusSeconds(30);

    @TempDir
    Path tempDir;

    @Test
    void exactCompleteEvidenceAtomicallyMarksSucceededAndConsumesGrant() throws Exception {
        Fixture fixture = fixture("success");
        var coordinator = new SleeperCounterTradeOutcomeCoordinator(fixture.database());
        var decision = decision(fixture, SleeperTradeReconciliationPolicy.State.MATCH_COMPLETE);

        var applied = coordinator.apply(decision, APPLIED_AT);

        assertEquals(SleeperCounterTradeOutcomeCoordinator.ApplyState.APPLIED, applied.state());
        assertEquals("tx-complete", applied.outcome().sleeperTransactionId());
        assertEquals(7, applied.outcome().sleeperWeek());
        assertEquals(TradeCounterExecutionAttemptRepository.State.SUCCEEDED,
            applied.outcome().terminalState());

        var attempt = new TradeCounterExecutionAttemptRepository(fixture.database())
            .findByAttemptId(fixture.attemptId()).orElseThrow();
        assertEquals(TradeCounterExecutionAttemptRepository.State.SUCCEEDED, attempt.state());
        assertEquals(APPLIED_AT, attempt.terminalAt());
        assertTrue(attempt.outcomeDetail().contains("tx-complete"));

        var grant = new TradeCounterAuthorizationGrantRepository(fixture.database())
            .findById(fixture.grantId()).orElseThrow();
        assertTrue(grant.consumed());
        assertEquals(APPLIED_AT, grant.consumedAt());
    }

    @Test
    void exactRepeatIsIdempotent() throws Exception {
        Fixture fixture = fixture("repeat");
        var coordinator = new SleeperCounterTradeOutcomeCoordinator(fixture.database());
        var decision = decision(fixture, SleeperTradeReconciliationPolicy.State.MATCH_COMPLETE);

        var first = coordinator.apply(decision, APPLIED_AT);
        var second = coordinator.apply(decision, APPLIED_AT.plusSeconds(60));

        assertEquals(SleeperCounterTradeOutcomeCoordinator.ApplyState.APPLIED, first.state());
        assertEquals(SleeperCounterTradeOutcomeCoordinator.ApplyState.ALREADY_APPLIED, second.state());
        assertEquals(first.outcome().outcomeId(), second.outcome().outcomeId());
        assertEquals(APPLIED_AT, second.outcome().appliedAt());
    }

    @Test
    void differentCompletedEvidenceAfterFinalizationReturnsMismatchAndPreservesOriginal() throws Exception {
        Fixture fixture = fixture("different-completed-evidence");
        var coordinator = new SleeperCounterTradeOutcomeCoordinator(fixture.database());
        var original = decision(fixture, SleeperTradeReconciliationPolicy.State.MATCH_COMPLETE);
        var first = coordinator.apply(original, APPLIED_AT);
        var conflicting = new SleeperCounterTradeReconciliationOutcomePolicy.Decision(
            original.policyId(),
            original.reconciliationServiceId(),
            original.reconciliationPolicyId(),
            original.grantId(),
            original.claimId(),
            original.handoffId(),
            original.movementSha256(),
            original.week(),
            original.state(),
            original.reasonCode(),
            original.terminalOutcomeEligibility(),
            List.of("tx-other"),
            "A different completed transaction was later presented as evidence.");

        var second = coordinator.apply(conflicting, APPLIED_AT.plusSeconds(60));

        assertEquals(SleeperCounterTradeOutcomeCoordinator.ApplyState.APPLIED, first.state());
        assertEquals(SleeperCounterTradeOutcomeCoordinator.ApplyState.MISMATCH, second.state());
        assertEquals(first.outcome().outcomeId(), second.outcome().outcomeId());
        assertEquals("tx-complete", second.outcome().sleeperTransactionId());
        assertEquals(APPLIED_AT, second.outcome().appliedAt());
        assertEquals("tx-complete",
            coordinator.findByClaimId(fixture.claimId()).orElseThrow().sleeperTransactionId());
        assertEquals(TradeCounterExecutionAttemptRepository.State.SUCCEEDED,
            new TradeCounterExecutionAttemptRepository(fixture.database())
                .findByAttemptId(fixture.attemptId()).orElseThrow().state());
        assertTrue(new TradeCounterAuthorizationGrantRepository(fixture.database())
            .findById(fixture.grantId()).orElseThrow().consumed());
    }

    @Test
    void pendingAndNoMatchEvidenceNeverMutateAttemptOrGrant() throws Exception {
        for (var state : List.of(
            SleeperTradeReconciliationPolicy.State.MATCH_PENDING,
            SleeperTradeReconciliationPolicy.State.NO_MATCH)) {
            Fixture fixture = fixture("nonterminal-" + state.name());
            var coordinator = new SleeperCounterTradeOutcomeCoordinator(fixture.database());

            var result = coordinator.apply(decision(fixture, state), APPLIED_AT);

            assertEquals(SleeperCounterTradeOutcomeCoordinator.ApplyState.NOT_ELIGIBLE, result.state());
            assertEquals(TradeCounterExecutionAttemptRepository.State.IN_FLIGHT,
                new TradeCounterExecutionAttemptRepository(fixture.database())
                    .findByAttemptId(fixture.attemptId()).orElseThrow().state());
            assertFalse(new TradeCounterAuthorizationGrantRepository(fixture.database())
                .findById(fixture.grantId()).orElseThrow().consumed());
            assertTrue(coordinator.findByClaimId(fixture.claimId()).isEmpty());
        }
    }

    @Test
    void mismatchedFrozenMovementFailsClosed() throws Exception {
        Fixture fixture = fixture("mismatch");
        var original = decision(fixture, SleeperTradeReconciliationPolicy.State.MATCH_COMPLETE);
        var mismatched = new SleeperCounterTradeReconciliationOutcomePolicy.Decision(
            original.policyId(), original.reconciliationServiceId(), original.reconciliationPolicyId(),
            original.grantId(), original.claimId(), original.handoffId(), "b".repeat(64),
            original.week(), original.state(), original.reasonCode(),
            original.terminalOutcomeEligibility(), original.transactionIds(), original.reason());

        var result = new SleeperCounterTradeOutcomeCoordinator(fixture.database())
            .apply(mismatched, APPLIED_AT);

        assertEquals(SleeperCounterTradeOutcomeCoordinator.ApplyState.MISMATCH, result.state());
        assertEquals(TradeCounterExecutionAttemptRepository.State.IN_FLIGHT,
            new TradeCounterExecutionAttemptRepository(fixture.database())
                .findByAttemptId(fixture.attemptId()).orElseThrow().state());
        assertFalse(new TradeCounterAuthorizationGrantRepository(fixture.database())
            .findById(fixture.grantId()).orElseThrow().consumed());
    }

    @Test
    void databaseGuardsRejectDirectSuccessOrConsumptionWithoutDurableOutcome() throws Exception {
        Fixture fixture = fixture("guards");
        new SleeperCounterTradeOutcomeCoordinator(fixture.database()).initialize();

        try (var connection = fixture.database().openConnection(); var statement = connection.createStatement()) {
            assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate(
                "UPDATE trade_counter_execution_attempts SET state='SUCCEEDED', terminal_at='"
                    + APPLIED_AT + "', outcome_detail='bypass', updated_at='" + APPLIED_AT
                    + "' WHERE attempt_id='" + fixture.attemptId() + "'"));
            assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate(
                "UPDATE trade_counter_authorization_grants SET consumed_at='" + APPLIED_AT
                    + "' WHERE grant_id='" + fixture.grantId() + "'"));
        }
    }

    @Test
    void originalBf395ExecutorOutcomeStillWorksAfterGuardUpgrade() throws Exception {
        Fixture fixture = fixture("compatibility");
        new SleeperCounterTradeOutcomeCoordinator(fixture.database()).initialize();

        var request = new TradeCounterExecutionRequestRepository(fixture.database())
            .findByClaimId(fixture.claimId()).orElseThrow();
        var executorResult = new TradeCounterActionExecutor.ExecutionResult(
            "test-live-executor",
            TradeCounterActionExecutor.Mode.LIVE,
            TradeCounterActionExecutor.State.DISPATCHED,
            request.claimId(), request.attemptId(), request.grantId(), request.payloadSha256(),
            "Platform accepted the action.");
        var directive = TradeCounterExecutionOutcomePolicy.classify(request, executorResult);

        var applied = new TradeCounterExecutionOutcomeCoordinator(fixture.database())
            .apply(directive, APPLIED_AT);

        assertEquals(TradeCounterExecutionOutcomeCoordinator.ApplyState.APPLIED, applied.state());
        assertEquals(TradeCounterExecutionAttemptRepository.State.SUCCEEDED,
            new TradeCounterExecutionAttemptRepository(fixture.database())
                .findByAttemptId(fixture.attemptId()).orElseThrow().state());
        assertTrue(new TradeCounterAuthorizationGrantRepository(fixture.database())
            .findById(fixture.grantId()).orElseThrow().consumed());
    }

    private Fixture fixture(String suffix) throws Exception {
        Database database = new Database(tempDir.resolve("bf410-" + suffix + ".db"));
        database.initialize();
        seedSleeperIdentity(database);
        Artifacts artifacts = artifacts();

        var destination = new TradeCounterAuthorizationPolicy.Destination(
            TradeCounterAuthorizationPolicy.DestinationType.LEAGUE, "l1");
        var request = TradeCounterAuthorizationPolicy.request(
            artifacts.identity(), TradeCounterAuthorizationPolicy.Action.SUBMIT_COUNTER_TRADE, destination);
        var grant = TradeCounterAuthorizationPolicy.authorize(
            request, request.requiredConfirmation()).grant();
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

        return new Fixture(
            database,
            grant.grantId(),
            coordinated.claimId(),
            coordinated.attemptId(),
            coordinated.handoffId(),
            snapshotResult.snapshot(),
            artifacts);
    }

    private static SleeperCounterTradeReconciliationOutcomePolicy.Decision decision(
        Fixture fixture,
        SleeperTradeReconciliationPolicy.State state) {
        List<String> ids = switch (state) {
            case MATCH_COMPLETE -> List.of("tx-complete");
            case MATCH_PENDING -> List.of("tx-pending");
            case NO_MATCH -> List.of();
            case AMBIGUOUS -> List.of("tx-1", "tx-2");
            case INCONCLUSIVE -> List.of("tx-weird");
        };
        boolean incomplete = state == SleeperTradeReconciliationPolicy.State.INCONCLUSIVE;
        var expected = fixture.snapshot().expectedTrade(7, PRESENTED_AT.toEpochMilli());
        var reconciliation = new SleeperTradeReconciliationPolicy.Result(
            SleeperTradeReconciliationPolicy.POLICY_ID,
            state,
            expected,
            ids,
            incomplete,
            "Evidence state " + state + ".");
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
        SleeperCounterTradeExpectationSnapshotRepository.Snapshot snapshot,
        Artifacts artifacts) {}
}
