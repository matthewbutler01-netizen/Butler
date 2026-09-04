package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ButlerCommandRouterHelpTest {
    @Test
    void noArgumentsRouteToGlobalHelpLauncher() {
        assertEquals(ButlerCommandRouter.Route.HELP, ButlerCommandRouter.route(new String[0]));
    }

    @Test
    void nullArgumentsRouteToGlobalHelpLauncher() {
        assertEquals(ButlerCommandRouter.Route.HELP, ButlerCommandRouter.route(null));
    }

    @Test
    void explicitHelpRoutesToGlobalHelpLauncherCaseInsensitively() {
        assertEquals(ButlerCommandRouter.Route.HELP, ButlerCommandRouter.route(new String[]{"HELP"}));
    }
}
