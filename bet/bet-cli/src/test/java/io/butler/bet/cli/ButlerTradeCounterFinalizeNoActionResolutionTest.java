package io.butler.bet.cli;

import io.butler.bet.integration.sleeper.SleeperCounterTradeOutcomeCoordinator;
import io.butler.bet.integration.sleeper.SleeperCounterTradeReconciliationOutcomePolicy;
import io.butler.bet.integration.sleeper.SleeperCounterTradeSnapshotReconciliationService;
import io.butler.bet.integration.sleeper.SleeperReadOnlyClient;
import io.butler.bet.integration.sleeper.SleeperTradeReconciliationPolicy;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerTradeCounterFinalizeNoActionResolutionTest {
    @Test
    void postClosureDiscrepancyWarnsWithoutClaimingSuccessRewrite() {
        var expected = new SleeperTradeReconciliationPolicy.ExpectedTrade(
            "289646328504385536", 7, Set.of(1, 2),
            Map.of("101", 2), Map.of("101", 1), Set.of(), null, 1_000L);
        var reconciliation = new SleeperTradeReconciliationPolicy.Result(
            SleeperTradeReconciliationPolicy.POLICY_ID,
            SleeperTradeReconciliationPolicy.State.MATCH_COMPLETE,
            expected,
            List.of("tx-late"),
            false,
            "Exactly one completed trade matched.");
        var report = new SleeperCounterTradeSnapshotReconciliationService.Report(
            SleeperCounterTradeSnapshotReconciliationService.SERVICE_ID,
            SleeperCounterTradeSnapshotReconciliationService.State.RECONCILED,
            "grant-1", "claim-1", "handoff-1", "a".repeat(64),
            7, 1_000L, reconciliation,
            List.of(new SleeperReadOnlyClient.SleeperTransaction(
                "tx-late", "trade", "complete", null, 1_500L, 1_500L, 1,
                List.of(1, 2), List.of(1, 2), Map.of("101", 2), Map.of("101", 1), List.of())),
            "Read evidence evaluated.");
        var decision = SleeperCounterTradeReconciliationOutcomePolicy.classify(report);
        var application = new SleeperCounterTradeOutcomeCoordinator.ApplyResult(
            SleeperCounterTradeOutcomeCoordinator.ApplyState.POST_CLOSURE_DISCREPANCY,
            null,
            "Exact completed readback appeared after local no-action closure; state was not rewritten.");

        String output = capture(() -> ButlerTradeCounterFinalizeCli.print(report, decision, application));

        assertTrue(output.contains("Finalization state: POST_CLOSURE_DISCREPANCY"));
        assertTrue(output.contains("POST-CLOSURE DISCREPANCY"));
        assertTrue(output.contains("FAILED terminal state and consumed authorization remain unchanged"));
        assertTrue(output.contains("investigate the external action"));
        assertFalse(output.contains("Local Butler execution is SUCCEEDED"));
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
