package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ButlerCommandRouterCounterFinalizeTest {
    @Test
    void routesCounterFinalizeToDedicatedSurface() {
        assertEquals(
            ButlerCommandRouter.Route.TRADE_COUNTER_FINALIZE,
            ButlerCommandRouter.route(new String[]{"trade", "counter-finalize", "grant-1", "7"}));
    }

    @Test
    void routingIsCaseInsensitive() {
        assertEquals(
            ButlerCommandRouter.Route.TRADE_COUNTER_FINALIZE,
            ButlerCommandRouter.route(new String[]{"TRADE", "COUNTER-FINALIZE", "grant-1", "7"}));
    }
}
