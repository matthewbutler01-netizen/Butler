package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ButlerTeamWeekMatchupRouteTest {
    @Test
    void routesExactWeeklyMatchupEvidenceThroughCentralizedRouter() {
        assertEquals(
            ButlerCommandRouter.Route.LEAGUE_TEAM_WEEK_MATCHUP_EVIDENCE,
            ButlerCommandRouter.route(new String[]{
                "league", "team-week-matchup-evidence", "l1", "t1", "2026", "3"}));
    }
}
