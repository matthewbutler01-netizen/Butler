package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ButlerCommandRouterPlayerDetailTest {

    @Test
    void routesPlayerDetailSeparatelyFromProfileAndComposedCommands() {
        assertEquals(
            ButlerCommandRouter.Route.PLAYER_DETAIL,
            ButlerCommandRouter.route(new String[]{"league", "player-detail", "l1", "p1"}));
        assertEquals(
            ButlerCommandRouter.Route.PLAYER_EVIDENCE_PROFILE,
            ButlerCommandRouter.route(new String[]{"league", "player-evidence-profile", "l1"}));
    }
}
