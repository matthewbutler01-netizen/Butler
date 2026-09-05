package io.butler.bet.cli;

import io.butler.bet.data.TradeCounterExecutionAttemptRepository;
import io.butler.bet.data.TradeCounterExecutionRequestRepository;
import io.butler.bet.execution.TradeCounterExecutionOutcomePolicy;
import io.butler.bet.integration.sleeper.SleeperCounterTradeExpectationSnapshotRepository;
import io.butler.bet.integration.sleeper.SleeperCounterTradeOutcomeCoordinator;
import io.butler.bet.integration.sleeper.SleeperCounterTradeReconciliationOutcomePolicy;
import io.butler.bet.integration.sleeper.SleeperCounterTradeSnapshotReconciliationService;
import io.butler.bet.integration.sleeper.SleeperManualCounterHandoffRepository;
import io.butler.bet.integration.sleeper.SleeperManualCounterHandoffService;
import io.butler.bet.integration.sleeper.SleeperManualCounterNoActionAcknowledgmentPolicy;
import io.butler.bet.integration.sleeper.SleeperManualCounterNoActionAcknowledgmentRepository;
import io.butler.bet.integration.sleeper.SleeperManualCounterNoActionOutcomeCoordinator;
import io.butler.bet.integration.sleeper.SleeperPlatformCapabilityPolicy;
import io.butler.bet.integration.sleeper.SleeperTradeExpectationResolver;
import io.butler.bet.integration.sleeper.SleeperTradeReconciliationPolicy;
import io.butler.bet.intelligence.TradeCounterAuthorizationPolicy;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerTradeCounterStatusCliTest {
    private static final Instant PRESENTED_AT = Instant.parse("2026-09-04T23:45:00Z");
    private static final Instant SNAPSHOTTED_AT = PRESENTED_AT.minusSeconds(1);
    private static final Instant ACKNOWLEDGED_AT = PRESENTED_AT.plusSeconds(30);
    private static final Instant APPLIED_AT = PRESENTED_AT.plusSeconds(45);
    private static final String MOVEMENT_JSON =
        "{\"rosterIds\":[1,2],\"playerAdds\":{},\"playerDrops\":{},\"draftPicks\":[]}";
    private static final String MOVEMENT_SHA256 =
        "02e4ff7d4a09d05592e6ff8efcaead4c6ed5bd49c616beb0275c9b10ced26c7c";

    @Test
    void parsesExactlyOneTrustedGrantId() {
        assertEquals("grant-1", ButlerTradeCounterStatusCli.parseGrantId(
            new String[]{"trade", "counter-status", "grant-1"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerTradeCounterStatusCli.parseGrantId(
            new String[]{"trade", "counter-status"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerTradeCounterStatusCli.parseGrantId(
            new String[]{"trade", "counter-status", "grant-1", "extra"}));
    }

    @Test
    void snapshotMissingDoesNotInferExternalSleeperState() {
        var status = ButlerTradeCounterStatusCli.inspect(handoff(), null, null);
        assertEquals(ButlerTradeCounterStatusCli.State.SNAPSHOT_MISSING, status.state());

        String output = capture(() -> ButlerTradeCounterStatusCli.print(status));
        assertTrue(output.contains("Provider expectation snapshot: NOT_RECORDED"));
        assertTrue(output.contains("External Sleeper completion: NOT_INFERRED"));
        assertTrue(output.contains("performs no Sleeper request"));
        assertFalse(output.contains("Completed Sleeper transaction ID:"));
    }

    @Test
    void localUnfinalizedStatusPointsToSeparateGetOnlyReconciliation() {
        var status = ButlerTradeCounterStatusCli.inspect(handoff(), snapshot(), null);
        assertEquals(ButlerTradeCounterStatusCli.State.LOCAL_UNFINALIZED, status.state());

        String output = capture(() -> ButlerTradeCounterStatusCli.print(status));
        assertTrue(output.contains("Provider expectation snapshot: RECORDED"));
        assertTrue(output.contains("Local terminal outcome: NOT_RECORDED"));
        assertTrue(output.contains("counter-reconcile"));
        assertTrue(output.contains("External Sleeper completion: NOT_INFERRED"));
        assertTrue(output.contains("does not submit, accept, reject, alter, reconcile, acknowledge, or finalize a trade"));
    }

    @Test
    void finalizedStatusReportsOnlyPreviouslyPersistedLocalEvidence() {
        var status = ButlerTradeCounterStatusCli.inspect(handoff(), snapshot(), successOutcome());
        assertEquals(ButlerTradeCounterStatusCli.State.FINALIZED, status.state());

        String output = capture(() -> ButlerTradeCounterStatusCli.print(status));
        assertTrue(output.contains("Local terminal outcome: RECORDED_SUCCESS"));
        assertTrue(output.contains("Completed Sleeper transaction ID: tx-123"));
        assertTrue(output.contains("Terminal execution state: SUCCEEDED"));
        assertTrue(output.contains("Authorization disposition: CONSUME"));
        assertTrue(output.contains("Local inspection only; this command performs no Sleeper request"));
        assertTrue(output.contains("does not change execution state or consume authorization"));
    }

    @Test
    void noActionAcknowledgedIsVisibleEvenWithoutProviderSnapshot() {
        var status = ButlerTradeCounterStatusCli.inspect(
            handoff(), null, null, noActionAcknowledgment(), null);
        assertEquals(
            ButlerTradeCounterStatusCli.State.NO_ACTION_ACKNOWLEDGED_PENDING_FINALIZATION,
            status.state());

        String output = capture(() -> ButlerTradeCounterStatusCli.print(status));
        assertTrue(output.contains("Provider expectation snapshot: NOT_RECORDED"));
        assertTrue(output.contains("No-action acknowledgment evidence: RECORDED"));
        assertTrue(output.contains("NO_EXTERNAL_ACTION_TAKEN"));
        assertTrue(output.contains("counter-no-action-finalize"));
        assertTrue(output.contains("External Sleeper completion: NOT_INFERRED"));
        assertFalse(output.contains("counter-reconcile with an explicit Sleeper week"));
    }

    @Test
    void noActionFinalizedReportsFailedConsumeWithoutInferringSleeperCompletion() {
        var status = ButlerTradeCounterStatusCli.inspect(
            handoff(), snapshot(), null, noActionAcknowledgment(), noActionOutcome());
        assertEquals(ButlerTradeCounterStatusCli.State.NO_ACTION_FINALIZED, status.state());

        String output = capture(() -> ButlerTradeCounterStatusCli.print(status));
        assertTrue(output.contains("Local terminal outcome: RECORDED_NO_ACTION"));
        assertTrue(output.contains("Terminal execution state: FAILED"));
        assertTrue(output.contains("Authorization disposition: CONSUME"));
        assertTrue(output.contains("External Sleeper completion: NOT_INFERRED"));
        assertTrue(output.contains("retry requires fresh explicit authorization"));
        assertFalse(output.contains("Completed Sleeper transaction ID:"));
    }

    @Test
    void conflictingSuccessAndNoActionEvidenceFailsClosed() {
        assertThrows(IllegalStateException.class, () -> ButlerTradeCounterStatusCli.inspect(
            handoff(), snapshot(), successOutcome(), noActionAcknowledgment(), null));
    }

    @Test
    void noActionOutcomeWithoutAcknowledgmentFailsClosed() {
        assertThrows(IllegalStateException.class, () -> ButlerTradeCounterStatusCli.inspect(
            handoff(), snapshot(), null, null, noActionOutcome()));
    }

    @Test
    void outcomeWithoutSnapshotFailsClosed() {
        assertThrows(IllegalStateException.class, () ->
            ButlerTradeCounterStatusCli.inspect(handoff(), null, successOutcome()));
    }

    @Test
    void mismatchedSnapshotFailsClosed() {
        assertThrows(IllegalStateException.class, () ->
            ButlerTradeCounterStatusCli.inspect(handoff(), snapshot("league-other"), null));
    }

    @Test
    void unavailableOutputStatesNoNetworkOrMutation() {
        String output = capture(() -> ButlerTradeCounterStatusCli.printUnavailable("grant-1"));
        assertTrue(output.contains("status unavailable"));
        assertTrue(output.contains("no Sleeper request"));
        assertTrue(output.contains("no Sleeper request or local lifecycle mutation occurred"));
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
            "b".repeat(64),
            TradeCounterAuthorizationPolicy.Action.SUBMIT_COUNTER_TRADE,
            new TradeCounterAuthorizationPolicy.Destination(
                TradeCounterAuthorizationPolicy.DestinationType.LEAGUE, "league-1"),
            "COUNTER_TRADE_REQUEST_JSON",
            "a".repeat(64),
            SleeperManualCounterHandoffService.ReconciliationMode.SLEEPER_TRANSACTION_READBACK,
            PRESENTED_AT);
    }

    private static SleeperCounterTradeExpectationSnapshotRepository.Snapshot snapshot() {
        return snapshot("league-1");
    }

    private static SleeperCounterTradeExpectationSnapshotRepository.Snapshot snapshot(String butlerLeagueId) {
        return new SleeperCounterTradeExpectationSnapshotRepository.Snapshot(
            SleeperCounterTradeExpectationSnapshotRepository.POLICY_ID,
            SleeperTradeExpectationResolver.POLICY_ID,
            "claim-1",
            "handoff-1",
            butlerLeagueId,
            "team-a",
            "team-b",
            "999999",
            Set.of(1, 2),
            Map.of(),
            Map.of(),
            Set.of(),
            MOVEMENT_JSON,
            MOVEMENT_SHA256,
            SNAPSHOTTED_AT);
    }

    private static SleeperCounterTradeOutcomeCoordinator.StoredOutcome successOutcome() {
        return new SleeperCounterTradeOutcomeCoordinator.StoredOutcome(
            "outcome-1",
            SleeperCounterTradeOutcomeCoordinator.COORDINATOR_POLICY_ID,
            SleeperCounterTradeReconciliationOutcomePolicy.POLICY_ID,
            SleeperCounterTradeSnapshotReconciliationService.SERVICE_ID,
            SleeperTradeReconciliationPolicy.POLICY_ID,
            "claim-1",
            "handoff-1",
            "attempt-1",
            "grant-1",
            MOVEMENT_SHA256,
            1,
            "tx-123",
            TradeCounterExecutionAttemptRepository.State.SUCCEEDED,
            "CONSUME",
            "Exact completed Sleeper trade matched the frozen handoff.",
            APPLIED_AT);
    }

    private static SleeperManualCounterNoActionAcknowledgmentRepository.StoredAcknowledgment noActionAcknowledgment() {
        return new SleeperManualCounterNoActionAcknowledgmentRepository.StoredAcknowledgment(
            "no-action-ack-1",
            SleeperManualCounterNoActionAcknowledgmentRepository.JOURNAL_POLICY_ID,
            SleeperManualCounterNoActionAcknowledgmentPolicy.POLICY_ID,
            SleeperManualCounterHandoffRepository.JOURNAL_POLICY_ID,
            SleeperManualCounterHandoffService.SERVICE_ID,
            "claim-1",
            "attempt-1",
            "grant-1",
            "handoff-1",
            "a".repeat(64),
            TradeCounterAuthorizationPolicy.Action.SUBMIT_COUNTER_TRADE,
            new TradeCounterAuthorizationPolicy.Destination(
                TradeCounterAuthorizationPolicy.DestinationType.LEAGUE, "league-1"),
            SleeperManualCounterNoActionAcknowledgmentPolicy.REQUIRED_CONFIRMATION,
            SleeperManualCounterNoActionAcknowledgmentPolicy.LocalTerminalEligibility.CONFIRMED_NO_ACTION_FAILURE,
            TradeCounterExecutionAttemptRepository.State.FAILED,
            TradeCounterExecutionOutcomePolicy.GrantDisposition.CONSUME,
            PRESENTED_AT,
            ACKNOWLEDGED_AT,
            "User explicitly acknowledged no external action was taken.",
            ACKNOWLEDGED_AT);
    }

    private static SleeperManualCounterNoActionOutcomeCoordinator.StoredOutcome noActionOutcome() {
        return new SleeperManualCounterNoActionOutcomeCoordinator.StoredOutcome(
            "no-action-outcome-1",
            SleeperManualCounterNoActionOutcomeCoordinator.COORDINATOR_POLICY_ID,
            SleeperManualCounterNoActionAcknowledgmentRepository.JOURNAL_POLICY_ID,
            SleeperManualCounterNoActionAcknowledgmentPolicy.POLICY_ID,
            "no-action-ack-1",
            "claim-1",
            "attempt-1",
            "grant-1",
            "handoff-1",
            "a".repeat(64),
            TradeCounterAuthorizationPolicy.Action.SUBMIT_COUNTER_TRADE,
            new TradeCounterAuthorizationPolicy.Destination(
                TradeCounterAuthorizationPolicy.DestinationType.LEAGUE, "league-1"),
            SleeperManualCounterNoActionAcknowledgmentPolicy.REQUIRED_CONFIRMATION,
            ACKNOWLEDGED_AT,
            TradeCounterExecutionAttemptRepository.State.FAILED,
            TradeCounterExecutionOutcomePolicy.GrantDisposition.CONSUME,
            "User explicitly acknowledged no external action was taken.",
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
