package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ButlerLeagueLineupCaptureRankingRouteTest {
    @Test
    void routesLeagueSeasonLineupCaptureRankingEvidenceThroughCentralRouter() {
        assertEquals(
            ButlerCommandRouter.Route.LEAGUE_SEASON_LINEUP_CAPTURE_RANKING_EVIDENCE,
            ButlerCommandRouter.route(new String[]{
                "league", "season-lineup-capture-ranking-evidence", "l1", "2026"
            }));
    }
}
