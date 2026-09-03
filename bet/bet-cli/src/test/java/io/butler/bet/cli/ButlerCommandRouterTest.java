package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ButlerCommandRouterTest {
    @Test
    void routesSpecializedCommandFamiliesWithoutLauncherChaining() {
        assertEquals(ButlerCommandRouter.Route.AGE_CONTEXT,
            ButlerCommandRouter.route(new String[]{"league", "age-context", "l1"}));
        assertEquals(ButlerCommandRouter.Route.AGE_PRODUCTION_CONTEXT,
            ButlerCommandRouter.route(new String[]{"league", "age-production-context", "l1"}));
        assertEquals(ButlerCommandRouter.Route.LEAGUE_AGING_MODEL_EVIDENCE,
            ButlerCommandRouter.route(new String[]{"league", "aging-model-evidence", "l1", "2026"}));
        assertEquals(ButlerCommandRouter.Route.PLAYER_EVIDENCE_PROFILE,
            ButlerCommandRouter.route(new String[]{"league", "player-evidence-profile", "l1"}));
        assertEquals(ButlerCommandRouter.Route.LONGITUDINAL_EVIDENCE,
            ButlerCommandRouter.route(new String[]{"league", "longitudinal-evidence", "l1"}));
        assertEquals(ButlerCommandRouter.Route.AGING_MODEL_UNIVERSE,
            ButlerCommandRouter.route(new String[]{"nflverse", "aging-model-players-preview"}));
        assertEquals(ButlerCommandRouter.Route.AGING_MODEL_UNIVERSE,
            ButlerCommandRouter.route(new String[]{"nflverse", "aging-model-production-refresh", "2018", "2025"}));
        assertEquals(ButlerCommandRouter.Route.AGING_MODEL_SAMPLE_AUDIT,
            ButlerCommandRouter.route(new String[]{"aging-model", "sample-audit"}));
        assertEquals(ButlerCommandRouter.Route.AGING_MODEL_SAMPLE_BREADTH,
            ButlerCommandRouter.route(new String[]{"aging-model", "sample-breadth"}));
        assertEquals(ButlerCommandRouter.Route.AGING_MODEL_LOCAL_SMOOTHER,
            ButlerCommandRouter.route(new String[]{"aging-model", "local-smoother"}));
        assertEquals(ButlerCommandRouter.Route.AGING_MODEL_PUBLISHED_SMOOTHER,
            ButlerCommandRouter.route(new String[]{"aging-model", "published-smoother"}));
        assertEquals(ButlerCommandRouter.Route.AGING_MODEL_PUBLISHED_CELL,
            ButlerCommandRouter.route(new String[]{"aging-model", "published-cell", "WR", "RECEIVING_YARDS_PER_GAME", "38"}));
        assertEquals(ButlerCommandRouter.Route.AGING_MODEL_POSITION_AGE_EVIDENCE,
            ButlerCommandRouter.route(new String[]{"aging-model", "position-age-evidence", "WR", "28"}));
        assertEquals(ButlerCommandRouter.Route.AGING_MODEL_POSITION_AGE_COVERAGE,
            ButlerCommandRouter.route(new String[]{"aging-model", "position-age-coverage"}));
        assertEquals(ButlerCommandRouter.Route.AGING_MODEL_TEMPORAL_HOLDOUT,
            ButlerCommandRouter.route(new String[]{"aging-model", "temporal-holdout"}));
        assertEquals(ButlerCommandRouter.Route.AGING_MODEL_SMOOTHING_SENSITIVITY,
            ButlerCommandRouter.route(new String[]{"aging-model", "smoothing-sensitivity"}));
        assertEquals(ButlerCommandRouter.Route.AGING_MODEL_TRANSITION_STABILITY,
            ButlerCommandRouter.route(new String[]{"aging-model", "transition-stability"}));
        assertEquals(ButlerCommandRouter.Route.AGING_MODEL_NORMALIZED_STABILITY,
            ButlerCommandRouter.route(new String[]{"aging-model", "normalized-stability"}));
        assertEquals(ButlerCommandRouter.Route.AGING_MODEL_SUPPORT_THRESHOLDS,
            ButlerCommandRouter.route(new String[]{"aging-model", "support-thresholds"}));
        assertEquals(ButlerCommandRouter.Route.AGING_MODEL_AGE_BAND_STABILITY,
            ButlerCommandRouter.route(new String[]{"aging-model", "age-band-stability"}));
        assertEquals(ButlerCommandRouter.Route.AGING_MODEL_AGE_BAND_THRESHOLD_FRONTIER,
            ButlerCommandRouter.route(new String[]{"aging-model", "age-band-threshold-frontier"}));
        assertEquals(ButlerCommandRouter.Route.PRODUCTION_HISTORY,
            ButlerCommandRouter.route(new String[]{"nflverse", "production-history-preview", "2022", "2025"}));
        assertEquals(ButlerCommandRouter.Route.PRODUCTION_HISTORY,
            ButlerCommandRouter.route(new String[]{"nflverse", "production-history-refresh", "2022", "2025"}));
        assertEquals(ButlerCommandRouter.Route.EVIDENCE,
            ButlerCommandRouter.route(new String[]{"league", "evidence-overview", "l1"}));
        assertEquals(ButlerCommandRouter.Route.EVIDENCE,
            ButlerCommandRouter.route(new String[]{"league", "production-context", "l1"}));
        assertEquals(ButlerCommandRouter.Route.COMPOSED,
            ButlerCommandRouter.route(new String[]{"league", "team-profile", "l1"}));
        assertEquals(ButlerCommandRouter.Route.COMPOSED,
            ButlerCommandRouter.route(new String[]{"league", "player-evidence-readiness", "l1"}));
        assertEquals(ButlerCommandRouter.Route.COMPOSED,
            ButlerCommandRouter.route(new String[]{"nflverse", "production-preview", "2025"}));
    }

    @Test
    void preservesEstablishedFallbackSurface() {
        assertEquals(ButlerCommandRouter.Route.COMPOSED, ButlerCommandRouter.route(null));
        assertEquals(ButlerCommandRouter.Route.COMPOSED, ButlerCommandRouter.route(new String[]{"help"}));
        assertEquals(ButlerCommandRouter.Route.COMPOSED,
            ButlerCommandRouter.route(new String[]{"league", "status", "l1"}));
        assertEquals(ButlerCommandRouter.Route.COMPOSED,
            ButlerCommandRouter.route(new String[]{"trade", "compare", "l1", "a", "b"}));
    }
}
