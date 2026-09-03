package io.butler.bet.cli;

/**
 * Single application entry point for Butler CLI routing. Specialized command handlers remain
 * focused on their own parsing/rendering, but the application no longer relies on a chain of
 * launchers delegating to one another to discover a command.
 */
public final class ButlerCommandRouter {
    private ButlerCommandRouter() {}

    public static void main(String[] args) {
        switch (route(args)) {
            case AGE_CONTEXT -> ButlerAgeLauncher.main(args);
            case AGE_PRODUCTION_CONTEXT -> ButlerAgeProductionContextCli.main(args);
            case PLAYER_EVIDENCE_PROFILE -> ButlerPlayerEvidenceProfileCli.main(args);
            case LONGITUDINAL_EVIDENCE -> ButlerLongitudinalEvidenceCli.main(args);
            case AGING_MODEL_UNIVERSE -> ButlerAgingModelUniverseCli.main(args);
            case AGING_MODEL_SAMPLE_AUDIT -> ButlerAgingModelSampleAuditCli.main(args);
            case AGING_MODEL_SAMPLE_BREADTH -> ButlerAgingModelSampleBreadthCli.main(args);
            case AGING_MODEL_LOCAL_SMOOTHER -> ButlerAgingModelLocalSmootherCli.main(args);
            case AGING_MODEL_TEMPORAL_HOLDOUT -> ButlerAgingModelTemporalHoldoutCli.main(args);
            case AGING_MODEL_SMOOTHING_SENSITIVITY -> ButlerAgingModelSmoothingSensitivityCli.main(args);
            case AGING_MODEL_TRANSITION_STABILITY -> ButlerAgingModelTransitionStabilityCli.main(args);
            case AGING_MODEL_NORMALIZED_STABILITY -> ButlerAgingModelNormalizedStabilityCli.main(args);
            case PRODUCTION_HISTORY -> ButlerProductionHistoryCli.main(args);
            case EVIDENCE -> ButlerEvidenceLauncher.main(args);
            case COMPOSED -> ButlerLauncher.main(args);
        }
    }

    static Route route(String[] args) {
        if (args != null && args.length >= 2) {
            if (equals(args[0], "league") && equals(args[1], "age-context")) return Route.AGE_CONTEXT;
            if (equals(args[0], "league") && equals(args[1], "age-production-context")) return Route.AGE_PRODUCTION_CONTEXT;
            if (equals(args[0], "league") && equals(args[1], "player-evidence-profile")) return Route.PLAYER_EVIDENCE_PROFILE;
            if (equals(args[0], "league") && equals(args[1], "longitudinal-evidence")) return Route.LONGITUDINAL_EVIDENCE;
            if (equals(args[0], "nflverse")
                && (equals(args[1], "aging-model-players-preview") || equals(args[1], "aging-model-players-refresh")
                    || equals(args[1], "aging-model-production-preview") || equals(args[1], "aging-model-production-refresh"))) {
                return Route.AGING_MODEL_UNIVERSE;
            }
            if (equals(args[0], "aging-model") && equals(args[1], "sample-audit")) return Route.AGING_MODEL_SAMPLE_AUDIT;
            if (equals(args[0], "aging-model") && equals(args[1], "sample-breadth")) return Route.AGING_MODEL_SAMPLE_BREADTH;
            if (equals(args[0], "aging-model") && equals(args[1], "local-smoother")) return Route.AGING_MODEL_LOCAL_SMOOTHER;
            if (equals(args[0], "aging-model") && equals(args[1], "temporal-holdout")) return Route.AGING_MODEL_TEMPORAL_HOLDOUT;
            if (equals(args[0], "aging-model") && equals(args[1], "smoothing-sensitivity")) return Route.AGING_MODEL_SMOOTHING_SENSITIVITY;
            if (equals(args[0], "aging-model") && equals(args[1], "transition-stability")) return Route.AGING_MODEL_TRANSITION_STABILITY;
            if (equals(args[0], "aging-model") && equals(args[1], "normalized-stability")) return Route.AGING_MODEL_NORMALIZED_STABILITY;
            if (equals(args[0], "nflverse")
                && (equals(args[1], "production-history-preview") || equals(args[1], "production-history-refresh"))) {
                return Route.PRODUCTION_HISTORY;
            }
            if (equals(args[0], "league")
                && (equals(args[1], "evidence-overview") || equals(args[1], "production-context"))) return Route.EVIDENCE;
            if (equals(args[0], "league")
                && (equals(args[1], "team-profile") || equals(args[1], "player-evidence-readiness"))) return Route.COMPOSED;
            if (equals(args[0], "nflverse")
                && (equals(args[1], "production-preview") || equals(args[1], "production-refresh"))) return Route.COMPOSED;
        }
        return Route.COMPOSED;
    }

    private static boolean equals(String actual, String expected) {
        return actual != null && actual.equalsIgnoreCase(expected);
    }

    enum Route { AGE_CONTEXT, AGE_PRODUCTION_CONTEXT, PLAYER_EVIDENCE_PROFILE,
                 LONGITUDINAL_EVIDENCE, AGING_MODEL_UNIVERSE, AGING_MODEL_SAMPLE_AUDIT,
                 AGING_MODEL_SAMPLE_BREADTH, AGING_MODEL_LOCAL_SMOOTHER, AGING_MODEL_TEMPORAL_HOLDOUT,
                 AGING_MODEL_SMOOTHING_SENSITIVITY, AGING_MODEL_TRANSITION_STABILITY,
                 AGING_MODEL_NORMALIZED_STABILITY, PRODUCTION_HISTORY, EVIDENCE, COMPOSED }
}
