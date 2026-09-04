package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TradeCounterOpportunityPolicyTest {
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 1);

    @Test
    void marketRejectWithEligibleCandidatesMakesCounterAvailableWithoutSelectingOne() {
        var eligibility = eligibility(List.of(clearCandidate(2), clearCandidate(5)));

        var decision = TradeCounterOpportunityPolicy.classify(
            TradeRecommendationPolicy.Recommendation.SIDE_A_PACKAGE_PREFERRED,
            TradeTeamPerspectiveRecommendationPolicy.Action.REJECT,
            TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM,
            true,
            eligibility);

        assertEquals(TradeCounterOpportunityPolicy.State.COUNTER_AVAILABLE, decision.state());
        assertEquals(TradeCounterOpportunityPolicy.ReasonCode.MARKET_REJECT_WITH_ELIGIBLE_CANDIDATE,
            decision.reasonCode());
        assertEquals(List.of(2, 5), decision.eligibleMarketRanks());
    }

    @Test
    void rejectWithoutEligibleCandidateDoesNotManufactureCounter() {
        var decision = TradeCounterOpportunityPolicy.classify(
            TradeRecommendationPolicy.Recommendation.SIDE_B_PACKAGE_PREFERRED,
            TradeTeamPerspectiveRecommendationPolicy.Action.REJECT,
            TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_B_TEAM,
            true,
            eligibility(List.of()));

        assertEquals(TradeCounterOpportunityPolicy.State.NO_COUNTER, decision.state());
        assertEquals(TradeCounterOpportunityPolicy.ReasonCode.NO_STRATEGICALLY_ELIGIBLE_CANDIDATE,
            decision.reasonCode());
        assertEquals(List.of(), decision.eligibleMarketRanks());
    }

    @Test
    void acceptAndHoldAreTerminalNoCounterEvenWhenEligibilityIsUnavailable() {
        var unavailable = unavailableEligibility();

        var accept = TradeCounterOpportunityPolicy.classify(
            TradeRecommendationPolicy.Recommendation.SIDE_B_PACKAGE_PREFERRED,
            TradeTeamPerspectiveRecommendationPolicy.Action.ACCEPT,
            TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM,
            true,
            unavailable);
        var hold = TradeCounterOpportunityPolicy.classify(
            TradeRecommendationPolicy.Recommendation.HOLD,
            TradeTeamPerspectiveRecommendationPolicy.Action.HOLD,
            TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM,
            true,
            unavailable);

        assertEquals(TradeCounterOpportunityPolicy.State.NO_COUNTER, accept.state());
        assertEquals(TradeCounterOpportunityPolicy.ReasonCode.V5_ACTION_NOT_REJECT, accept.reasonCode());
        assertEquals(TradeCounterOpportunityPolicy.State.NO_COUNTER, hold.state());
        assertEquals(TradeCounterOpportunityPolicy.ReasonCode.V5_ACTION_NOT_REJECT, hold.reasonCode());
    }

    @Test
    void incompleteV5EvidenceIsInconclusiveBeforeEligibility() {
        var decision = TradeCounterOpportunityPolicy.classify(
            TradeRecommendationPolicy.Recommendation.INCONCLUSIVE,
            TradeTeamPerspectiveRecommendationPolicy.Action.INCONCLUSIVE,
            TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM,
            false,
            unavailableEligibility());

        assertEquals(TradeCounterOpportunityPolicy.State.INCONCLUSIVE, decision.state());
        assertEquals(TradeCounterOpportunityPolicy.ReasonCode.V5_EVIDENCE_INCOMPLETE,
            decision.reasonCode());
    }

    @Test
    void rejectWithUnavailableStrategicEligibilityIsInconclusive() {
        var decision = TradeCounterOpportunityPolicy.classify(
            TradeRecommendationPolicy.Recommendation.SIDE_A_PACKAGE_PREFERRED,
            TradeTeamPerspectiveRecommendationPolicy.Action.REJECT,
            TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM,
            true,
            unavailableEligibility());

        assertEquals(TradeCounterOpportunityPolicy.State.INCONCLUSIVE, decision.state());
        assertEquals(TradeCounterOpportunityPolicy.ReasonCode.STRATEGIC_ELIGIBILITY_UNAVAILABLE,
            decision.reasonCode());
    }

    @Test
    void rejectsActionThatDoesNotMatchRecommendationAndPerspective() {
        assertThrows(IllegalArgumentException.class, () -> TradeCounterOpportunityPolicy.classify(
            TradeRecommendationPolicy.Recommendation.SIDE_A_PACKAGE_PREFERRED,
            TradeTeamPerspectiveRecommendationPolicy.Action.ACCEPT,
            TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM,
            true,
            eligibility(List.of(clearCandidate(1)))));
    }

    @Test
    void locksPolicyProvenance() {
        var decision = TradeCounterOpportunityPolicy.classify(
            TradeRecommendationPolicy.Recommendation.HOLD,
            TradeTeamPerspectiveRecommendationPolicy.Action.HOLD,
            TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM,
            true,
            eligibility(List.of()));

        assertEquals("trade-counter-opportunity-v1-v5-reject-plus-strategic-eligibility",
            decision.policyId());
        assertEquals(TradeRecommendationFlexibleTransitionMaterialLossPolicy.POLICY_ID,
            decision.recommendationPolicyId());
        assertEquals(TradeTeamPerspectiveRecommendationPolicy.POLICY_ID,
            decision.perspectivePolicyId());
        assertEquals(TradeCounterStrategicEligibilityPolicy.POLICY_ID,
            decision.strategicEligibilityPolicyId());
    }

    private static TradeCounterStrategicEligibilityPolicy.EligibilityReport eligibility(
        List<TradeCounterStrategicCandidateVettingAnalyzer.StrategicCandidate> eligible) {
        return new TradeCounterStrategicEligibilityPolicy.EligibilityReport(
            TradeCounterStrategicEligibilityPolicy.POLICY_ID,
            TradeCounterStrategicCandidateVettingAnalyzer.POLICY_ID,
            "l1", 2026, "source", AS_OF, true, null, eligible, List.of());
    }

    private static TradeCounterStrategicEligibilityPolicy.EligibilityReport unavailableEligibility() {
        return new TradeCounterStrategicEligibilityPolicy.EligibilityReport(
            TradeCounterStrategicEligibilityPolicy.POLICY_ID,
            TradeCounterStrategicCandidateVettingAnalyzer.POLICY_ID,
            "l1", 2026, "source", AS_OF, false,
            "strategic evidence unavailable", List.of(), List.of());
    }

    private static TradeCounterStrategicCandidateVettingAnalyzer.StrategicCandidate clearCandidate(int rank) {
        var market = new TradeCounterSingleAssetCandidateAnalyzer.Candidate(
            TradeCounterSingleAssetCandidateAnalyzer.AdjustmentType.ADD_ASSET_TO_LOWER_PACKAGE,
            TradeCounterValueTargetAnalyzer.Side.SIDE_B,
            TradeCounterSingleAssetCandidateAnalyzer.AssetType.PLAYER,
            "p" + rank, "Player " + rank, "B", "Team B", 5.0, AS_OF,
            4.0, 1.0, 105.0, 100.0, 4.878,
            TradeFairnessPolicy.Classification.MARKET_FAIR);
        var sideA = new TradeCounterStrategicCandidateVettingAnalyzer.SideVetting(
            TradeCounterStrategicCandidateVettingAnalyzer.Side.SIDE_A,
            "A", "Team A", TradeRecommendationVetoPolicy.VetoState.CLEAR, List.of());
        var sideB = new TradeCounterStrategicCandidateVettingAnalyzer.SideVetting(
            TradeCounterStrategicCandidateVettingAnalyzer.Side.SIDE_B,
            "B", "Team B", TradeRecommendationVetoPolicy.VetoState.CLEAR, List.of());
        return new TradeCounterStrategicCandidateVettingAnalyzer.StrategicCandidate(
            rank, market, TradeCounterStrategicCandidateVettingAnalyzer.VettingState.CLEAR,
            sideA, sideB);
    }
}
