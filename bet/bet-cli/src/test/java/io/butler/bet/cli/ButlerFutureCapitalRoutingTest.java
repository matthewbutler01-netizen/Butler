package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ButlerFutureCapitalRoutingTest {
    @Test
    void routesFutureCapitalToSpecializedCli() {
        assertEquals(ButlerCommandRouter.Route.LEAGUE_FUTURE_CAPITAL,
            ButlerCommandRouter.route(new String[]{"league", "future-capital", "l1"}));
    }
}
