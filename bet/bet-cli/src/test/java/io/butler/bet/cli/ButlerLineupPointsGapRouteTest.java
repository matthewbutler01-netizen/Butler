package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ButlerLineupPointsGapRouteTest {
    @Test
    void routesLineupPointsGapEvidenceThroughCentralizedRouter() {
        assertEquals(
            ButlerCommandRouter.Route.LEAGUE_TEAM_WEEK_LINEUP_POINTS_GAP_EVIDENCE,
            ButlerCommandRouter.route(new String[]{
                "league", "team-week-lineup-points-gap-evidence", "l1", "t1", "2026", "3"}));
    }
}
