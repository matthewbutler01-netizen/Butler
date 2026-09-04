package io.butler.bet.cli;

import io.butler.bet.integration.sleeper.SleeperCounterTradeSnapshotReconciliationService;
import io.butler.bet.integration.sleeper.SleeperReadOnlyClient;
import io.butler.bet.integration.sleeper.SleeperTradeReconciliationPolicy;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerTradeCounterReconcileCliTest {
    @Test
    void parsesTrustedGrantAndExplicitWeekOnly() {
        var options = ButlerTradeCounterReconcileCli.parse(
            new String[]{"trade", "counter-reconcile", "grant-1", "7"});
        assertEquals("grant-1", options.grantId());
        assertEquals(7, options.week());

        assertThrows(IllegalArgumentException.class, () -> ButlerTradeCounterReconcileCli.parse(
            new String[]{"trade", "counter-reconcile", "grant-1"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerTradeCounterReconcileCli.parse(
            new String[]{"trade", "counter-reconcile", "grant-1", "0"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerTradeCounterReconcileCli.parse(
            new String[]{"trade", "counter-reconcile", "grant-1", "31"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerTradeCounterReconcileCli.parse(
            new String[]{"trade", "counter-reconcile", "grant-1", "current"}));
    }

    @Test
    void completeMatchPrintsEvidenceWithoutDeclaringExecutionSuccess() {
        var expected = new SleeperTradeReconciliationPolicy.ExpectedTrade(
            "289646328504385536", 7, Set.of(1, 2),
            Map.of("101", 2), Map.of("101", 1), Set.of(), null, 1_000L);
        var reconciliation = new SleeperTradeReconciliationPolicy.Result(
            SleeperTradeReconciliationPolicy.POLICY_ID,
            SleeperTradeReconciliationPolicy.State.MATCH_COMPLETE,
            expected,
            List.of("tx-1"),
            false,
            "Exactly one matching trade.");
        var transaction = new SleeperReadOnlyClient.SleeperTransaction(
            "tx-1", "trade", "complete", null, 1_500L, 1_500L, 1,
            List.of(1, 2), List.of(1, 2), Map.of("101", 2), Map.of("101", 1), List.of());
        var report = new SleeperCounterTradeSnapshotReconciliationService.Report(
            SleeperCounterTradeSnapshotReconciliationService.SERVICE_ID,
            SleeperCounterTradeSnapshotReconciliationService.State.RECONCILED,
            "grant-1", "claim-1", "handoff-1", "a".repeat(64),
            7, 1_000L, reconciliation, List.of(transaction), "Read evidence evaluated.");

        String output = capture(() -> ButlerTradeCounterReconcileCli.print(report));

        assertTrue(output.contains("Reconciliation state: MATCH_COMPLETE"));
        assertTrue(output.contains("Matching transaction IDs: [tx-1]"));
        assertTrue(output.contains("Observed Sleeper transactions: 1"));
        assertTrue(output.contains("does not mark execution SUCCEEDED, FAILED, or UNKNOWN"));
        assertTrue(output.contains("does not consume the authorization grant"));
        assertFalse(output.contains("Trade succeeded"));
        assertFalse(output.contains("Execution succeeded"));
    }

    @Test
    void unavailableReportPrintsNoTransactionEvidenceClaim() {
        var report = new SleeperCounterTradeSnapshotReconciliationService.Report(
            SleeperCounterTradeSnapshotReconciliationService.SERVICE_ID,
            SleeperCounterTradeSnapshotReconciliationService.State.NOT_AVAILABLE,
            "grant-1", null, null, null, 3, null, null, List.of(),
            "Trade handoff has no immutable provider snapshot.");

        String output = capture(() -> ButlerTradeCounterReconcileCli.print(report));

        assertTrue(output.contains("Service state: NOT_AVAILABLE"));
        assertTrue(output.contains("No Sleeper transaction evidence was evaluated."));
        assertFalse(output.contains("Reconciliation state:"));
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
