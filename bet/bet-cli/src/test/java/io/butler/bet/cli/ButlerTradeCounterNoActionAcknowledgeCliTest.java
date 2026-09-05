package io.butler.bet.cli;

import io.butler.bet.data.TradeCounterExecutionAttemptRepository;
import io.butler.bet.data.TradeCounterExecutionRequestRepository;
import io.butler.bet.integration.sleeper.SleeperManualCounterHandoffRepository;
import io.butler.bet.integration.sleeper.SleeperManualCounterHandoffService;
import io.butler.bet.integration.sleeper.SleeperManualCounterNoActionAcknowledgmentPolicy;
import io.butler.bet.integration.sleeper.SleeperManualCounterNoActionAcknowledgmentRepository;
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

class ButlerTradeCounterNoActionAcknowledgeCliTest {
    private static final Instant PRESENTED_AT = Instant.parse("2026-09-05T00:35:00Z");
    private static final Instant ACKNOWLEDGED_AT = PRESENTED_AT.plusSeconds(20);
    private static final Instant RECORDED_AT = ACKNOWLEDGED_AT.plusSeconds(1);

    @Test
    void parserRequiresOneGrantAndPreservesRawOptionalConfirmation() {
        var preview = ButlerTradeCounterNoActionAcknowledgeCli.parse(
            new String[]{"trade", "counter-no-action-ack", "grant-1"});
        assertEquals("grant-1", preview.grantId());
        assertTrue(preview.confirmation() == null);

        var exact = ButlerTradeCounterNoActionAcknowledgeCli.parse(
            new String[]{"trade", "counter-no-action-ack", "grant-1", "--confirm", "NO_EXTERNAL_ACTION_TAKEN"});
        assertEquals("NO_EXTERNAL_ACTION_TAKEN", exact.confirmation());

        var spaced = ButlerTradeCounterNoActionAcknowledgeCli.parse(
            new String[]{"trade", "counter-no-action-ack", "grant-1", "--confirm", " NO_EXTERNAL_ACTION_TAKEN "});
        assertEquals(" NO_EXTERNAL_ACTION_TAKEN ", spaced.confirmation());

        assertThrows(IllegalArgumentException.class, () -> ButlerTradeCounterNoActionAcknowledgeCli.parse(
            new String[]{"trade", "counter-no-action-ack"}));
    }

    @Test
    void exactConfirmationIsByteForByte() {
        assertTrue(ButlerTradeCounterNoActionAcknowledgeCli.exactConfirmation("NO_EXTERNAL_ACTION_TAKEN"));
        assertFalse(ButlerTradeCounterNoActionAcknowledgeCli.exactConfirmation(" NO_EXTERNAL_ACTION_TAKEN "));
        assertFalse(ButlerTradeCounterNoActionAcknowledgeCli.exactConfirmation("no_external_action_taken"));
    }

    @Test
    void previewPrintsExactCoordinatesAndNoMutationContract() {
        String output = capture(() -> ButlerTradeCounterNoActionAcknowledgeCli.printRequired(tradeHandoff()));

        assertTrue(output.contains("manual no-action acknowledgment required"));
        assertTrue(output.contains("Required exact confirmation: NO_EXTERNAL_ACTION_TAKEN"));
        assertTrue(output.contains("Use this only if this exact presented handoff was not acted on externally"));
        assertTrue(output.contains("Execution remains unchanged"));
        assertTrue(output.contains("authorization remains unconsumed"));
        assertFalse(output.contains("Execution terminal state: FAILED"));
    }

    @Test
    void acceptedOutputShowsEligibilityButExplicitlyDoesNotFinalize() {
        var handoff = tradeHandoff();
        var decision = decision(handoff);
        var stored = stored(handoff, decision);
        var record = new SleeperManualCounterNoActionAcknowledgmentRepository.RecordResult(
            SleeperManualCounterNoActionAcknowledgmentRepository.RecordState.RECORDED,
            stored,
            "Recorded.");

        String output = capture(() -> ButlerTradeCounterNoActionAcknowledgeCli.print(
            handoff, decision, record));

        assertTrue(output.contains("Local terminal eligibility: CONFIRMED_NO_ACTION_FAILURE"));
        assertTrue(output.contains("Eligible terminal state: FAILED"));
        assertTrue(output.contains("Eligible authorization disposition: CONSUME"));
        assertTrue(output.contains("Durable local evidence now records"));
        assertTrue(output.contains("does not mark execution FAILED"));
        assertTrue(output.contains("does not consume authorization"));
        assertTrue(output.contains("performs no Sleeper request or external action"));
    }

    @Test
    void conflictingSentMessageEvidenceOutputDoesNotClaimNoActionWasRecorded() {
        var handoff = messageHandoff();
        var decision = decision(handoff);
        var record = new SleeperManualCounterNoActionAcknowledgmentRepository.RecordResult(
            SleeperManualCounterNoActionAcknowledgmentRepository.RecordState.CONFLICTING_SUCCESS_EVIDENCE,
            null,
            "Durable SENT_EXACT_MESSAGE evidence already exists.");

        String output = capture(() -> ButlerTradeCounterNoActionAcknowledgeCli.print(
            handoff, decision, record));

        assertTrue(output.contains("CONFLICTING_SUCCESS_EVIDENCE"));
        assertTrue(output.contains("SENT_EXACT_MESSAGE"));
        assertTrue(output.contains("No durable no-action evidence was recorded"));
        assertTrue(output.contains("does not mark execution FAILED"));
    }

    @Test
    void rejectedOutputKeepsAttemptAndAuthorizationUnchanged() {
        String output = capture(() -> ButlerTradeCounterNoActionAcknowledgeCli.printRejected(
            messageHandoff(), "NO_EXTERNAL_ACTION_TAKEN "));

        assertTrue(output.contains("acknowledgment rejected"));
        assertTrue(output.contains("NOT_ACKNOWLEDGED"));
        assertTrue(output.contains("not an exact match"));
        assertTrue(output.contains("Execution remains unchanged"));
        assertTrue(output.contains("authorization remains unconsumed"));
    }

    private static SleeperManualCounterNoActionAcknowledgmentPolicy.Decision decision(
        SleeperManualCounterHandoffRepository.PresentedHandoff handoff) {
        return SleeperManualCounterNoActionAcknowledgmentPolicy.acknowledge(
            handoff,
            new SleeperManualCounterNoActionAcknowledgmentPolicy.AcknowledgmentRequest(
                handoff.grantId(), handoff.handoffId(), handoff.payloadSha256(),
                SleeperManualCounterNoActionAcknowledgmentPolicy.REQUIRED_CONFIRMATION,
                ACKNOWLEDGED_AT));
    }

    private static SleeperManualCounterNoActionAcknowledgmentRepository.StoredAcknowledgment stored(
        SleeperManualCounterHandoffRepository.PresentedHandoff handoff,
        SleeperManualCounterNoActionAcknowledgmentPolicy.Decision decision) {
        return new SleeperManualCounterNoActionAcknowledgmentRepository.StoredAcknowledgment(
            "no-action-ack-1",
            SleeperManualCounterNoActionAcknowledgmentRepository.JOURNAL_POLICY_ID,
            decision.policyId(),
            decision.handoffJournalPolicyId(),
            decision.handoffServiceId(),
            handoff.claimId(),
            handoff.attemptId(),
            handoff.grantId(),
            handoff.handoffId(),
            handoff.payloadSha256(),
            handoff.action(),
            handoff.destination(),
            decision.suppliedConfirmation(),
            decision.localTerminalEligibility(),
            TradeCounterExecutionAttemptRepository.State.FAILED,
            decision.grantDisposition(),
            handoff.presentedAt(),
            ACKNOWLEDGED_AT,
            decision.reason(),
            RECORDED_AT);
    }

    private static SleeperManualCounterHandoffRepository.PresentedHandoff messageHandoff() {
        return handoff(
            "handoff-message", "claim-message", "attempt-message", "grant-message",
            TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE,
            new TradeCounterAuthorizationPolicy.Destination(
                TradeCounterAuthorizationPolicy.DestinationType.MANAGER, "manager-22"),
            TradeCounterExecutionAttemptRepository.PayloadKind.NEGOTIATION_MESSAGE_TEXT.name(),
            SleeperManualCounterHandoffService.ReconciliationMode.NO_OFFICIAL_READBACK,
            "b".repeat(64));
    }

    private static SleeperManualCounterHandoffRepository.PresentedHandoff tradeHandoff() {
        return handoff(
            "handoff-trade", "claim-trade", "attempt-trade", "grant-trade",
            TradeCounterAuthorizationPolicy.Action.SUBMIT_COUNTER_TRADE,
            new TradeCounterAuthorizationPolicy.Destination(
                TradeCounterAuthorizationPolicy.DestinationType.LEAGUE, "league-1"),
            TradeCounterExecutionAttemptRepository.PayloadKind.COUNTER_TRADE_REQUEST_JSON.name(),
            SleeperManualCounterHandoffService.ReconciliationMode.SLEEPER_TRANSACTION_READBACK,
            "c".repeat(64));
    }

    private static SleeperManualCounterHandoffRepository.PresentedHandoff handoff(
        String handoffId,
        String claimId,
        String attemptId,
        String grantId,
        TradeCounterAuthorizationPolicy.Action action,
        TradeCounterAuthorizationPolicy.Destination destination,
        String payloadKind,
        SleeperManualCounterHandoffService.ReconciliationMode reconciliationMode,
        String payloadSha256) {
        return new SleeperManualCounterHandoffRepository.PresentedHandoff(
            handoffId,
            SleeperManualCounterHandoffRepository.JOURNAL_POLICY_ID,
            SleeperManualCounterHandoffService.SERVICE_ID,
            SleeperPlatformCapabilityPolicy.POLICY_ID,
            TradeCounterExecutionRequestRepository.REQUEST_POLICY_ID,
            claimId,
            attemptId,
            grantId,
            "a".repeat(64),
            action,
            destination,
            payloadKind,
            payloadSha256,
            reconciliationMode,
            PRESENTED_AT);
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
