package io.butler.bet.cli;

import io.butler.bet.data.TradeCounterExecutionAttemptRepository;
import io.butler.bet.integration.sleeper.SleeperCounterTradeOutcomeCoordinator;
import io.butler.bet.integration.sleeper.SleeperCounterTradeReconciliationOutcomePolicy;
import io.butler.bet.integration.sleeper.SleeperCounterTradeSnapshotReconciliationService;
import io.butler.bet.integration.sleeper.SleeperReadOnlyClient;
import io.butler.bet.integration.sleeper.SleeperTradeReconciliationPolicy;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerTradeCounterFinalizeMismatchOutputTest {
    @Test
    void mismatchShowsExistingOutcomeWithoutClaimingConflictingEvidenceWasApplied() {
        var expected = new SleeperTradeReconciliationPolicy.ExpectedTrade(
            "289646328504385536", 7, Set.of(1, 2),
            Map.of("101", 2), Map.of("101", 1), Set.of(), null, 1_000L);
        var reconciliation = new SleeperTradeReconciliationPolicy.Result(
            SleeperTradeReconciliationPolicy.POLICY_ID,
            SleeperTradeReconciliationPolicy.State.MATCH_COMPLETE,
            expected,
            List.of("tx-current"),
            false,
            "Current exact completed evidence matched.");
        var report = new SleeperCounterTradeSnapshotReconciliationService.Report(
            SleeperCounterTradeSnapshotReconciliationService.SERVICE_ID,
            SleeperCounterTradeSnapshotReconciliationService.State.RECONCILED,
            "grant-1", "claim-1", "handoff-1", "a".repeat(64),
            7, 1_000L, reconciliation,
            List.of(new SleeperReadOnlyClient.SleeperTransaction(
                "tx-current", "trade", "complete", null, 1_500L, 1_500L, 1,
                List.of(1, 2), List.of(1, 2), Map.of("101", 2), Map.of("101", 1), List.of())),
            "Read evidence evaluated.");
        var decision = SleeperCounterTradeReconciliationOutcomePolicy.classify(report);
        var existingOutcome = new SleeperCounterTradeOutcomeCoordinator.StoredOutcome(
            "outcome-original",
            SleeperCounterTradeOutcomeCoordinator.COORDINATOR_POLICY_ID,
            SleeperCounterTradeReconciliationOutcomePolicy.POLICY_ID,
            SleeperCounterTradeSnapshotReconciliationService.SERVICE_ID,
            SleeperTradeReconciliationPolicy.POLICY_ID,
            "claim-1",
            "handoff-1",
            "attempt-1",
            "grant-1",
            "a".repeat(64),
            7,
            "tx-original",
            TradeCounterExecutionAttemptRepository.State.SUCCEEDED,
            "CONSUME",
            "Original exact completed evidence was already finalized.",
            Instant.parse("2026-09-05T01:00:00Z"));
        var application = new SleeperCounterTradeOutcomeCoordinator.ApplyResult(
            SleeperCounterTradeOutcomeCoordinator.ApplyState.MISMATCH,
            existingOutcome,
            "A different manual-trade terminal outcome already exists for this claim.");

        String output = capture(() -> ButlerTradeCounterFinalizeCli.print(report, decision, application));

        assertTrue(output.contains("Matching transaction IDs: [tx-current]"));
        assertTrue(output.contains("Finalization state: MISMATCH"));
        assertTrue(output.contains("Existing attempt ID: attempt-1"));
        assertTrue(output.contains("Existing completed Sleeper transaction ID: tx-original"));
        assertTrue(output.contains("Conflicting current completed-readback evidence was NOT applied."));
        assertTrue(output.contains("existing durable SUCCEEDED outcome and consumed one-shot authorization remain unchanged"));
        assertFalse(output.contains("Local Butler execution is SUCCEEDED and the one-shot authorization is consumed."));
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
