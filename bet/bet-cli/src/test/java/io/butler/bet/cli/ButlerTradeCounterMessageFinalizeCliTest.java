package io.butler.bet.cli;

import io.butler.bet.data.TradeCounterExecutionAttemptRepository;
import io.butler.bet.integration.sleeper.SleeperManualMessageAcknowledgmentPolicy;
import io.butler.bet.integration.sleeper.SleeperManualMessageAcknowledgmentRepository;
import io.butler.bet.integration.sleeper.SleeperManualMessageOutcomeCoordinator;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerTradeCounterMessageFinalizeCliTest {
    private static final Instant ACKNOWLEDGED_AT = Instant.parse("2026-09-04T23:30:00Z");
    private static final Instant APPLIED_AT = ACKNOWLEDGED_AT.plusSeconds(5);

    @Test
    void parsesExactlyOneTrustedGrantId() {
        assertEquals("grant-1", ButlerTradeCounterMessageFinalizeCli.parseGrantId(
            new String[]{"trade", "counter-message-finalize", "grant-1"}));

        assertThrows(IllegalArgumentException.class, () -> ButlerTradeCounterMessageFinalizeCli.parseGrantId(
            new String[]{"trade", "counter-message-finalize"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerTradeCounterMessageFinalizeCli.parseGrantId(
            new String[]{"trade", "counter-message-finalize", "grant-1", "extra"}));
    }

    @Test
    void appliedOutputShowsTerminalSuccessAndConsumptionAsLocalOnly() {
        var result = new SleeperManualMessageOutcomeCoordinator.ApplyResult(
            SleeperManualMessageOutcomeCoordinator.ApplyState.APPLIED,
            outcome("grant-1", "claim-1"),
            "Applied.");

        String output = capture(() -> ButlerTradeCounterMessageFinalizeCli.print(
            "grant-1", "claim-1", result));

        assertTrue(output.contains("Finalization state: APPLIED"));
        assertTrue(output.contains("Acknowledgment ID: ack-1"));
        assertTrue(output.contains("Execution terminal state: SUCCEEDED"));
        assertTrue(output.contains("Authorization disposition: CONSUME"));
        assertTrue(output.contains("Butler did not send or modify the Sleeper message"));
        assertTrue(output.contains("no Sleeper write or private API call"));
    }

    @Test
    void notFoundOutputMakesNoTerminalOrGrantMutationClaim() {
        var result = new SleeperManualMessageOutcomeCoordinator.ApplyResult(
            SleeperManualMessageOutcomeCoordinator.ApplyState.NOT_FOUND,
            null,
            "Durable acknowledgment was not found.");

        String output = capture(() -> ButlerTradeCounterMessageFinalizeCli.print(
            "grant-1", "claim-1", result));

        assertTrue(output.contains("Finalization state: NOT_FOUND"));
        assertTrue(output.contains("No terminal execution state or authorization change was applied."));
        assertFalse(output.contains("Execution terminal state: SUCCEEDED"));
        assertFalse(output.contains("Authorization disposition: CONSUME"));
    }

    @Test
    void outputRejectsOutcomeFromDifferentTrustedCoordinates() {
        var result = new SleeperManualMessageOutcomeCoordinator.ApplyResult(
            SleeperManualMessageOutcomeCoordinator.ApplyState.APPLIED,
            outcome("grant-other", "claim-1"),
            "Applied.");

        assertThrows(IllegalStateException.class, () -> ButlerTradeCounterMessageFinalizeCli.print(
            "grant-1", "claim-1", result));
    }

    @Test
    void unavailableOutputStatesNoMutationAndNoSleeperAction() {
        String output = capture(() -> ButlerTradeCounterMessageFinalizeCli.printUnavailable("grant-1"));

        assertTrue(output.contains("finalization unavailable"));
        assertTrue(output.contains("No terminal execution state or authorization change was applied."));
        assertTrue(output.contains("No Sleeper action occurred."));
    }

    private static SleeperManualMessageOutcomeCoordinator.StoredOutcome outcome(
        String grantId,
        String claimId) {
        return new SleeperManualMessageOutcomeCoordinator.StoredOutcome(
            "outcome-1",
            SleeperManualMessageOutcomeCoordinator.COORDINATOR_POLICY_ID,
            SleeperManualMessageAcknowledgmentRepository.JOURNAL_POLICY_ID,
            SleeperManualMessageAcknowledgmentPolicy.POLICY_ID,
            "ack-1",
            claimId,
            "attempt-1",
            grantId,
            "handoff-1",
            "a".repeat(64),
            "manager-22",
            SleeperManualMessageAcknowledgmentPolicy.REQUIRED_CONFIRMATION,
            ACKNOWLEDGED_AT,
            TradeCounterExecutionAttemptRepository.State.SUCCEEDED,
            "CONSUME",
            "User explicitly acknowledged sending the exact trusted message.",
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
