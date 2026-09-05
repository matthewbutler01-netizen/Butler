package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ButlerLeagueSeasonLineupPointsGapRouteTest {
    @Test
    void routesLeagueSeasonLineupPointsGapEvidenceThroughCentralizedRouter() {
        assertEquals(
            ButlerCommandRouter.Route.LEAGUE_SEASON_LINEUP_POINTS_GAP_EVIDENCE,
            ButlerCommandRouter.route(new String[]{
                "league", "season-lineup-points-gap-evidence", "l1", "2026"}));
    }
}
