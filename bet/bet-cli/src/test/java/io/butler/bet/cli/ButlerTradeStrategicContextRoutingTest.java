package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ButlerTradeStrategicContextRoutingTest {
    @Test
    void routesStrategicContextToSpecializedCli() {
        assertEquals(ButlerCommandRouter.Route.TRADE_STRATEGIC_CONTEXT,
            ButlerCommandRouter.route(new String[]{"trade", "strategic-context", "l1", "2026", "p1", "p2"}));
    }
}
