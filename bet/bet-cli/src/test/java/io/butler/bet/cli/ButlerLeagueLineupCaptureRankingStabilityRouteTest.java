package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ButlerLeagueLineupCaptureRankingStabilityRouteTest {
    @Test
    void routesLeagueSeasonRankingStabilityEvidenceToDedicatedCommand() {
        assertEquals(
            ButlerCommandRouter.Route.LEAGUE_SEASON_LINEUP_CAPTURE_RANKING_STABILITY_EVIDENCE,
            ButlerCommandRouter.route(new String[] {
                "league", "season-lineup-capture-ranking-stability-evidence", "l1", "2026"
            }));
    }
}
