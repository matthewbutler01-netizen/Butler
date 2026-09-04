package io.butler.bet.integration.sleeper;

import io.butler.bet.intelligence.TradeCounterAuthorizationPolicy;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperReadOnlyClientTest {
    @Test
    void capabilityPolicyRequiresManualHandoffForBothWrites() {
        var message = SleeperPlatformCapabilityPolicy.assess(
            TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE);
        var trade = SleeperPlatformCapabilityPolicy.assess(
            TradeCounterAuthorizationPolicy.Action.SUBMIT_COUNTER_TRADE);

        assertEquals(SleeperPlatformCapabilityPolicy.POLICY_ID, message.policyId());
        assertEquals(SleeperPlatformCapabilityPolicy.WriteCapability.UNSUPPORTED_OFFICIAL_API,
            message.writeCapability());
        assertEquals(SleeperPlatformCapabilityPolicy.ExecutionChannel.MANUAL_HANDOFF_REQUIRED,
            message.executionChannel());
        assertEquals(SleeperPlatformCapabilityPolicy.ReadReconciliationCapability.NOT_AVAILABLE,
            message.readReconciliationCapability());

        assertEquals(SleeperPlatformCapabilityPolicy.WriteCapability.UNSUPPORTED_OFFICIAL_API,
            trade.writeCapability());
        assertEquals(SleeperPlatformCapabilityPolicy.ExecutionChannel.MANUAL_HANDOFF_REQUIRED,
            trade.executionChannel());
        assertEquals(SleeperPlatformCapabilityPolicy.ReadReconciliationCapability.TRANSACTIONS_SUPPORTED,
            trade.readReconciliationCapability());
    }

    @Test
    void transactionsUsesOnlyDocumentedGetUriAndParsesTradeEvidence() throws Exception {
        AtomicReference<URI> requested = new AtomicReference<>();
        String body = """
            [
              {
                "type": "trade",
                "transaction_id": "434852362033561600",
                "status_updated": 1558039402803,
                "status": "complete",
                "roster_ids": [2, 1],
                "leg": 1,
                "draft_picks": [
                  {
                    "season": "2027",
                    "round": 2,
                    "roster_id": 1,
                    "previous_owner_id": 1,
                    "owner_id": 2
                  }
                ],
                "creator": "160000000000000000",
                "created": 1558039391576,
                "consenter_ids": [2, 1],
                "adds": {"p2": 1},
                "drops": {"p1": 2}
              }
            ]
            """;
        var client = new SleeperReadOnlyClient(uri -> {
            requested.set(uri);
            return new SleeperReadOnlyClient.Response(200, body);
        });

        var transactions = client.transactions("289646328504385536", 1);

        assertEquals(URI.create(
            "https://api.sleeper.app/v1/league/289646328504385536/transactions/1"),
            requested.get());
        assertEquals(1, transactions.size());
        var transaction = transactions.getFirst();
        assertTrue(transaction.trade());
        assertEquals("434852362033561600", transaction.transactionId());
        assertEquals("complete", transaction.status());
        assertEquals("160000000000000000", transaction.creatorUserId());
        assertEquals(1, transaction.leg());
        assertEquals(1, transaction.adds().get("p2"));
        assertEquals(2, transaction.drops().get("p1"));
        assertEquals(1, transaction.draftPicks().size());
        var pick = transaction.draftPicks().getFirst();
        assertEquals("2027", pick.season());
        assertEquals(2, pick.round());
        assertEquals(1, pick.previousOwnerId());
        assertEquals(2, pick.ownerId());
    }

    @Test
    void nullSleeperMapsAndListsBecomeEmptyEvidence() throws Exception {
        var client = new SleeperReadOnlyClient(uri -> new SleeperReadOnlyClient.Response(200, """
            [{
              "type":"trade",
              "transaction_id":"1",
              "status":"pending",
              "roster_ids":null,
              "consenter_ids":null,
              "adds":null,
              "drops":null,
              "draft_picks":null
            }]
            """));

        var transaction = client.transactions("1", 18).getFirst();
        assertTrue(transaction.rosterIds().isEmpty());
        assertTrue(transaction.consenterIds().isEmpty());
        assertTrue(transaction.adds().isEmpty());
        assertTrue(transaction.drops().isEmpty());
        assertTrue(transaction.draftPicks().isEmpty());
        assertFalse(transaction.status().isBlank());
    }

    @Test
    void nonSuccessHttpAndMalformedBodyFailClosed() {
        var unavailable = new SleeperReadOnlyClient(uri ->
            new SleeperReadOnlyClient.Response(503, "service unavailable"));
        var malformed = new SleeperReadOnlyClient(uri ->
            new SleeperReadOnlyClient.Response(200, "{}"));

        assertThrows(IOException.class, () -> unavailable.transactions("1", 1));
        assertThrows(IOException.class, () -> malformed.transactions("1", 1));
    }

    @Test
    void officialCoordinatesAreStrictAndTransportSurfaceIsGetOnly() {
        var client = new SleeperReadOnlyClient(uri ->
            new SleeperReadOnlyClient.Response(200, "[]"));

        assertThrows(IllegalArgumentException.class, () -> client.transactions("league-1", 1));
        assertThrows(IllegalArgumentException.class, () -> client.transactions("1", 0));
        assertThrows(IllegalArgumentException.class, () -> client.transactions("1", 31));
        assertTrue(SleeperReadOnlyClient.GetTransport.class.isAnnotationPresent(FunctionalInterface.class));
        assertEquals(1, SleeperReadOnlyClient.GetTransport.class.getDeclaredMethods().length);
        assertEquals("get", SleeperReadOnlyClient.GetTransport.class.getDeclaredMethods()[0].getName());
    }
}
