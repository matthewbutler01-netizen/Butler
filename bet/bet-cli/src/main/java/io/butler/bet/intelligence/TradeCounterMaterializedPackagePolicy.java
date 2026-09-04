package io.butler.bet.intelligence;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Materializes the complete revised trade packages represented by a bound single-asset counter.
 * This policy is read-only and does not submit or mutate a trade.
 */
public final class TradeCounterMaterializedPackagePolicy {
    public static final String POLICY_ID =
        "trade-counter-materialized-package-v1-bound-single-adjustment";

    private TradeCounterMaterializedPackagePolicy() {}

    public enum State {
        MATERIALIZED,
        NO_PACKAGE,
        INCONCLUSIVE
    }

    public enum ReasonCode {
        GOVERNED_COUNTER_MATERIALIZED,
        COUNTER_PROPOSAL_NO_ACTION,
        COUNTER_PROPOSAL_INCONCLUSIVE
    }

    public static MaterializedCounter materialize(TradeCounterProposalEnvelopePolicy.Envelope envelope) {
        Objects.requireNonNull(envelope, "envelope must not be null");

        return switch (envelope.action()) {
            case INCONCLUSIVE -> result(
                envelope,
                State.INCONCLUSIVE,
                ReasonCode.COUNTER_PROPOSAL_INCONCLUSIVE,
                null,
                null);
            case NO_ACTION -> result(
                envelope,
                State.NO_PACKAGE,
                ReasonCode.COUNTER_PROPOSAL_NO_ACTION,
                null,
                null);
            case COUNTER -> {
                var proposal = Objects.requireNonNull(
                    envelope.proposal(), "COUNTER envelope requires proposal");
                var sideA = envelope.originalSideA();
                var sideB = envelope.originalSideB();
                if (proposal.side() == TradeCounterValueTargetAnalyzer.Side.SIDE_A) {
                    sideA = apply(sideA, proposal);
                } else {
                    sideB = apply(sideB, proposal);
                }
                yield result(
                    envelope,
                    State.MATERIALIZED,
                    ReasonCode.GOVERNED_COUNTER_MATERIALIZED,
                    sideA,
                    sideB);
            }
        };
    }

    private static TradeAssetAnalyzer.TradePackage apply(
        TradeAssetAnalyzer.TradePackage original,
        TradeCounterProposalPolicy.Proposal proposal) {
        List<String> players = new ArrayList<>(original.playerIds());
        List<String> picks = new ArrayList<>(original.draftPickIds());
        List<String> target = proposal.assetType() == TradeCounterSingleAssetCandidateAnalyzer.AssetType.PLAYER
            ? players : picks;

        switch (proposal.adjustmentType()) {
            case ADD_ASSET_TO_LOWER_PACKAGE -> {
                if (target.contains(proposal.assetId())) {
                    throw new IllegalArgumentException("counter materialization cannot add duplicate asset");
                }
                target.add(proposal.assetId());
            }
            case REMOVE_ASSET_FROM_HIGHER_PACKAGE -> {
                if (!target.remove(proposal.assetId())) {
                    throw new IllegalArgumentException("counter materialization cannot remove missing asset");
                }
            }
        }

        if (players.isEmpty() && picks.isEmpty()) {
            throw new IllegalArgumentException("counter materialization cannot produce an empty trade side");
        }
        return new TradeAssetAnalyzer.TradePackage(List.copyOf(players), List.copyOf(picks));
    }

    private static MaterializedCounter result(
        TradeCounterProposalEnvelopePolicy.Envelope envelope,
        State state,
        ReasonCode reasonCode,
        TradeAssetAnalyzer.TradePackage revisedSideA,
        TradeAssetAnalyzer.TradePackage revisedSideB) {
        return new MaterializedCounter(
            POLICY_ID,
            TradeCounterProposalEnvelopePolicy.POLICY_ID,
            TradeCounterProposalPolicy.POLICY_ID,
            envelope.leagueId(),
            envelope.season(),
            envelope.source(),
            envelope.minimumAsOfDate(),
            envelope.perspective(),
            state,
            reasonCode,
            envelope.originalSideA(),
            envelope.originalSideB(),
            revisedSideA,
            revisedSideB);
    }

    public record MaterializedCounter(
        String policyId,
        String envelopePolicyId,
        String proposalPolicyId,
        String leagueId,
        int season,
        String source,
        LocalDate minimumAsOfDate,
        TradeTeamPerspectiveRecommendationPolicy.Perspective perspective,
        State state,
        ReasonCode reasonCode,
        TradeAssetAnalyzer.TradePackage originalSideA,
        TradeAssetAnalyzer.TradePackage originalSideB,
        TradeAssetAnalyzer.TradePackage revisedSideA,
        TradeAssetAnalyzer.TradePackage revisedSideB) {
        public MaterializedCounter {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            if (!TradeCounterProposalEnvelopePolicy.POLICY_ID.equals(envelopePolicyId)) {
                throw new IllegalArgumentException("unexpected envelopePolicyId");
            }
            if (!TradeCounterProposalPolicy.POLICY_ID.equals(proposalPolicyId)) {
                throw new IllegalArgumentException("unexpected proposalPolicyId");
            }
            if (leagueId == null || leagueId.isBlank()) throw new IllegalArgumentException("leagueId must not be blank");
            if (season < 1999 || season > 2100) throw new IllegalArgumentException("invalid season");
            if (source == null || source.isBlank()) throw new IllegalArgumentException("source must not be blank");
            Objects.requireNonNull(perspective, "perspective must not be null");
            Objects.requireNonNull(state, "state must not be null");
            Objects.requireNonNull(reasonCode, "reasonCode must not be null");
            Objects.requireNonNull(originalSideA, "originalSideA must not be null");
            Objects.requireNonNull(originalSideB, "originalSideB must not be null");
            if (state == State.MATERIALIZED) {
                Objects.requireNonNull(revisedSideA, "MATERIALIZED requires revisedSideA");
                Objects.requireNonNull(revisedSideB, "MATERIALIZED requires revisedSideB");
            } else if (revisedSideA != null || revisedSideB != null) {
                throw new IllegalArgumentException("non-materialized state cannot carry revised packages");
            }
        }
    }
}
