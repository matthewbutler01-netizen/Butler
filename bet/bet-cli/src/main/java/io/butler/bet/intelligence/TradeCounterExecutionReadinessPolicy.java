package io.butler.bet.intelligence;

import java.util.Objects;

/**
 * Read-only execution-readiness gate for a trusted persisted counter authorization.
 * This policy never consumes a grant and never performs an external action.
 */
public final class TradeCounterExecutionReadinessPolicy {
    public static final String POLICY_ID =
        "trade-counter-execution-readiness-v1-trusted-grant-fresh-replay-no-consume";

    private TradeCounterExecutionReadinessPolicy() {}

    public enum State {
        READY,
        DRIFTED,
        INCONCLUSIVE,
        BLOCKED_ALREADY_CONSUMED,
        BLOCKED_MISSING_REPLAY_CONTEXT
    }

    public static Result assess(
        TradeCounterAuthorizationPolicy.AuthorizationGrant grant,
        boolean consumed,
        boolean replayContextAvailable,
        TradeCounterProposalIdentityPolicy.Identity freshIdentity) {
        Objects.requireNonNull(grant, "grant must not be null");

        if (consumed) {
            if (freshIdentity != null) {
                throw new IllegalArgumentException(
                    "consumed grant readiness must not evaluate fresh proposal identity");
            }
            return blocked(
                grant,
                State.BLOCKED_ALREADY_CONSUMED,
                "Trusted authorization grant was already consumed.");
        }

        if (!replayContextAvailable) {
            if (freshIdentity != null) {
                throw new IllegalArgumentException(
                    "missing replay context readiness must not evaluate fresh proposal identity");
            }
            return blocked(
                grant,
                State.BLOCKED_MISSING_REPLAY_CONTEXT,
                "Trusted authorization grant has no immutable original trade replay context.");
        }

        Objects.requireNonNull(
            freshIdentity,
            "freshIdentity must not be null when active grant replay context is available");
        var revalidation = TradeCounterAuthorizationPolicy.revalidate(grant, freshIdentity);
        return switch (revalidation.state()) {
            case MATCH -> new Result(
                POLICY_ID,
                TradeCounterAuthorizationPolicy.POLICY_ID,
                TradeCounterProposalIdentityPolicy.POLICY_ID,
                grant.grantId(),
                grant.proposalFingerprint(),
                freshIdentity.fingerprint(),
                grant.action(),
                grant.destination(),
                State.READY,
                revalidation.state(),
                "Fresh governed proposal identity exactly matches the trusted authorization grant.");
            case DRIFTED -> new Result(
                POLICY_ID,
                TradeCounterAuthorizationPolicy.POLICY_ID,
                TradeCounterProposalIdentityPolicy.POLICY_ID,
                grant.grantId(),
                grant.proposalFingerprint(),
                freshIdentity.fingerprint(),
                grant.action(),
                grant.destination(),
                State.DRIFTED,
                revalidation.state(),
                revalidation.reason());
            case INCONCLUSIVE -> new Result(
                POLICY_ID,
                TradeCounterAuthorizationPolicy.POLICY_ID,
                TradeCounterProposalIdentityPolicy.POLICY_ID,
                grant.grantId(),
                grant.proposalFingerprint(),
                null,
                grant.action(),
                grant.destination(),
                State.INCONCLUSIVE,
                revalidation.state(),
                revalidation.reason());
        };
    }

    private static Result blocked(
        TradeCounterAuthorizationPolicy.AuthorizationGrant grant,
        State state,
        String reason) {
        return new Result(
            POLICY_ID,
            TradeCounterAuthorizationPolicy.POLICY_ID,
            TradeCounterProposalIdentityPolicy.POLICY_ID,
            grant.grantId(),
            grant.proposalFingerprint(),
            null,
            grant.action(),
            grant.destination(),
            state,
            null,
            reason);
    }

    public record Result(
        String policyId,
        String authorizationPolicyId,
        String identityPolicyId,
        String grantId,
        String authorizedFingerprint,
        String freshFingerprint,
        TradeCounterAuthorizationPolicy.Action action,
        TradeCounterAuthorizationPolicy.Destination destination,
        State state,
        TradeCounterAuthorizationPolicy.RevalidationState revalidationState,
        String reason) {
        public Result {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            if (!TradeCounterAuthorizationPolicy.POLICY_ID.equals(authorizationPolicyId)) {
                throw new IllegalArgumentException("unexpected authorizationPolicyId");
            }
            if (!TradeCounterProposalIdentityPolicy.POLICY_ID.equals(identityPolicyId)) {
                throw new IllegalArgumentException("unexpected identityPolicyId");
            }
            if (grantId == null || grantId.isBlank()) throw new IllegalArgumentException("grantId must not be blank");
            requireFingerprint(authorizedFingerprint, "authorizedFingerprint");
            Objects.requireNonNull(action, "action must not be null");
            Objects.requireNonNull(destination, "destination must not be null");
            Objects.requireNonNull(state, "state must not be null");
            if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason must not be blank");

            switch (state) {
                case READY -> {
                    if (revalidationState != TradeCounterAuthorizationPolicy.RevalidationState.MATCH) {
                        throw new IllegalArgumentException("READY requires MATCH revalidation");
                    }
                    requireFingerprint(freshFingerprint, "freshFingerprint");
                    if (!authorizedFingerprint.equals(freshFingerprint)) {
                        throw new IllegalArgumentException("READY requires equal fingerprints");
                    }
                }
                case DRIFTED -> {
                    if (revalidationState != TradeCounterAuthorizationPolicy.RevalidationState.DRIFTED) {
                        throw new IllegalArgumentException("DRIFTED requires DRIFTED revalidation");
                    }
                    if (freshFingerprint != null) requireFingerprint(freshFingerprint, "freshFingerprint");
                }
                case INCONCLUSIVE -> {
                    if (revalidationState != TradeCounterAuthorizationPolicy.RevalidationState.INCONCLUSIVE) {
                        throw new IllegalArgumentException("INCONCLUSIVE requires INCONCLUSIVE revalidation");
                    }
                    if (freshFingerprint != null) {
                        throw new IllegalArgumentException("INCONCLUSIVE cannot carry freshFingerprint");
                    }
                }
                case BLOCKED_ALREADY_CONSUMED, BLOCKED_MISSING_REPLAY_CONTEXT -> {
                    if (revalidationState != null) {
                        throw new IllegalArgumentException("blocked readiness cannot carry revalidation state");
                    }
                    if (freshFingerprint != null) {
                        throw new IllegalArgumentException("blocked readiness cannot carry freshFingerprint");
                    }
                }
            }
        }
    }

    private static void requireFingerprint(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be lowercase SHA-256");
        }
    }
}
