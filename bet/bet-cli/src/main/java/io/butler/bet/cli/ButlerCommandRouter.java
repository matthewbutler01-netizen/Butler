package io.butler.bet.cli;

import java.util.function.Consumer;

/**
 * Single application entry point for Butler CLI routing. Specialized command handlers remain
 * focused on their own parsing/rendering, but the application no longer relies on a chain of
 * launchers delegating to one another to discover a command.
 */
public final class ButlerCommandRouter {
    private static final CommandTarget TRADE_RECOMMENDATION_TARGET = new CommandTarget(
        ButlerTradeRecommendationV5Cli.class,
        ButlerTradeRecommendationV5Cli::main);

    private ButlerCommandRouter() {}

    public static void main(String[] args) {
        switch (route(args)) {
            case AGE_CONTEXT -> ButlerAgeLauncher.main(args);
            case AGE_PRODUCTION_CONTEXT -> ButlerAgeProductionContextCli.main(args);
            case LEAGUE_AGING_MODEL_EVIDENCE -> ButlerLeagueAgingModelEvidenceCli.main(args);
            case LEAGUE_AGE_OUTLOOK -> ButlerLeagueAgeOutlookCli.main(args);
            case LEAGUE_SUPPORTING_EVIDENCE -> ButlerLeagueSupportingEvidenceCli.main(args);
            case LEAGUE_PERFORMANCE_EVIDENCE -> ButlerLeaguePerformanceEvidenceCli.main(args);
            case LEAGUE_SCORING_SETTINGS -> ButlerLeagueScoringSettingsCli.main(args);
            case LEAGUE_SCORING_COVERAGE -> ButlerLeagueScoringCoverageCli.main(args);
            case LEAGUE_PLAYER_SCORE -> ButlerLeaguePlayerScoreCli.main(args);
            case LEAGUE_SCORED_PRODUCTION_EVIDENCE -> ButlerLeagueScoredProductionEvidenceCli.main(args);
            case LEAGUE_TEAM_SCORED_PRODUCTION_EVIDENCE -> ButlerLeagueTeamScoredProductionEvidenceCli.main(args);
            case LEAGUE_TEAM_WEEK_POTENTIAL_LINEUP -> ButlerLeagueTeamWeekPotentialLineupCli.main(args);
            case LEAGUE_TEAM_WEEK_STARTED_LINEUP_EVIDENCE -> ButlerLeagueTeamWeekStartedLineupEvidenceCli.main(args);
            case LEAGUE_TEAM_WEEK_LINEUP_POINTS_GAP_EVIDENCE -> ButlerLeagueTeamWeekLineupPointsGapEvidenceCli.main(args);
            case LEAGUE_TEAM_WEEK_LINEUP_CAPTURE_EVIDENCE -> ButlerLeagueTeamWeekLineupCaptureEvidenceCli.main(args);
            case LEAGUE_TEAM_SEASON_POTENTIAL_LINEUP_EVIDENCE -> ButlerLeagueTeamSeasonPotentialLineupEvidenceCli.main(args);
            case LEAGUE_TEAM_SEASON_LINEUP_POINTS_GAP_EVIDENCE -> ButlerLeagueTeamSeasonLineupPointsGapEvidenceCli.main(args);
            case LEAGUE_TEAM_SEASON_LINEUP_CAPTURE_EVIDENCE -> ButlerLeagueTeamSeasonLineupCaptureEvidenceCli.main(args);
            case LEAGUE_SEASON_POTENTIAL_LINEUP_EVIDENCE -> ButlerLeagueSeasonPotentialLineupEvidenceCli.main(args);
            case LEAGUE_SEASON_LINEUP_POINTS_GAP_EVIDENCE -> ButlerLeagueSeasonLineupPointsGapEvidenceCli.main(args);
            case LEAGUE_ROSTER_STRENGTH -> ButlerLeagueRosterStrengthCli.main(args);
            case LEAGUE_POSITIONAL_PRESSURE -> ButlerLeaguePositionalPressureCli.main(args);
            case LEAGUE_TEAM_POSTURE -> ButlerLeagueTeamPostureCli.main(args);
            case LEAGUE_FUTURE_CAPITAL -> ButlerLeagueFutureCapitalCli.main(args);
            case TRADE_SUPPORTING_EVIDENCE -> ButlerTradeSupportingEvidenceCli.main(args);
            case TRADE_STRATEGIC_CONTEXT -> ButlerTradeStrategicContextCli.main(args);
            case TRADE_COUNTER_VALUE -> ButlerTradeCounterValueCli.main(args);
            case TRADE_COUNTER_STRATEGIC -> ButlerTradeCounterStrategicCli.main(args);
            case TRADE_COUNTER_DECISION -> ButlerTradeCounterDecisionCli.main(args);
            case TRADE_COUNTER_PROPOSAL -> ButlerTradeCounterProposalCli.main(args);
            case TRADE_COUNTER_AUTHORIZATION -> ButlerTradeCounterAuthorizationCli.main(args);
            case TRADE_COUNTER_READINESS -> ButlerTradeCounterReadinessCli.main(args);
            case TRADE_COUNTER_HANDOFF -> ButlerTradeCounterHandoffCli.main(args);
            case TRADE_COUNTER_RECONCILE -> ButlerTradeCounterReconcileCli.main(args);
            case TRADE_COUNTER_FINALIZE -> ButlerTradeCounterFinalizeCli.main(args);
            case TRADE_COUNTER_STATUS -> ButlerTradeCounterStatusCli.main(args);
            case TRADE_COUNTER_MESSAGE_ACK -> ButlerTradeCounterMessageAcknowledgeCli.main(args);
            case TRADE_COUNTER_MESSAGE_FINALIZE -> ButlerTradeCounterMessageFinalizeCli.main(args);
            case TRADE_COUNTER_MESSAGE_STATUS -> ButlerTradeCounterMessageStatusCli.main(args);
            case TRADE_COUNTER_NO_ACTION_ACK -> ButlerTradeCounterNoActionAcknowledgeCli.main(args);
            case TRADE_COUNTER_NO_ACTION_FINALIZE -> ButlerTradeCounterNoActionFinalizeCli.main(args);
            case TRADE_RECOMMENDATION -> TRADE_RECOMMENDATION_TARGET.run(args);
            case PLAYER_EVIDENCE_PROFILE -> ButlerPlayerEvidenceProfileCli.main(args);
            case LONGITUDINAL_EVIDENCE -> ButlerLongitudinalEvidenceCli.main(args);
            case AGING_MODEL_UNIVERSE -> ButlerAgingModelUniverseCli.main(args);
            case AGING_MODEL_SAMPLE_AUDIT -> ButlerAgingModelSampleAuditCli.main(args);
            case AGING_MODEL_SAMPLE_BREADTH -> ButlerAgingModelSampleBreadthCli.main(args);
            case AGING_MODEL_LOCAL_SMOOTHER -> ButlerAgingModelLocalSmootherCli.main(args);
            case AGING_MODEL_PUBLISHED_SMOOTHER -> ButlerAgingModelPublishedSmootherCli.main(args);
            case AGING_MODEL_PUBLICATION_VALIDATION -> ButlerAgingModelPublicationValidationCli.main(args);
            case AGING_MODEL_AGE_OUTLOOK -> ButlerAgingModelAgeOutlookCli.main(args);
            case AGING_MODEL_PUBLISHED_CELL -> ButlerAgingModelPublishedCellCli.main(args);
            case AGING_MODEL_POSITION_AGE_EVIDENCE -> ButlerAgingModelPositionAgeEvidenceCli.main(args);
            case AGING_MODEL_POSITION_AGE_COVERAGE -> ButlerAgingModelPositionAgeCoverageCli.main(args);
            case AGING_MODEL_TEMPORAL_HOLDOUT -> ButlerAgingModelTemporalHoldoutCli.main(args);
            case AGING_MODEL_SMOOTHING_SENSITIVITY -> ButlerAgingModelSmoothingSensitivityCli.main(args);
            case AGING_MODEL_TRANSITION_STABILITY -> ButlerAgingModelTransitionStabilityCli.main(args);
            case AGING_MODEL_NORMALIZED_STABILITY -> ButlerAgingModelNormalizedStabilityCli.main(args);
            case AGING_MODEL_SUPPORT_THRESHOLDS -> ButlerAgingModelSupportThresholdTradeoffCli.main(args);
            case AGING_MODEL_AGE_BAND_STABILITY -> ButlerAgingModelAgeBandStabilityCli.main(args);
            case AGING_MODEL_AGE_BAND_THRESHOLD_FRONTIER -> ButlerAgingModelAgeBandThresholdFrontierCli.main(args);
            case PRODUCTION_HISTORY -> ButlerProductionHistoryCli.main(args);
            case WEEKLY_PRODUCTION -> ButlerWeeklyProductionCli.main(args);
            case EVIDENCE -> ButlerEvidenceLauncher.main(args);
            case COMPOSED -> {
                if (isGlobalHelp(args)) ButlerHelpLauncher.main(args);
                else ButlerLauncher.main(args);
            }
        }
    }

    static Class<?> tradeRecommendationImplementation() {
        return TRADE_RECOMMENDATION_TARGET.implementation();
    }

    static boolean isGlobalHelp(String[] args) {
        return args == null || args.length == 0
            || (args.length == 1 && equals(args[0], "help"));
    }

    static Route route(String[] args) {
        if (args != null && args.length >= 2) {
            if (equals(args[0], "league") && equals(args[1], "age-context")) return Route.AGE_CONTEXT;
            if (equals(args[0], "league") && equals(args[1], "age-production-context")) return Route.AGE_PRODUCTION_CONTEXT;
            if (equals(args[0], "league") && equals(args[1], "aging-model-evidence")) return Route.LEAGUE_AGING_MODEL_EVIDENCE;
            if (equals(args[0], "league") && equals(args[1], "age-outlook")) return Route.LEAGUE_AGE_OUTLOOK;
            if (equals(args[0], "league") && equals(args[1], "supporting-evidence")) return Route.LEAGUE_SUPPORTING_EVIDENCE;
            if (equals(args[0], "league") && equals(args[1], "performance-evidence")) return Route.LEAGUE_PERFORMANCE_EVIDENCE;
            if (equals(args[0], "league") && equals(args[1], "scoring-settings")) return Route.LEAGUE_SCORING_SETTINGS;
            if (equals(args[0], "league") && equals(args[1], "scoring-coverage")) return Route.LEAGUE_SCORING_COVERAGE;
            if (equals(args[0], "league") && equals(args[1], "player-score")) return Route.LEAGUE_PLAYER_SCORE;
            if (equals(args[0], "league") && equals(args[1], "scored-production-evidence")) return Route.LEAGUE_SCORED_PRODUCTION_EVIDENCE;
            if (equals(args[0], "league") && equals(args[1], "team-scored-production-evidence")) return Route.LEAGUE_TEAM_SCORED_PRODUCTION_EVIDENCE;
            if (equals(args[0], "league") && equals(args[1], "team-week-potential-lineup")) return Route.LEAGUE_TEAM_WEEK_POTENTIAL_LINEUP;
            if (equals(args[0], "league") && equals(args[1], "team-week-started-lineup-evidence")) return Route.LEAGUE_TEAM_WEEK_STARTED_LINEUP_EVIDENCE;
            if (equals(args[0], "league") && equals(args[1], "team-week-lineup-points-gap-evidence")) return Route.LEAGUE_TEAM_WEEK_LINEUP_POINTS_GAP_EVIDENCE;
            if (equals(args[0], "league") && equals(args[1], "team-week-lineup-capture-evidence")) return Route.LEAGUE_TEAM_WEEK_LINEUP_CAPTURE_EVIDENCE;
            if (equals(args[0], "league") && equals(args[1], "team-season-potential-lineup-evidence")) return Route.LEAGUE_TEAM_SEASON_POTENTIAL_LINEUP_EVIDENCE;
            if (equals(args[0], "league") && equals(args[1], "team-season-lineup-points-gap-evidence")) return Route.LEAGUE_TEAM_SEASON_LINEUP_POINTS_GAP_EVIDENCE;
            if (equals(args[0], "league") && equals(args[1], "team-season-lineup-capture-evidence")) return Route.LEAGUE_TEAM_SEASON_LINEUP_CAPTURE_EVIDENCE;
            if (equals(args[0], "league") && equals(args[1], "season-potential-lineup-evidence")) return Route.LEAGUE_SEASON_POTENTIAL_LINEUP_EVIDENCE;
            if (equals(args[0], "league") && equals(args[1], "season-lineup-points-gap-evidence")) return Route.LEAGUE_SEASON_LINEUP_POINTS_GAP_EVIDENCE;
            if (equals(args[0], "league") && equals(args[1], "roster-strength")) return Route.LEAGUE_ROSTER_STRENGTH;
            if (equals(args[0], "league") && equals(args[1], "positional-pressure")) return Route.LEAGUE_POSITIONAL_PRESSURE;
            if (equals(args[0], "league") && equals(args[1], "team-posture")) return Route.LEAGUE_TEAM_POSTURE;
            if (equals(args[0], "league") && equals(args[1], "future-capital")) return Route.LEAGUE_FUTURE_CAPITAL;
            if (equals(args[0], "trade") && equals(args[1], "supporting-evidence")) return Route.TRADE_SUPPORTING_EVIDENCE;
            if (equals(args[0], "trade") && equals(args[1], "strategic-context")) return Route.TRADE_STRATEGIC_CONTEXT;
            if (equals(args[0], "trade") && equals(args[1], "counter-value")) return Route.TRADE_COUNTER_VALUE;
            if (equals(args[0], "trade") && equals(args[1], "counter-strategic")) return Route.TRADE_COUNTER_STRATEGIC;
            if (equals(args[0], "trade") && equals(args[1], "counter-decision")) return Route.TRADE_COUNTER_DECISION;
            if (equals(args[0], "trade") && equals(args[1], "counter-proposal")) return Route.TRADE_COUNTER_PROPOSAL;
            if (equals(args[0], "trade") && equals(args[1], "counter-authorize")) return Route.TRADE_COUNTER_AUTHORIZATION;
            if (equals(args[0], "trade") && equals(args[1], "counter-readiness")) return Route.TRADE_COUNTER_READINESS;
            if (equals(args[0], "trade") && equals(args[1], "counter-handoff")) return Route.TRADE_COUNTER_HANDOFF;
            if (equals(args[0], "trade") && equals(args[1], "counter-reconcile")) return Route.TRADE_COUNTER_RECONCILE;
            if (equals(args[0], "trade") && equals(args[1], "counter-finalize")) return Route.TRADE_COUNTER_FINALIZE;
            if (equals(args[0], "trade") && equals(args[1], "counter-status")) return Route.TRADE_COUNTER_STATUS;
            if (equals(args[0], "trade") && equals(args[1], "counter-message-ack")) return Route.TRADE_COUNTER_MESSAGE_ACK;
            if (equals(args[0], "trade") && equals(args[1], "counter-message-finalize")) return Route.TRADE_COUNTER_MESSAGE_FINALIZE;
            if (equals(args[0], "trade") && equals(args[1], "counter-message-status")) return Route.TRADE_COUNTER_MESSAGE_STATUS;
            if (equals(args[0], "trade") && equals(args[1], "counter-no-action-ack")) return Route.TRADE_COUNTER_NO_ACTION_ACK;
            if (equals(args[0], "trade") && equals(args[1], "counter-no-action-finalize")) return Route.TRADE_COUNTER_NO_ACTION_FINALIZE;
            if (equals(args[0], "trade") && equals(args[1], "recommendation")) return Route.TRADE_RECOMMENDATION;
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
            if (equals(args[0], "aging-model") && equals(args[1], "published-smoother")) return Route.AGING_MODEL_PUBLISHED_SMOOTHER;
            if (equals(args[0], "aging-model") && equals(args[1], "publication-validation")) return Route.AGING_MODEL_PUBLICATION_VALIDATION;
            if (equals(args[0], "aging-model") && equals(args[1], "age-outlook")) return Route.AGING_MODEL_AGE_OUTLOOK;
            if (equals(args[0], "aging-model") && equals(args[1], "published-cell")) return Route.AGING_MODEL_PUBLISHED_CELL;
            if (equals(args[0], "aging-model") && equals(args[1], "position-age-evidence")) return Route.AGING_MODEL_POSITION_AGE_EVIDENCE;
            if (equals(args[0], "aging-model") && equals(args[1], "position-age-coverage")) return Route.AGING_MODEL_POSITION_AGE_COVERAGE;
            if (equals(args[0], "aging-model") && equals(args[1], "temporal-holdout")) return Route.AGING_MODEL_TEMPORAL_HOLDOUT;
            if (equals(args[0], "aging-model") && equals(args[1], "smoothing-sensitivity")) return Route.AGING_MODEL_SMOOTHING_SENSITIVITY;
            if (equals(args[0], "aging-model") && equals(args[1], "transition-stability")) return Route.AGING_MODEL_TRANSITION_STABILITY;
            if (equals(args[0], "aging-model") && equals(args[1], "normalized-stability")) return Route.AGING_MODEL_NORMALIZED_STABILITY;
            if (equals(args[0], "aging-model") && equals(args[1], "support-thresholds")) return Route.AGING_MODEL_SUPPORT_THRESHOLDS;
            if (equals(args[0], "aging-model") && equals(args[1], "age-band-stability")) return Route.AGING_MODEL_AGE_BAND_STABILITY;
            if (equals(args[0], "aging-model") && equals(args[1], "age-band-threshold-frontier")) return Route.AGING_MODEL_AGE_BAND_THRESHOLD_FRONTIER;
            if (equals(args[0], "nflverse")
                && (equals(args[1], "production-history-preview") || equals(args[1], "production-history-refresh"))) return Route.PRODUCTION_HISTORY;
            if (equals(args[0], "nflverse")
                && (equals(args[1], "weekly-production-preview") || equals(args[1], "weekly-production-refresh"))) return Route.WEEKLY_PRODUCTION;
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

    private record CommandTarget(Class<?> implementation, Consumer<String[]> runner) {
        void run(String[] args) {
            runner.accept(args);
        }
    }

    enum Route { AGE_CONTEXT, AGE_PRODUCTION_CONTEXT, LEAGUE_AGING_MODEL_EVIDENCE, LEAGUE_AGE_OUTLOOK,
                 LEAGUE_SUPPORTING_EVIDENCE, LEAGUE_PERFORMANCE_EVIDENCE, LEAGUE_SCORING_SETTINGS,
                 LEAGUE_SCORING_COVERAGE, LEAGUE_PLAYER_SCORE, LEAGUE_SCORED_PRODUCTION_EVIDENCE,
                 LEAGUE_TEAM_SCORED_PRODUCTION_EVIDENCE, LEAGUE_TEAM_WEEK_POTENTIAL_LINEUP,
                 LEAGUE_TEAM_WEEK_STARTED_LINEUP_EVIDENCE, LEAGUE_TEAM_WEEK_LINEUP_POINTS_GAP_EVIDENCE,
                 LEAGUE_TEAM_WEEK_LINEUP_CAPTURE_EVIDENCE,
                 LEAGUE_TEAM_SEASON_POTENTIAL_LINEUP_EVIDENCE, LEAGUE_TEAM_SEASON_LINEUP_POINTS_GAP_EVIDENCE,
                 LEAGUE_TEAM_SEASON_LINEUP_CAPTURE_EVIDENCE,
                 LEAGUE_SEASON_POTENTIAL_LINEUP_EVIDENCE, LEAGUE_SEASON_LINEUP_POINTS_GAP_EVIDENCE,
                 LEAGUE_ROSTER_STRENGTH, LEAGUE_POSITIONAL_PRESSURE,
                 LEAGUE_TEAM_POSTURE, LEAGUE_FUTURE_CAPITAL,
                 TRADE_SUPPORTING_EVIDENCE, TRADE_STRATEGIC_CONTEXT, TRADE_COUNTER_VALUE, TRADE_COUNTER_STRATEGIC,
                 TRADE_COUNTER_DECISION, TRADE_COUNTER_PROPOSAL, TRADE_COUNTER_AUTHORIZATION, TRADE_COUNTER_READINESS,
                 TRADE_COUNTER_HANDOFF, TRADE_COUNTER_RECONCILE, TRADE_COUNTER_FINALIZE, TRADE_COUNTER_STATUS,
                 TRADE_COUNTER_MESSAGE_ACK, TRADE_COUNTER_MESSAGE_FINALIZE, TRADE_COUNTER_MESSAGE_STATUS,
                 TRADE_COUNTER_NO_ACTION_ACK, TRADE_COUNTER_NO_ACTION_FINALIZE, TRADE_RECOMMENDATION,
                 PLAYER_EVIDENCE_PROFILE, LONGITUDINAL_EVIDENCE, AGING_MODEL_UNIVERSE, AGING_MODEL_SAMPLE_AUDIT,
                 AGING_MODEL_SAMPLE_BREADTH, AGING_MODEL_LOCAL_SMOOTHER, AGING_MODEL_PUBLISHED_SMOOTHER,
                 AGING_MODEL_PUBLICATION_VALIDATION, AGING_MODEL_AGE_OUTLOOK, AGING_MODEL_PUBLISHED_CELL,
                 AGING_MODEL_POSITION_AGE_EVIDENCE, AGING_MODEL_POSITION_AGE_COVERAGE,
                 AGING_MODEL_TEMPORAL_HOLDOUT, AGING_MODEL_SMOOTHING_SENSITIVITY,
                 AGING_MODEL_TRANSITION_STABILITY, AGING_MODEL_NORMALIZED_STABILITY,
                 AGING_MODEL_SUPPORT_THRESHOLDS, AGING_MODEL_AGE_BAND_STABILITY,
                 AGING_MODEL_AGE_BAND_THRESHOLD_FRONTIER, PRODUCTION_HISTORY, WEEKLY_PRODUCTION, EVIDENCE, COMPOSED }
}
