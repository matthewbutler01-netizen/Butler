package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ButlerLeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditRouteTest {
    @Test
    void routesHistoricalCalibrationCorpusAuditThroughCentralRouter() {
        assertEquals(
            ButlerCommandRouter.Route.LEAGUE_LINEUP_CAPTURE_RANKING_SENSITIVITY_CALIBRATION_CORPUS_AUDIT,
            ButlerCommandRouter.route(new String[] {
                "league", "lineup-capture-ranking-sensitivity-calibration-corpus-audit", "2024", "2026"
            }));
    }
}
