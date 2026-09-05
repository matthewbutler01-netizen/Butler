package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ButlerTeamSeasonLineupCaptureRouteTest {
    @Test
    void routesTeamSeasonLineupCaptureEvidenceThroughCentralizedRouter() {
        assertEquals(
            ButlerCommandRouter.Route.LEAGUE_TEAM_SEASON_LINEUP_CAPTURE_EVIDENCE,
            ButlerCommandRouter.route(new String[]{
                "league", "team-season-lineup-capture-evidence", "l1", "t1", "2026"}));
    }
}
