package io.butler.bet.integration.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.TradeCounterAuthorizationGrantRepository;
import io.butler.bet.data.TradeCounterExecutionAttemptRepository;
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

class SleeperManualMessageOutcomeCoordinatorTest {
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 1);
    private static final Instant PRESENTED_AT = Instant.parse("2026-09-04T23:05:00Z");
    private static final Instant ACKNOWLEDGED_AT = PRESENTED_AT.plusSeconds(20);
    private static final Instant RECORDED_AT = ACKNOWLEDGED_AT.plusSeconds(1);
    private static final Instant APPLIED_AT = RECORDED_AT.plusSeconds(1);

    @TempDir
    Path tempDir;

    @Test
    void durableAcknowledgmentAtomicallyMarksSucceededAndConsumesGrant() throws Exception {
        Fixture fixture = fixture("success", true);
        var coordinator = new SleeperManualMessageOutcomeCoordinator(fixture.database());

        var result = coordinator.apply(fixture.claimId(), APPLIED_AT);

        assertEquals(SleeperManualMessageOutcomeCoordinator.ApplyState.APPLIED, result.state());
        assertEquals(fixture.claimId(), result.outcome().claimId());
        assertEquals(fixture.acknowledgmentId(), result.outcome().acknowledgmentId());
        assertEquals(fixture.handoff().payloadSha256(), result.outcome().payloadSha256());
        assertEquals(TradeCounterExecutionAttemptRepository.State.SUCCEEDED,
            result.outcome().terminalState());
        assertEquals("CONSUME", result.outcome().grantDisposition());

        var attempt = new TradeCounterExecutionAttemptRepository(fixture.database())
            .findByAttemptId(fixture.attemptId()).orElseThrow();
        assertEquals(TradeCounterExecutionAttemptRepository.State.SUCCEEDED, attempt.state());
        assertEquals(APPLIED_AT, attempt.terminalAt());
        assertTrue(attempt.outcomeDetail().contains("manager-22"));

        var grant = new TradeCounterAuthorizationGrantRepository(fixture.database())
            .findById(fixture.grantId()).orElseThrow();
        assertTrue(grant.consumed());
        assertEquals(APPLIED_AT, grant.consumedAt());
    }

    @Test
    void exactRepeatIsIdempotentAndPreservesFirstOutcome() throws Exception {
        Fixture fixture = fixture("repeat", true);
        var coordinator = new SleeperManualMessageOutcomeCoordinator(fixture.database());

        var first = coordinator.apply(fixture.claimId(), APPLIED_AT);
        var second = coordinator.apply(fixture.claimId(), APPLIED_AT.plusSeconds(60));

        assertEquals(SleeperManualMessageOutcomeCoordinator.ApplyState.APPLIED, first.state());
        assertEquals(SleeperManualMessageOutcomeCoordinator.ApplyState.ALREADY_APPLIED, second.state());
        assertEquals(first.outcome().outcomeId(), second.outcome().outcomeId());
        assertEquals(APPLIED_AT, second.outcome().appliedAt());
    }

    @Test
    void missingDurableAcknowledgmentCannotFinalize() throws Exception {
        Fixture fixture = fixture("missing-ack", false);
        var coordinator = new SleeperManualMessageOutcomeCoordinator(fixture.database());

        var result = coordinator.apply(fixture.claimId(), APPLIED_AT);

        assertEquals(SleeperManualMessageOutcomeCoordinator.ApplyState.NOT_FOUND, result.state());
        assertEquals(TradeCounterExecutionAttemptRepository.State.IN_FLIGHT,
            new TradeCounterExecutionAttemptRepository(fixture.database())
                .findByAttemptId(fixture.attemptId()).orElseThrow().state());
        assertFalse(new TradeCounterAuthorizationGrantRepository(fixture.database())
            .findById(fixture.grantId()).orElseThrow().consumed());
    }

    @Test
    void acknowledgmentAloneCannotBypassTerminalOrConsumptionGuards() throws Exception {
        Fixture fixture = fixture("guards", true);
        new SleeperManualMessageOutcomeCoordinator(fixture.database()).initialize();

        try (var connection = fixture.database().openConnection(); var statement = connection.createStatement()) {
            assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate(
                "UPDATE trade_counter_execution_attempts SET state='SUCCEEDED', terminal_at='"
                    + APPLIED_AT + "', outcome_detail='bypass', updated_at='" + APPLIED_AT
                    + "' WHERE attempt_id='" + fixture.attemptId() + "'"));
            assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate(
                "UPDATE trade_counter_authorization_grants SET consumed_at='" + APPLIED_AT
                    + "' WHERE grant_id='" + fixture.grantId() + "'"));
        }

        var applied = new SleeperManualMessageOutcomeCoordinator(fixture.database())
            .apply(fixture.claimId(), APPLIED_AT);
        assertEquals(SleeperManualMessageOutcomeCoordinator.ApplyState.APPLIED, applied.state());
    }

    @Test
    void messageAndTradeTerminalSupportCoexistInSharedGuards() throws Exception {
        Fixture fixture = fixture("coexist", true);
        new SleeperManualMessageOutcomeCoordinator(fixture.database()).initialize();
        new SleeperCounterTradeOutcomeCoordinator(fixture.database()).initialize();
        new SleeperManualMessageOutcomeCoordinator(fixture.database()).initialize();

        String terminal = triggerSql(fixture.database(),
            "trg_trade_counter_execution_terminal_outcome_required");
        String consumption = triggerSql(fixture.database(),
            "trg_trade_counter_execution_claimed_grant_consumption_guard");

        assertTrue(terminal.contains("sleeper_manual_message_terminal_outcomes"));
        assertTrue(terminal.contains("sleeper_counter_trade_terminal_outcomes"));
        assertTrue(consumption.contains("sleeper_manual_message_terminal_outcomes"));
        assertTrue(consumption.contains("sleeper_counter_trade_terminal_outcomes"));
        assertTrue(consumption.contains("trade_counter_execution_unknown_resolutions"));
    }

    @Test
    void terminalOutcomeIsImmutable() throws Exception {
        Fixture fixture = fixture("immutable", true);
        var coordinator = new SleeperManualMessageOutcomeCoordinator(fixture.database());
        coordinator.apply(fixture.claimId(), APPLIED_AT);

        try (var connection = fixture.database().openConnection(); var statement = connection.createStatement()) {
            assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate(
                "UPDATE sleeper_manual_message_terminal_outcomes SET destination_id='other'"
                    + " WHERE claim_id='" + fixture.claimId() + "'"));
        }
    }

    private Fixture fixture(String suffix, boolean recordAcknowledgment) throws Exception {
        Database database = new Database(tempDir.resolve("bf416-" + suffix + ".db"));
        database.initialize();
        Artifacts artifacts = artifacts();

        var destination = new TradeCounterAuthorizationPolicy.Destination(
            TradeCounterAuthorizationPolicy.DestinationType.MANAGER, "manager-22");
        var authorizationRequest = TradeCounterAuthorizationPolicy.request(
            artifacts.identity(), TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE, destination);
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

        var handoff = new SleeperManualCounterHandoffRepository(database)
            .findByClaimId(coordinated.claimId()).orElseThrow();
        String acknowledgmentId = null;
        if (recordAcknowledgment) {
            var request = new SleeperManualMessageAcknowledgmentPolicy.AcknowledgmentRequest(
                handoff.grantId(), handoff.handoffId(), handoff.payloadSha256(),
                SleeperManualMessageAcknowledgmentPolicy.REQUIRED_CONFIRMATION,
                ACKNOWLEDGED_AT);
            var decision = SleeperManualMessageAcknowledgmentPolicy.acknowledge(handoff, request);
            var recorded = new SleeperManualMessageAcknowledgmentRepository(database)
                .record(decision, RECORDED_AT);
            assertTrue(recorded.state() == SleeperManualMessageAcknowledgmentRepository.RecordState.RECORDED
                || recorded.state() == SleeperManualMessageAcknowledgmentRepository.RecordState.ALREADY_RECORDED);
            acknowledgmentId = recorded.acknowledgment().acknowledgmentId();
        }

        return new Fixture(
            database,
            grant.grantId(),
            coordinated.claimId(),
            coordinated.attemptId(),
            handoff,
            acknowledgmentId);
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

    private static String triggerSql(Database database, String trigger) throws Exception {
        try (var connection = database.openConnection();
             var statement = connection.prepareStatement(
                 "SELECT sql FROM sqlite_master WHERE type='trigger' AND name=?")) {
            statement.setString(1, trigger);
            try (var rs = statement.executeQuery()) {
                if (!rs.next()) throw new AssertionError("missing trigger " + trigger);
                return rs.getString(1);
            }
        }
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
        SleeperManualCounterHandoffRepository.PresentedHandoff handoff,
        String acknowledgmentId) {}
}
