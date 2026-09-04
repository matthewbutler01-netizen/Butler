package io.butler.bet.integration.sleeper;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class SleeperCounterTradeReconciliationOutcomePolicyTest {
    @Test
    void exactCompleteTradeIsTheOnlyConfirmedSuccessEvidence() {
        var decision = SleeperCounterTradeReconciliationOutcomePolicy.classify(
            reconciled(SleeperTradeReconciliationPolicy.State.MATCH_COMPLETE, List.of("tx-1"), false));

        assertEquals(SleeperCounterTradeReconciliationOutcomePolicy.State.CONFIRMED_SUCCESS_EVIDENCE,
            decision.state());
        assertEquals(SleeperCounterTradeReconciliationOutcomePolicy.ReasonCode.EXACT_COMPLETE_TRADE_CONFIRMED,
            decision.reasonCode());
        assertEquals(SleeperCounterTradeReconciliationOutcomePolicy.TerminalOutcomeEligibility.CONFIRMED_SUCCESS,
            decision.terminalOutcomeEligibility());
        assertEquals(List.of("tx-1"), decision.transactionIds());
        assertEquals("grant-1", decision.grantId());
        assertEquals("claim-1", decision.claimId());
        assertEquals("handoff-1", decision.handoffId());
        assertEquals("a".repeat(64), decision.movementSha256());
    }

    @Test
    void pendingExactTradeCannotFinalizeAnything() {
        var decision = SleeperCounterTradeReconciliationOutcomePolicy.classify(
            reconciled(SleeperTradeReconciliationPolicy.State.MATCH_PENDING, List.of("tx-pending"), false));

        assertEquals(SleeperCounterTradeReconciliationOutcomePolicy.State.PENDING, decision.state());
        assertEquals(SleeperCounterTradeReconciliationOutcomePolicy.TerminalOutcomeEligibility.NONE,
            decision.terminalOutcomeEligibility());
        assertEquals(List.of("tx-pending"), decision.transactionIds());
    }

    @Test
    void noMatchNeverBecomesFailureEvidence() {
        var decision = SleeperCounterTradeReconciliationOutcomePolicy.classify(
            reconciled(SleeperTradeReconciliationPolicy.State.NO_MATCH, List.of(), false));

        assertEquals(SleeperCounterTradeReconciliationOutcomePolicy.State.NO_TERMINAL_OUTCOME,
            decision.state());
        assertEquals(SleeperCounterTradeReconciliationOutcomePolicy.ReasonCode.NO_EXACT_TRADE_OBSERVED,
            decision.reasonCode());
        assertEquals(SleeperCounterTradeReconciliationOutcomePolicy.TerminalOutcomeEligibility.NONE,
            decision.terminalOutcomeEligibility());
        assertFalse(decision.reason().toLowerCase().contains("failure confirmed"));
    }

    @Test
    void ambiguousExactTradesRemainInconclusive() {
        var decision = SleeperCounterTradeReconciliationOutcomePolicy.classify(
            reconciled(SleeperTradeReconciliationPolicy.State.AMBIGUOUS, List.of("tx-1", "tx-2"), false));

        assertEquals(SleeperCounterTradeReconciliationOutcomePolicy.State.INCONCLUSIVE, decision.state());
        assertEquals(SleeperCounterTradeReconciliationOutcomePolicy.ReasonCode.AMBIGUOUS_EXACT_TRADES,
            decision.reasonCode());
        assertEquals(SleeperCounterTradeReconciliationOutcomePolicy.TerminalOutcomeEligibility.NONE,
            decision.terminalOutcomeEligibility());
    }

    @Test
    void incompleteSleeperEvidenceRemainsInconclusive() {
        var decision = SleeperCounterTradeReconciliationOutcomePolicy.classify(
            reconciled(SleeperTradeReconciliationPolicy.State.INCONCLUSIVE, List.of("tx-weird"), true));

        assertEquals(SleeperCounterTradeReconciliationOutcomePolicy.State.INCONCLUSIVE, decision.state());
        assertEquals(SleeperCounterTradeReconciliationOutcomePolicy.ReasonCode.RECONCILIATION_EVIDENCE_INCONCLUSIVE,
            decision.reasonCode());
        assertEquals(SleeperCounterTradeReconciliationOutcomePolicy.TerminalOutcomeEligibility.NONE,
            decision.terminalOutcomeEligibility());
    }

    @Test
    void unavailableTrustedReconciliationCarriesNoTerminalEvidenceCoordinates() {
        var report = new SleeperCounterTradeSnapshotReconciliationService.Report(
            SleeperCounterTradeSnapshotReconciliationService.SERVICE_ID,
            SleeperCounterTradeSnapshotReconciliationService.State.NOT_AVAILABLE,
            "grant-1", null, null, null, 7, null, null, List.of(),
            "Trusted snapshot unavailable.");

        var decision = SleeperCounterTradeReconciliationOutcomePolicy.classify(report);

        assertEquals(SleeperCounterTradeReconciliationOutcomePolicy.State.INCONCLUSIVE, decision.state());
        assertEquals(SleeperCounterTradeReconciliationOutcomePolicy.ReasonCode.TRUSTED_RECONCILIATION_UNAVAILABLE,
            decision.reasonCode());
        assertEquals(SleeperCounterTradeReconciliationOutcomePolicy.TerminalOutcomeEligibility.NONE,
            decision.terminalOutcomeEligibility());
        assertNull(decision.claimId());
        assertNull(decision.handoffId());
        assertNull(decision.movementSha256());
    }

    private static SleeperCounterTradeSnapshotReconciliationService.Report reconciled(
        SleeperTradeReconciliationPolicy.State state,
        List<String> transactionIds,
        boolean incomplete) {
        var expected = new SleeperTradeReconciliationPolicy.ExpectedTrade(
            "289646328504385536", 7, Set.of(1, 2),
            Map.of("101", 2), Map.of("101", 1), Set.of(), null, 1_000L);
        var reconciliation = new SleeperTradeReconciliationPolicy.Result(
            SleeperTradeReconciliationPolicy.POLICY_ID,
            state,
            expected,
            transactionIds,
            incomplete,
            "Evidence state " + state + ".");
        return new SleeperCounterTradeSnapshotReconciliationService.Report(
            SleeperCounterTradeSnapshotReconciliationService.SERVICE_ID,
            SleeperCounterTradeSnapshotReconciliationService.State.RECONCILED,
            "grant-1", "claim-1", "handoff-1", "a".repeat(64),
            7, 1_000L, reconciliation, List.of(), "Read evidence evaluated.");
    }
}
