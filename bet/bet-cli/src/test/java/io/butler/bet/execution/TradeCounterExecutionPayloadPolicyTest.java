package io.butler.bet.execution;

import io.butler.bet.data.TradeCounterExecutionAttemptRepository;
import io.butler.bet.intelligence.TradeAssetAnalyzer;
import io.butler.bet.intelligence.TradeCounterAuthorizationPolicy;
import io.butler.bet.intelligence.TradeCounterCandidateSelectionPolicy;
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

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeCounterExecutionPayloadPolicyTest {
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 1);

    @Test
    void messagePayloadUsesExactGovernedNegotiationTextAfterFreshFingerprintMatch() {
        var artifacts = artifacts("p3", "Player Three");
        var grant = grant(
            artifacts.identity(),
            TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE,
            new TradeCounterAuthorizationPolicy.Destination(
                TradeCounterAuthorizationPolicy.DestinationType.MANAGER, "manager-22"));

        var result = TradeCounterExecutionPayloadPolicy.materialize(
            grant, artifacts.identity(), artifacts.materialized(), artifacts.message());

        assertEquals(TradeCounterExecutionPayloadPolicy.State.PAYLOAD_AVAILABLE, result.state());
        assertEquals(TradeCounterExecutionPayloadPolicy.ReasonCode.GOVERNED_NEGOTIATION_MESSAGE_PAYLOAD,
            result.reasonCode());
        assertEquals(TradeCounterExecutionAttemptRepository.PayloadKind.NEGOTIATION_MESSAGE_TEXT,
            result.payload().payloadKind());
        assertEquals(artifacts.message().text(), result.payload().payloadText());
        assertEquals(grant.grantId(), result.payload().grantId());
        assertEquals(artifacts.identity().fingerprint(), result.payload().proposalFingerprint());
    }

    @Test
    void tradePayloadIsDeterministicButlerManualHandoffJsonNotSleeperApiShape() {
        var artifacts = artifacts("p3", "Player Three");
        var grant = grant(
            artifacts.identity(),
            TradeCounterAuthorizationPolicy.Action.SUBMIT_COUNTER_TRADE,
            new TradeCounterAuthorizationPolicy.Destination(
                TradeCounterAuthorizationPolicy.DestinationType.LEAGUE, "l1"));

        var result = TradeCounterExecutionPayloadPolicy.materialize(
            grant, artifacts.identity(), artifacts.materialized(), artifacts.message());

        assertEquals(TradeCounterExecutionPayloadPolicy.State.PAYLOAD_AVAILABLE, result.state());
        assertEquals(TradeCounterExecutionPayloadPolicy.ReasonCode.GOVERNED_COUNTER_TRADE_PAYLOAD,
            result.reasonCode());
        assertEquals(TradeCounterExecutionAttemptRepository.PayloadKind.COUNTER_TRADE_REQUEST_JSON,
            result.payload().payloadKind());
        String json = result.payload().payloadText();
        assertTrue(json.startsWith("{\"schema\":\"butler-counter-trade-request-v1\""));
        assertTrue(json.contains("\"proposalFingerprint\":\"" + artifacts.identity().fingerprint() + "\""));
        assertTrue(json.contains("\"leagueId\":\"l1\""));
        assertTrue(json.contains("\"destinationLeagueId\":\"l1\""));
        assertTrue(json.contains("\"sideA\":{\"players\":[\"p1\"],\"draftPicks\":[\"k1\"]}"));
        assertTrue(json.contains("\"sideB\":{\"players\":[\"p2\",\"p3\"],\"draftPicks\":[]}"));
        assertTrue(!json.contains("api.sleeper.app"));
    }

    @Test
    void driftedFreshProposalCannotMaterializeAuthorizedPayload() {
        var authorized = artifacts("p3", "Player Three");
        var drifted = artifacts("p4", "Player Four");
        var grant = grant(
            authorized.identity(),
            TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE,
            new TradeCounterAuthorizationPolicy.Destination(
                TradeCounterAuthorizationPolicy.DestinationType.MANAGER, "manager-22"));

        var result = TradeCounterExecutionPayloadPolicy.materialize(
            grant, drifted.identity(), drifted.materialized(), drifted.message());

        assertEquals(TradeCounterExecutionPayloadPolicy.State.NOT_AVAILABLE, result.state());
        assertEquals(TradeCounterExecutionPayloadPolicy.ReasonCode.FRESH_PROPOSAL_DRIFTED,
            result.reasonCode());
        assertNull(result.payload());
    }

    private static TradeCounterAuthorizationPolicy.AuthorizationGrant grant(
        TradeCounterProposalIdentityPolicy.Identity identity,
        TradeCounterAuthorizationPolicy.Action action,
        TradeCounterAuthorizationPolicy.Destination destination) {
        var request = TradeCounterAuthorizationPolicy.request(identity, action, destination);
        return TradeCounterAuthorizationPolicy.authorize(
            request, request.requiredConfirmation()).grant();
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
            "l1",
            2026,
            "source",
            AS_OF,
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
}
