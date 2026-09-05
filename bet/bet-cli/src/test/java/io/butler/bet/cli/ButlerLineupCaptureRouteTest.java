package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ButlerLineupCaptureRouteTest {
    @Test
    void routesTeamWeekLineupCaptureEvidenceThroughCentralizedRouter() {
        assertEquals(
            ButlerCommandRouter.Route.LEAGUE_TEAM_WEEK_LINEUP_CAPTURE_EVIDENCE,
            ButlerCommandRouter.route(new String[]{
                "league", "team-week-lineup-capture-evidence", "l1", "t1", "2026", "3"}));
    }
}
