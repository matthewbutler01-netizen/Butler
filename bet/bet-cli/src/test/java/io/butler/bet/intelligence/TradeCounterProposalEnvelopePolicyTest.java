package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TradeCounterProposalEnvelopePolicyTest {
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 1);

    @Test
    void bindsAddProposalToPerspectiveAndOriginalPackages() {
        var result = counterResult(proposal(
            TradeCounterSingleAssetCandidateAnalyzer.AdjustmentType.ADD_ASSET_TO_LOWER_PACKAGE,
            TradeCounterValueTargetAnalyzer.Side.SIDE_B,
            TradeCounterSingleAssetCandidateAnalyzer.AssetType.PLAYER,
            "p3"));

        var envelope = TradeCounterProposalEnvelopePolicy.bind(
            result,
            TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM,
            new TradeAssetAnalyzer.TradePackage(List.of("p1"), List.of()),
            new TradeAssetAnalyzer.TradePackage(List.of("p2"), List.of()));

        assertEquals(TradeCounterProposalEnvelopePolicy.POLICY_ID, envelope.policyId());
        assertEquals(TradeCounterProposalPolicy.POLICY_ID, envelope.proposalPolicyId());
        assertEquals(TradeTeamPerspectiveRecommendationPolicy.POLICY_ID, envelope.perspectivePolicyId());
        assertEquals(TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM, envelope.perspective());
        assertEquals(List.of("p1"), envelope.originalSideA().playerIds());
        assertEquals(List.of("p2"), envelope.originalSideB().playerIds());
        assertEquals("p3", envelope.proposal().assetId());
    }

    @Test
    void bindsValidRemovalOnlyWhenAssetExistsAndSideRemainsNonEmpty() {
        var result = counterResult(proposal(
            TradeCounterSingleAssetCandidateAnalyzer.AdjustmentType.REMOVE_ASSET_FROM_HIGHER_PACKAGE,
            TradeCounterValueTargetAnalyzer.Side.SIDE_A,
            TradeCounterSingleAssetCandidateAnalyzer.AssetType.PLAYER,
            "p2"));

        var envelope = TradeCounterProposalEnvelopePolicy.bind(
            result,
            TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_B_TEAM,
            new TradeAssetAnalyzer.TradePackage(List.of("p1", "p2"), List.of()),
            new TradeAssetAnalyzer.TradePackage(List.of("p3"), List.of()));

        assertEquals(TradeCounterProposalPolicy.Action.COUNTER, envelope.action());
        assertEquals("p2", envelope.proposal().assetId());
        assertEquals(TradeCounterValueTargetAnalyzer.Side.SIDE_A, envelope.proposal().side());
    }

    @Test
    void rejectsAddAssetAlreadyPresentInOriginalTrade() {
        var result = counterResult(proposal(
            TradeCounterSingleAssetCandidateAnalyzer.AdjustmentType.ADD_ASSET_TO_LOWER_PACKAGE,
            TradeCounterValueTargetAnalyzer.Side.SIDE_B,
            TradeCounterSingleAssetCandidateAnalyzer.AssetType.PLAYER,
            "p2"));

        assertThrows(IllegalArgumentException.class, () -> TradeCounterProposalEnvelopePolicy.bind(
            result,
            TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM,
            new TradeAssetAnalyzer.TradePackage(List.of("p1"), List.of()),
            new TradeAssetAnalyzer.TradePackage(List.of("p2"), List.of())));
    }

    @Test
    void rejectsRemovalAssetMissingFromGovernedSide() {
        var result = counterResult(proposal(
            TradeCounterSingleAssetCandidateAnalyzer.AdjustmentType.REMOVE_ASSET_FROM_HIGHER_PACKAGE,
            TradeCounterValueTargetAnalyzer.Side.SIDE_A,
            TradeCounterSingleAssetCandidateAnalyzer.AssetType.PLAYER,
            "missing"));

        assertThrows(IllegalArgumentException.class, () -> TradeCounterProposalEnvelopePolicy.bind(
            result,
            TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM,
            new TradeAssetAnalyzer.TradePackage(List.of("p1", "p2"), List.of()),
            new TradeAssetAnalyzer.TradePackage(List.of("p3"), List.of())));
    }

    @Test
    void rejectsRemovalThatWouldEmptyOriginalSide() {
        var result = counterResult(proposal(
            TradeCounterSingleAssetCandidateAnalyzer.AdjustmentType.REMOVE_ASSET_FROM_HIGHER_PACKAGE,
            TradeCounterValueTargetAnalyzer.Side.SIDE_A,
            TradeCounterSingleAssetCandidateAnalyzer.AssetType.PLAYER,
            "p1"));

        assertThrows(IllegalArgumentException.class, () -> TradeCounterProposalEnvelopePolicy.bind(
            result,
            TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM,
            new TradeAssetAnalyzer.TradePackage(List.of("p1"), List.of()),
            new TradeAssetAnalyzer.TradePackage(List.of("p2"), List.of())));
    }

    @Test
    void nonCounterOutcomeStillBindsOriginalTradeWithoutProposal() {
        var result = new TradeCounterProposalPolicy.Result(
            TradeCounterProposalPolicy.POLICY_ID,
            TradeCounterOpportunityPolicy.POLICY_ID,
            TradeCounterCandidateSelectionPolicy.POLICY_ID,
            "l1", 2026, "source", AS_OF,
            TradeCounterProposalPolicy.Action.NO_ACTION,
            TradeCounterProposalPolicy.ReasonCode.AMBIGUOUS_SELECTION,
            null);

        var envelope = TradeCounterProposalEnvelopePolicy.bind(
            result,
            TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM,
            new TradeAssetAnalyzer.TradePackage(List.of("p1"), List.of()),
            new TradeAssetAnalyzer.TradePackage(List.of("p2"), List.of()));

        assertEquals(TradeCounterProposalPolicy.Action.NO_ACTION, envelope.action());
        assertNull(envelope.proposal());
    }

    @Test
    void rejectsDuplicateOrOverlappingOriginalPackageIdentity() {
        var result = new TradeCounterProposalPolicy.Result(
            TradeCounterProposalPolicy.POLICY_ID,
            TradeCounterOpportunityPolicy.POLICY_ID,
            TradeCounterCandidateSelectionPolicy.POLICY_ID,
            "l1", 2026, "source", AS_OF,
            TradeCounterProposalPolicy.Action.NO_ACTION,
            TradeCounterProposalPolicy.ReasonCode.NO_COUNTER_OPPORTUNITY,
            null);

        assertThrows(IllegalArgumentException.class, () -> TradeCounterProposalEnvelopePolicy.bind(
            result,
            TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM,
            new TradeAssetAnalyzer.TradePackage(List.of("p1", "p1"), List.of()),
            new TradeAssetAnalyzer.TradePackage(List.of("p2"), List.of())));

        assertThrows(IllegalArgumentException.class, () -> TradeCounterProposalEnvelopePolicy.bind(
            result,
            TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM,
            new TradeAssetAnalyzer.TradePackage(List.of("p1"), List.of()),
            new TradeAssetAnalyzer.TradePackage(List.of("p1"), List.of())));
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
        TradeCounterSingleAssetCandidateAnalyzer.AssetType assetType,
        String assetId) {
        return new TradeCounterProposalPolicy.Proposal(
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
    }
}
