package io.butler.bet.cli;

import io.butler.bet.data.TradeCounterExecutionAttemptRepository;
import io.butler.bet.execution.TradeCounterExecutionOutcomePolicy;
import io.butler.bet.integration.sleeper.SleeperManualCounterNoActionAcknowledgmentPolicy;
import io.butler.bet.integration.sleeper.SleeperManualCounterNoActionAcknowledgmentRepository;
import io.butler.bet.integration.sleeper.SleeperManualCounterNoActionOutcomeCoordinator;
import io.butler.bet.intelligence.TradeCounterAuthorizationPolicy;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerTradeCounterNoActionFinalizeCliTest {
    private static final Instant ACKNOWLEDGED_AT = Instant.parse("2026-09-05T00:40:00Z");
    private static final Instant APPLIED_AT = ACKNOWLEDGED_AT.plusSeconds(10);

    @Test
    void parsesExactlyOneTrustedGrantId() {
        assertEquals("grant-1", ButlerTradeCounterNoActionFinalizeCli.parseGrantId(
            new String[]{"trade", "counter-no-action-finalize", "grant-1"}));

        assertThrows(IllegalArgumentException.class, () -> ButlerTradeCounterNoActionFinalizeCli.parseGrantId(
            new String[]{"trade", "counter-no-action-finalize"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerTradeCounterNoActionFinalizeCli.parseGrantId(
            new String[]{"trade", "counter-no-action-finalize", "grant-1", "extra"}));
    }

    @Test
    void appliedOutputReportsFailedConsumeAndFreshAuthorizationRequirement() {
        var outcome = outcome("grant-1", "claim-1");
        var result = new SleeperManualCounterNoActionOutcomeCoordinator.ApplyResult(
            SleeperManualCounterNoActionOutcomeCoordinator.ApplyState.APPLIED,
            outcome,
            "Applied.");

        String output = capture(() -> ButlerTradeCounterNoActionFinalizeCli.print(
            "grant-1", "claim-1", result));

        assertTrue(output.contains("Finalization state: APPLIED"));
        assertTrue(output.contains("Execution terminal state: FAILED"));
        assertTrue(output.contains("Authorization disposition: CONSUME"));
        assertTrue(output.contains("Authorized action: SUBMIT_COUNTER_TRADE"));
        assertTrue(output.contains("Authorized destination: LEAGUE:league-1"));
        assertTrue(output.contains("Any retry now requires a fresh explicit authorization"));
        assertTrue(output.contains("performs no Sleeper request or external action"));
    }

    @Test
    void notFoundOutputReportsNoTerminalOrAuthorizationMutation() {
        var result = new SleeperManualCounterNoActionOutcomeCoordinator.ApplyResult(
            SleeperManualCounterNoActionOutcomeCoordinator.ApplyState.NOT_FOUND,
            null,
            "Durable no-action acknowledgment was not found.");

        String output = capture(() -> ButlerTradeCounterNoActionFinalizeCli.print(
            "grant-1", "claim-1", result));

        assertTrue(output.contains("Finalization state: NOT_FOUND"));
        assertTrue(output.contains("No terminal execution state or authorization change was applied"));
        assertTrue(output.contains("performs no Sleeper request or external action"));
    }

    @Test
    void mismatchedOutcomeProvenanceFailsClosed() {
        var result = new SleeperManualCounterNoActionOutcomeCoordinator.ApplyResult(
            SleeperManualCounterNoActionOutcomeCoordinator.ApplyState.APPLIED,
            outcome("grant-other", "claim-1"),
            "Applied.");

        assertThrows(IllegalStateException.class, () ->
            ButlerTradeCounterNoActionFinalizeCli.print("grant-1", "claim-1", result));
    }

    @Test
    void unavailableOutputStatesNoLocalOrSleeperAction() {
        String output = capture(() -> ButlerTradeCounterNoActionFinalizeCli.printUnavailable("grant-1"));

        assertTrue(output.contains("finalization unavailable"));
        assertTrue(output.contains("No terminal execution state or authorization change was applied"));
        assertTrue(output.contains("No Sleeper action occurred"));
    }

    private static SleeperManualCounterNoActionOutcomeCoordinator.StoredOutcome outcome(
        String grantId,
        String claimId) {
        return new SleeperManualCounterNoActionOutcomeCoordinator.StoredOutcome(
            "outcome-1",
            SleeperManualCounterNoActionOutcomeCoordinator.COORDINATOR_POLICY_ID,
            SleeperManualCounterNoActionAcknowledgmentRepository.JOURNAL_POLICY_ID,
            SleeperManualCounterNoActionAcknowledgmentPolicy.POLICY_ID,
            "ack-1",
            claimId,
            "attempt-1",
            grantId,
            "handoff-1",
            "b".repeat(64),
            TradeCounterAuthorizationPolicy.Action.SUBMIT_COUNTER_TRADE,
            new TradeCounterAuthorizationPolicy.Destination(
                TradeCounterAuthorizationPolicy.DestinationType.LEAGUE, "league-1"),
            SleeperManualCounterNoActionAcknowledgmentPolicy.REQUIRED_CONFIRMATION,
            ACKNOWLEDGED_AT,
            TradeCounterExecutionAttemptRepository.State.FAILED,
            TradeCounterExecutionOutcomePolicy.GrantDisposition.CONSUME,
            "User explicitly acknowledged no external action.",
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
