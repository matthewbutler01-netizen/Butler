package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TradeCounterNegotiationMessagePolicyTest {
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 1);

    @Test
    void rendersOtherManagerAddWhenProposalTouchesOppositeSide() {
        var envelope = envelope(
            TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM,
            TradeCounterSingleAssetCandidateAnalyzer.AdjustmentType.ADD_ASSET_TO_LOWER_PACKAGE,
            TradeCounterValueTargetAnalyzer.Side.SIDE_B,
            "p3",
            "Player Three");

        var message = TradeCounterNegotiationMessagePolicy.compose(envelope);

        assertEquals(TradeCounterNegotiationMessagePolicy.State.MESSAGE_AVAILABLE, message.state());
        assertEquals(TradeCounterNegotiationMessagePolicy.Actor.OTHER_MANAGER, message.actor());
        assertEquals("I'd counter if you add Player Three to your side of the deal.", message.text());
    }

    @Test
    void rendersMyAddWhenProposalTouchesPerspectiveSide() {
        var envelope = envelope(
            TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_B_TEAM,
            TradeCounterSingleAssetCandidateAnalyzer.AdjustmentType.ADD_ASSET_TO_LOWER_PACKAGE,
            TradeCounterValueTargetAnalyzer.Side.SIDE_B,
            "p3",
            "Player Three");

        var message = TradeCounterNegotiationMessagePolicy.compose(envelope);

        assertEquals(TradeCounterNegotiationMessagePolicy.Actor.ME, message.actor());
        assertEquals("I'd counter by adding Player Three to my side of the deal.", message.text());
    }

    @Test
    void rendersOtherManagerRemoveWhenProposalTouchesOppositeSide() {
        var envelope = removalEnvelope(
            TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_B_TEAM,
            TradeCounterValueTargetAnalyzer.Side.SIDE_A,
            "p2",
            "Player Two");

        var message = TradeCounterNegotiationMessagePolicy.compose(envelope);

        assertEquals(TradeCounterNegotiationMessagePolicy.Actor.OTHER_MANAGER, message.actor());
        assertEquals("I'd counter if you remove Player Two from your side of the deal.", message.text());
    }

    @Test
    void rendersMyRemoveWhenProposalTouchesPerspectiveSide() {
        var envelope = removalEnvelope(
            TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM,
            TradeCounterValueTargetAnalyzer.Side.SIDE_A,
            "p2",
            "Player Two");

        var message = TradeCounterNegotiationMessagePolicy.compose(envelope);

        assertEquals(TradeCounterNegotiationMessagePolicy.Actor.ME, message.actor());
        assertEquals("I'd counter by removing Player Two from my side of the deal.", message.text());
    }

    @Test
    void noActionProducesNoMessage() {
        var envelope = nonCounterEnvelope(
            TradeCounterProposalPolicy.Action.NO_ACTION,
            TradeCounterProposalPolicy.ReasonCode.AMBIGUOUS_SELECTION);

        var message = TradeCounterNegotiationMessagePolicy.compose(envelope);

        assertEquals(TradeCounterNegotiationMessagePolicy.State.NO_MESSAGE, message.state());
        assertEquals(TradeCounterNegotiationMessagePolicy.ReasonCode.COUNTER_PROPOSAL_NO_ACTION,
            message.reasonCode());
        assertNull(message.actor());
        assertNull(message.text());
    }

    @Test
    void inconclusiveProposalRemainsInconclusive() {
        var envelope = nonCounterEnvelope(
            TradeCounterProposalPolicy.Action.INCONCLUSIVE,
            TradeCounterProposalPolicy.ReasonCode.COUNTER_DECISION_INCONCLUSIVE);

        var message = TradeCounterNegotiationMessagePolicy.compose(envelope);

        assertEquals(TradeCounterNegotiationMessagePolicy.State.INCONCLUSIVE, message.state());
        assertEquals(TradeCounterNegotiationMessagePolicy.ReasonCode.COUNTER_PROPOSAL_INCONCLUSIVE,
            message.reasonCode());
        assertNull(message.text());
    }

    @Test
    void preservesBoundPolicyProvenanceAndCoordinates() {
        var envelope = envelope(
            TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM,
            TradeCounterSingleAssetCandidateAnalyzer.AdjustmentType.ADD_ASSET_TO_LOWER_PACKAGE,
            TradeCounterValueTargetAnalyzer.Side.SIDE_B,
            "p3",
            "Player Three");

        var message = TradeCounterNegotiationMessagePolicy.compose(envelope);

        assertEquals(TradeCounterNegotiationMessagePolicy.POLICY_ID, message.policyId());
        assertEquals(TradeCounterProposalEnvelopePolicy.POLICY_ID, message.envelopePolicyId());
        assertEquals(TradeCounterProposalPolicy.POLICY_ID, message.proposalPolicyId());
        assertEquals(TradeTeamPerspectiveRecommendationPolicy.POLICY_ID, message.perspectivePolicyId());
        assertEquals("l1", message.leagueId());
        assertEquals(2026, message.season());
        assertEquals("source", message.source());
        assertEquals(AS_OF, message.minimumAsOfDate());
        assertEquals(TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM,
            message.perspective());
    }

    private static TradeCounterProposalEnvelopePolicy.Envelope envelope(
        TradeTeamPerspectiveRecommendationPolicy.Perspective perspective,
        TradeCounterSingleAssetCandidateAnalyzer.AdjustmentType adjustment,
        TradeCounterValueTargetAnalyzer.Side side,
        String assetId,
        String displayName) {
        var proposal = proposal(adjustment, side, assetId, displayName);
        var result = counterResult(proposal);
        return TradeCounterProposalEnvelopePolicy.bind(
            result,
            perspective,
            new TradeAssetAnalyzer.TradePackage(List.of("p1"), List.of()),
            new TradeAssetAnalyzer.TradePackage(List.of("p2"), List.of()));
    }

    private static TradeCounterProposalEnvelopePolicy.Envelope removalEnvelope(
        TradeTeamPerspectiveRecommendationPolicy.Perspective perspective,
        TradeCounterValueTargetAnalyzer.Side side,
        String assetId,
        String displayName) {
        var proposal = proposal(
            TradeCounterSingleAssetCandidateAnalyzer.AdjustmentType.REMOVE_ASSET_FROM_HIGHER_PACKAGE,
            side,
            assetId,
            displayName);
        var result = counterResult(proposal);
        return TradeCounterProposalEnvelopePolicy.bind(
            result,
            perspective,
            new TradeAssetAnalyzer.TradePackage(List.of("p1", "p2"), List.of()),
            new TradeAssetAnalyzer.TradePackage(List.of("p3", "p4"), List.of()));
    }

    private static TradeCounterProposalEnvelopePolicy.Envelope nonCounterEnvelope(
        TradeCounterProposalPolicy.Action action,
        TradeCounterProposalPolicy.ReasonCode reason) {
        var result = new TradeCounterProposalPolicy.Result(
            TradeCounterProposalPolicy.POLICY_ID,
            TradeCounterOpportunityPolicy.POLICY_ID,
            TradeCounterCandidateSelectionPolicy.POLICY_ID,
            "l1", 2026, "source", AS_OF,
            action, reason, null);
        return TradeCounterProposalEnvelopePolicy.bind(
            result,
            TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM,
            new TradeAssetAnalyzer.TradePackage(List.of("p1"), List.of()),
            new TradeAssetAnalyzer.TradePackage(List.of("p2"), List.of()));
    }

    private static TradeCounterProposalPolicy.Result counterResult(
        TradeCounterProposalPolicy.Proposal proposal) {
        return new TradeCounterProposalPolicy.Result(
            TradeCounterProposalPolicy.POLICY_ID,
            TradeCounterOpportunityPolicy.POLICY_ID,
            TradeCounterCandidateSelectionPolicy.POLICY_ID,
            "l1", 2026, "source", AS_OF,
            TradeCounterProposalPolicy.Action.COUNTER,
            TradeCounterProposalPolicy.ReasonCode.UNIQUE_SELECTED_CANDIDATE,
            proposal);
    }

    private static TradeCounterProposalPolicy.Proposal proposal(
        TradeCounterSingleAssetCandidateAnalyzer.AdjustmentType adjustment,
        TradeCounterValueTargetAnalyzer.Side side,
        String assetId,
        String displayName) {
        return new TradeCounterProposalPolicy.Proposal(
            1,
            adjustment,
            side,
            TradeCounterSingleAssetCandidateAnalyzer.AssetType.PLAYER,
            assetId,
            displayName,
            "team-1",
            "Team 1",
            5.0,
            AS_OF,
            4.0,
            1.0,
            100.0,
            104.0,
            3.921568627,
            TradeFairnessPolicy.Classification.MARKET_FAIR);
    }
}
