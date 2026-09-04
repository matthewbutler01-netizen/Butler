package io.butler.bet.intelligence;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Governs explicit, one-shot authorization intent for a fingerprinted COUNTER proposal.
 * This policy does not send a message or submit a trade.
 */
public final class TradeCounterAuthorizationPolicy {
    public static final String POLICY_ID =
        "trade-counter-authorization-v1-explicit-fingerprint-action-destination-once";
    public static final int MAX_USES = 1;

    private TradeCounterAuthorizationPolicy() {}

    public enum Action {
        SEND_NEGOTIATION_MESSAGE,
        SUBMIT_COUNTER_TRADE
    }

    public enum DestinationType {
        MANAGER,
        LEAGUE
    }

    public enum DecisionState {
        AUTHORIZED,
        REJECTED
    }

    public enum RevalidationState {
        MATCH,
        DRIFTED,
        INCONCLUSIVE
    }

    public record Destination(DestinationType type, String id) {
        public Destination {
            Objects.requireNonNull(type, "destination type must not be null");
            id = requireStableId(id, "destination id");
        }
    }

    public static AuthorizationRequest request(
        TradeCounterProposalIdentityPolicy.Identity identity,
        Action action,
        Destination destination) {
        Objects.requireNonNull(identity, "identity must not be null");
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(destination, "destination must not be null");
        requireIdentified(identity);
        requireCompatibleDestination(identity, action, destination);

        String confirmation = "AUTHORIZE_ONCE action=" + action
            + " proposal=" + identity.fingerprint()
            + " destination=" + destination.type() + ":" + destination.id();
        return new AuthorizationRequest(
            POLICY_ID,
            TradeCounterProposalIdentityPolicy.POLICY_ID,
            identity.leagueId(),
            identity.season(),
            identity.source(),
            identity.minimumAsOfDate(),
            identity.perspective(),
            identity.fingerprint(),
            action,
            destination,
            confirmation,
            MAX_USES);
    }

    public static AuthorizationDecision authorize(
        AuthorizationRequest request,
        String confirmationText) {
        Objects.requireNonNull(request, "request must not be null");
        if (!request.requiredConfirmation().equals(confirmationText)) {
            return new AuthorizationDecision(
                POLICY_ID,
                DecisionState.REJECTED,
                "Explicit authorization text did not exactly match the governed request.",
                null);
        }
        var grant = new AuthorizationGrant(
            POLICY_ID,
            UUID.randomUUID().toString(),
            Instant.now(),
            request.leagueId(),
            request.season(),
            request.source(),
            request.minimumAsOfDate(),
            request.perspective(),
            request.proposalFingerprint(),
            request.action(),
            request.destination(),
            MAX_USES);
        return new AuthorizationDecision(
            POLICY_ID,
            DecisionState.AUTHORIZED,
            "Explicit one-shot authorization granted for the exact fingerprint, action, and destination.",
            grant);
    }

    /**
     * Compares an authorization grant with a newly re-run proposal identity.
     * The caller is responsible for producing currentIdentity from fresh evidence immediately before execution.
     */
    public static RevalidationResult revalidate(
        AuthorizationGrant grant,
        TradeCounterProposalIdentityPolicy.Identity currentIdentity) {
        Objects.requireNonNull(grant, "grant must not be null");
        Objects.requireNonNull(currentIdentity, "currentIdentity must not be null");
        if (currentIdentity.state() == TradeCounterProposalIdentityPolicy.State.INCONCLUSIVE) {
            return new RevalidationResult(
                POLICY_ID,
                RevalidationState.INCONCLUSIVE,
                "Fresh proposal identity is inconclusive.");
        }
        if (currentIdentity.state() != TradeCounterProposalIdentityPolicy.State.IDENTIFIED) {
            return new RevalidationResult(
                POLICY_ID,
                RevalidationState.DRIFTED,
                "Fresh proposal no longer has an executable counter identity.");
        }
        boolean same = grant.leagueId().equals(currentIdentity.leagueId())
            && grant.season() == currentIdentity.season()
            && grant.source().equals(currentIdentity.source())
            && Objects.equals(grant.minimumAsOfDate(), currentIdentity.minimumAsOfDate())
            && grant.perspective() == currentIdentity.perspective()
            && grant.proposalFingerprint().equals(currentIdentity.fingerprint());
        return same
            ? new RevalidationResult(
                POLICY_ID,
                RevalidationState.MATCH,
                "Fresh proposal identity exactly matches the authorized proposal.")
            : new RevalidationResult(
                POLICY_ID,
                RevalidationState.DRIFTED,
                "Fresh proposal identity differs from the authorized proposal.");
    }

    private static void requireIdentified(TradeCounterProposalIdentityPolicy.Identity identity) {
        if (identity.state() != TradeCounterProposalIdentityPolicy.State.IDENTIFIED
            || identity.fingerprint() == null) {
            throw new IllegalArgumentException(
                "counter authorization requires an IDENTIFIED proposal fingerprint");
        }
    }

    private static void requireCompatibleDestination(
        TradeCounterProposalIdentityPolicy.Identity identity,
        Action action,
        Destination destination) {
        switch (action) {
            case SEND_NEGOTIATION_MESSAGE -> {
                if (destination.type() != DestinationType.MANAGER) {
                    throw new IllegalArgumentException(
                        "SEND_NEGOTIATION_MESSAGE requires MANAGER destination");
                }
            }
            case SUBMIT_COUNTER_TRADE -> {
                if (destination.type() != DestinationType.LEAGUE) {
                    throw new IllegalArgumentException(
                        "SUBMIT_COUNTER_TRADE requires LEAGUE destination");
                }
                if (!identity.leagueId().equals(destination.id())) {
                    throw new IllegalArgumentException(
                        "SUBMIT_COUNTER_TRADE destination must match proposal leagueId");
                }
            }
        }
    }

    public record AuthorizationRequest(
        String policyId,
        String identityPolicyId,
        String leagueId,
        int season,
        String source,
        java.time.LocalDate minimumAsOfDate,
        TradeTeamPerspectiveRecommendationPolicy.Perspective perspective,
        String proposalFingerprint,
        Action action,
        Destination destination,
        String requiredConfirmation,
        int maxUses) {
        public AuthorizationRequest {
            requirePolicy(policyId);
            if (!TradeCounterProposalIdentityPolicy.POLICY_ID.equals(identityPolicyId)) {
                throw new IllegalArgumentException("unexpected identityPolicyId");
            }
            leagueId = requireText(leagueId, "leagueId");
            if (season < 1999 || season > 2100) throw new IllegalArgumentException("invalid season");
            source = requireText(source, "source");
            Objects.requireNonNull(perspective, "perspective must not be null");
            proposalFingerprint = requireFingerprint(proposalFingerprint);
            Objects.requireNonNull(action, "action must not be null");
            Objects.requireNonNull(destination, "destination must not be null");
            requiredConfirmation = requireText(requiredConfirmation, "requiredConfirmation");
            if (maxUses != MAX_USES) throw new IllegalArgumentException("authorization must be single-use");
        }
    }

    public record AuthorizationGrant(
        String policyId,
        String grantId,
        Instant grantedAt,
        String leagueId,
        int season,
        String source,
        java.time.LocalDate minimumAsOfDate,
        TradeTeamPerspectiveRecommendationPolicy.Perspective perspective,
        String proposalFingerprint,
        Action action,
        Destination destination,
        int maxUses) {
        public AuthorizationGrant {
            requirePolicy(policyId);
            grantId = requireText(grantId, "grantId");
            Objects.requireNonNull(grantedAt, "grantedAt must not be null");
            leagueId = requireText(leagueId, "leagueId");
            if (season < 1999 || season > 2100) throw new IllegalArgumentException("invalid season");
            source = requireText(source, "source");
            Objects.requireNonNull(perspective, "perspective must not be null");
            proposalFingerprint = requireFingerprint(proposalFingerprint);
            Objects.requireNonNull(action, "action must not be null");
            Objects.requireNonNull(destination, "destination must not be null");
            if (maxUses != MAX_USES) throw new IllegalArgumentException("authorization must be single-use");
        }
    }

    public record AuthorizationDecision(
        String policyId,
        DecisionState state,
        String reason,
        AuthorizationGrant grant) {
        public AuthorizationDecision {
            requirePolicy(policyId);
            Objects.requireNonNull(state, "state must not be null");
            reason = requireText(reason, "reason");
            if (state == DecisionState.AUTHORIZED && grant == null) {
                throw new IllegalArgumentException("AUTHORIZED decision requires grant");
            }
            if (state == DecisionState.REJECTED && grant != null) {
                throw new IllegalArgumentException("REJECTED decision cannot carry grant");
            }
        }
    }

    public record RevalidationResult(
        String policyId,
        RevalidationState state,
        String reason) {
        public RevalidationResult {
            requirePolicy(policyId);
            Objects.requireNonNull(state, "state must not be null");
            reason = requireText(reason, "reason");
        }
    }

    private static void requirePolicy(String policyId) {
        if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
    }

    private static String requireFingerprint(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("proposalFingerprint must be lowercase SHA-256");
        }
        return value;
    }

    private static String requireStableId(String value, String field) {
        value = requireText(value, field);
        if (!value.matches("[A-Za-z0-9._:@/-]{1,200}")) {
            throw new IllegalArgumentException(field + " must be a stable identifier without whitespace");
        }
        return value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
