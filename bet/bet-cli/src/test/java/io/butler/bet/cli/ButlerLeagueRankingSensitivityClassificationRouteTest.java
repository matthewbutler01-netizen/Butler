package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ButlerLeagueRankingSensitivityClassificationRouteTest {
    @Test
    void centralizedRouterOwnsRankingSensitivityClassificationCommand() {
        assertEquals(
            ButlerCommandRouter.Route.LEAGUE_SEASON_LINEUP_CAPTURE_RANKING_SENSITIVITY_CLASSIFICATION_EVIDENCE,
            ButlerCommandRouter.route(new String[] {
                "league", "season-lineup-capture-ranking-sensitivity-classification-evidence", "l1", "2026"
            }));
    }
}
