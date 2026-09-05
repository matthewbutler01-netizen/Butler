package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ButlerLeagueRankingChangeFrequencyRouteTest {
    @Test
    void routesRankingChangeFrequencyCommandThroughCentralRouter() {
        assertEquals(
            ButlerCommandRouter.Route.LEAGUE_SEASON_LINEUP_CAPTURE_RANKING_CHANGE_FREQUENCY_EVIDENCE,
            ButlerCommandRouter.route(new String[] {
                "league", "season-lineup-capture-ranking-change-frequency-evidence", "l1", "2026"
            }));
    }
}
