package io.butler.bet.intelligence;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Selects a single strategically eligible counter candidate only when one candidate is uniquely
 * best on governed market criteria. Deterministic tail ordering such as asset type or ID is not
 * allowed to break a decision tie.
 */
public final class TradeCounterCandidateSelectionPolicy {
    public static final String POLICY_ID =
        "trade-counter-candidate-selection-v1-unique-best-market-criteria-fail-ambiguous";

    private TradeCounterCandidateSelectionPolicy() {}

    public enum State {
        SELECTED,
        AMBIGUOUS,
        NO_SELECTION,
        INCONCLUSIVE
    }

    public enum ReasonCode {
        UNIQUE_BEST_GOVERNED_MARKET_CANDIDATE,
        TOP_GOVERNED_MARKET_CRITERIA_TIED,
        NO_COUNTER_OPPORTUNITY,
        COUNTER_OPPORTUNITY_INCONCLUSIVE
    }

    public static Selection classify(
        TradeCounterOpportunityPolicy.Decision opportunity,
        TradeCounterStrategicEligibilityPolicy.EligibilityReport eligibility) {
        Objects.requireNonNull(opportunity, "opportunity must not be null");
        Objects.requireNonNull(eligibility, "eligibility must not be null");
        requireMatchingCoordinates(opportunity, eligibility);

        if (opportunity.state() == TradeCounterOpportunityPolicy.State.INCONCLUSIVE) {
            return result(
                opportunity,
                State.INCONCLUSIVE,
                ReasonCode.COUNTER_OPPORTUNITY_INCONCLUSIVE,
                null,
                List.of());
        }
        if (opportunity.state() == TradeCounterOpportunityPolicy.State.NO_COUNTER) {
            return result(
                opportunity,
                State.NO_SELECTION,
                ReasonCode.NO_COUNTER_OPPORTUNITY,
                null,
                List.of());
        }

        if (!eligibility.available()) {
            throw new IllegalArgumentException(
                "COUNTER_AVAILABLE requires available strategic eligibility evidence");
        }
        List<Integer> eligibleRanks = eligibility.eligibleCandidates().stream()
            .map(TradeCounterStrategicCandidateVettingAnalyzer.StrategicCandidate::marketRank)
            .toList();
        if (!eligibleRanks.equals(opportunity.eligibleMarketRanks())) {
            throw new IllegalArgumentException(
                "counter opportunity eligible ranks must match strategic eligibility ranks");
        }
        if (eligibility.eligibleCandidates().isEmpty()) {
            throw new IllegalArgumentException("COUNTER_AVAILABLE requires eligible candidates");
        }

        List<TradeCounterStrategicCandidateVettingAnalyzer.StrategicCandidate> ordered =
            eligibility.eligibleCandidates().stream()
                .sorted(Comparator
                    .comparingDouble((TradeCounterStrategicCandidateVettingAnalyzer.StrategicCandidate candidate) ->
                        candidate.candidate().excessValue())
                    .thenComparingDouble(candidate -> candidate.candidate().assetValue()))
                .toList();
        var best = ordered.get(0);
        List<Integer> tiedRanks = ordered.stream()
            .filter(candidate -> sameGovernedSelectionCriteria(best, candidate))
            .map(TradeCounterStrategicCandidateVettingAnalyzer.StrategicCandidate::marketRank)
            .toList();

        if (tiedRanks.size() > 1) {
            return result(
                opportunity,
                State.AMBIGUOUS,
                ReasonCode.TOP_GOVERNED_MARKET_CRITERIA_TIED,
                null,
                tiedRanks);
        }
        return result(
            opportunity,
            State.SELECTED,
            ReasonCode.UNIQUE_BEST_GOVERNED_MARKET_CANDIDATE,
            best,
            List.of());
    }

    private static boolean sameGovernedSelectionCriteria(
        TradeCounterStrategicCandidateVettingAnalyzer.StrategicCandidate left,
        TradeCounterStrategicCandidateVettingAnalyzer.StrategicCandidate right) {
        return Double.compare(left.candidate().excessValue(), right.candidate().excessValue()) == 0
            && Double.compare(left.candidate().assetValue(), right.candidate().assetValue()) == 0;
    }

    private static void requireMatchingCoordinates(
        TradeCounterOpportunityPolicy.Decision opportunity,
        TradeCounterStrategicEligibilityPolicy.EligibilityReport eligibility) {
        if (!opportunity.leagueId().equals(eligibility.leagueId())
            || opportunity.season() != eligibility.season()
            || !opportunity.source().equals(eligibility.source())
            || !Objects.equals(opportunity.minimumAsOfDate(), eligibility.minimumAsOfDate())
            || !TradeCounterStrategicEligibilityPolicy.POLICY_ID.equals(opportunity.strategicEligibilityPolicyId())) {
            throw new IllegalArgumentException(
                "counter opportunity and strategic eligibility coordinates must match");
        }
    }

    private static Selection result(
        TradeCounterOpportunityPolicy.Decision opportunity,
        State state,
        ReasonCode reasonCode,
        TradeCounterStrategicCandidateVettingAnalyzer.StrategicCandidate selectedCandidate,
        List<Integer> ambiguousMarketRanks) {
        return new Selection(
            POLICY_ID,
            TradeCounterOpportunityPolicy.POLICY_ID,
            TradeCounterStrategicEligibilityPolicy.POLICY_ID,
            opportunity.leagueId(),
            opportunity.season(),
            opportunity.source(),
            opportunity.minimumAsOfDate(),
            state,
            reasonCode,
            selectedCandidate,
            ambiguousMarketRanks);
    }

    public record Selection(
        String policyId,
        String opportunityPolicyId,
        String strategicEligibilityPolicyId,
        String leagueId,
        int season,
        String source,
        java.time.LocalDate minimumAsOfDate,
        State state,
        ReasonCode reasonCode,
        TradeCounterStrategicCandidateVettingAnalyzer.StrategicCandidate selectedCandidate,
        List<Integer> ambiguousMarketRanks) {
        public Selection {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            if (!TradeCounterOpportunityPolicy.POLICY_ID.equals(opportunityPolicyId)) {
                throw new IllegalArgumentException("unexpected opportunityPolicyId");
            }
            if (!TradeCounterStrategicEligibilityPolicy.POLICY_ID.equals(strategicEligibilityPolicyId)) {
                throw new IllegalArgumentException("unexpected strategicEligibilityPolicyId");
            }
            if (leagueId == null || leagueId.isBlank()) throw new IllegalArgumentException("leagueId must not be blank");
            if (season < 1999 || season > 2100) throw new IllegalArgumentException("invalid season");
            if (source == null || source.isBlank()) throw new IllegalArgumentException("source must not be blank");
            Objects.requireNonNull(state, "state must not be null");
            Objects.requireNonNull(reasonCode, "reasonCode must not be null");
            ambiguousMarketRanks = List.copyOf(Objects.requireNonNull(
                ambiguousMarketRanks, "ambiguousMarketRanks must not be null"));
            if (state == State.SELECTED && selectedCandidate == null) {
                throw new IllegalArgumentException("SELECTED requires selectedCandidate");
            }
            if (state != State.SELECTED && selectedCandidate != null) {
                throw new IllegalArgumentException("non-selected state cannot carry selectedCandidate");
            }
            if (state == State.AMBIGUOUS && ambiguousMarketRanks.size() < 2) {
                throw new IllegalArgumentException("AMBIGUOUS requires at least two tied market ranks");
            }
            if (state != State.AMBIGUOUS && !ambiguousMarketRanks.isEmpty()) {
                throw new IllegalArgumentException("non-ambiguous state cannot carry ambiguous market ranks");
            }
        }
    }
}
