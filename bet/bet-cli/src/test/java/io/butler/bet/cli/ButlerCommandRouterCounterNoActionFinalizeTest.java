package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ButlerCommandRouterCounterNoActionFinalizeTest {
    @Test
    void routesManualNoActionFinalizationToDedicatedSurface() {
        assertEquals(
            ButlerCommandRouter.Route.TRADE_COUNTER_NO_ACTION_FINALIZE,
            ButlerCommandRouter.route(
                new String[]{"trade", "counter-no-action-finalize", "grant-1"}));
    }

    @Test
    void routingIsCaseInsensitive() {
        assertEquals(
            ButlerCommandRouter.Route.TRADE_COUNTER_NO_ACTION_FINALIZE,
            ButlerCommandRouter.route(
                new String[]{"TRADE", "COUNTER-NO-ACTION-FINALIZE", "grant-1"}));
    }
}
