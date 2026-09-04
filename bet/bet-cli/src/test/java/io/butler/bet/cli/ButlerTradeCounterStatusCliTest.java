package io.butler.bet.cli;

import io.butler.bet.data.TradeCounterExecutionAttemptRepository;
import io.butler.bet.data.TradeCounterExecutionRequestRepository;
import io.butler.bet.integration.sleeper.SleeperCounterTradeExpectationSnapshotRepository;
import io.butler.bet.integration.sleeper.SleeperCounterTradeOutcomeCoordinator;
import io.butler.bet.integration.sleeper.SleeperCounterTradeReconciliationOutcomePolicy;
import io.butler.bet.integration.sleeper.SleeperCounterTradeSnapshotReconciliationService;
import io.butler.bet.integration.sleeper.SleeperManualCounterHandoffRepository;
import io.butler.bet.integration.sleeper.SleeperManualCounterHandoffService;
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
        assertTrue(output.contains("does not submit, accept, reject, alter, reconcile, or finalize a trade"));
    }

    @Test
    void finalizedStatusReportsOnlyPreviouslyPersistedLocalEvidence() {
        var status = ButlerTradeCounterStatusCli.inspect(handoff(), snapshot(), outcome());
        assertEquals(ButlerTradeCounterStatusCli.State.FINALIZED, status.state());

        String output = capture(() -> ButlerTradeCounterStatusCli.print(status));
        assertTrue(output.contains("Local terminal outcome: RECORDED"));
        assertTrue(output.contains("Completed Sleeper transaction ID: tx-123"));
        assertTrue(output.contains("Terminal execution state: SUCCEEDED"));
        assertTrue(output.contains("Authorization disposition: CONSUME"));
        assertTrue(output.contains("Local inspection only; this command performs no Sleeper request"));
        assertTrue(output.contains("does not change execution state or consume authorization"));
    }

    @Test
    void outcomeWithoutSnapshotFailsClosed() {
        assertThrows(IllegalStateException.class, () ->
            ButlerTradeCounterStatusCli.inspect(handoff(), null, outcome()));
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

    private static SleeperCounterTradeOutcomeCoordinator.StoredOutcome outcome() {
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
