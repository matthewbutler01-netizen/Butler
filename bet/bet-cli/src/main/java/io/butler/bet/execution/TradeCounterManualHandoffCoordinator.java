package io.butler.bet.execution;

import io.butler.bet.data.Database;
import io.butler.bet.data.TradeCounterExecutionAttemptRepository;
import io.butler.bet.data.TradeCounterExecutionClaimRepository;
import io.butler.bet.integration.sleeper.SleeperManualCounterHandoffRepository;
import io.butler.bet.intelligence.TradeCounterAuthorizationPolicy;
import io.butler.bet.intelligence.TradeCounterExecutionReadinessPolicy;
import io.butler.bet.intelligence.TradeCounterMaterializedPackagePolicy;
import io.butler.bet.intelligence.TradeCounterNegotiationMessagePolicy;
import io.butler.bet.intelligence.TradeCounterProposalIdentityPolicy;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;

/**
 * Coordinates the durable local path from fresh READY authorization to manual Sleeper handoff presentation.
 * No external platform write occurs here.
 */
public final class TradeCounterManualHandoffCoordinator {
    public static final String COORDINATOR_ID =
        "trade-counter-manual-handoff-coordinator-v1-payload-prepare-claim-present";

    private final TradeCounterExecutionAttemptRepository attempts;
    private final TradeCounterExecutionClaimRepository claims;
    private final SleeperManualCounterHandoffRepository handoffs;

    public TradeCounterManualHandoffCoordinator(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.attempts = new TradeCounterExecutionAttemptRepository(database);
        this.claims = new TradeCounterExecutionClaimRepository(database);
        this.handoffs = new SleeperManualCounterHandoffRepository(database);
    }

    public Result coordinate(
        TradeCounterAuthorizationPolicy.AuthorizationGrant grant,
        TradeCounterExecutionReadinessPolicy.Result readiness,
        TradeCounterProposalIdentityPolicy.Identity freshIdentity,
        TradeCounterMaterializedPackagePolicy.MaterializedCounter materialized,
        TradeCounterNegotiationMessagePolicy.MessageResult message,
        Instant at) throws SQLException {
        Objects.requireNonNull(grant, "grant must not be null");
        Objects.requireNonNull(readiness, "readiness must not be null");
        Objects.requireNonNull(freshIdentity, "freshIdentity must not be null");
        Objects.requireNonNull(materialized, "materialized must not be null");
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(at, "at must not be null");
        requireReadinessMatchesGrant(grant, readiness);

        if (readiness.state() != TradeCounterExecutionReadinessPolicy.State.READY) {
            return result(
                State.READINESS_NOT_READY,
                readiness.reason(),
                null, null, null, null, null);
        }

        var payloadResult = TradeCounterExecutionPayloadPolicy.materialize(
            grant, freshIdentity, materialized, message);
        if (payloadResult.state() != TradeCounterExecutionPayloadPolicy.State.PAYLOAD_AVAILABLE) {
            return result(
                State.PAYLOAD_NOT_AVAILABLE,
                "Governed execution payload is " + payloadResult.state()
                    + ": " + payloadResult.reasonCode(),
                null, null, null, null, null);
        }
        var payload = payloadResult.payload();

        var preparation = attempts.prepare(
            grant.grantId(), payload.payloadKind(), payload.payloadText(), at);
        var attempt = preparation.attempt();

        var claimResult = claims.claim(attempt.attemptId(), readiness, at);
        if (claimResult.state() != TradeCounterExecutionClaimRepository.ClaimState.CLAIMED
            && claimResult.state() != TradeCounterExecutionClaimRepository.ClaimState.ALREADY_CLAIMED) {
            return result(
                State.CLAIM_FAILED,
                claimResult.reason(),
                attempt.attemptId(), null, null,
                attempt.payloadKind(), attempt.payloadSha256());
        }
        var claim = claimResult.claim();

        var handoffResult = handoffs.recordPresented(claim.claimId(), at);
        if (handoffResult.state() == SleeperManualCounterHandoffRepository.RecordState.NOT_AVAILABLE) {
            return result(
                State.HANDOFF_NOT_AVAILABLE,
                handoffResult.reason(),
                attempt.attemptId(), claim.claimId(), null,
                attempt.payloadKind(), attempt.payloadSha256());
        }
        var handoff = handoffResult.handoff();
        State state = handoffResult.state() == SleeperManualCounterHandoffRepository.RecordState.PRESENTED
            ? State.HANDOFF_PRESENTED
            : State.HANDOFF_ALREADY_PRESENTED;
        return result(
            state,
            handoffResult.reason(),
            attempt.attemptId(),
            claim.claimId(),
            handoff.handoffId(),
            attempt.payloadKind(),
            attempt.payloadSha256());
    }

    private static void requireReadinessMatchesGrant(
        TradeCounterAuthorizationPolicy.AuthorizationGrant grant,
        TradeCounterExecutionReadinessPolicy.Result readiness) {
        boolean matches = grant.grantId().equals(readiness.grantId())
            && grant.proposalFingerprint().equals(readiness.authorizedFingerprint())
            && grant.action() == readiness.action()
            && grant.destination().equals(readiness.destination());
        if (!matches) {
            throw new IllegalArgumentException("readiness coordinates must match the trusted authorization grant");
        }
    }

    private static Result result(
        State state,
        String reason,
        String attemptId,
        String claimId,
        String handoffId,
        TradeCounterExecutionAttemptRepository.PayloadKind payloadKind,
        String payloadSha256) {
        return new Result(
            COORDINATOR_ID,
            TradeCounterExecutionPayloadPolicy.POLICY_ID,
            TradeCounterExecutionAttemptRepository.JOURNAL_POLICY_ID,
            TradeCounterExecutionClaimRepository.CLAIM_POLICY_ID,
            SleeperManualCounterHandoffRepository.JOURNAL_POLICY_ID,
            state,
            reason,
            attemptId,
            claimId,
            handoffId,
            payloadKind,
            payloadSha256);
    }

    public enum State {
        HANDOFF_PRESENTED,
        HANDOFF_ALREADY_PRESENTED,
        READINESS_NOT_READY,
        PAYLOAD_NOT_AVAILABLE,
        CLAIM_FAILED,
        HANDOFF_NOT_AVAILABLE
    }

    public record Result(
        String coordinatorId,
        String payloadPolicyId,
        String attemptPolicyId,
        String claimPolicyId,
        String handoffJournalPolicyId,
        State state,
        String reason,
        String attemptId,
        String claimId,
        String handoffId,
        TradeCounterExecutionAttemptRepository.PayloadKind payloadKind,
        String payloadSha256) {
        public Result {
            if (!COORDINATOR_ID.equals(coordinatorId)) throw new IllegalArgumentException("unexpected coordinatorId");
            if (!TradeCounterExecutionPayloadPolicy.POLICY_ID.equals(payloadPolicyId)) {
                throw new IllegalArgumentException("unexpected payloadPolicyId");
            }
            if (!TradeCounterExecutionAttemptRepository.JOURNAL_POLICY_ID.equals(attemptPolicyId)) {
                throw new IllegalArgumentException("unexpected attemptPolicyId");
            }
            if (!TradeCounterExecutionClaimRepository.CLAIM_POLICY_ID.equals(claimPolicyId)) {
                throw new IllegalArgumentException("unexpected claimPolicyId");
            }
            if (!SleeperManualCounterHandoffRepository.JOURNAL_POLICY_ID.equals(handoffJournalPolicyId)) {
                throw new IllegalArgumentException("unexpected handoffJournalPolicyId");
            }
            Objects.requireNonNull(state, "state must not be null");
            if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason must not be blank");
            boolean presented = state == State.HANDOFF_PRESENTED || state == State.HANDOFF_ALREADY_PRESENTED;
            if (presented) {
                requireText(attemptId, "attemptId");
                requireText(claimId, "claimId");
                requireText(handoffId, "handoffId");
                Objects.requireNonNull(payloadKind, "presented result requires payloadKind");
                requireFingerprint(payloadSha256, "payloadSha256");
            }
        }
    }

    private static void requireFingerprint(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be lowercase SHA-256");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
