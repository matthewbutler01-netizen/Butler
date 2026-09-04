package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ButlerCommandRouterCounterStatusTest {
    @Test
    void routesLocalManualTradeStatusToDedicatedSurface() {
        assertEquals(
            ButlerCommandRouter.Route.TRADE_COUNTER_STATUS,
            ButlerCommandRouter.route(new String[]{"trade", "counter-status", "grant-1"}));
    }

    @Test
    void routingIsCaseInsensitive() {
        assertEquals(
            ButlerCommandRouter.Route.TRADE_COUNTER_STATUS,
            ButlerCommandRouter.route(new String[]{"TRADE", "COUNTER-STATUS", "grant-1"}));
    }
}
