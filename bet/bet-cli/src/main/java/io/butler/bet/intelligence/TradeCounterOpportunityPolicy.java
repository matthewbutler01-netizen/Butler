package io.butler.bet.intelligence;

import java.util.List;
import java.util.Objects;

/**
 * Perspective-aware gate that determines whether a governed counter opportunity exists.
 * It consumes the live v5 recommendation result and the strategically eligible candidate set,
 * but deliberately does not select a candidate or emit a COUNTER action.
 */
public final class TradeCounterOpportunityPolicy {
    public static final String POLICY_ID =
        "trade-counter-opportunity-v1-v5-reject-plus-strategic-eligibility";

    private TradeCounterOpportunityPolicy() {}

    public enum State {
        COUNTER_AVAILABLE,
        NO_COUNTER,
        INCONCLUSIVE
    }

    public enum ReasonCode {
        V5_EVIDENCE_INCOMPLETE,
        STRATEGIC_ELIGIBILITY_UNAVAILABLE,
        V5_ACTION_NOT_REJECT,
        NO_STRATEGICALLY_ELIGIBLE_CANDIDATE,
        MARKET_REJECT_WITH_ELIGIBLE_CANDIDATE
    }

    public static Decision classify(
        TradeRecommendationPolicy.Recommendation packageRecommendation,
        TradeTeamPerspectiveRecommendationPolicy.Action action,
        TradeTeamPerspectiveRecommendationPolicy.Perspective perspective,
        boolean v5EvidenceComplete,
        TradeCounterStrategicEligibilityPolicy.EligibilityReport eligibility) {
        Objects.requireNonNull(packageRecommendation, "packageRecommendation must not be null");
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(perspective, "perspective must not be null");
        Objects.requireNonNull(eligibility, "eligibility must not be null");

        var expectedAction = TradeTeamPerspectiveRecommendationPolicy.classify(
            packageRecommendation, perspective);
        if (expectedAction != action) {
            throw new IllegalArgumentException("action must match package recommendation and perspective");
        }

        if (!v5EvidenceComplete || packageRecommendation == TradeRecommendationPolicy.Recommendation.INCONCLUSIVE) {
            return decision(State.INCONCLUSIVE, ReasonCode.V5_EVIDENCE_INCOMPLETE, eligibility, List.of());
        }
        if (!eligibility.available()) {
            return decision(State.INCONCLUSIVE, ReasonCode.STRATEGIC_ELIGIBILITY_UNAVAILABLE, eligibility, List.of());
        }
        if (action != TradeTeamPerspectiveRecommendationPolicy.Action.REJECT) {
            return decision(State.NO_COUNTER, ReasonCode.V5_ACTION_NOT_REJECT, eligibility, List.of());
        }
        if (eligibility.eligibleCandidates().isEmpty()) {
            return decision(State.NO_COUNTER, ReasonCode.NO_STRATEGICALLY_ELIGIBLE_CANDIDATE, eligibility, List.of());
        }

        List<Integer> ranks = eligibility.eligibleCandidates().stream()
            .map(TradeCounterStrategicCandidateVettingAnalyzer.StrategicCandidate::marketRank)
            .toList();
        return decision(
            State.COUNTER_AVAILABLE,
            ReasonCode.MARKET_REJECT_WITH_ELIGIBLE_CANDIDATE,
            eligibility,
            ranks);
    }

    private static Decision decision(
        State state,
        ReasonCode reason,
        TradeCounterStrategicEligibilityPolicy.EligibilityReport eligibility,
        List<Integer> eligibleMarketRanks) {
        return new Decision(
            POLICY_ID,
            TradeRecommendationFlexibleTransitionMaterialLossPolicy.POLICY_ID,
            TradeTeamPerspectiveRecommendationPolicy.POLICY_ID,
            TradeCounterStrategicEligibilityPolicy.POLICY_ID,
            state,
            reason,
            eligibility.leagueId(),
            eligibility.season(),
            eligibility.source(),
            eligibility.minimumAsOfDate(),
            eligibleMarketRanks);
    }

    public record Decision(
        String policyId,
        String recommendationPolicyId,
        String perspectivePolicyId,
        String strategicEligibilityPolicyId,
        State state,
        ReasonCode reasonCode,
        String leagueId,
        int season,
        String source,
        java.time.LocalDate minimumAsOfDate,
        List<Integer> eligibleMarketRanks) {
        public Decision {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            if (!TradeRecommendationFlexibleTransitionMaterialLossPolicy.POLICY_ID.equals(recommendationPolicyId)) {
                throw new IllegalArgumentException("unexpected recommendationPolicyId");
            }
            if (!TradeTeamPerspectiveRecommendationPolicy.POLICY_ID.equals(perspectivePolicyId)) {
                throw new IllegalArgumentException("unexpected perspectivePolicyId");
            }
            if (!TradeCounterStrategicEligibilityPolicy.POLICY_ID.equals(strategicEligibilityPolicyId)) {
                throw new IllegalArgumentException("unexpected strategicEligibilityPolicyId");
            }
            Objects.requireNonNull(state, "state must not be null");
            Objects.requireNonNull(reasonCode, "reasonCode must not be null");
            if (leagueId == null || leagueId.isBlank()) throw new IllegalArgumentException("leagueId must not be blank");
            if (season < 1999 || season > 2100) throw new IllegalArgumentException("invalid season");
            if (source == null || source.isBlank()) throw new IllegalArgumentException("source must not be blank");
            eligibleMarketRanks = List.copyOf(Objects.requireNonNull(eligibleMarketRanks, "eligibleMarketRanks must not be null"));
            if (state == State.COUNTER_AVAILABLE && eligibleMarketRanks.isEmpty()) {
                throw new IllegalArgumentException("COUNTER_AVAILABLE requires eligible market ranks");
            }
            if (state != State.COUNTER_AVAILABLE && !eligibleMarketRanks.isEmpty()) {
                throw new IllegalArgumentException("non-available counter decision cannot carry eligible market ranks");
            }
        }
    }
}
