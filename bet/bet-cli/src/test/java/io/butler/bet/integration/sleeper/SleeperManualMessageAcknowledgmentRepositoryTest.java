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

class SleeperManualMessageAcknowledgmentRepositoryTest {
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 1);
    private static final Instant PRESENTED_AT = Instant.parse("2026-09-04T22:45:00Z");
    private static final Instant ACKNOWLEDGED_AT = PRESENTED_AT.plusSeconds(20);
    private static final Instant RECORDED_AT = ACKNOWLEDGED_AT.plusSeconds(1);

    @TempDir
    Path tempDir;

    @Test
    void exactAcknowledgmentPersistsWithoutTerminalizingAttemptOrGrant() throws Exception {
        Fixture fixture = fixture("record");
        var repository = new SleeperManualMessageAcknowledgmentRepository(fixture.database());

        var result = repository.record(fixture.decision(), RECORDED_AT);

        assertEquals(SleeperManualMessageAcknowledgmentRepository.RecordState.RECORDED, result.state());
        assertEquals(fixture.claimId(), result.acknowledgment().claimId());
        assertEquals(fixture.handoff().payloadSha256(), result.acknowledgment().payloadSha256());
        assertEquals(ACKNOWLEDGED_AT, result.acknowledgment().acknowledgedAt());
        assertEquals(RECORDED_AT, result.acknowledgment().recordedAt());
        assertEquals(
            TradeCounterExecutionAttemptRepository.State.IN_FLIGHT,
            new TradeCounterExecutionAttemptRepository(fixture.database())
                .findByAttemptId(fixture.attemptId()).orElseThrow().state());
        assertFalse(new TradeCounterAuthorizationGrantRepository(fixture.database())
            .findById(fixture.grantId()).orElseThrow().consumed());
    }

    @Test
    void exactRepeatIsIdempotentAndPreservesFirstRecord() throws Exception {
        Fixture fixture = fixture("repeat");
        var repository = new SleeperManualMessageAcknowledgmentRepository(fixture.database());

        var first = repository.record(fixture.decision(), RECORDED_AT);
        var second = repository.record(fixture.decision(), RECORDED_AT.plusSeconds(60));

        assertEquals(SleeperManualMessageAcknowledgmentRepository.RecordState.RECORDED, first.state());
        assertEquals(SleeperManualMessageAcknowledgmentRepository.RecordState.ALREADY_RECORDED, second.state());
        assertEquals(first.acknowledgment().acknowledgmentId(), second.acknowledgment().acknowledgmentId());
        assertEquals(RECORDED_AT, second.acknowledgment().recordedAt());
    }

    @Test
    void nonAcknowledgedEvidenceIsNotPersisted() throws Exception {
        Fixture fixture = fixture("not-eligible");
        var handoff = fixture.handoff();
        var request = new SleeperManualMessageAcknowledgmentPolicy.AcknowledgmentRequest(
            handoff.grantId(), handoff.handoffId(), handoff.payloadSha256(),
            "not-confirmed", ACKNOWLEDGED_AT);
        var decision = SleeperManualMessageAcknowledgmentPolicy.acknowledge(handoff, request);
        var repository = new SleeperManualMessageAcknowledgmentRepository(fixture.database());

        var result = repository.record(decision, RECORDED_AT);

        assertEquals(SleeperManualMessageAcknowledgmentRepository.RecordState.NOT_ELIGIBLE, result.state());
        assertTrue(repository.findByClaimId(fixture.claimId()).isEmpty());
        assertEquals(
            TradeCounterExecutionAttemptRepository.State.IN_FLIGHT,
            new TradeCounterExecutionAttemptRepository(fixture.database())
                .findByAttemptId(fixture.attemptId()).orElseThrow().state());
        assertFalse(new TradeCounterAuthorizationGrantRepository(fixture.database())
            .findById(fixture.grantId()).orElseThrow().consumed());
    }

    @Test
    void mismatchedPayloadEvidenceFailsClosed() throws Exception {
        Fixture fixture = fixture("mismatch");
        var original = fixture.decision();
        var mismatched = new SleeperManualMessageAcknowledgmentPolicy.Decision(
            original.policyId(),
            original.handoffJournalPolicyId(),
            original.handoffServiceId(),
            original.claimId(),
            original.attemptId(),
            original.grantId(),
            original.handoffId(),
            "c".repeat(64),
            original.destination(),
            original.presentedAt(),
            original.suppliedConfirmation(),
            original.state(),
            original.reasonCode(),
            original.localCompletionEligibility(),
            original.acknowledgedAt(),
            original.reason());

        var result = new SleeperManualMessageAcknowledgmentRepository(fixture.database())
            .record(mismatched, RECORDED_AT);

        assertEquals(SleeperManualMessageAcknowledgmentRepository.RecordState.MISMATCH, result.state());
        assertFalse(new TradeCounterAuthorizationGrantRepository(fixture.database())
            .findById(fixture.grantId()).orElseThrow().consumed());
    }

    @Test
    void durableAcknowledgmentIsImmutable() throws Exception {
        Fixture fixture = fixture("immutable");
        var repository = new SleeperManualMessageAcknowledgmentRepository(fixture.database());
        repository.record(fixture.decision(), RECORDED_AT);

        try (var connection = fixture.database().openConnection(); var statement = connection.createStatement()) {
            assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate(
                "UPDATE sleeper_manual_message_acknowledgments SET destination_id='other'"
                    + " WHERE claim_id='" + fixture.claimId() + "'"));
        }
    }

    private Fixture fixture(String suffix) throws Exception {
        Database database = new Database(tempDir.resolve("bf414-" + suffix + ".db"));
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
        var acknowledgmentRequest = new SleeperManualMessageAcknowledgmentPolicy.AcknowledgmentRequest(
            handoff.grantId(), handoff.handoffId(), handoff.payloadSha256(),
            SleeperManualMessageAcknowledgmentPolicy.REQUIRED_CONFIRMATION,
            ACKNOWLEDGED_AT);
        var decision = SleeperManualMessageAcknowledgmentPolicy.acknowledge(
            handoff, acknowledgmentRequest);
        assertEquals(SleeperManualMessageAcknowledgmentPolicy.State.ACKNOWLEDGED, decision.state());

        return new Fixture(
            database,
            grant.grantId(),
            coordinated.claimId(),
            coordinated.attemptId(),
            handoff,
            decision);
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
        SleeperManualCounterHandoffRepository.PresentedHandoff handoff,
        SleeperManualMessageAcknowledgmentPolicy.Decision decision) {}
}
