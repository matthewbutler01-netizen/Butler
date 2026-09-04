package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ButlerCommandRouterCounterReconcileTest {
    @Test
    void routesCounterReconcileToDedicatedSurface() {
        assertEquals(
            ButlerCommandRouter.Route.TRADE_COUNTER_RECONCILE,
            ButlerCommandRouter.route(new String[]{"trade", "counter-reconcile", "grant-1", "7"}));
    }

    @Test
    void routingIsCaseInsensitive() {
        assertEquals(
            ButlerCommandRouter.Route.TRADE_COUNTER_RECONCILE,
            ButlerCommandRouter.route(new String[]{"TRADE", "COUNTER-RECONCILE", "grant-1", "7"}));
    }
}
