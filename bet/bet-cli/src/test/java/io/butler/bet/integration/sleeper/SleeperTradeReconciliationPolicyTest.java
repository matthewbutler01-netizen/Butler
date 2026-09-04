package io.butler.bet.integration.sleeper;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperTradeReconciliationPolicyTest {
    private static final long NOT_BEFORE = 2_000L;

    @Test
    void exactPendingTradeMatchesOneTransaction() {
        var expected = expected();
        var result = SleeperTradeReconciliationPolicy.reconcile(expected,
            List.of(transaction("tx-1", "pending", 2_500L, "user-1")));

        assertEquals(SleeperTradeReconciliationPolicy.State.MATCH_PENDING, result.state());
        assertEquals(List.of("tx-1"), result.matchingTransactionIds());
        assertFalse(result.reconciliationEvidenceIncomplete());
    }

    @Test
    void exactCompleteTradeMatchesOneTransaction() {
        var result = SleeperTradeReconciliationPolicy.reconcile(expected(),
            List.of(transaction("tx-1", "complete", 2_500L, "user-1")));

        assertEquals(SleeperTradeReconciliationPolicy.State.MATCH_COMPLETE, result.state());
        assertEquals(List.of("tx-1"), result.matchingTransactionIds());
    }

    @Test
    void oldIdenticalTradeDoesNotMatchBecauseOfNotBeforeBoundary() {
        var result = SleeperTradeReconciliationPolicy.reconcile(expected(),
            List.of(transaction("old", "complete", 1_999L, "user-1")));

        assertEquals(SleeperTradeReconciliationPolicy.State.NO_MATCH, result.state());
        assertTrue(result.matchingTransactionIds().isEmpty());
    }

    @Test
    void creatorMismatchDoesNotMatchWhenCreatorIsGoverned() {
        var result = SleeperTradeReconciliationPolicy.reconcile(expected(),
            List.of(transaction("tx-1", "pending", 2_500L, "someone-else")));

        assertEquals(SleeperTradeReconciliationPolicy.State.NO_MATCH, result.state());
    }

    @Test
    void exactCoordinatesWithUnsupportedStatusAreInconclusive() {
        var result = SleeperTradeReconciliationPolicy.reconcile(expected(),
            List.of(transaction("tx-1", "failed", 2_500L, "user-1")));

        assertEquals(SleeperTradeReconciliationPolicy.State.INCONCLUSIVE, result.state());
        assertEquals(List.of("tx-1"), result.matchingTransactionIds());
        assertTrue(result.reconciliationEvidenceIncomplete());
    }

    @Test
    void exactCoordinatesWithoutCreatedTimestampAreInconclusive() {
        var result = SleeperTradeReconciliationPolicy.reconcile(expected(),
            List.of(transaction("tx-1", "pending", null, "user-1")));

        assertEquals(SleeperTradeReconciliationPolicy.State.INCONCLUSIVE, result.state());
        assertTrue(result.matchingTransactionIds().isEmpty());
        assertTrue(result.reconciliationEvidenceIncomplete());
    }

    @Test
    void multipleExactEligibleTradesFailClosedAsAmbiguous() {
        var result = SleeperTradeReconciliationPolicy.reconcile(expected(), List.of(
            transaction("tx-1", "pending", 2_500L, "user-1"),
            transaction("tx-2", "complete", 2_600L, "user-1")));

        assertEquals(SleeperTradeReconciliationPolicy.State.AMBIGUOUS, result.state());
        assertEquals(List.of("tx-1", "tx-2"), result.matchingTransactionIds());
    }

    @Test
    void anyAssetOrRosterDifferenceProducesNoMatch() {
        var base = transaction("tx-1", "pending", 2_500L, "user-1");
        var wrongAdds = new SleeperReadOnlyClient.SleeperTransaction(
            base.transactionId(), base.type(), base.status(), base.creatorUserId(),
            base.createdEpochMillis(), base.statusUpdatedEpochMillis(), base.leg(),
            base.rosterIds(), base.consenterIds(), Map.of("player-2", 10),
            base.drops(), base.draftPicks());

        assertEquals(SleeperTradeReconciliationPolicy.State.NO_MATCH,
            SleeperTradeReconciliationPolicy.reconcile(expected(), List.of(wrongAdds)).state());
    }

    @Test
    void expectedTradeRejectsWeakCoordinates() {
        assertThrows(IllegalArgumentException.class, () -> new SleeperTradeReconciliationPolicy.ExpectedTrade(
            "league-1", 1, Set.of(10, 11), Map.of("p", 11), Map.of("p", 10), Set.of(), null, 0));
        assertThrows(IllegalArgumentException.class, () -> new SleeperTradeReconciliationPolicy.ExpectedTrade(
            "123", 1, Set.of(10), Map.of("p", 10), Map.of(), Set.of(), null, 0));
        assertThrows(IllegalArgumentException.class, () -> new SleeperTradeReconciliationPolicy.ExpectedTrade(
            "123", 1, Set.of(10, 11), Map.of(), Map.of(), Set.of(), null, 0));
        assertThrows(IllegalArgumentException.class, () -> new SleeperTradeReconciliationPolicy.ExpectedTrade(
            "123", 1, Set.of(10, 11), Map.of("p", 12), Map.of(), Set.of(), null, 0));
    }

    private static SleeperTradeReconciliationPolicy.ExpectedTrade expected() {
        return new SleeperTradeReconciliationPolicy.ExpectedTrade(
            "289646328504385536",
            1,
            Set.of(10, 11),
            Map.of("player-2", 11),
            Map.of("player-1", 10),
            Set.of(new SleeperReadOnlyClient.DraftPick("2027", 2, 10, 10, 11)),
            "user-1",
            NOT_BEFORE);
    }

    private static SleeperReadOnlyClient.SleeperTransaction transaction(
        String id,
        String status,
        Long created,
        String creator) {
        return new SleeperReadOnlyClient.SleeperTransaction(
            id,
            "trade",
            status,
            creator,
            created,
            created == null ? null : created + 100,
            1,
            List.of(10, 11),
            List.of(10),
            Map.of("player-2", 11),
            Map.of("player-1", 10),
            List.of(new SleeperReadOnlyClient.DraftPick("2027", 2, 10, 10, 11)));
    }
}
