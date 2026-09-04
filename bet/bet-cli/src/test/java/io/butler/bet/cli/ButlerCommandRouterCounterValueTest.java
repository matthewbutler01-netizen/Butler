package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ButlerCommandRouterCounterValueTest {
    @Test
    void routesCounterValueSeparatelyFromRecommendation() {
        assertEquals(
            ButlerCommandRouter.Route.TRADE_COUNTER_VALUE,
            ButlerCommandRouter.route(new String[]{
                "trade", "counter-value", "l1", "player:p1", "pick:k1"}));
        assertEquals(
            ButlerCommandRouter.Route.TRADE_RECOMMENDATION,
            ButlerCommandRouter.route(new String[]{
                "trade", "recommendation", "l1", "2026", "p1", "p2", "side-a"}));
    }
}
