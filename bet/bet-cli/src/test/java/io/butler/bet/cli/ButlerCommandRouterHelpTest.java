package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerCommandRouterHelpTest {
    @Test
    void noArgumentsRemainOnEstablishedComposedRouteAndAreRecognizedAsGlobalHelp() {
        assertEquals(ButlerCommandRouter.Route.COMPOSED, ButlerCommandRouter.route(new String[0]));
        assertTrue(ButlerCommandRouter.isGlobalHelp(new String[0]));
    }

    @Test
    void nullArgumentsRemainOnEstablishedComposedRouteAndAreRecognizedAsGlobalHelp() {
        assertEquals(ButlerCommandRouter.Route.COMPOSED, ButlerCommandRouter.route(null));
        assertTrue(ButlerCommandRouter.isGlobalHelp(null));
    }

    @Test
    void explicitHelpRemainsOnEstablishedComposedRouteAndIsRecognizedCaseInsensitively() {
        assertEquals(ButlerCommandRouter.Route.COMPOSED, ButlerCommandRouter.route(new String[]{"HELP"}));
        assertTrue(ButlerCommandRouter.isGlobalHelp(new String[]{"HELP"}));
    }

    @Test
    void ordinaryComposedCommandsDoNotReceiveGlobalHelpAppendix() {
        assertFalse(ButlerCommandRouter.isGlobalHelp(new String[]{"league", "status", "l1"}));
        assertFalse(ButlerCommandRouter.isGlobalHelp(new String[]{"trade", "compare", "l1", "a", "b"}));
    }
}
