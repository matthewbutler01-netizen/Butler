package io.butler.bet.intelligence;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Converts a bound read-only counter proposal into neutral first-person negotiation wording.
 * This policy generates text only; it never sends a message or mutates a trade.
 */
public final class TradeCounterNegotiationMessagePolicy {
    public static final String POLICY_ID =
        "trade-counter-negotiation-message-v1-bound-neutral-first-person";

    private TradeCounterNegotiationMessagePolicy() {}

    public enum State {
        MESSAGE_AVAILABLE,
        NO_MESSAGE,
        INCONCLUSIVE
    }

    public enum Actor {
        ME,
        OTHER_MANAGER
    }

    public enum ReasonCode {
        GOVERNED_COUNTER_MESSAGE_AVAILABLE,
        COUNTER_PROPOSAL_NO_ACTION,
        COUNTER_PROPOSAL_INCONCLUSIVE
    }

    public static MessageResult compose(TradeCounterProposalEnvelopePolicy.Envelope envelope) {
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
                State.NO_MESSAGE,
                ReasonCode.COUNTER_PROPOSAL_NO_ACTION,
                null,
                null);
            case COUNTER -> {
                var proposal = Objects.requireNonNull(
                    envelope.proposal(), "COUNTER envelope requires proposal");
                Actor actor = actor(envelope.perspective(), proposal.side());
                String text = render(actor, proposal);
                yield result(
                    envelope,
                    State.MESSAGE_AVAILABLE,
                    ReasonCode.GOVERNED_COUNTER_MESSAGE_AVAILABLE,
                    actor,
                    text);
            }
        };
    }

    private static Actor actor(
        TradeTeamPerspectiveRecommendationPolicy.Perspective perspective,
        TradeCounterValueTargetAnalyzer.Side proposalSide) {
        boolean ownSide = (perspective == TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM
                && proposalSide == TradeCounterValueTargetAnalyzer.Side.SIDE_A)
            || (perspective == TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_B_TEAM
                && proposalSide == TradeCounterValueTargetAnalyzer.Side.SIDE_B);
        return ownSide ? Actor.ME : Actor.OTHER_MANAGER;
    }

    private static String render(Actor actor, TradeCounterProposalPolicy.Proposal proposal) {
        String asset = proposal.displayName();
        return switch (proposal.adjustmentType()) {
            case ADD_ASSET_TO_LOWER_PACKAGE -> actor == Actor.ME
                ? "I'd counter by adding " + asset + " to my side of the deal."
                : "I'd counter if you add " + asset + " to your side of the deal.";
            case REMOVE_ASSET_FROM_HIGHER_PACKAGE -> actor == Actor.ME
                ? "I'd counter by removing " + asset + " from my side of the deal."
                : "I'd counter if you remove " + asset + " from your side of the deal.";
        };
    }

    private static MessageResult result(
        TradeCounterProposalEnvelopePolicy.Envelope envelope,
        State state,
        ReasonCode reasonCode,
        Actor actor,
        String text) {
        return new MessageResult(
            POLICY_ID,
            TradeCounterProposalEnvelopePolicy.POLICY_ID,
            TradeCounterProposalPolicy.POLICY_ID,
            TradeTeamPerspectiveRecommendationPolicy.POLICY_ID,
            envelope.leagueId(),
            envelope.season(),
            envelope.source(),
            envelope.minimumAsOfDate(),
            envelope.perspective(),
            state,
            reasonCode,
            actor,
            text);
    }

    public record MessageResult(
        String policyId,
        String envelopePolicyId,
        String proposalPolicyId,
        String perspectivePolicyId,
        String leagueId,
        int season,
        String source,
        LocalDate minimumAsOfDate,
        TradeTeamPerspectiveRecommendationPolicy.Perspective perspective,
        State state,
        ReasonCode reasonCode,
        Actor actor,
        String text) {
        public MessageResult {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            if (!TradeCounterProposalEnvelopePolicy.POLICY_ID.equals(envelopePolicyId)) {
                throw new IllegalArgumentException("unexpected envelopePolicyId");
            }
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
            Objects.requireNonNull(state, "state must not be null");
            Objects.requireNonNull(reasonCode, "reasonCode must not be null");
            if (state == State.MESSAGE_AVAILABLE) {
                Objects.requireNonNull(actor, "MESSAGE_AVAILABLE requires actor");
                text = requireText(text, "text");
            } else {
                if (actor != null || text != null) {
                    throw new IllegalArgumentException(
                        "non-message state cannot carry actor or text");
                }
            }
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
