package io.butler.bet.execution;

import io.butler.bet.data.Database;
import io.butler.bet.data.TradeCounterAuthorizationGrantRepository;
import io.butler.bet.data.TradeCounterExecutionAttemptRepository;
import io.butler.bet.data.TradeCounterExecutionClaimRepository;
import io.butler.bet.integration.sleeper.SleeperManualCounterHandoffRepository;
import io.butler.bet.integration.sleeper.SleeperManualCounterHandoffService;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeCounterManualHandoffCoordinatorTest {
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 1);
    private static final Instant AT = Instant.parse("2026-09-04T21:05:00Z");

    @TempDir
    Path tempDir;

    @Test
    void readyMessageCoordinatesPrepareClaimAndDurablePresentationWithoutConsumingGrant() throws Exception {
        var fixture = fixture(TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE);
        var result = fixture.coordinator().coordinate(
            fixture.grant(), fixture.readiness(), fixture.artifacts().identity(),
            fixture.artifacts().materialized(), fixture.artifacts().message(), AT);

        assertEquals(TradeCounterManualHandoffCoordinator.State.HANDOFF_PRESENTED, result.state());
        assertEquals(TradeCounterExecutionAttemptRepository.PayloadKind.NEGOTIATION_MESSAGE_TEXT,
            result.payloadKind());

        var attempt = fixture.attempts().findByGrantId(fixture.grant().grantId()).orElseThrow();
        assertEquals(TradeCounterExecutionAttemptRepository.State.IN_FLIGHT, attempt.state());
        assertEquals(fixture.artifacts().message().text(), attempt.payloadText());
        assertEquals(result.payloadSha256(), attempt.payloadSha256());

        var claim = fixture.claims().findByAttemptId(attempt.attemptId()).orElseThrow();
        assertEquals(result.claimId(), claim.claimId());
        var handoff = fixture.handoffs().findByClaimId(claim.claimId()).orElseThrow();
        assertEquals(result.handoffId(), handoff.handoffId());
        assertEquals(AT, handoff.presentedAt());
        assertEquals(SleeperManualCounterHandoffService.ReconciliationMode.NO_OFFICIAL_READBACK,
            handoff.reconciliationMode());
        assertFalse(fixture.grants().findById(fixture.grant().grantId()).orElseThrow().consumed());
    }

    @Test
    void exactRepeatRecoversSameAttemptClaimAndFirstPresentationBoundary() throws Exception {
        var fixture = fixture(TradeCounterAuthorizationPolicy.Action.SUBMIT_COUNTER_TRADE);
        var first = fixture.coordinator().coordinate(
            fixture.grant(), fixture.readiness(), fixture.artifacts().identity(),
            fixture.artifacts().materialized(), fixture.artifacts().message(), AT);
        var second = fixture.coordinator().coordinate(
            fixture.grant(), fixture.readiness(), fixture.artifacts().identity(),
            fixture.artifacts().materialized(), fixture.artifacts().message(), AT.plusSeconds(120));

        assertEquals(TradeCounterManualHandoffCoordinator.State.HANDOFF_PRESENTED, first.state());
        assertEquals(TradeCounterManualHandoffCoordinator.State.HANDOFF_ALREADY_PRESENTED, second.state());
        assertEquals(first.attemptId(), second.attemptId());
        assertEquals(first.claimId(), second.claimId());
        assertEquals(first.handoffId(), second.handoffId());
        assertEquals(TradeCounterExecutionAttemptRepository.PayloadKind.COUNTER_TRADE_REQUEST_JSON,
            second.payloadKind());

        var handoff = fixture.handoffs().findByClaimId(second.claimId()).orElseThrow();
        assertEquals(AT, handoff.presentedAt());
        assertEquals(SleeperManualCounterHandoffService.ReconciliationMode.SLEEPER_TRANSACTION_READBACK,
            handoff.reconciliationMode());
        assertTrue(fixture.attempts().findByGrantId(fixture.grant().grantId()).orElseThrow()
            .payloadText().contains("\"schema\":\"butler-counter-trade-request-v1\""));
        assertFalse(fixture.grants().findById(fixture.grant().grantId()).orElseThrow().consumed());
    }

    @Test
    void nonReadyFreshProposalCreatesNoExecutionAttempt() throws Exception {
        var authorized = artifacts("p3", "Player Three");
        var drifted = artifacts("p4", "Player Four");
        var database = database();
        var grants = new TradeCounterAuthorizationGrantRepository(database);
        grants.initialize();
        var grant = grant(authorized.identity(), TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE);
        grants.save(grant);
        var readiness = TradeCounterExecutionReadinessPolicy.assess(grant, false, true, drifted.identity());
        var attempts = new TradeCounterExecutionAttemptRepository(database);

        var result = new TradeCounterManualHandoffCoordinator(database).coordinate(
            grant, readiness, drifted.identity(), drifted.materialized(), drifted.message(), AT);

        assertEquals(TradeCounterManualHandoffCoordinator.State.READINESS_NOT_READY, result.state());
        assertTrue(attempts.findByGrantId(grant.grantId()).isEmpty());
        assertFalse(grants.findById(grant.grantId()).orElseThrow().consumed());
    }

    private Fixture fixture(TradeCounterAuthorizationPolicy.Action action) throws Exception {
        var database = database();
        var artifacts = artifacts("p3", "Player Three");
        var grant = grant(artifacts.identity(), action);
        var grants = new TradeCounterAuthorizationGrantRepository(database);
        grants.initialize();
        grants.save(grant);
        var readiness = TradeCounterExecutionReadinessPolicy.assess(grant, false, true, artifacts.identity());
        return new Fixture(
            new TradeCounterManualHandoffCoordinator(database),
            grants,
            new TradeCounterExecutionAttemptRepository(database),
            new TradeCounterExecutionClaimRepository(database),
            new SleeperManualCounterHandoffRepository(database),
            grant,
            readiness,
            artifacts);
    }

    private Database database() throws Exception {
        var database = new Database(tempDir.resolve("butler.db"));
        database.initialize();
        return database;
    }

    private static TradeCounterAuthorizationPolicy.AuthorizationGrant grant(
        TradeCounterProposalIdentityPolicy.Identity identity,
        TradeCounterAuthorizationPolicy.Action action) {
        var destination = action == TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE
            ? new TradeCounterAuthorizationPolicy.Destination(
                TradeCounterAuthorizationPolicy.DestinationType.MANAGER, "manager-22")
            : new TradeCounterAuthorizationPolicy.Destination(
                TradeCounterAuthorizationPolicy.DestinationType.LEAGUE, "l1");
        var request = TradeCounterAuthorizationPolicy.request(identity, action, destination);
        return TradeCounterAuthorizationPolicy.authorize(request, request.requiredConfirmation()).grant();
    }

    private static Artifacts artifacts(String addedPlayerId, String displayName) {
        var proposal = new TradeCounterProposalPolicy.Proposal(
            1,
            TradeCounterSingleAssetCandidateAnalyzer.AdjustmentType.ADD_ASSET_TO_LOWER_PACKAGE,
            TradeCounterValueTargetAnalyzer.Side.SIDE_B,
            TradeCounterSingleAssetCandidateAnalyzer.AssetType.PLAYER,
            addedPlayerId,
            displayName,
            "team-b",
            "Team B",
            5.0,
            AS_OF,
            4.0,
            1.0,
            100.0,
            104.0,
            3.921568627,
            TradeFairnessPolicy.Classification.MARKET_FAIR);
        var result = new TradeCounterProposalPolicy.Result(
            TradeCounterProposalPolicy.POLICY_ID,
            TradeCounterOpportunityPolicy.POLICY_ID,
            TradeCounterCandidateSelectionPolicy.POLICY_ID,
            "l1", 2026, "source", AS_OF,
            TradeCounterProposalPolicy.Action.COUNTER,
            TradeCounterProposalPolicy.ReasonCode.UNIQUE_SELECTED_CANDIDATE,
            proposal);
        var envelope = TradeCounterProposalEnvelopePolicy.bind(
            result,
            TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM,
            new TradeAssetAnalyzer.TradePackage(List.of("p1"), List.of("k1")),
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
        TradeCounterManualHandoffCoordinator coordinator,
        TradeCounterAuthorizationGrantRepository grants,
        TradeCounterExecutionAttemptRepository attempts,
        TradeCounterExecutionClaimRepository claims,
        SleeperManualCounterHandoffRepository handoffs,
        TradeCounterAuthorizationPolicy.AuthorizationGrant grant,
        TradeCounterExecutionReadinessPolicy.Result readiness,
        Artifacts artifacts) {}
}
