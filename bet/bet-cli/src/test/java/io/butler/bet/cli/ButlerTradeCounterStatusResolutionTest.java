package io.butler.bet.cli;

import io.butler.bet.data.TradeCounterExecutionAttemptRepository;
import io.butler.bet.data.TradeCounterExecutionRequestRepository;
import io.butler.bet.execution.TradeCounterExecutionOutcomePolicy;
import io.butler.bet.integration.sleeper.SleeperCounterTradeExpectationSnapshotRepository;
import io.butler.bet.integration.sleeper.SleeperCounterTradeNoActionResolutionRepository;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerTradeCounterStatusResolutionTest {
    private static final Instant PRESENTED_AT = Instant.parse("2026-09-05T01:20:00Z");
    private static final Instant SNAPSHOTTED_AT = PRESENTED_AT.plusSeconds(1);
    private static final Instant ACKNOWLEDGED_AT = PRESENTED_AT.plusSeconds(10);
    private static final Instant APPLIED_AT = PRESENTED_AT.plusSeconds(30);
    private static final String MOVEMENT_JSON =
        "{\"rosterIds\":[1,2],\"playerAdds\":{},\"playerDrops\":{},\"draftPicks\":[]}";
    private static final String MOVEMENT_SHA256 =
        "02e4ff7d4a09d05592e6ff8efcaead4c6ed5bd49c616beb0275c9b10ced26c7c";

    @Test
    void supersededNoActionRendersGovernedSuccessInsteadOfConflict() {
        var status = ButlerTradeCounterStatusCli.inspect(
            handoff(),
            snapshot(),
            successOutcome(),
            noActionAcknowledgment(),
            null,
            supersededResolution());

        assertEquals(
            ButlerTradeCounterStatusCli.State.FINALIZED_AFTER_NO_ACTION_SUPERSESSION,
            status.state());
        String output = capture(() -> ButlerTradeCounterStatusCli.print(status));
        assertTrue(output.contains("No-action acknowledgment evidence: RECORDED_HISTORICAL"));
        assertTrue(output.contains("No-action resolution: SUPERSEDED_BY_CONFIRMED_TRADE"));
        assertTrue(output.contains("Completed Sleeper transaction ID: tx-123"));
        assertTrue(output.contains("Terminal execution state: SUCCEEDED"));
        assertTrue(output.contains("historical no-action acknowledgment remains immutable"));
    }

    @Test
    void finalizedNoActionWithLateTradeRendersInvestigationDiscrepancyWithoutRewrite() {
        var status = ButlerTradeCounterStatusCli.inspect(
            handoff(),
            snapshot(),
            null,
            noActionAcknowledgment(),
            noActionOutcome(),
            postClosureResolution());

        assertEquals(
            ButlerTradeCounterStatusCli.State.POST_CLOSURE_EXTERNAL_ACTION_DISCREPANCY,
            status.state());
        String output = capture(() -> ButlerTradeCounterStatusCli.print(status));
        assertTrue(output.contains("Local terminal outcome: RECORDED_NO_ACTION"));
        assertTrue(output.contains("Terminal execution state: FAILED"));
        assertTrue(output.contains("Authorization disposition: CONSUME"));
        assertTrue(output.contains("No-action resolution: POST_CLOSURE_EXTERNAL_ACTION"));
        assertTrue(output.contains("Observed completed Sleeper transaction ID: tx-123"));
        assertTrue(output.contains("INVESTIGATION REQUIRED"));
        assertTrue(output.contains("were not rewritten"));
    }

    @Test
    void supersessionWithMismatchedTransactionFailsClosed() {
        var wrong = resolution(
            SleeperCounterTradeNoActionResolutionRepository.ResolutionType.SUPERSEDED_BY_CONFIRMED_TRADE,
            null,
            "tx-other");

        assertThrows(IllegalStateException.class, () -> ButlerTradeCounterStatusCli.inspect(
            handoff(), snapshot(), successOutcome(), noActionAcknowledgment(), null, wrong));
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
        return new SleeperCounterTradeExpectationSnapshotRepository.Snapshot(
            SleeperCounterTradeExpectationSnapshotRepository.POLICY_ID,
            SleeperTradeExpectationResolver.POLICY_ID,
            "claim-1",
            "handoff-1",
            "league-1",
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
            "success-outcome-1",
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
            "Exact completed Sleeper trade matched frozen evidence.",
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
            "No external action was taken.",
            ACKNOWLEDGED_AT.plusSeconds(1));
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
            "No external action was taken.",
            APPLIED_AT.minusSeconds(5));
    }

    private static SleeperCounterTradeNoActionResolutionRepository.StoredResolution supersededResolution() {
        return resolution(
            SleeperCounterTradeNoActionResolutionRepository.ResolutionType.SUPERSEDED_BY_CONFIRMED_TRADE,
            null,
            "tx-123");
    }

    private static SleeperCounterTradeNoActionResolutionRepository.StoredResolution postClosureResolution() {
        return resolution(
            SleeperCounterTradeNoActionResolutionRepository.ResolutionType.POST_CLOSURE_EXTERNAL_ACTION,
            "no-action-outcome-1",
            "tx-123");
    }

    private static SleeperCounterTradeNoActionResolutionRepository.StoredResolution resolution(
        SleeperCounterTradeNoActionResolutionRepository.ResolutionType type,
        String noActionTerminalOutcomeId,
        String transactionId) {
        return new SleeperCounterTradeNoActionResolutionRepository.StoredResolution(
            "resolution-1",
            SleeperCounterTradeNoActionResolutionRepository.POLICY_ID,
            "no-action-ack-1",
            noActionTerminalOutcomeId,
            "claim-1",
            "attempt-1",
            "grant-1",
            "handoff-1",
            "a".repeat(64),
            MOVEMENT_SHA256,
            SleeperCounterTradeReconciliationOutcomePolicy.POLICY_ID,
            SleeperCounterTradeSnapshotReconciliationService.SERVICE_ID,
            SleeperTradeReconciliationPolicy.POLICY_ID,
            1,
            transactionId,
            type,
            "Governed exact readback resolution.",
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
