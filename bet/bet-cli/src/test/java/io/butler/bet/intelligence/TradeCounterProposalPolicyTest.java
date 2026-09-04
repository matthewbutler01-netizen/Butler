package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TradeCounterProposalPolicyTest {
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 1);

    @Test
    void emitsCounterOnlyForUniqueSelectedCandidate() {
        var eligibility = eligibility(List.of(candidate(4, 5.0, 1.0, "p4")));
        var opportunity = rejectOpportunity(eligibility);
        var selection = TradeCounterCandidateSelectionPolicy.classify(opportunity, eligibility);

        var result = TradeCounterProposalPolicy.classify(opportunity, selection);

        assertEquals(TradeCounterProposalPolicy.Action.COUNTER, result.action());
        assertEquals(TradeCounterProposalPolicy.ReasonCode.UNIQUE_SELECTED_CANDIDATE, result.reasonCode());
        assertEquals(4, result.proposal().marketRank());
        assertEquals(TradeCounterSingleAssetCandidateAnalyzer.AdjustmentType.ADD_ASSET_TO_LOWER_PACKAGE,
            result.proposal().adjustmentType());
        assertEquals(TradeCounterValueTargetAnalyzer.Side.SIDE_B, result.proposal().side());
        assertEquals("p4", result.proposal().assetId());
        assertEquals(TradeFairnessPolicy.Classification.MARKET_FAIR, result.proposal().resultingFairness());
    }

    @Test
    void ambiguousSelectionNeverEmitsCounter() {
        var eligibility = eligibility(List.of(
            candidate(2, 5.0, 1.0, "p2"),
            candidate(7, 5.0, 1.0, "p7")));
        var opportunity = rejectOpportunity(eligibility);
        var selection = TradeCounterCandidateSelectionPolicy.classify(opportunity, eligibility);

        var result = TradeCounterProposalPolicy.classify(opportunity, selection);

        assertEquals(TradeCounterProposalPolicy.Action.NO_ACTION, result.action());
        assertEquals(TradeCounterProposalPolicy.ReasonCode.AMBIGUOUS_SELECTION, result.reasonCode());
        assertNull(result.proposal());
    }

    @Test
    void noCounterOpportunityProducesNoAction() {
        var eligibility = eligibility(List.of(candidate(1, 5.0, 1.0, "p1")));
        var opportunity = TradeCounterOpportunityPolicy.classify(
            TradeRecommendationPolicy.Recommendation.SIDE_B_PACKAGE_PREFERRED,
            TradeTeamPerspectiveRecommendationPolicy.Action.ACCEPT,
            TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM,
            true,
            eligibility);
        var selection = TradeCounterCandidateSelectionPolicy.classify(opportunity, eligibility);

        var result = TradeCounterProposalPolicy.classify(opportunity, selection);

        assertEquals(TradeCounterProposalPolicy.Action.NO_ACTION, result.action());
        assertEquals(TradeCounterProposalPolicy.ReasonCode.NO_COUNTER_OPPORTUNITY, result.reasonCode());
        assertNull(result.proposal());
    }

    @Test
    void inconclusiveDecisionRemainsInconclusive() {
        var eligibility = unavailableEligibility("Strategic evidence unavailable.");
        var opportunity = TradeCounterOpportunityPolicy.classify(
            TradeRecommendationPolicy.Recommendation.INCONCLUSIVE,
            TradeTeamPerspectiveRecommendationPolicy.Action.INCONCLUSIVE,
            TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM,
            false,
            eligibility);
        var selection = TradeCounterCandidateSelectionPolicy.classify(opportunity, eligibility);

        var result = TradeCounterProposalPolicy.classify(opportunity, selection);

        assertEquals(TradeCounterProposalPolicy.Action.INCONCLUSIVE, result.action());
        assertEquals(TradeCounterProposalPolicy.ReasonCode.COUNTER_DECISION_INCONCLUSIVE, result.reasonCode());
        assertNull(result.proposal());
    }

    @Test
    void rejectsMismatchedOpportunityAndSelectionCoordinates() {
        var eligibility = eligibility(List.of(candidate(1, 5.0, 1.0, "p1")));
        var opportunity = rejectOpportunity(eligibility);
        var selection = TradeCounterCandidateSelectionPolicy.classify(opportunity, eligibility);
        var differentOpportunity = new TradeCounterOpportunityPolicy.Decision(
            TradeCounterOpportunityPolicy.POLICY_ID,
            TradeRecommendationFlexibleTransitionMaterialLossPolicy.POLICY_ID,
            TradeTeamPerspectiveRecommendationPolicy.POLICY_ID,
            TradeCounterStrategicEligibilityPolicy.POLICY_ID,
            TradeCounterOpportunityPolicy.State.COUNTER_AVAILABLE,
            TradeCounterOpportunityPolicy.ReasonCode.MARKET_REJECT_WITH_ELIGIBLE_CANDIDATE,
            "other-league", 2026, "source", AS_OF, List.of(1));

        assertThrows(IllegalArgumentException.class,
            () -> TradeCounterProposalPolicy.classify(differentOpportunity, selection));
    }

    @Test
    void preservesPolicyProvenance() {
        var eligibility = eligibility(List.of(candidate(1, 5.0, 1.0, "p1")));
        var opportunity = rejectOpportunity(eligibility);
        var selection = TradeCounterCandidateSelectionPolicy.classify(opportunity, eligibility);

        var result = TradeCounterProposalPolicy.classify(opportunity, selection);

        assertEquals(TradeCounterProposalPolicy.POLICY_ID, result.policyId());
        assertEquals(TradeCounterOpportunityPolicy.POLICY_ID, result.opportunityPolicyId());
        assertEquals(TradeCounterCandidateSelectionPolicy.POLICY_ID, result.selectionPolicyId());
    }

    private static TradeCounterOpportunityPolicy.Decision rejectOpportunity(
        TradeCounterStrategicEligibilityPolicy.EligibilityReport eligibility) {
        return TradeCounterOpportunityPolicy.classify(
            TradeRecommendationPolicy.Recommendation.SIDE_A_PACKAGE_PREFERRED,
            TradeTeamPerspectiveRecommendationPolicy.Action.REJECT,
            TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM,
            true,
            eligibility);
    }

    private static TradeCounterStrategicEligibilityPolicy.EligibilityReport eligibility(
        List<TradeCounterStrategicCandidateVettingAnalyzer.StrategicCandidate> candidates) {
        return new TradeCounterStrategicEligibilityPolicy.EligibilityReport(
            TradeCounterStrategicEligibilityPolicy.POLICY_ID,
            TradeCounterStrategicCandidateVettingAnalyzer.POLICY_ID,
            "l1", 2026, "source", AS_OF, true, null, candidates, List.of());
    }

    private static TradeCounterStrategicEligibilityPolicy.EligibilityReport unavailableEligibility(String reason) {
        return new TradeCounterStrategicEligibilityPolicy.EligibilityReport(
            TradeCounterStrategicEligibilityPolicy.POLICY_ID,
            TradeCounterStrategicCandidateVettingAnalyzer.POLICY_ID,
            "l1", 2026, "source", AS_OF, false, reason, List.of(), List.of());
    }

    private static TradeCounterStrategicCandidateVettingAnalyzer.StrategicCandidate candidate(
        int rank,
        double assetValue,
        double excessValue,
        String assetId) {
        double requiredChange = assetValue - excessValue;
        var market = new TradeCounterSingleAssetCandidateAnalyzer.Candidate(
            TradeCounterSingleAssetCandidateAnalyzer.AdjustmentType.ADD_ASSET_TO_LOWER_PACKAGE,
            TradeCounterValueTargetAnalyzer.Side.SIDE_B,
            TradeCounterSingleAssetCandidateAnalyzer.AssetType.PLAYER,
            assetId,
            "Asset " + rank,
            "B",
            "Team B",
            assetValue,
            AS_OF,
            requiredChange,
            excessValue,
            100.0,
            105.0,
            4.878,
            TradeFairnessPolicy.Classification.MARKET_FAIR);
        var sideA = new TradeCounterStrategicCandidateVettingAnalyzer.SideVetting(
            TradeCounterStrategicCandidateVettingAnalyzer.Side.SIDE_A,
            "A", "Team A", TradeRecommendationVetoPolicy.VetoState.CLEAR, List.of());
        var sideB = new TradeCounterStrategicCandidateVettingAnalyzer.SideVetting(
            TradeCounterStrategicCandidateVettingAnalyzer.Side.SIDE_B,
            "B", "Team B", TradeRecommendationVetoPolicy.VetoState.CLEAR, List.of());
        return new TradeCounterStrategicCandidateVettingAnalyzer.StrategicCandidate(
            rank, market, TradeCounterStrategicCandidateVettingAnalyzer.VettingState.CLEAR, sideA, sideB);
    }
}
