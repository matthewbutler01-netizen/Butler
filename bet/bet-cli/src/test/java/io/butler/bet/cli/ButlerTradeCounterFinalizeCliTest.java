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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerTradeCounterFinalizeCliTest {
    private static final Instant APPLIED_AT = Instant.parse("2026-09-04T22:30:00Z");

    @Test
    void parsesTrustedGrantAndExplicitWeekOnly() {
        var options = ButlerTradeCounterFinalizeCli.parse(
            new String[]{"trade", "counter-finalize", "grant-1", "7"});
        assertEquals("grant-1", options.grantId());
        assertEquals(7, options.week());

        assertThrows(IllegalArgumentException.class, () -> ButlerTradeCounterFinalizeCli.parse(
            new String[]{"trade", "counter-finalize", "grant-1"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerTradeCounterFinalizeCli.parse(
            new String[]{"trade", "counter-finalize", "grant-1", "0"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerTradeCounterFinalizeCli.parse(
            new String[]{"trade", "counter-finalize", "grant-1", "31"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerTradeCounterFinalizeCli.parse(
            new String[]{"trade", "counter-finalize", "grant-1", "current"}));
    }

    @Test
    void appliedExactCompleteEvidencePrintsLocalSuccessAndConsumption() {
        var report = completeReport();
        var decision = SleeperCounterTradeReconciliationOutcomePolicy.classify(report);
        var outcome = new SleeperCounterTradeOutcomeCoordinator.StoredOutcome(
            "outcome-1",
            SleeperCounterTradeOutcomeCoordinator.COORDINATOR_POLICY_ID,
            decision.policyId(),
            decision.reconciliationServiceId(),
            decision.reconciliationPolicyId(),
            "claim-1",
            "handoff-1",
            "attempt-1",
            "grant-1",
            "a".repeat(64),
            7,
            "tx-1",
            TradeCounterExecutionAttemptRepository.State.SUCCEEDED,
            "CONSUME",
            decision.reason(),
            APPLIED_AT);
        var application = new SleeperCounterTradeOutcomeCoordinator.ApplyResult(
            SleeperCounterTradeOutcomeCoordinator.ApplyState.APPLIED,
            outcome,
            "Exact completed Sleeper trade evidence was finalized.");

        String output = capture(() -> ButlerTradeCounterFinalizeCli.print(
            report, decision, application));

        assertTrue(output.contains("Trade counter Sleeper finalization"));
        assertTrue(output.contains("Reconciliation state: MATCH_COMPLETE"));
        assertTrue(output.contains("Outcome eligibility state: CONFIRMED_SUCCESS_EVIDENCE"));
        assertTrue(output.contains("Terminal outcome eligibility: CONFIRMED_SUCCESS"));
        assertTrue(output.contains("Finalization state: APPLIED"));
        assertTrue(output.contains("Completed Sleeper transaction ID: tx-1"));
        assertTrue(output.contains("Terminal execution state: SUCCEEDED"));
        assertTrue(output.contains("Authorization disposition: CONSUME"));
        assertTrue(output.contains("one-shot authorization is consumed"));
        assertTrue(output.contains("Sleeper access is GET-only"));
        assertFalse(output.contains("submitted to Sleeper"));
        assertFalse(output.contains("Sleeper trade changed"));
    }

    @Test
    void noMatchPrintsNotEligibleAndNoLocalFinalization() {
        var expected = expected(5);
        var reconciliation = new SleeperTradeReconciliationPolicy.Result(
            SleeperTradeReconciliationPolicy.POLICY_ID,
            SleeperTradeReconciliationPolicy.State.NO_MATCH,
            expected,
            List.of(),
            false,
            "No exact trade found.");
        var report = reconciled(5, reconciliation, List.of());
        var decision = SleeperCounterTradeReconciliationOutcomePolicy.classify(report);
        var application = new SleeperCounterTradeOutcomeCoordinator.ApplyResult(
            SleeperCounterTradeOutcomeCoordinator.ApplyState.NOT_ELIGIBLE,
            null,
            "Only BF-409 CONFIRMED_SUCCESS_EVIDENCE may finalize a manual trade execution.");

        String output = capture(() -> ButlerTradeCounterFinalizeCli.print(
            report, decision, application));

        assertTrue(output.contains("Reconciliation state: NO_MATCH"));
        assertTrue(output.contains("Outcome eligibility state: NO_TERMINAL_OUTCOME"));
        assertTrue(output.contains("Terminal outcome eligibility: NONE"));
        assertTrue(output.contains("Finalization state: NOT_ELIGIBLE"));
        assertTrue(output.contains("No local execution finalization was applied by this command invocation."));
        assertTrue(output.contains("NO_MATCH, PENDING, AMBIGUOUS, or INCONCLUSIVE evidence never finalizes failure or success."));
        assertFalse(output.contains("Terminal execution state: SUCCEEDED"));
        assertFalse(output.contains("Authorization disposition: CONSUME"));
    }

    @Test
    void rejectsMismatchedDecisionCoordinatesBeforeRendering() {
        var report = completeReport();
        var decision = SleeperCounterTradeReconciliationOutcomePolicy.classify(report);
        var mismatched = new SleeperCounterTradeReconciliationOutcomePolicy.Decision(
            decision.policyId(),
            decision.reconciliationServiceId(),
            decision.reconciliationPolicyId(),
            "different-grant",
            decision.claimId(),
            decision.handoffId(),
            decision.movementSha256(),
            decision.week(),
            decision.state(),
            decision.reasonCode(),
            decision.terminalOutcomeEligibility(),
            decision.transactionIds(),
            decision.reason());
        var application = new SleeperCounterTradeOutcomeCoordinator.ApplyResult(
            SleeperCounterTradeOutcomeCoordinator.ApplyState.NOT_ELIGIBLE,
            null,
            "Not applied.");

        assertThrows(IllegalArgumentException.class, () -> ButlerTradeCounterFinalizeCli.print(
            report, mismatched, application));
    }

    private static SleeperCounterTradeSnapshotReconciliationService.Report completeReport() {
        var reconciliation = new SleeperTradeReconciliationPolicy.Result(
            SleeperTradeReconciliationPolicy.POLICY_ID,
            SleeperTradeReconciliationPolicy.State.MATCH_COMPLETE,
            expected(7),
            List.of("tx-1"),
            false,
            "Exactly one matching trade.");
        return reconciled(7, reconciliation, List.of(transaction("tx-1", "complete")));
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
