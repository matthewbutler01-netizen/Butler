package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ButlerCommandRouterCounterHandoffTest {
    @Test
    void routesExactCounterHandoffCommand() {
        assertEquals(
            ButlerCommandRouter.Route.TRADE_COUNTER_HANDOFF,
            ButlerCommandRouter.route(new String[]{"trade", "counter-handoff", "grant-1"}));
    }

    @Test
    void nearMissFallsBackToComposedRouter() {
        assertEquals(
            ButlerCommandRouter.Route.COMPOSED,
            ButlerCommandRouter.route(new String[]{"trade", "counter-handoffx", "grant-1"}));
    }
}
