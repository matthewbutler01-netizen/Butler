package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ButlerCommandRouterCounterMessageStatusTest {
    @Test
    void routesManualMessageLifecycleStatusToDedicatedSurface() {
        assertEquals(
            ButlerCommandRouter.Route.TRADE_COUNTER_MESSAGE_STATUS,
            ButlerCommandRouter.route(
                new String[]{"trade", "counter-message-status", "grant-1"}));
    }

    @Test
    void routingIsCaseInsensitive() {
        assertEquals(
            ButlerCommandRouter.Route.TRADE_COUNTER_MESSAGE_STATUS,
            ButlerCommandRouter.route(
                new String[]{"TRADE", "COUNTER-MESSAGE-STATUS", "grant-1"}));
    }
}
