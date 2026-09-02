package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Converts league health diagnostics into an ordered, deterministic next-step plan.
 * Required actions only address blockers to current/core analysis; movement-history
 * actions remain optional when current franchise analysis is already ready.
 */
public final class LeagueActionPlanAnalyzer {
    private final LeagueHealthAnalyzer healthAnalyzer;

    public LeagueActionPlanAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.healthAnalyzer = new LeagueHealthAnalyzer(database);
    }

    public ActionPlan analyze(String leagueId) throws SQLException {
        return build(healthAnalyzer.analyze(leagueId));
    }

    public ActionPlan analyze(String leagueId, String sourceOverride) throws SQLException {
        return build(healthAnalyzer.analyze(leagueId, sourceOverride));
    }

    public ActionPlan analyze(String leagueId, LocalDate minimumAsOfDate) throws SQLException {
        return build(healthAnalyzer.analyze(leagueId, minimumAsOfDate));
    }

    public ActionPlan analyze(String leagueId, String sourceOverride,
                              LocalDate minimumAsOfDate) throws SQLException {
        return build(healthAnalyzer.analyze(leagueId, sourceOverride, minimumAsOfDate));
    }

    private static ActionPlan build(LeagueHealthAnalyzer.HealthReport health) {
        var actions = new ArrayList<Action>();
        int priority = 1;

        switch (health.status()) {
            case SOURCE_REQUIRED -> {
                if (hasText(health.sleeperLeagueId())) {
                    actions.add(new Action(priority++, ActionKind.RESYNC_LEAGUE, true,
                        "Refresh Sleeper league metadata so Butler can re-detect the league value format.",
                        "butler sleeper sync-all " + health.sleeperLeagueId()));
                }
                actions.add(new Action(priority++, ActionKind.PROVIDE_VALUE_SOURCE, true,
                    "If the league format remains unavailable or UNKNOWN, rerun the health workflow with an explicit value source instead of guessing.",
                    null));
            }
            case EMPTY -> {
                if (hasText(health.sleeperLeagueId())) {
                    actions.add(new Action(priority++, ActionKind.RESYNC_LEAGUE, true,
                        "Synchronize the Sleeper league because no current franchise assets are available for analysis.",
                        "butler sleeper sync-all " + health.sleeperLeagueId()));
                } else {
                    actions.add(new Action(priority++, ActionKind.POPULATE_LEAGUE, true,
                        "Populate teams, rosters, and draft-pick ownership before running franchise analysis.",
                        null));
                }
            }
            case VALUES_UNAVAILABLE, PARTIAL, STALE -> {
                if (hasText(health.sleeperLeagueId())) {
                    actions.add(new Action(priority++, ActionKind.REFRESH_LEAGUE_VALUES, true,
                        valueRefreshDescription(health),
                        "butler sleeper sync-all " + health.sleeperLeagueId()));
                } else {
                    actions.add(new Action(priority++, ActionKind.REFRESH_PLAYER_VALUES, true,
                        "Refresh persisted player values for the league's selected source.",
                        "butler player value-refresh dynastyprocess"));
                    actions.add(new Action(priority++, ActionKind.REFRESH_DRAFT_PICK_VALUES, true,
                        "Refresh persisted draft-pick values for this league.",
                        "butler league draft-pick-values " + health.leagueId() + " dynastyprocess"));
                }
            }
            case READY -> {
                if (!health.movementReady()) {
                    actions.add(new Action(priority++, ActionKind.CAPTURE_FUTURE_VALUE_SNAPSHOT, false,
                        movementDescription(health),
                        "butler player value-refresh dynastyprocess"));
                }
            }
        }

        return new ActionPlan(health, List.copyOf(actions));
    }

    private static String valueRefreshDescription(LeagueHealthAnalyzer.HealthReport health) {
        return switch (health.status()) {
            case VALUES_UNAVAILABLE -> "Refresh league data and provider values because current franchise assets have no usable values.";
            case PARTIAL -> "Refresh league data and provider values because franchise-value coverage is incomplete.";
            case STALE -> "Refresh league data and provider values because one or more assets fail the requested minimum as-of date.";
            default -> throw new IllegalArgumentException("status does not require a value refresh: " + health.status());
        };
    }

    private static String movementDescription(LeagueHealthAnalyzer.HealthReport health) {
        if (health.movementReadiness() == null) {
            return "Capture a later provider value snapshot when available to enable movement analysis.";
        }
        return switch (health.movementReadiness().readiness()) {
            case UNAVAILABLE -> "Capture a later provider value snapshot when available; movement analysis needs two source snapshots.";
            case BLOCKED -> "Capture a later provider value snapshot after rostered-player coverage improves; no players are currently comparable across snapshots.";
            case PARTIAL -> "Capture later provider value snapshots to improve movement coverage across rostered players.";
            case READY -> "Movement analysis is already ready.";
        };
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public enum ActionKind {
        RESYNC_LEAGUE,
        PROVIDE_VALUE_SOURCE,
        POPULATE_LEAGUE,
        REFRESH_LEAGUE_VALUES,
        REFRESH_PLAYER_VALUES,
        REFRESH_DRAFT_PICK_VALUES,
        CAPTURE_FUTURE_VALUE_SNAPSHOT
    }

    public record Action(int priority, ActionKind kind, boolean requiredForCoreAnalysis,
                         String description, String command) {
        public Action {
            if (priority < 1) throw new IllegalArgumentException("priority must be >= 1");
            Objects.requireNonNull(kind, "kind must not be null");
            if (description == null || description.isBlank()) {
                throw new IllegalArgumentException("description must not be blank");
            }
        }

        public boolean hasCommand() {
            return command != null && !command.isBlank();
        }
    }

    public record ActionPlan(LeagueHealthAnalyzer.HealthReport health, List<Action> actions) {
        public ActionPlan {
            Objects.requireNonNull(health, "health must not be null");
            actions = List.copyOf(Objects.requireNonNull(actions, "actions must not be null"));
        }

        public boolean coreAnalysisReady() {
            return health.coreAnalysisReady();
        }

        public boolean hasRequiredActions() {
            return actions.stream().anyMatch(Action::requiredForCoreAnalysis);
        }

        public List<Action> requiredActions() {
            return actions.stream().filter(Action::requiredForCoreAnalysis).toList();
        }
    }
}
