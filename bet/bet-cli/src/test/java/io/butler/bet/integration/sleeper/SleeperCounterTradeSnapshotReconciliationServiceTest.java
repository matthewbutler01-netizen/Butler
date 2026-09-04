package io.butler.bet.integration.sleeper;

import io.butler.bet.data.TradeCounterExecutionRequestRepository;
import io.butler.bet.intelligence.TradeCounterAuthorizationPolicy;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperCounterTradeSnapshotReconciliationServiceTest {
    private static final Instant PRESENTED_AT = Instant.parse("2026-09-04T21:20:00Z");
    private static final String MOVEMENT_JSON =
        "{\"rosterIds\":[1,2],\"playerAdds\":{\"101\":2,\"202\":1},"
            + "\"playerDrops\":{\"101\":1,\"202\":2},\"draftPicks\":[]}";

    @Test
    void exactCompleteTradeAfterPresentationMatchesUsingExplicitWeek() throws Exception {
        var snapshot = snapshot();
        var handoff = handoff();
        var client = new SleeperReadOnlyClient(uri -> {
            assertEquals(
                URI.create("https://api.sleeper.app/v1/league/289646328504385536/transactions/7"),
                uri);
            return new SleeperReadOnlyClient.Response(200, transactionJson(
                "tx-1", "complete", PRESENTED_AT.toEpochMilli() + 5_000));
        });

        var report = SleeperCounterTradeSnapshotReconciliationService.reconcile(
            snapshot, handoff, 7, client);

        assertEquals(SleeperCounterTradeSnapshotReconciliationService.State.RECONCILED, report.state());
        assertEquals(7, report.week());
        assertEquals(PRESENTED_AT.toEpochMilli(), report.notBeforeEpochMillis());
        assertEquals(SleeperTradeReconciliationPolicy.State.MATCH_COMPLETE,
            report.reconciliation().state());
        assertEquals(java.util.List.of("tx-1"), report.reconciliation().matchingTransactionIds());
        assertEquals(1, report.observedTransactions().size());
    }

    @Test
    void identicalHistoricalTradeBeforePresentationDoesNotMatch() throws Exception {
        var client = new SleeperReadOnlyClient(uri -> new SleeperReadOnlyClient.Response(
            200,
            transactionJson("old-tx", "complete", PRESENTED_AT.toEpochMilli() - 1)));

        var report = SleeperCounterTradeSnapshotReconciliationService.reconcile(
            snapshot(), handoff(), 4, client);

        assertEquals(SleeperTradeReconciliationPolicy.State.NO_MATCH,
            report.reconciliation().state());
        assertTrue(report.reconciliation().matchingTransactionIds().isEmpty());
    }

    @Test
    void pendingExactTradeRemainsPendingEvidence() throws Exception {
        var client = new SleeperReadOnlyClient(uri -> new SleeperReadOnlyClient.Response(
            200,
            transactionJson("pending-tx", "pending", PRESENTED_AT.toEpochMilli() + 1_000)));

        var report = SleeperCounterTradeSnapshotReconciliationService.reconcile(
            snapshot(), handoff(), 2, client);

        assertEquals(SleeperTradeReconciliationPolicy.State.MATCH_PENDING,
            report.reconciliation().state());
        assertEquals(java.util.List.of("pending-tx"), report.reconciliation().matchingTransactionIds());
    }

    @Test
    void snapshotAndHandoffMustReferenceSameDurablePresentation() {
        var mismatched = new SleeperManualCounterHandoffRepository.PresentedHandoff(
            "other-handoff",
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
                TradeCounterAuthorizationPolicy.DestinationType.LEAGUE, "l1"),
            "COUNTER_TRADE_REQUEST_JSON",
            "a".repeat(64),
            SleeperManualCounterHandoffService.ReconciliationMode.SLEEPER_TRANSACTION_READBACK,
            PRESENTED_AT);

        assertThrows(IllegalArgumentException.class, () ->
            SleeperCounterTradeSnapshotReconciliationService.reconcile(
                snapshot(), mismatched, 1,
                new SleeperReadOnlyClient(uri -> new SleeperReadOnlyClient.Response(200, "[]"))));
    }

    @Test
    void weekMustBeExplicitAndGoverned() {
        assertThrows(IllegalArgumentException.class, () ->
            SleeperCounterTradeSnapshotReconciliationService.reconcile(
                snapshot(), handoff(), 0,
                new SleeperReadOnlyClient(uri -> new SleeperReadOnlyClient.Response(200, "[]"))));
        assertThrows(IllegalArgumentException.class, () ->
            SleeperCounterTradeSnapshotReconciliationService.reconcile(
                snapshot(), handoff(), 31,
                new SleeperReadOnlyClient(uri -> new SleeperReadOnlyClient.Response(200, "[]"))));
    }

    private static SleeperCounterTradeExpectationSnapshotRepository.Snapshot snapshot() {
        return new SleeperCounterTradeExpectationSnapshotRepository.Snapshot(
            SleeperCounterTradeExpectationSnapshotRepository.POLICY_ID,
            SleeperTradeExpectationResolver.POLICY_ID,
            "claim-1",
            "handoff-1",
            "l1",
            "team-a",
            "team-b",
            "289646328504385536",
            Set.of(1, 2),
            Map.of("101", 2, "202", 1),
            Map.of("101", 1, "202", 2),
            Set.of(),
            MOVEMENT_JSON,
            sha256(MOVEMENT_JSON),
            PRESENTED_AT.plusSeconds(1));
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
                TradeCounterAuthorizationPolicy.DestinationType.LEAGUE, "l1"),
            "COUNTER_TRADE_REQUEST_JSON",
            "a".repeat(64),
            SleeperManualCounterHandoffService.ReconciliationMode.SLEEPER_TRANSACTION_READBACK,
            PRESENTED_AT);
    }

    private static String transactionJson(String id, String status, long created) {
        return "[{"
            + "\"transaction_id\":\"" + id + "\","
            + "\"type\":\"trade\","
            + "\"status\":\"" + status + "\","
            + "\"creator\":null,"
            + "\"created\":" + created + ","
            + "\"status_updated\":" + created + ","
            + "\"leg\":1,"
            + "\"roster_ids\":[1,2],"
            + "\"consenter_ids\":[1,2],"
            + "\"adds\":{\"101\":2,\"202\":1},"
            + "\"drops\":{\"101\":1,\"202\":2},"
            + "\"draft_picks\":[]"
            + "}]";
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
