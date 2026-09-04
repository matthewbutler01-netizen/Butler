package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ButlerCommandRouterCounterMessageFinalizeTest {
    @Test
    void routesManualMessageFinalizationToDedicatedSurface() {
        assertEquals(
            ButlerCommandRouter.Route.TRADE_COUNTER_MESSAGE_FINALIZE,
            ButlerCommandRouter.route(
                new String[]{"trade", "counter-message-finalize", "grant-1"}));
    }

    @Test
    void routingIsCaseInsensitive() {
        assertEquals(
            ButlerCommandRouter.Route.TRADE_COUNTER_MESSAGE_FINALIZE,
            ButlerCommandRouter.route(
                new String[]{"TRADE", "COUNTER-MESSAGE-FINALIZE", "grant-1"}));
    }
}
