package io.butler.bet.intelligence;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Converts a governed counter opportunity plus BF-375 selection into a read-only COUNTER proposal.
 * This policy does not submit, send, mutate, or execute a trade.
 */
public final class TradeCounterProposalPolicy {
    public static final String POLICY_ID =
        "trade-counter-proposal-v1-selected-candidate-read-only-counter";

    private TradeCounterProposalPolicy() {}

    public enum Action {
        COUNTER,
        NO_ACTION,
        INCONCLUSIVE
    }

    public enum ReasonCode {
        UNIQUE_SELECTED_CANDIDATE,
        AMBIGUOUS_SELECTION,
        NO_COUNTER_OPPORTUNITY,
        COUNTER_DECISION_INCONCLUSIVE
    }

    public static Result classify(
        TradeCounterOpportunityPolicy.Decision opportunity,
        TradeCounterCandidateSelectionPolicy.Selection selection) {
        Objects.requireNonNull(opportunity, "opportunity must not be null");
        Objects.requireNonNull(selection, "selection must not be null");
        requireMatchingCoordinates(opportunity, selection);

        return switch (opportunity.state()) {
            case INCONCLUSIVE -> {
                if (selection.state() != TradeCounterCandidateSelectionPolicy.State.INCONCLUSIVE) {
                    throw new IllegalArgumentException(
                        "inconclusive counter opportunity requires inconclusive candidate selection");
                }
                yield result(
                    opportunity,
                    Action.INCONCLUSIVE,
                    ReasonCode.COUNTER_DECISION_INCONCLUSIVE,
                    null);
            }
            case NO_COUNTER -> {
                if (selection.state() != TradeCounterCandidateSelectionPolicy.State.NO_SELECTION) {
                    throw new IllegalArgumentException(
                        "NO_COUNTER opportunity requires NO_SELECTION candidate state");
                }
                yield result(
                    opportunity,
                    Action.NO_ACTION,
                    ReasonCode.NO_COUNTER_OPPORTUNITY,
                    null);
            }
            case COUNTER_AVAILABLE -> switch (selection.state()) {
                case SELECTED -> result(
                    opportunity,
                    Action.COUNTER,
                    ReasonCode.UNIQUE_SELECTED_CANDIDATE,
                    proposal(selection.selectedCandidate()));
                case AMBIGUOUS -> result(
                    opportunity,
                    Action.NO_ACTION,
                    ReasonCode.AMBIGUOUS_SELECTION,
                    null);
                case NO_SELECTION, INCONCLUSIVE -> throw new IllegalArgumentException(
                    "COUNTER_AVAILABLE requires SELECTED or AMBIGUOUS candidate selection");
            };
        };
    }

    private static Proposal proposal(
        TradeCounterStrategicCandidateVettingAnalyzer.StrategicCandidate selected) {
        Objects.requireNonNull(selected, "selected candidate must not be null");
        var candidate = selected.candidate();
        return new Proposal(
            selected.marketRank(),
            candidate.adjustmentType(),
            candidate.side(),
            candidate.assetType(),
            candidate.assetId(),
            candidate.displayName(),
            candidate.teamId(),
            candidate.teamName(),
            candidate.assetValue(),
            candidate.asOfDate(),
            candidate.requiredValueChange(),
            candidate.excessValue(),
            candidate.resultingSideAValue(),
            candidate.resultingSideBValue(),
            candidate.resultingGapPercent(),
            candidate.resultingFairness());
    }

    private static Result result(
        TradeCounterOpportunityPolicy.Decision opportunity,
        Action action,
        ReasonCode reasonCode,
        Proposal proposal) {
        return new Result(
            POLICY_ID,
            TradeCounterOpportunityPolicy.POLICY_ID,
            TradeCounterCandidateSelectionPolicy.POLICY_ID,
            opportunity.leagueId(),
            opportunity.season(),
            opportunity.source(),
            opportunity.minimumAsOfDate(),
            action,
            reasonCode,
            proposal);
    }

    private static void requireMatchingCoordinates(
        TradeCounterOpportunityPolicy.Decision opportunity,
        TradeCounterCandidateSelectionPolicy.Selection selection) {
        if (!opportunity.leagueId().equals(selection.leagueId())
            || opportunity.season() != selection.season()
            || !opportunity.source().equals(selection.source())
            || !Objects.equals(opportunity.minimumAsOfDate(), selection.minimumAsOfDate())
            || !opportunity.policyId().equals(selection.opportunityPolicyId())) {
            throw new IllegalArgumentException(
                "counter opportunity and candidate selection coordinates must match");
        }
    }

    public record Proposal(
        int marketRank,
        TradeCounterSingleAssetCandidateAnalyzer.AdjustmentType adjustmentType,
        TradeCounterValueTargetAnalyzer.Side side,
        TradeCounterSingleAssetCandidateAnalyzer.AssetType assetType,
        String assetId,
        String displayName,
        String teamId,
        String teamName,
        double assetValue,
        LocalDate asOfDate,
        double requiredValueChange,
        double excessValue,
        double resultingSideAValue,
        double resultingSideBValue,
        double resultingGapPercent,
        TradeFairnessPolicy.Classification resultingFairness) {
        public Proposal {
            if (marketRank < 1) throw new IllegalArgumentException("marketRank must be positive");
            Objects.requireNonNull(adjustmentType, "adjustmentType must not be null");
            Objects.requireNonNull(side, "side must not be null");
            Objects.requireNonNull(assetType, "assetType must not be null");
            assetId = requireText(assetId, "assetId");
            displayName = requireText(displayName, "displayName");
            teamId = requireText(teamId, "teamId");
            teamName = requireText(teamName, "teamName");
            requireFiniteNonNegative(assetValue, "assetValue");
            if (!Double.isFinite(requiredValueChange) || requiredValueChange <= 0.0) {
                throw new IllegalArgumentException("requiredValueChange must be finite and positive");
            }
            requireFiniteNonNegative(excessValue, "excessValue");
            requireFiniteNonNegative(resultingSideAValue, "resultingSideAValue");
            requireFiniteNonNegative(resultingSideBValue, "resultingSideBValue");
            requireFiniteNonNegative(resultingGapPercent, "resultingGapPercent");
            if (resultingFairness != TradeFairnessPolicy.Classification.MARKET_FAIR) {
                throw new IllegalArgumentException("counter proposal must produce MARKET_FAIR result");
            }
        }
    }

    public record Result(
        String policyId,
        String opportunityPolicyId,
        String selectionPolicyId,
        String leagueId,
        int season,
        String source,
        LocalDate minimumAsOfDate,
        Action action,
        ReasonCode reasonCode,
        Proposal proposal) {
        public Result {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            if (!TradeCounterOpportunityPolicy.POLICY_ID.equals(opportunityPolicyId)) {
                throw new IllegalArgumentException("unexpected opportunityPolicyId");
            }
            if (!TradeCounterCandidateSelectionPolicy.POLICY_ID.equals(selectionPolicyId)) {
                throw new IllegalArgumentException("unexpected selectionPolicyId");
            }
            leagueId = requireText(leagueId, "leagueId");
            if (season < 1999 || season > 2100) throw new IllegalArgumentException("invalid season");
            source = requireText(source, "source");
            Objects.requireNonNull(action, "action must not be null");
            Objects.requireNonNull(reasonCode, "reasonCode must not be null");
            if (action == Action.COUNTER && proposal == null) {
                throw new IllegalArgumentException("COUNTER requires proposal");
            }
            if (action != Action.COUNTER && proposal != null) {
                throw new IllegalArgumentException("non-COUNTER action cannot carry proposal");
            }
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static void requireFiniteNonNegative(double value, String field) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(field + " must be finite and non-negative");
        }
    }
}
