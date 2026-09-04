package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ButlerCommandRouterCounterMessageAckTest {
    @Test
    void routesManualMessageAcknowledgmentToDedicatedSurface() {
        assertEquals(
            ButlerCommandRouter.Route.TRADE_COUNTER_MESSAGE_ACK,
            ButlerCommandRouter.route(new String[]{"trade", "counter-message-ack", "grant-1"}));
    }

    @Test
    void routingIsCaseInsensitive() {
        assertEquals(
            ButlerCommandRouter.Route.TRADE_COUNTER_MESSAGE_ACK,
            ButlerCommandRouter.route(new String[]{"TRADE", "COUNTER-MESSAGE-ACK", "grant-1"}));
    }
}
