package io.butler.bet.intelligence;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Binds a governed counter proposal to the explicit team perspective and original trade packages.
 * The envelope verifies that the selected add/remove operation is valid against those packages.
 */
public final class TradeCounterProposalEnvelopePolicy {
    public static final String POLICY_ID =
        "trade-counter-proposal-envelope-v1-original-trade-perspective-bound";

    private TradeCounterProposalEnvelopePolicy() {}

    public static Envelope bind(
        TradeCounterProposalPolicy.Result result,
        TradeTeamPerspectiveRecommendationPolicy.Perspective perspective,
        TradeAssetAnalyzer.TradePackage sideA,
        TradeAssetAnalyzer.TradePackage sideB) {
        Objects.requireNonNull(result, "result must not be null");
        Objects.requireNonNull(perspective, "perspective must not be null");
        var normalizedA = normalize(sideA, "sideA");
        var normalizedB = normalize(sideB, "sideB");
        rejectOverlap(normalizedA, normalizedB);

        if (result.action() == TradeCounterProposalPolicy.Action.COUNTER) {
            validateProposalAgainstOriginalTrade(result.proposal(), normalizedA, normalizedB);
        }

        return new Envelope(
            POLICY_ID,
            TradeCounterProposalPolicy.POLICY_ID,
            TradeTeamPerspectiveRecommendationPolicy.POLICY_ID,
            result.leagueId(),
            result.season(),
            result.source(),
            result.minimumAsOfDate(),
            perspective,
            normalizedA,
            normalizedB,
            result.action(),
            result.reasonCode(),
            result.proposal());
    }

    private static void validateProposalAgainstOriginalTrade(
        TradeCounterProposalPolicy.Proposal proposal,
        TradeAssetAnalyzer.TradePackage sideA,
        TradeAssetAnalyzer.TradePackage sideB) {
        Objects.requireNonNull(proposal, "COUNTER proposal must not be null");
        var target = proposal.side() == TradeCounterValueTargetAnalyzer.Side.SIDE_A ? sideA : sideB;
        boolean inSideA = contains(sideA, proposal.assetType(), proposal.assetId());
        boolean inSideB = contains(sideB, proposal.assetType(), proposal.assetId());

        switch (proposal.adjustmentType()) {
            case ADD_ASSET_TO_LOWER_PACKAGE -> {
                if (inSideA || inSideB) {
                    throw new IllegalArgumentException(
                        "counter add asset must not already appear in either original trade package");
                }
            }
            case REMOVE_ASSET_FROM_HIGHER_PACKAGE -> {
                if (!contains(target, proposal.assetType(), proposal.assetId())) {
                    throw new IllegalArgumentException(
                        "counter remove asset must exist on the governed original trade side");
                }
                if (assetCount(target) <= 1) {
                    throw new IllegalArgumentException(
                        "counter remove operation cannot leave the governed trade side empty");
                }
            }
        }
    }

    private static boolean contains(
        TradeAssetAnalyzer.TradePackage tradePackage,
        TradeCounterSingleAssetCandidateAnalyzer.AssetType assetType,
        String assetId) {
        return switch (assetType) {
            case PLAYER -> tradePackage.playerIds().contains(assetId);
            case DRAFT_PICK -> tradePackage.draftPickIds().contains(assetId);
        };
    }

    private static int assetCount(TradeAssetAnalyzer.TradePackage tradePackage) {
        return tradePackage.playerIds().size() + tradePackage.draftPickIds().size();
    }

    private static TradeAssetAnalyzer.TradePackage normalize(
        TradeAssetAnalyzer.TradePackage tradePackage,
        String field) {
        Objects.requireNonNull(tradePackage, field + " must not be null");
        List<String> players = normalizeIds(tradePackage.playerIds(), field + ".playerIds");
        List<String> picks = normalizeIds(tradePackage.draftPickIds(), field + ".draftPickIds");
        if (players.isEmpty() && picks.isEmpty()) {
            throw new IllegalArgumentException(field + " must contain at least one asset");
        }
        return new TradeAssetAnalyzer.TradePackage(players, picks);
    }

    private static List<String> normalizeIds(List<String> ids, String field) {
        Objects.requireNonNull(ids, field + " must not be null");
        Set<String> normalized = new LinkedHashSet<>();
        for (String id : ids) {
            String value = requireText(id, field + " entry");
            if (!normalized.add(value)) {
                throw new IllegalArgumentException(field + " contains duplicate asset: " + value);
            }
        }
        return List.copyOf(normalized);
    }

    private static void rejectOverlap(
        TradeAssetAnalyzer.TradePackage sideA,
        TradeAssetAnalyzer.TradePackage sideB) {
        Set<String> players = new HashSet<>(sideA.playerIds());
        if (sideB.playerIds().stream().anyMatch(players::contains)) {
            throw new IllegalArgumentException("player appears on both original trade sides");
        }
        Set<String> picks = new HashSet<>(sideA.draftPickIds());
        if (sideB.draftPickIds().stream().anyMatch(picks::contains)) {
            throw new IllegalArgumentException("draft pick appears on both original trade sides");
        }
    }

    public record Envelope(
        String policyId,
        String proposalPolicyId,
        String perspectivePolicyId,
        String leagueId,
        int season,
        String source,
        LocalDate minimumAsOfDate,
        TradeTeamPerspectiveRecommendationPolicy.Perspective perspective,
        TradeAssetAnalyzer.TradePackage originalSideA,
        TradeAssetAnalyzer.TradePackage originalSideB,
        TradeCounterProposalPolicy.Action action,
        TradeCounterProposalPolicy.ReasonCode reasonCode,
        TradeCounterProposalPolicy.Proposal proposal) {
        public Envelope {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            if (!TradeCounterProposalPolicy.POLICY_ID.equals(proposalPolicyId)) {
                throw new IllegalArgumentException("unexpected proposalPolicyId");
            }
            if (!TradeTeamPerspectiveRecommendationPolicy.POLICY_ID.equals(perspectivePolicyId)) {
                throw new IllegalArgumentException("unexpected perspectivePolicyId");
            }
            leagueId = requireText(leagueId, "leagueId");
            if (season < 1999 || season > 2100) throw new IllegalArgumentException("invalid season");
            source = requireText(source, "source");
            Objects.requireNonNull(perspective, "perspective must not be null");
            Objects.requireNonNull(originalSideA, "originalSideA must not be null");
            Objects.requireNonNull(originalSideB, "originalSideB must not be null");
            Objects.requireNonNull(action, "action must not be null");
            Objects.requireNonNull(reasonCode, "reasonCode must not be null");
            if (action == TradeCounterProposalPolicy.Action.COUNTER && proposal == null) {
                throw new IllegalArgumentException("COUNTER envelope requires proposal");
            }
            if (action != TradeCounterProposalPolicy.Action.COUNTER && proposal != null) {
                throw new IllegalArgumentException("non-COUNTER envelope cannot carry proposal");
            }
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
