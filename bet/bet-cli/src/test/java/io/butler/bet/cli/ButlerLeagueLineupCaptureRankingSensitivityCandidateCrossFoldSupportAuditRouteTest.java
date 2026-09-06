package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ButlerLeagueLineupCaptureRankingSensitivityCandidateCrossFoldSupportAuditRouteTest {
    @Test
    void routesCandidateCrossFoldSupportAuditThroughCentralRouter() {
        assertEquals(
            ButlerCommandRouter.Route.LEAGUE_LINEUP_CAPTURE_RANKING_SENSITIVITY_CANDIDATE_CROSS_FOLD_SUPPORT_AUDIT,
            ButlerCommandRouter.route(new String[] {
                "league", "lineup-capture-ranking-sensitivity-candidate-cross-fold-support-audit", "2024", "2026"
            }));
    }
}
