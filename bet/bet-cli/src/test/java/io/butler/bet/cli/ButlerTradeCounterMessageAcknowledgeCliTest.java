package io.butler.bet.cli;

import io.butler.bet.data.TradeCounterExecutionRequestRepository;
import io.butler.bet.integration.sleeper.SleeperManualCounterHandoffRepository;
import io.butler.bet.integration.sleeper.SleeperManualCounterHandoffService;
import io.butler.bet.integration.sleeper.SleeperManualMessageAcknowledgmentPolicy;
import io.butler.bet.integration.sleeper.SleeperManualMessageAcknowledgmentRepository;
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

class ButlerTradeCounterMessageAcknowledgeCliTest {
    private static final Instant PRESENTED_AT = Instant.parse("2026-09-04T23:20:00Z");
    private static final Instant ACKNOWLEDGED_AT = PRESENTED_AT.plusSeconds(30);

    @Test
    void parsesPreviewAndExactConfirmationWithoutNormalizingRawValue() {
        var preview = ButlerTradeCounterMessageAcknowledgeCli.parse(
            new String[]{"trade", "counter-message-ack", "grant-1"});
        assertEquals("grant-1", preview.grantId());
        assertEquals(null, preview.confirmation());

        var exact = ButlerTradeCounterMessageAcknowledgeCli.parse(
            new String[]{"trade", "counter-message-ack", "grant-1", "--confirm", "SENT_EXACT_MESSAGE"});
        assertTrue(ButlerTradeCounterMessageAcknowledgeCli.exactConfirmation(exact.confirmation()));

        var padded = ButlerTradeCounterMessageAcknowledgeCli.parse(
            new String[]{"trade", "counter-message-ack", "grant-1", "--confirm", " SENT_EXACT_MESSAGE "});
        assertFalse(ButlerTradeCounterMessageAcknowledgeCli.exactConfirmation(padded.confirmation()));
        assertThrows(IllegalArgumentException.class, () -> ButlerTradeCounterMessageAcknowledgeCli.parse(
            new String[]{"trade", "counter-message-ack", "grant-1", "--Confirm", "SENT_EXACT_MESSAGE"}));
    }

    @Test
    void previewPrintsRequiredConfirmationAndNoMutationClaim() {
        String output = capture(() -> ButlerTradeCounterMessageAcknowledgeCli.printRequired(handoff()));

        assertTrue(output.contains("Required exact confirmation: SENT_EXACT_MESSAGE"));
        assertTrue(output.contains("No acknowledgment was recorded."));
        assertTrue(output.contains("does not send the message"));
        assertTrue(output.contains("does not"));
        assertFalse(output.contains("Execution state: SUCCEEDED"));
    }

    @Test
    void rejectedVariantNeverClaimsAcknowledgment() {
        String output = capture(() -> ButlerTradeCounterMessageAcknowledgeCli.printRejected(
            handoff(), " SENT_EXACT_MESSAGE "));

        assertTrue(output.contains("Acknowledgment state: NOT_ACKNOWLEDGED"));
        assertTrue(output.contains("no acknowledgment was recorded"));
        assertTrue(output.contains("authorization remains unconsumed"));
    }

    @Test
    void acceptedOutputSeparatesUserEvidenceFromButlerExecution() {
        var handoff = handoff();
        var request = new SleeperManualMessageAcknowledgmentPolicy.AcknowledgmentRequest(
            handoff.grantId(), handoff.handoffId(), handoff.payloadSha256(),
            SleeperManualMessageAcknowledgmentPolicy.REQUIRED_CONFIRMATION, ACKNOWLEDGED_AT);
        var decision = SleeperManualMessageAcknowledgmentPolicy.acknowledge(handoff, request);
        var stored = new SleeperManualMessageAcknowledgmentRepository.StoredAcknowledgment(
            "ack-1",
            SleeperManualMessageAcknowledgmentRepository.JOURNAL_POLICY_ID,
            decision.policyId(),
            decision.handoffJournalPolicyId(),
            decision.handoffServiceId(),
            decision.claimId(),
            decision.attemptId(),
            decision.grantId(),
            decision.handoffId(),
            decision.payloadSha256(),
            decision.destination().id(),
            decision.suppliedConfirmation(),
            decision.localCompletionEligibility().name(),
            decision.presentedAt(),
            decision.acknowledgedAt(),
            decision.reason(),
            ACKNOWLEDGED_AT);
        var record = new SleeperManualMessageAcknowledgmentRepository.RecordResult(
            SleeperManualMessageAcknowledgmentRepository.RecordState.RECORDED,
            stored,
            "Recorded.");

        String output = capture(() -> ButlerTradeCounterMessageAcknowledgeCli.print(
            handoff, decision, record));

        assertTrue(output.contains("Acknowledgment state: ACKNOWLEDGED"));
        assertTrue(output.contains("Acknowledgment journal state: RECORDED"));
        assertTrue(output.contains("Butler did not send it"));
        assertTrue(output.contains("does not mark execution SUCCEEDED"));
        assertTrue(output.contains("does not consume authorization"));
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
