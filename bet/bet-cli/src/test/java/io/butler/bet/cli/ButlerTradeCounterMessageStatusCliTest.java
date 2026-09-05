package io.butler.bet.cli;

import io.butler.bet.data.TradeCounterExecutionAttemptRepository;
import io.butler.bet.data.TradeCounterExecutionRequestRepository;
import io.butler.bet.execution.TradeCounterExecutionOutcomePolicy;
import io.butler.bet.integration.sleeper.SleeperManualCounterHandoffRepository;
import io.butler.bet.integration.sleeper.SleeperManualCounterHandoffService;
import io.butler.bet.integration.sleeper.SleeperManualCounterNoActionAcknowledgmentPolicy;
import io.butler.bet.integration.sleeper.SleeperManualCounterNoActionAcknowledgmentRepository;
import io.butler.bet.integration.sleeper.SleeperManualCounterNoActionOutcomeCoordinator;
import io.butler.bet.integration.sleeper.SleeperManualMessageAcknowledgmentPolicy;
import io.butler.bet.integration.sleeper.SleeperManualMessageAcknowledgmentRepository;
import io.butler.bet.integration.sleeper.SleeperManualMessageOutcomeCoordinator;
import io.butler.bet.integration.sleeper.SleeperPlatformCapabilityPolicy;
import io.butler.bet.intelligence.TradeCounterAuthorizationPolicy;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerTradeCounterMessageStatusCliTest {
    private static final Instant PRESENTED_AT = Instant.parse("2026-09-04T23:20:00Z");
    private static final Instant ACKNOWLEDGED_AT = PRESENTED_AT.plusSeconds(30);
    private static final Instant APPLIED_AT = ACKNOWLEDGED_AT.plusSeconds(5);

    @Test
    void parsesExactlyOneTrustedGrantId() {
        assertEquals("grant-1", ButlerTradeCounterMessageStatusCli.parseGrantId(
            new String[]{"trade", "counter-message-status", "grant-1"}));

        assertThrows(IllegalArgumentException.class, () -> ButlerTradeCounterMessageStatusCli.parseGrantId(
            new String[]{"trade", "counter-message-status"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerTradeCounterMessageStatusCli.parseGrantId(
            new String[]{"trade", "counter-message-status", "grant-1", "extra"}));
    }

    @Test
    void pendingStatusReportsNoAcknowledgmentOrFinalizationAndNoMutation() {
        var status = ButlerTradeCounterMessageStatusCli.inspect(handoff(), null, null);

        assertEquals(ButlerTradeCounterMessageStatusCli.State.PENDING_ACKNOWLEDGMENT, status.state());
        String output = capture(() -> ButlerTradeCounterMessageStatusCli.print(status));
        assertTrue(output.contains("Sent-message acknowledgment evidence: NOT_RECORDED"));
        assertTrue(output.contains("No-action acknowledgment evidence: NOT_RECORDED"));
        assertTrue(output.contains("Finalization evidence: NOT_APPLIED"));
        assertTrue(output.contains("does not acknowledge, finalize, change execution state, or consume authorization"));
        assertTrue(output.contains("no Sleeper write or private API call"));
        assertFalse(output.contains("Execution terminal state: SUCCEEDED"));
        assertFalse(output.contains("Execution terminal state: FAILED"));
    }

    @Test
    void acknowledgedStatusKeepsHumanSendEvidenceSeparateFromButlerExecution() {
        var status = ButlerTradeCounterMessageStatusCli.inspect(handoff(), acknowledgment(), null);

        assertEquals(ButlerTradeCounterMessageStatusCli.State.ACKNOWLEDGED_PENDING_FINALIZATION, status.state());
        String output = capture(() -> ButlerTradeCounterMessageStatusCli.print(status));
        assertTrue(output.contains("Sent-message acknowledgment evidence: RECORDED"));
        assertTrue(output.contains("Human-send confirmation: SENT_EXACT_MESSAGE (Butler did not send it)."));
        assertTrue(output.contains("No-action acknowledgment evidence: NOT_RECORDED"));
        assertTrue(output.contains("Finalization evidence: NOT_APPLIED"));
        assertTrue(output.contains("counter-message-finalize"));
    }

    @Test
    void finalizedStatusReportsExistingSuccessEvidenceWithoutClaimingThisInspectionAppliedIt() {
        var status = ButlerTradeCounterMessageStatusCli.inspect(handoff(), acknowledgment(), successOutcome());

        assertEquals(ButlerTradeCounterMessageStatusCli.State.FINALIZED, status.state());
        String output = capture(() -> ButlerTradeCounterMessageStatusCli.print(status));
        assertTrue(output.contains("Finalization evidence: APPLIED_SUCCESS"));
        assertTrue(output.contains("Execution terminal state: SUCCEEDED"));
        assertTrue(output.contains("Authorization disposition: CONSUME"));
        assertTrue(output.contains("Inspection only"));
        assertTrue(output.contains("does not acknowledge, finalize, change execution state, or consume authorization"));
    }

    @Test
    void noActionAcknowledgedStatusDoesNotPretendMessageWasSent() {
        var status = ButlerTradeCounterMessageStatusCli.inspect(
            handoff(), null, null, noActionAcknowledgment(), null);

        assertEquals(
            ButlerTradeCounterMessageStatusCli.State.NO_ACTION_ACKNOWLEDGED_PENDING_FINALIZATION,
            status.state());
        String output = capture(() -> ButlerTradeCounterMessageStatusCli.print(status));
        assertTrue(output.contains("Sent-message acknowledgment evidence: NOT_RECORDED"));
        assertTrue(output.contains("No-action acknowledgment evidence: RECORDED"));
        assertTrue(output.contains("NO_EXTERNAL_ACTION_TAKEN"));
        assertTrue(output.contains("counter-no-action-finalize"));
        assertFalse(output.contains("Human-send confirmation: SENT_EXACT_MESSAGE"));
    }

    @Test
    void noActionFinalizedStatusReportsFailedConsumeAndFreshAuthorizationRequirement() {
        var status = ButlerTradeCounterMessageStatusCli.inspect(
            handoff(), null, null, noActionAcknowledgment(), noActionOutcome());

        assertEquals(ButlerTradeCounterMessageStatusCli.State.NO_ACTION_FINALIZED, status.state());
        String output = capture(() -> ButlerTradeCounterMessageStatusCli.print(status));
        assertTrue(output.contains("Finalization evidence: APPLIED_NO_ACTION"));
        assertTrue(output.contains("Execution terminal state: FAILED"));
        assertTrue(output.contains("Authorization disposition: CONSUME"));
        assertTrue(output.contains("retry requires fresh explicit authorization"));
        assertFalse(output.contains("Execution terminal state: SUCCEEDED"));
    }

    @Test
    void conflictingSentAndNoActionEvidenceFailsClosed() {
        assertThrows(IllegalStateException.class, () -> ButlerTradeCounterMessageStatusCli.inspect(
            handoff(), acknowledgment(), null, noActionAcknowledgment(), null));
    }

    @Test
    void noActionOutcomeWithoutNoActionAcknowledgmentFailsClosed() {
        assertThrows(IllegalStateException.class, () -> ButlerTradeCounterMessageStatusCli.inspect(
            handoff(), null, null, null, noActionOutcome()));
    }

    @Test
    void outcomeWithoutAcknowledgmentFailsClosed() {
        assertThrows(IllegalStateException.class, () ->
            ButlerTradeCounterMessageStatusCli.inspect(handoff(), null, successOutcome()));
    }

    @Test
    void mismatchedAcknowledgmentFailsClosed() {
        var wrong = acknowledgment("grant-other");
        assertThrows(IllegalStateException.class, () ->
            ButlerTradeCounterMessageStatusCli.inspect(handoff(), wrong, null));
    }

    @Test
    void tradeHandoffCannotBeInspectedAsMessageLifecycle() {
        assertThrows(IllegalStateException.class, () ->
            ButlerTradeCounterMessageStatusCli.inspect(tradeHandoff(), null, null));
    }

    @Test
    void unavailableOutputStatesInspectionOnlyAndNoSleeperAction() {
        String output = capture(() -> ButlerTradeCounterMessageStatusCli.printUnavailable("grant-1"));
        assertTrue(output.contains("lifecycle status unavailable"));
        assertTrue(output.contains("manual message handoff"));
        assertTrue(output.contains("no local lifecycle state changed"));
        assertTrue(output.contains("no Sleeper action occurred"));
    }

    private static SleeperManualCounterHandoffRepository.PresentedHandoff handoff() {
        return new SleeperManualCounterHandoffRepository.PresentedHandoff(
            "handoff-1",
            SleeperManualCounterHandoffRepository.JOURNAL_POLICY_ID,
            SleeperManualCounterHandoffService.SERVICE_ID,
            SleeperPlatformCapabilityPolicy.POLICY_ID,
            TradeCounterExecutionRequestRepository.REQUEST_POLICY_ID,
            "claim-1",
            "attempt-1",
            "grant-1",
            "a".repeat(64),
            TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE,
            new TradeCounterAuthorizationPolicy.Destination(
                TradeCounterAuthorizationPolicy.DestinationType.MANAGER, "manager-22"),
            "NEGOTIATION_MESSAGE_TEXT",
            "b".repeat(64),
            SleeperManualCounterHandoffService.ReconciliationMode.NO_OFFICIAL_READBACK,
            PRESENTED_AT);
    }

    private static SleeperManualCounterHandoffRepository.PresentedHandoff tradeHandoff() {
        return new SleeperManualCounterHandoffRepository.PresentedHandoff(
            "handoff-trade",
            SleeperManualCounterHandoffRepository.JOURNAL_POLICY_ID,
            SleeperManualCounterHandoffService.SERVICE_ID,
            SleeperPlatformCapabilityPolicy.POLICY_ID,
            TradeCounterExecutionRequestRepository.REQUEST_POLICY_ID,
            "claim-trade",
            "attempt-trade",
            "grant-trade",
            "c".repeat(64),
            TradeCounterAuthorizationPolicy.Action.SUBMIT_COUNTER_TRADE,
            new TradeCounterAuthorizationPolicy.Destination(
                TradeCounterAuthorizationPolicy.DestinationType.LEAGUE, "league-1"),
            "COUNTER_TRADE_REQUEST_JSON",
            "d".repeat(64),
            SleeperManualCounterHandoffService.ReconciliationMode.SLEEPER_TRANSACTION_READBACK,
            PRESENTED_AT);
    }

    private static SleeperManualMessageAcknowledgmentRepository.StoredAcknowledgment acknowledgment() {
        return acknowledgment("grant-1");
    }

    private static SleeperManualMessageAcknowledgmentRepository.StoredAcknowledgment acknowledgment(String grantId) {
        return new SleeperManualMessageAcknowledgmentRepository.StoredAcknowledgment(
            "ack-1",
            SleeperManualMessageAcknowledgmentRepository.JOURNAL_POLICY_ID,
            SleeperManualMessageAcknowledgmentPolicy.POLICY_ID,
            SleeperManualCounterHandoffRepository.JOURNAL_POLICY_ID,
            SleeperManualCounterHandoffService.SERVICE_ID,
            "claim-1",
            "attempt-1",
            grantId,
            "handoff-1",
            "b".repeat(64),
            "manager-22",
            SleeperManualMessageAcknowledgmentPolicy.REQUIRED_CONFIRMATION,
            SleeperManualMessageAcknowledgmentPolicy.LocalCompletionEligibility.MANUAL_MESSAGE_SUCCESS.name(),
            PRESENTED_AT,
            ACKNOWLEDGED_AT,
            "User explicitly acknowledged sending the exact trusted message.",
            ACKNOWLEDGED_AT);
    }

    private static SleeperManualMessageOutcomeCoordinator.StoredOutcome successOutcome() {
        return new SleeperManualMessageOutcomeCoordinator.StoredOutcome(
            "outcome-1",
            SleeperManualMessageOutcomeCoordinator.COORDINATOR_POLICY_ID,
            SleeperManualMessageAcknowledgmentRepository.JOURNAL_POLICY_ID,
            SleeperManualMessageAcknowledgmentPolicy.POLICY_ID,
            "ack-1",
            "claim-1",
            "attempt-1",
            "grant-1",
            "handoff-1",
            "b".repeat(64),
            "manager-22",
            SleeperManualMessageAcknowledgmentPolicy.REQUIRED_CONFIRMATION,
            ACKNOWLEDGED_AT,
            TradeCounterExecutionAttemptRepository.State.SUCCEEDED,
            "CONSUME",
            "User explicitly acknowledged sending the exact trusted message.",
            APPLIED_AT);
    }

    private static SleeperManualCounterNoActionAcknowledgmentRepository.StoredAcknowledgment noActionAcknowledgment() {
        return new SleeperManualCounterNoActionAcknowledgmentRepository.StoredAcknowledgment(
            "no-action-ack-1",
            SleeperManualCounterNoActionAcknowledgmentRepository.JOURNAL_POLICY_ID,
            SleeperManualCounterNoActionAcknowledgmentPolicy.POLICY_ID,
            SleeperManualCounterHandoffRepository.JOURNAL_POLICY_ID,
            SleeperManualCounterHandoffService.SERVICE_ID,
            "claim-1",
            "attempt-1",
            "grant-1",
            "handoff-1",
            "b".repeat(64),
            TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE,
            new TradeCounterAuthorizationPolicy.Destination(
                TradeCounterAuthorizationPolicy.DestinationType.MANAGER, "manager-22"),
            SleeperManualCounterNoActionAcknowledgmentPolicy.REQUIRED_CONFIRMATION,
            SleeperManualCounterNoActionAcknowledgmentPolicy.LocalTerminalEligibility.CONFIRMED_NO_ACTION_FAILURE,
            TradeCounterExecutionAttemptRepository.State.FAILED,
            TradeCounterExecutionOutcomePolicy.GrantDisposition.CONSUME,
            PRESENTED_AT,
            ACKNOWLEDGED_AT,
            "User explicitly acknowledged no external action was taken.",
            ACKNOWLEDGED_AT);
    }

    private static SleeperManualCounterNoActionOutcomeCoordinator.StoredOutcome noActionOutcome() {
        return new SleeperManualCounterNoActionOutcomeCoordinator.StoredOutcome(
            "no-action-outcome-1",
            SleeperManualCounterNoActionOutcomeCoordinator.COORDINATOR_POLICY_ID,
            SleeperManualCounterNoActionAcknowledgmentRepository.JOURNAL_POLICY_ID,
            SleeperManualCounterNoActionAcknowledgmentPolicy.POLICY_ID,
            "no-action-ack-1",
            "claim-1",
            "attempt-1",
            "grant-1",
            "handoff-1",
            "b".repeat(64),
            TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE,
            new TradeCounterAuthorizationPolicy.Destination(
                TradeCounterAuthorizationPolicy.DestinationType.MANAGER, "manager-22"),
            SleeperManualCounterNoActionAcknowledgmentPolicy.REQUIRED_CONFIRMATION,
            ACKNOWLEDGED_AT,
            TradeCounterExecutionAttemptRepository.State.FAILED,
            TradeCounterExecutionOutcomePolicy.GrantDisposition.CONSUME,
            "User explicitly acknowledged no external action was taken.",
            APPLIED_AT);
    }

    private static String capture(Runnable runnable) {
        PrintStream previous = System.out;
        var bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes));
            runnable.run();
            return bytes.toString();
        } finally {
            System.setOut(previous);
        }
    }
}
