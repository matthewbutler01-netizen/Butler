package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TradeCounterCandidateSelectionPolicyTest {
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 1);

    @Test
    void selectsUniqueLowestExcessEvenWhenItHasLaterMarketRank() {
        var rankTwo = candidate(2, 6.0, 2.0, "p2", TradeCounterSingleAssetCandidateAnalyzer.AssetType.PLAYER);
        var rankSeven = candidate(7, 8.0, 1.0, "p7", TradeCounterSingleAssetCandidateAnalyzer.AssetType.PLAYER);
        var eligibility = eligibility(List.of(rankTwo, rankSeven));
        var opportunity = rejectOpportunity(eligibility);

        var selection = TradeCounterCandidateSelectionPolicy.classify(opportunity, eligibility);

        assertEquals(TradeCounterCandidateSelectionPolicy.State.SELECTED, selection.state());
        assertEquals(TradeCounterCandidateSelectionPolicy.ReasonCode.UNIQUE_BEST_GOVERNED_MARKET_CANDIDATE,
            selection.reasonCode());
        assertEquals(7, selection.selectedCandidate().marketRank());
        assertEquals("p7", selection.selectedCandidate().candidate().assetId());
    }

    @Test
    void usesSmallerAssetInterventionAsSecondGovernedCriterion() {
        var larger = candidate(2, 7.0, 1.0, "larger", TradeCounterSingleAssetCandidateAnalyzer.AssetType.PLAYER);
        var smaller = candidate(5, 5.0, 1.0, "smaller", TradeCounterSingleAssetCandidateAnalyzer.AssetType.PLAYER);
        var eligibility = eligibility(List.of(larger, smaller));

        var selection = TradeCounterCandidateSelectionPolicy.classify(
            rejectOpportunity(eligibility), eligibility);

        assertEquals(TradeCounterCandidateSelectionPolicy.State.SELECTED, selection.state());
        assertEquals(5, selection.selectedCandidate().marketRank());
    }

    @Test
    void exactTieOnGovernedCriteriaIsAmbiguousInsteadOfUsingAssetIdOrType() {
        var player = candidate(3, 5.0, 1.0, "aaa-player", TradeCounterSingleAssetCandidateAnalyzer.AssetType.PLAYER);
        var pick = candidate(9, 5.0, 1.0, "zzz-pick", TradeCounterSingleAssetCandidateAnalyzer.AssetType.DRAFT_PICK);
        var eligibility = eligibility(List.of(player, pick));

        var selection = TradeCounterCandidateSelectionPolicy.classify(
            rejectOpportunity(eligibility), eligibility);

        assertEquals(TradeCounterCandidateSelectionPolicy.State.AMBIGUOUS, selection.state());
        assertEquals(TradeCounterCandidateSelectionPolicy.ReasonCode.TOP_GOVERNED_MARKET_CRITERIA_TIED,
            selection.reasonCode());
        assertNull(selection.selectedCandidate());
        assertEquals(List.of(3, 9), selection.ambiguousMarketRanks());
    }

    @Test
    void noCounterOpportunityNeverSelectsEvenIfEligibilityContainsCandidates() {
        var eligibility = eligibility(List.of(candidate(
            1, 5.0, 1.0, "p1", TradeCounterSingleAssetCandidateAnalyzer.AssetType.PLAYER)));
        var opportunity = TradeCounterOpportunityPolicy.classify(
            TradeRecommendationPolicy.Recommendation.SIDE_B_PACKAGE_PREFERRED,
            TradeTeamPerspectiveRecommendationPolicy.Action.ACCEPT,
            TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM,
            true,
            eligibility);

        var selection = TradeCounterCandidateSelectionPolicy.classify(opportunity, eligibility);

        assertEquals(TradeCounterCandidateSelectionPolicy.State.NO_SELECTION, selection.state());
        assertEquals(TradeCounterCandidateSelectionPolicy.ReasonCode.NO_COUNTER_OPPORTUNITY,
            selection.reasonCode());
        assertNull(selection.selectedCandidate());
    }

    @Test
    void inconclusiveCounterOpportunityRemainsInconclusive() {
        var eligibility = unavailableEligibility("Strategic evidence unavailable.");
        var opportunity = TradeCounterOpportunityPolicy.classify(
            TradeRecommendationPolicy.Recommendation.INCONCLUSIVE,
            TradeTeamPerspectiveRecommendationPolicy.Action.INCONCLUSIVE,
            TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM,
            false,
            eligibility);

        var selection = TradeCounterCandidateSelectionPolicy.classify(opportunity, eligibility);

        assertEquals(TradeCounterCandidateSelectionPolicy.State.INCONCLUSIVE, selection.state());
        assertEquals(TradeCounterCandidateSelectionPolicy.ReasonCode.COUNTER_OPPORTUNITY_INCONCLUSIVE,
            selection.reasonCode());
    }

    @Test
    void rejectsCounterAvailableEvidenceWhoseEligibleRanksDoNotMatch() {
        var originalEligibility = eligibility(List.of(candidate(
            1, 5.0, 1.0, "p1", TradeCounterSingleAssetCandidateAnalyzer.AssetType.PLAYER)));
        var opportunity = rejectOpportunity(originalEligibility);
        var differentEligibility = eligibility(List.of(candidate(
            2, 5.0, 1.0, "p2", TradeCounterSingleAssetCandidateAnalyzer.AssetType.PLAYER)));

        assertThrows(IllegalArgumentException.class,
            () -> TradeCounterCandidateSelectionPolicy.classify(opportunity, differentEligibility));
    }

    @Test
    void preservesPolicyProvenance() {
        var eligibility = eligibility(List.of(candidate(
            1, 5.0, 1.0, "p1", TradeCounterSingleAssetCandidateAnalyzer.AssetType.PLAYER)));
        var selection = TradeCounterCandidateSelectionPolicy.classify(
            rejectOpportunity(eligibility), eligibility);

        assertEquals(TradeCounterCandidateSelectionPolicy.POLICY_ID, selection.policyId());
        assertEquals(TradeCounterOpportunityPolicy.POLICY_ID, selection.opportunityPolicyId());
        assertEquals(TradeCounterStrategicEligibilityPolicy.POLICY_ID,
            selection.strategicEligibilityPolicyId());
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
        String assetId,
        TradeCounterSingleAssetCandidateAnalyzer.AssetType assetType) {
        double requiredChange = assetValue - excessValue;
        var market = new TradeCounterSingleAssetCandidateAnalyzer.Candidate(
            TradeCounterSingleAssetCandidateAnalyzer.AdjustmentType.ADD_ASSET_TO_LOWER_PACKAGE,
            TradeCounterValueTargetAnalyzer.Side.SIDE_B,
            assetType,
            assetId,
            "Asset " + rank,
            "B",
            "Team B",
            assetValue,
            AS_OF,
            requiredChange,
            excessValue,
            105.0,
            100.0,
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
