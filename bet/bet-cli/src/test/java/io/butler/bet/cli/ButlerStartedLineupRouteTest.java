package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ButlerStartedLineupRouteTest {
    @Test
    void routesStartedLineupEvidenceThroughCentralizedRouter() {
        assertEquals(
            ButlerCommandRouter.Route.LEAGUE_TEAM_WEEK_STARTED_LINEUP_EVIDENCE,
            ButlerCommandRouter.route(new String[]{
                "league", "team-week-started-lineup-evidence", "l1", "t1", "2026", "3"}));
    }
}
