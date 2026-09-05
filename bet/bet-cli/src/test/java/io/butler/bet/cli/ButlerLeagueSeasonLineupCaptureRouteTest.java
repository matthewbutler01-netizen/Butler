package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ButlerLeagueSeasonLineupCaptureRouteTest {
    @Test
    void routesLeagueSeasonLineupCaptureEvidenceThroughCentralizedRouter() {
        assertEquals(
            ButlerCommandRouter.Route.LEAGUE_SEASON_LINEUP_CAPTURE_EVIDENCE,
            ButlerCommandRouter.route(new String[]{
                "league", "season-lineup-capture-evidence", "l1", "2026"}));
    }
}
