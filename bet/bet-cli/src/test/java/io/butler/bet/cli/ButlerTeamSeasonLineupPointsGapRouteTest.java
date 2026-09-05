package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ButlerTeamSeasonLineupPointsGapRouteTest {
    @Test
    void routesTeamSeasonLineupPointsGapEvidenceThroughCentralizedRouter() {
        assertEquals(
            ButlerCommandRouter.Route.LEAGUE_TEAM_SEASON_LINEUP_POINTS_GAP_EVIDENCE,
            ButlerCommandRouter.route(new String[]{
                "league", "team-season-lineup-points-gap-evidence", "l1", "t1", "2026"}));
    }
}
