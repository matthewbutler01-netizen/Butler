package io.butler.bet.execution;

import io.butler.bet.data.TradeCounterExecutionAttemptRepository;
import io.butler.bet.data.TradeCounterExecutionRequestRepository;
import io.butler.bet.intelligence.TradeCounterAuthorizationPolicy;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeCounterExecutionOutcomePolicyTest {
    private static final String PAYLOAD = "I'd counter if you add Player X.";
    private static final String HASH = sha256(PAYLOAD);

    @Test
    void dryRunConfirmationProducesNoMutationDirective() {
        var request = request();
        var result = result(
            TradeCounterActionExecutor.Mode.DRY_RUN,
            TradeCounterActionExecutor.State.DRY_RUN_CONFIRMED,
            "Dry run only; no external call.");

        var directive = TradeCounterExecutionOutcomePolicy.classify(request, result);

        assertEquals(TradeCounterExecutionOutcomePolicy.POLICY_ID, directive.policyId());
        assertEquals(TradeCounterExecutionOutcomePolicy.OutcomeState.DRY_RUN_NO_MUTATION,
            directive.state());
        assertNull(directive.attemptTerminalState());
        assertEquals(TradeCounterExecutionOutcomePolicy.GrantDisposition.RETAIN_ACTIVE,
            directive.grantDisposition());
        assertFalse(directive.reconciliationRequired());
    }

    @Test
    void affirmativeLivePlatformAcceptanceFinalizesSuccessAndConsumesAuthorization() {
        var directive = TradeCounterExecutionOutcomePolicy.classify(
            request(),
            result(
                TradeCounterActionExecutor.Mode.LIVE,
                TradeCounterActionExecutor.State.DISPATCHED,
                "Platform acknowledged and accepted the requested action."));

        assertEquals(TradeCounterExecutionOutcomePolicy.OutcomeState.CONFIRMED_SUCCESS,
            directive.state());
        assertEquals(TradeCounterExecutionAttemptRepository.State.SUCCEEDED,
            directive.attemptTerminalState());
        assertEquals(TradeCounterExecutionOutcomePolicy.GrantDisposition.CONSUME,
            directive.grantDisposition());
        assertFalse(directive.reconciliationRequired());
    }

    @Test
    void definiteNoActionFailureFinalizesFailedAndClosesAuthorization() {
        var directive = TradeCounterExecutionOutcomePolicy.classify(
            request(),
            result(
                TradeCounterActionExecutor.Mode.LIVE,
                TradeCounterActionExecutor.State.DEFINITE_FAILURE,
                "Platform rejected request before creating any remote action."));

        assertEquals(TradeCounterExecutionOutcomePolicy.OutcomeState.CONFIRMED_NO_ACTION_FAILURE,
            directive.state());
        assertEquals(TradeCounterExecutionAttemptRepository.State.FAILED,
            directive.attemptTerminalState());
        assertEquals(TradeCounterExecutionOutcomePolicy.GrantDisposition.CONSUME,
            directive.grantDisposition());
        assertFalse(directive.reconciliationRequired());
        assertTrue(directive.reason().contains("fresh explicit authorization"));
    }

    @Test
    void unknownOutcomeCreatesTerminalUnknownAndRetainsGrantAsRetryLock() {
        var directive = unknownDirective();

        assertEquals(TradeCounterExecutionOutcomePolicy.OutcomeState.UNKNOWN_PENDING_RECONCILIATION,
            directive.state());
        assertEquals(TradeCounterExecutionAttemptRepository.State.UNKNOWN,
            directive.attemptTerminalState());
        assertEquals(TradeCounterExecutionOutcomePolicy.GrantDisposition.RETAIN_ACTIVE,
            directive.grantDisposition());
        assertTrue(directive.reconciliationRequired());
        assertTrue(directive.reason().contains("require reconciliation"));
    }

    @Test
    void unknownRemoteSuccessReconciliationClosesGrantWithoutRewritingHistoricalAttempt() {
        var resolution = TradeCounterExecutionOutcomePolicy.reconcileUnknown(
            unknownDirective(),
            TradeCounterExecutionOutcomePolicy.UnknownResolution.REMOTE_ACTION_CONFIRMED,
            "Platform lookup found the exact remote action created by this execution attempt.");

        assertEquals(TradeCounterExecutionOutcomePolicy.UnknownResolution.REMOTE_ACTION_CONFIRMED,
            resolution.resolution());
        assertEquals(TradeCounterExecutionOutcomePolicy.GrantDisposition.CONSUME,
            resolution.grantDisposition());
        assertTrue(resolution.remoteActionConfirmed());
        assertTrue(resolution.reason().contains("historical attempt remains UNKNOWN"));
    }

    @Test
    void unknownNoActionReconciliationClosesOldGrantAndRequiresFreshAuthorizationForRetry() {
        var resolution = TradeCounterExecutionOutcomePolicy.reconcileUnknown(
            unknownDirective(),
            TradeCounterExecutionOutcomePolicy.UnknownResolution.REMOTE_NO_ACTION_CONFIRMED,
            "Platform lookup proved no matching message or trade exists.");

        assertEquals(TradeCounterExecutionOutcomePolicy.UnknownResolution.REMOTE_NO_ACTION_CONFIRMED,
            resolution.resolution());
        assertEquals(TradeCounterExecutionOutcomePolicy.GrantDisposition.CONSUME,
            resolution.grantDisposition());
        assertFalse(resolution.remoteActionConfirmed());
        assertTrue(resolution.reason().contains("fresh explicit authorization"));
    }

    @Test
    void executorResultMustMatchTrustedRequestIdentity() {
        var mismatch = new TradeCounterActionExecutor.ExecutionResult(
            "fake-live",
            TradeCounterActionExecutor.Mode.LIVE,
            TradeCounterActionExecutor.State.DISPATCHED,
            "other-claim",
            "attempt-1",
            "grant-1",
            HASH,
            "Platform accepted action.");

        assertThrows(IllegalArgumentException.class, () ->
            TradeCounterExecutionOutcomePolicy.classify(request(), mismatch));
    }

    @Test
    void dryRunAndLiveStateVocabularyCannotBeCrossUsed() {
        assertThrows(IllegalArgumentException.class, () ->
            TradeCounterExecutionOutcomePolicy.classify(
                request(),
                result(
                    TradeCounterActionExecutor.Mode.DRY_RUN,
                    TradeCounterActionExecutor.State.UNKNOWN,
                    "Impossible dry-run unknown.")));

        assertThrows(IllegalArgumentException.class, () ->
            TradeCounterExecutionOutcomePolicy.classify(
                request(),
                result(
                    TradeCounterActionExecutor.Mode.LIVE,
                    TradeCounterActionExecutor.State.DRY_RUN_CONFIRMED,
                    "Impossible live dry-run result.")));
    }

    @Test
    void onlyUnknownPendingDirectiveCanBeReconciled() {
        var success = TradeCounterExecutionOutcomePolicy.classify(
            request(),
            result(
                TradeCounterActionExecutor.Mode.LIVE,
                TradeCounterActionExecutor.State.DISPATCHED,
                "Platform accepted action."));

        assertThrows(IllegalArgumentException.class, () ->
            TradeCounterExecutionOutcomePolicy.reconcileUnknown(
                success,
                TradeCounterExecutionOutcomePolicy.UnknownResolution.REMOTE_ACTION_CONFIRMED,
                "not applicable"));
        assertThrows(IllegalArgumentException.class, () ->
            TradeCounterExecutionOutcomePolicy.reconcileUnknown(
                unknownDirective(),
                TradeCounterExecutionOutcomePolicy.UnknownResolution.REMOTE_NO_ACTION_CONFIRMED,
                " "));
    }

    private static TradeCounterExecutionOutcomePolicy.Directive unknownDirective() {
        return TradeCounterExecutionOutcomePolicy.classify(
            request(),
            result(
                TradeCounterActionExecutor.Mode.LIVE,
                TradeCounterActionExecutor.State.UNKNOWN,
                "Request may have reached platform but acknowledgement was lost."));
    }

    private static TradeCounterExecutionRequestRepository.ExecutionRequest request() {
        return new TradeCounterExecutionRequestRepository.ExecutionRequest(
            TradeCounterExecutionRequestRepository.REQUEST_POLICY_ID,
            "claim-1",
            "attempt-1",
            "grant-1",
            "1f7c8beb37acdcc2f2d0f93e75a36bfb3bc5b4828e730330696ee05e8f1182f8",
            TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE,
            new TradeCounterAuthorizationPolicy.Destination(
                TradeCounterAuthorizationPolicy.DestinationType.MANAGER,
                "manager-22"),
            TradeCounterExecutionAttemptRepository.PayloadKind.NEGOTIATION_MESSAGE_TEXT,
            PAYLOAD,
            HASH);
    }

    private static TradeCounterActionExecutor.ExecutionResult result(
        TradeCounterActionExecutor.Mode mode,
        TradeCounterActionExecutor.State state,
        String detail) {
        return new TradeCounterActionExecutor.ExecutionResult(
            "fake-executor",
            mode,
            state,
            "claim-1",
            "attempt-1",
            "grant-1",
            HASH,
            detail);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
