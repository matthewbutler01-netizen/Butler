package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ButlerCommandRouterCounterStrategicTest {
    @Test
    void routesCounterStrategicSeparatelyFromMarketOnlyCounterAndRecommendation() {
        assertEquals(
            ButlerCommandRouter.Route.TRADE_COUNTER_STRATEGIC,
            ButlerCommandRouter.route(new String[]{
                "trade", "counter-strategic", "l1", "2026", "p1", "p2"}));
        assertEquals(
            ButlerCommandRouter.Route.TRADE_COUNTER_VALUE,
            ButlerCommandRouter.route(new String[]{
                "trade", "counter-value", "l1", "p1", "p2"}));
        assertEquals(
            ButlerCommandRouter.Route.TRADE_RECOMMENDATION,
            ButlerCommandRouter.route(new String[]{
                "trade", "recommendation", "l1", "2026", "p1", "p2", "side-a"}));
    }
}
