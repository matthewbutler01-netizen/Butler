package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ButlerCommandRouterCounterNoActionAckTest {
    @Test
    void routesManualNoActionAcknowledgmentToDedicatedSurface() {
        assertEquals(
            ButlerCommandRouter.Route.TRADE_COUNTER_NO_ACTION_ACK,
            ButlerCommandRouter.route(
                new String[]{"trade", "counter-no-action-ack", "grant-1"}));
    }

    @Test
    void routingIsCaseInsensitive() {
        assertEquals(
            ButlerCommandRouter.Route.TRADE_COUNTER_NO_ACTION_ACK,
            ButlerCommandRouter.route(
                new String[]{"TRADE", "COUNTER-NO-ACTION-ACK", "grant-1"}));
    }
}
