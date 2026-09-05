package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ButlerLeagueLineupCaptureRankingSensitivityCandidateThresholdStudyRouteTest {
    @Test
    void routesCandidateThresholdStudyThroughCentralRouter() {
        assertEquals(
            ButlerCommandRouter.Route.LEAGUE_LINEUP_CAPTURE_RANKING_SENSITIVITY_CANDIDATE_THRESHOLD_STUDY,
            ButlerCommandRouter.route(new String[] {
                "league", "lineup-capture-ranking-sensitivity-candidate-threshold-study", "2024", "2026"
            }));
    }
}
