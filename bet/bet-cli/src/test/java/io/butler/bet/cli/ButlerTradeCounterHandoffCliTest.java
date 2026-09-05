package io.butler.bet.cli;

import io.butler.bet.data.TradeCounterExecutionAttemptRepository;
import io.butler.bet.data.TradeCounterExecutionClaimRepository;
import io.butler.bet.data.TradeCounterExecutionRequestRepository;
import io.butler.bet.execution.TradeCounterExecutionPayloadPolicy;
import io.butler.bet.execution.TradeCounterManualHandoffCoordinator;
import io.butler.bet.integration.sleeper.SleeperCounterTradeExpectationSnapshotRepository;
import io.butler.bet.integration.sleeper.SleeperManualCounterHandoffRepository;
import io.butler.bet.integration.sleeper.SleeperManualCounterHandoffService;
import io.butler.bet.integration.sleeper.SleeperPlatformCapabilityPolicy;
import io.butler.bet.integration.sleeper.SleeperTradeExpectationResolver;
import io.butler.bet.intelligence.TradeCounterAuthorizationPolicy;
import io.butler.bet.intelligence.TradeCounterExecutionReadinessPolicy;
import io.butler.bet.intelligence.TradeCounterProposalIdentityPolicy;
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

class ButlerTradeCounterHandoffCliTest {
    private static final String FINGERPRINT = "b".repeat(64);
    private static final String HASH = "a".repeat(64);
    private static final Instant PRESENTED_AT = Instant.parse("2026-09-04T21:10:00Z");
    private static final String MOVEMENT_JSON =
        "{\"rosterIds\":[1,2],\"playerAdds\":{},\"playerDrops\":{},\"draftPicks\":[]}";
    private static final String MOVEMENT_SHA256 =
        "02e4ff7d4a09d05592e6ff8efcaead4c6ed5bd49c616beb0275c9b10ced26c7c";

    @Test
    void parsesOnlyOneTrustedGrantId() {
        assertEquals("grant-1", ButlerTradeCounterHandoffCli.parseGrantId(
            new String[]{"trade", "counter-handoff", "grant-1"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerTradeCounterHandoffCli.parseGrantId(
            new String[]{"trade", "counter-handoff", "grant-1", "extra"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerTradeCounterHandoffCli.parseGrantId(
            new String[]{"trade", "counter-handoff", "  "}));
    }

    @Test
    void messageHandoffPrintsExactPayloadAndAcknowledgmentGatedNextSteps() {
        var destination = new TradeCounterAuthorizationPolicy.Destination(
            TradeCounterAuthorizationPolicy.DestinationType.MANAGER, "manager-22");
        String output = capturePresented(
            TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE,
            destination,
            TradeCounterExecutionAttemptRepository.PayloadKind.NEGOTIATION_MESSAGE_TEXT,
            "I'd counter if you add Player Three to your side of the deal.",
            SleeperManualCounterHandoffService.ReconciliationMode.NO_OFFICIAL_READBACK);

        assertTrue(output.contains("Trade counter Sleeper manual handoff"));
        assertTrue(output.contains("Execution readiness: READY"));
        assertTrue(output.contains("Exact governed handoff payload:"));
        assertTrue(output.contains("I'd counter if you add Player Three to your side of the deal."));
        assertTrue(output.contains("complete this action manually in Sleeper"));
        assertTrue(output.contains("does not prove that the action was completed"));
        assertTrue(output.contains("no supported official message readback"));
        assertTrue(output.contains("Next safe local inspection: butler trade counter-message-status grant-1"));
        assertTrue(output.contains("butler trade counter-message-ack grant-1 --confirm SENT_EXACT_MESSAGE"));
        assertTrue(output.contains("butler trade counter-message-finalize grant-1"));
        assertTrue(output.contains("Butler does not send the negotiation message."));
        assertTrue(output.contains("authorization grant remains unconsumed"));
        assertTrue(output.contains("execution attempt remains IN_FLIGHT"));
        assertFalse(output.contains("butler trade counter-reconcile grant-1"));
        assertFalse(output.contains("sent successfully"));
        assertFalse(output.contains("submitted successfully"));
    }

    @Test
    void tradeHandoffWithoutSnapshotShowsLocalStatusButBlocksReconcileAndFinalizeGuidance() {
        var destination = new TradeCounterAuthorizationPolicy.Destination(
            TradeCounterAuthorizationPolicy.DestinationType.LEAGUE, "l1");
        String payload = "{\"schema\":\"butler-counter-trade-request-v1\",\"proposalFingerprint\":\""
            + FINGERPRINT + "\"}";
        String output = capturePresented(
            TradeCounterAuthorizationPolicy.Action.SUBMIT_COUNTER_TRADE,
            destination,
            TradeCounterExecutionAttemptRepository.PayloadKind.COUNTER_TRADE_REQUEST_JSON,
            payload,
            SleeperManualCounterHandoffService.ReconciliationMode.SLEEPER_TRANSACTION_READBACK);

        assertTrue(output.contains(payload));
        assertTrue(output.contains("Sleeper exposes official transaction readback"));
        assertTrue(output.contains("does not yet have a usable immutable provider expectation"));
        assertTrue(output.contains(PRESENTED_AT.toString()));
        assertTrue(output.contains("Next safe local inspection: butler trade counter-status grant-1"));
        assertTrue(output.contains("Do not reconcile or finalize this trade until a usable immutable provider expectation snapshot is available."));
        assertFalse(output.contains("butler trade counter-reconcile grant-1 <sleeper-week>"));
        assertFalse(output.contains("butler trade counter-finalize grant-1 <sleeper-week>"));
        assertFalse(output.contains("immutable provider expectation snapshot."));
        assertFalse(output.contains("submitted successfully"));
    }

    @Test
    void tradeHandoffWithSnapshotShowsSeparateGetOnlyEvidenceAndExactReadbackFinalizationSteps() {
        var destination = new TradeCounterAuthorizationPolicy.Destination(
            TradeCounterAuthorizationPolicy.DestinationType.LEAGUE, "l1");
        String payload = "{\"schema\":\"butler-counter-trade-request-v1\",\"proposalFingerprint\":\""
            + FINGERPRINT + "\"}";
        String output = capturePresented(
            TradeCounterAuthorizationPolicy.Action.SUBMIT_COUNTER_TRADE,
            destination,
            TradeCounterExecutionAttemptRepository.PayloadKind.COUNTER_TRADE_REQUEST_JSON,
            payload,
            SleeperManualCounterHandoffService.ReconciliationMode.SLEEPER_TRANSACTION_READBACK,
            usableExpectation());

        assertTrue(output.contains("Sleeper trade expectation snapshot: SNAPSHOTTED"));
        assertTrue(output.contains("Next safe local inspection: butler trade counter-status grant-1"));
        assertTrue(output.contains("separate official GET-only step with an explicit Sleeper week"));
        assertTrue(output.contains("butler trade counter-reconcile grant-1 <sleeper-week>"));
        assertTrue(output.contains("may proceed only after exact completed readback"));
        assertTrue(output.contains("butler trade counter-finalize grant-1 <sleeper-week>"));
        assertFalse(output.contains("butler trade counter-message-ack"));
        assertFalse(output.contains("submitted successfully"));
    }

    @Test
    void blockedReadinessPrintsNoPayload() {
        var destination = new TradeCounterAuthorizationPolicy.Destination(
            TradeCounterAuthorizationPolicy.DestinationType.MANAGER, "manager-22");
        var readiness = new TradeCounterExecutionReadinessPolicy.Result(
            TradeCounterExecutionReadinessPolicy.POLICY_ID,
            TradeCounterAuthorizationPolicy.POLICY_ID,
            TradeCounterProposalIdentityPolicy.POLICY_ID,
            "grant-1",
            FINGERPRINT,
            FINGERPRINT,
            TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE,
            destination,
            TradeCounterExecutionReadinessPolicy.State.DRIFTED,
            TradeCounterAuthorizationPolicy.RevalidationState.DRIFTED,
            "Fresh proposal drifted.");

        String output = capture(() -> ButlerTradeCounterHandoffCli.printBlocked(readiness));

        assertTrue(output.contains("manual handoff unavailable"));
        assertTrue(output.contains("Execution readiness: DRIFTED"));
        assertTrue(output.contains("No execution attempt is created"));
        assertFalse(output.contains("Exact governed handoff payload:"));
    }

    private static String capturePresented(
        TradeCounterAuthorizationPolicy.Action action,
        TradeCounterAuthorizationPolicy.Destination destination,
        TradeCounterExecutionAttemptRepository.PayloadKind payloadKind,
        String payload,
        SleeperManualCounterHandoffService.ReconciliationMode reconciliationMode) {
        return capturePresented(action, destination, payloadKind, payload, reconciliationMode, null);
    }

    private static String capturePresented(
        TradeCounterAuthorizationPolicy.Action action,
        TradeCounterAuthorizationPolicy.Destination destination,
        TradeCounterExecutionAttemptRepository.PayloadKind payloadKind,
        String payload,
        SleeperManualCounterHandoffService.ReconciliationMode reconciliationMode,
        SleeperCounterTradeExpectationSnapshotRepository.SnapshotResult expectation) {
        var readiness = ready(action, destination);
        var coordinated = new TradeCounterManualHandoffCoordinator.Result(
            TradeCounterManualHandoffCoordinator.COORDINATOR_ID,
            TradeCounterExecutionPayloadPolicy.POLICY_ID,
            TradeCounterExecutionAttemptRepository.JOURNAL_POLICY_ID,
            TradeCounterExecutionClaimRepository.CLAIM_POLICY_ID,
            SleeperManualCounterHandoffRepository.JOURNAL_POLICY_ID,
            TradeCounterManualHandoffCoordinator.State.HANDOFF_PRESENTED,
            "Presented.",
            "attempt-1",
            "claim-1",
            "handoff-1",
            payloadKind,
            HASH);
        var handoff = new SleeperManualCounterHandoffService.Handoff(
            SleeperManualCounterHandoffService.SERVICE_ID,
            SleeperPlatformCapabilityPolicy.POLICY_ID,
            TradeCounterExecutionRequestRepository.REQUEST_POLICY_ID,
            "claim-1",
            "attempt-1",
            "grant-1",
            FINGERPRINT,
            action,
            destination,
            payloadKind,
            payload,
            HASH,
            reconciliationMode,
            "Manual Sleeper action required; presentation does not prove completion.");
        var presentation = new SleeperManualCounterHandoffRepository.PresentedHandoff(
            "handoff-1",
            SleeperManualCounterHandoffRepository.JOURNAL_POLICY_ID,
            SleeperManualCounterHandoffService.SERVICE_ID,
            SleeperPlatformCapabilityPolicy.POLICY_ID,
            TradeCounterExecutionRequestRepository.REQUEST_POLICY_ID,
            "claim-1",
            "attempt-1",
            "grant-1",
            FINGERPRINT,
            action,
            destination,
            payloadKind.name(),
            HASH,
            reconciliationMode,
            PRESENTED_AT);
        return capture(() -> ButlerTradeCounterHandoffCli.print(
            readiness, coordinated, handoff, presentation, expectation));
    }

    private static SleeperCounterTradeExpectationSnapshotRepository.SnapshotResult usableExpectation() {
        var snapshot = new SleeperCounterTradeExpectationSnapshotRepository.Snapshot(
            SleeperCounterTradeExpectationSnapshotRepository.POLICY_ID,
            SleeperTradeExpectationResolver.POLICY_ID,
            "claim-1",
            "handoff-1",
            "l1",
            "team-a",
            "team-b",
            "999999",
            Set.of(1, 2),
            Map.of(),
            Map.of(),
            Set.of(),
            MOVEMENT_JSON,
            MOVEMENT_SHA256,
            PRESENTED_AT.minusSeconds(1));
        return new SleeperCounterTradeExpectationSnapshotRepository.SnapshotResult(
            SleeperCounterTradeExpectationSnapshotRepository.State.SNAPSHOTTED,
            snapshot,
            "Provider movement was durably snapshotted.");
    }

    private static TradeCounterExecutionReadinessPolicy.Result ready(
        TradeCounterAuthorizationPolicy.Action action,
        TradeCounterAuthorizationPolicy.Destination destination) {
        return new TradeCounterExecutionReadinessPolicy.Result(
            TradeCounterExecutionReadinessPolicy.POLICY_ID,
            TradeCounterAuthorizationPolicy.POLICY_ID,
            TradeCounterProposalIdentityPolicy.POLICY_ID,
            "grant-1",
            FINGERPRINT,
            FINGERPRINT,
            action,
            destination,
            TradeCounterExecutionReadinessPolicy.State.READY,
            TradeCounterAuthorizationPolicy.RevalidationState.MATCH,
            "Fresh governed proposal identity exactly matches the trusted authorization grant.");
    }

    private static String capture(Runnable runnable) {
        var previous = System.out;
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
