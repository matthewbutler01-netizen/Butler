package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ButlerTeamPairLineupCaptureContrastRouteTest {
    @Test
    void routesPairwiseLineupCaptureContrastThroughCentralizedRouter() {
        assertEquals(
            ButlerCommandRouter.Route.LEAGUE_TEAM_PAIR_LINEUP_CAPTURE_CONTRAST_EVIDENCE,
            ButlerCommandRouter.route(new String[]{
                "league", "team-pair-lineup-capture-contrast-evidence", "l1", "ta", "tb", "2026"}));
    }
}
