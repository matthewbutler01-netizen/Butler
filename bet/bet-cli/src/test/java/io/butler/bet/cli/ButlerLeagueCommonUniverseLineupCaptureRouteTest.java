package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ButlerLeagueCommonUniverseLineupCaptureRouteTest {
    @Test
    void routesLeagueCommonUniverseLineupCaptureEvidenceCentrally() {
        assertEquals(
            ButlerCommandRouter.Route.LEAGUE_SEASON_LINEUP_CAPTURE_COMMON_UNIVERSE_EVIDENCE,
            ButlerCommandRouter.route(new String[] {
                "league", "season-lineup-capture-common-universe-evidence", "l1", "2026"
            }));
    }
}
