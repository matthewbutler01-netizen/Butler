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
    void completeMatchPrintsSuccessEligibilityWithoutFinalizingExecution() {
        var expected = expected(7);
        var reconciliation = new SleeperTradeReconciliationPolicy.Result(
            SleeperTradeReconciliationPolicy.POLICY_ID,
            SleeperTradeReconciliationPolicy.State.MATCH_COMPLETE,
            expected,
            List.of("tx-1"),
            false,
            "Exactly one matching trade.");
        var transaction = transaction("tx-1", "complete");
        var report = reconciled(7, reconciliation, List.of(transaction));

        String output = capture(() -> ButlerTradeCounterReconcileCli.print(report));

        assertTrue(output.contains("Reconciliation state: MATCH_COMPLETE"));
        assertTrue(output.contains("Matching transaction IDs: [tx-1]"));
        assertTrue(output.contains("Observed Sleeper transactions: 1"));
        assertTrue(output.contains("Outcome eligibility state: CONFIRMED_SUCCESS_EVIDENCE"));
        assertTrue(output.contains("Outcome eligibility reason code: EXACT_COMPLETE_TRADE_CONFIRMED"));
        assertTrue(output.contains("Terminal outcome eligibility: CONFIRMED_SUCCESS"));
        assertTrue(output.contains("Outcome eligibility transaction IDs: [tx-1]"));
        assertTrue(output.contains("eligible for a separate governed success-finalization step"));
        assertTrue(output.contains("does not mark execution SUCCEEDED, FAILED, or UNKNOWN"));
        assertTrue(output.contains("does not consume the authorization grant"));
        assertFalse(output.contains("Trade succeeded"));
        assertFalse(output.contains("Execution succeeded"));
        assertFalse(output.contains("Execution state changed"));
    }

    @Test
    void noMatchPrintsNoTerminalOutcomeAndNoFailureInference() {
        var reconciliation = new SleeperTradeReconciliationPolicy.Result(
            SleeperTradeReconciliationPolicy.POLICY_ID,
            SleeperTradeReconciliationPolicy.State.NO_MATCH,
            expected(5),
            List.of(),
            false,
            "No exact trade found.");
        var report = reconciled(5, reconciliation, List.of());

        String output = capture(() -> ButlerTradeCounterReconcileCli.print(report));

        assertTrue(output.contains("Reconciliation state: NO_MATCH"));
        assertTrue(output.contains("Outcome eligibility state: NO_TERMINAL_OUTCOME"));
        assertTrue(output.contains("Outcome eligibility reason code: NO_EXACT_TRADE_OBSERVED"));
        assertTrue(output.contains("Terminal outcome eligibility: NONE"));
        assertTrue(output.contains("No terminal execution finalization is eligible"));
        assertTrue(output.contains("does not mark execution SUCCEEDED, FAILED, or UNKNOWN"));
        assertFalse(output.contains("Execution failed"));
        assertFalse(output.contains("Trade failed"));
        assertFalse(output.contains("failure confirmed"));
    }

    @Test
    void unavailableReportPrintsInconclusiveEligibilityWithoutTransactionEvidenceClaim() {
        var report = new SleeperCounterTradeSnapshotReconciliationService.Report(
            SleeperCounterTradeSnapshotReconciliationService.SERVICE_ID,
            SleeperCounterTradeSnapshotReconciliationService.State.NOT_AVAILABLE,
            "grant-1", null, null, null, 3, null, null, List.of(),
            "Trade handoff has no immutable provider snapshot.");

        String output = capture(() -> ButlerTradeCounterReconcileCli.print(report));

        assertTrue(output.contains("Service state: NOT_AVAILABLE"));
        assertTrue(output.contains("No Sleeper transaction evidence was evaluated."));
        assertTrue(output.contains("Outcome eligibility state: INCONCLUSIVE"));
        assertTrue(output.contains("Outcome eligibility reason code: TRUSTED_RECONCILIATION_UNAVAILABLE"));
        assertTrue(output.contains("Terminal outcome eligibility: NONE"));
        assertFalse(output.contains("Reconciliation state:"));
    }

    private static SleeperCounterTradeSnapshotReconciliationService.Report reconciled(
        int week,
        SleeperTradeReconciliationPolicy.Result reconciliation,
        List<SleeperReadOnlyClient.SleeperTransaction> transactions) {
        return new SleeperCounterTradeSnapshotReconciliationService.Report(
            SleeperCounterTradeSnapshotReconciliationService.SERVICE_ID,
            SleeperCounterTradeSnapshotReconciliationService.State.RECONCILED,
            "grant-1", "claim-1", "handoff-1", "a".repeat(64),
            week, 1_000L, reconciliation, transactions, "Read evidence evaluated.");
    }

    private static SleeperTradeReconciliationPolicy.ExpectedTrade expected(int week) {
        return new SleeperTradeReconciliationPolicy.ExpectedTrade(
            "289646328504385536", week, Set.of(1, 2),
            Map.of("101", 2), Map.of("101", 1), Set.of(), null, 1_000L);
    }

    private static SleeperReadOnlyClient.SleeperTransaction transaction(String id, String status) {
        return new SleeperReadOnlyClient.SleeperTransaction(
            id, "trade", status, null, 1_500L, 1_500L, 1,
            List.of(1, 2), List.of(1, 2), Map.of("101", 2), Map.of("101", 1), List.of());
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
