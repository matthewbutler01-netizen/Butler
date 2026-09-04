package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TradeCounterMaterializedPackagePolicyTest {
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 1);

    @Test
    void materializesPlayerAddOnSideBAndPreservesOriginalOrder() {
        var envelope = counterEnvelope(
            TradeCounterSingleAssetCandidateAnalyzer.AdjustmentType.ADD_ASSET_TO_LOWER_PACKAGE,
            TradeCounterValueTargetAnalyzer.Side.SIDE_B,
            TradeCounterSingleAssetCandidateAnalyzer.AssetType.PLAYER,
            "p3",
            new TradeAssetAnalyzer.TradePackage(List.of("p1"), List.of("k1")),
            new TradeAssetAnalyzer.TradePackage(List.of("p2"), List.of()));

        var materialized = TradeCounterMaterializedPackagePolicy.materialize(envelope);

        assertEquals(TradeCounterMaterializedPackagePolicy.State.MATERIALIZED, materialized.state());
        assertEquals(List.of("p1"), materialized.revisedSideA().playerIds());
        assertEquals(List.of("k1"), materialized.revisedSideA().draftPickIds());
        assertEquals(List.of("p2", "p3"), materialized.revisedSideB().playerIds());
    }

    @Test
    void materializesDraftPickAddOnSideA() {
        var envelope = counterEnvelope(
            TradeCounterSingleAssetCandidateAnalyzer.AdjustmentType.ADD_ASSET_TO_LOWER_PACKAGE,
            TradeCounterValueTargetAnalyzer.Side.SIDE_A,
            TradeCounterSingleAssetCandidateAnalyzer.AssetType.DRAFT_PICK,
            "k3",
            new TradeAssetAnalyzer.TradePackage(List.of("p1"), List.of("k1")),
            new TradeAssetAnalyzer.TradePackage(List.of("p2"), List.of("k2")));

        var materialized = TradeCounterMaterializedPackagePolicy.materialize(envelope);

        assertEquals(List.of("k1", "k3"), materialized.revisedSideA().draftPickIds());
        assertEquals(List.of("k2"), materialized.revisedSideB().draftPickIds());
    }

    @Test
    void materializesPlayerRemovalOnSideA() {
        var envelope = counterEnvelope(
            TradeCounterSingleAssetCandidateAnalyzer.AdjustmentType.REMOVE_ASSET_FROM_HIGHER_PACKAGE,
            TradeCounterValueTargetAnalyzer.Side.SIDE_A,
            TradeCounterSingleAssetCandidateAnalyzer.AssetType.PLAYER,
            "p2",
            new TradeAssetAnalyzer.TradePackage(List.of("p1", "p2"), List.of("k1")),
            new TradeAssetAnalyzer.TradePackage(List.of("p3"), List.of()));

        var materialized = TradeCounterMaterializedPackagePolicy.materialize(envelope);

        assertEquals(List.of("p1"), materialized.revisedSideA().playerIds());
        assertEquals(List.of("k1"), materialized.revisedSideA().draftPickIds());
        assertEquals(List.of("p3"), materialized.revisedSideB().playerIds());
    }

    @Test
    void materializesDraftPickRemovalOnSideB() {
        var envelope = counterEnvelope(
            TradeCounterSingleAssetCandidateAnalyzer.AdjustmentType.REMOVE_ASSET_FROM_HIGHER_PACKAGE,
            TradeCounterValueTargetAnalyzer.Side.SIDE_B,
            TradeCounterSingleAssetCandidateAnalyzer.AssetType.DRAFT_PICK,
            "k2",
            new TradeAssetAnalyzer.TradePackage(List.of("p1"), List.of()),
            new TradeAssetAnalyzer.TradePackage(List.of("p2"), List.of("k1", "k2")));

        var materialized = TradeCounterMaterializedPackagePolicy.materialize(envelope);

        assertEquals(List.of("k1"), materialized.revisedSideB().draftPickIds());
    }

    @Test
    void noActionProducesNoMaterializedPackages() {
        var envelope = nonCounterEnvelope(
            TradeCounterProposalPolicy.Action.NO_ACTION,
            TradeCounterProposalPolicy.ReasonCode.AMBIGUOUS_SELECTION);

        var materialized = TradeCounterMaterializedPackagePolicy.materialize(envelope);

        assertEquals(TradeCounterMaterializedPackagePolicy.State.NO_PACKAGE, materialized.state());
        assertNull(materialized.revisedSideA());
        assertNull(materialized.revisedSideB());
    }

    @Test
    void inconclusiveProposalRemainsInconclusive() {
        var envelope = nonCounterEnvelope(
            TradeCounterProposalPolicy.Action.INCONCLUSIVE,
            TradeCounterProposalPolicy.ReasonCode.COUNTER_DECISION_INCONCLUSIVE);

        var materialized = TradeCounterMaterializedPackagePolicy.materialize(envelope);

        assertEquals(TradeCounterMaterializedPackagePolicy.State.INCONCLUSIVE, materialized.state());
        assertNull(materialized.revisedSideA());
    }

    @Test
    void preservesEnvelopeCoordinatesPerspectiveAndOriginalPackages() {
        var originalA = new TradeAssetAnalyzer.TradePackage(List.of("p1"), List.of("k1"));
        var originalB = new TradeAssetAnalyzer.TradePackage(List.of("p2"), List.of());
        var envelope = counterEnvelope(
            TradeCounterSingleAssetCandidateAnalyzer.AdjustmentType.ADD_ASSET_TO_LOWER_PACKAGE,
            TradeCounterValueTargetAnalyzer.Side.SIDE_B,
            TradeCounterSingleAssetCandidateAnalyzer.AssetType.PLAYER,
            "p3",
            originalA,
            originalB);

        var materialized = TradeCounterMaterializedPackagePolicy.materialize(envelope);

        assertEquals(TradeCounterMaterializedPackagePolicy.POLICY_ID, materialized.policyId());
        assertEquals(TradeCounterProposalEnvelopePolicy.POLICY_ID, materialized.envelopePolicyId());
        assertEquals(TradeCounterProposalPolicy.POLICY_ID, materialized.proposalPolicyId());
        assertEquals("l1", materialized.leagueId());
        assertEquals(2026, materialized.season());
        assertEquals("source", materialized.source());
        assertEquals(AS_OF, materialized.minimumAsOfDate());
        assertEquals(TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM,
            materialized.perspective());
        assertEquals(originalA, materialized.originalSideA());
        assertEquals(originalB, materialized.originalSideB());
    }

    private static TradeCounterProposalEnvelopePolicy.Envelope counterEnvelope(
        TradeCounterSingleAssetCandidateAnalyzer.AdjustmentType adjustment,
        TradeCounterValueTargetAnalyzer.Side side,
        TradeCounterSingleAssetCandidateAnalyzer.AssetType assetType,
        String assetId,
        TradeAssetAnalyzer.TradePackage sideA,
        TradeAssetAnalyzer.TradePackage sideB) {
        var proposal = new TradeCounterProposalPolicy.Proposal(
            1,
            adjustment,
            side,
            assetType,
            assetId,
            "Asset " + assetId,
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
        var result = new TradeCounterProposalPolicy.Result(
            TradeCounterProposalPolicy.POLICY_ID,
            TradeCounterOpportunityPolicy.POLICY_ID,
            TradeCounterCandidateSelectionPolicy.POLICY_ID,
            "l1", 2026, "source", AS_OF,
            TradeCounterProposalPolicy.Action.COUNTER,
            TradeCounterProposalPolicy.ReasonCode.UNIQUE_SELECTED_CANDIDATE,
            proposal);
        return TradeCounterProposalEnvelopePolicy.bind(
            result,
            TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM,
            sideA,
            sideB);
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
}
