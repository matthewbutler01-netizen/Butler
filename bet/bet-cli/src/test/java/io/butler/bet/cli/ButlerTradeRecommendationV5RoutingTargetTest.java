package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ButlerTradeRecommendationV5RoutingTargetTest {
    @Test
    void tradeRecommendationRouteTargetsV6Cli() {
        assertEquals(
            ButlerCommandRouter.Route.TRADE_RECOMMENDATION,
            ButlerCommandRouter.route(new String[]{
                "trade", "recommendation", "l1", "2026", "p1", "p2", "side-a"}));
        assertEquals(
            ButlerTradeRecommendationV6Cli.class,
            ButlerCommandRouter.tradeRecommendationImplementation());
    }
}
