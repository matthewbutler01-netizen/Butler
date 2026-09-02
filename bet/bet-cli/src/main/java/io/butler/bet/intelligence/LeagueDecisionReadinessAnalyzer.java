package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Classifies how much decision context Butler can safely support from current league evidence.
 * This deliberately adds no strategy thresholds: it only composes existing health and movement
 * readiness into a decision-readiness state.
 */
public final class LeagueDecisionReadinessAnalyzer {
    private final LeagueActionPlanAnalyzer actionPlans;

    public LeagueDecisionReadinessAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.actionPlans = new LeagueActionPlanAnalyzer(database);
    }

    public DecisionReadinessReport analyze(String leagueId) throws SQLException {
        return build(actionPlans.analyze(leagueId));
    }

    public DecisionReadinessReport analyze(String leagueId, String sourceOverride) throws SQLException {
        return build(actionPlans.analyze(leagueId, sourceOverride));
    }

    public DecisionReadinessReport analyze(String leagueId, LocalDate minimumAsOfDate) throws SQLException {
        return build(actionPlans.analyze(leagueId, minimumAsOfDate));
    }

    public DecisionReadinessReport analyze(String leagueId, String sourceOverride,
                                           LocalDate minimumAsOfDate) throws SQLException {
        return build(actionPlans.analyze(leagueId, sourceOverride, minimumAsOfDate));
    }

    private static DecisionReadinessReport build(LeagueActionPlanAnalyzer.ActionPlan actionPlan) {
        LeagueHealthAnalyzer.HealthReport health = actionPlan.health();
        DecisionReadiness readiness;
        if (!health.coreAnalysisReady()) {
            readiness = DecisionReadiness.BLOCKED;
        } else if (health.movementReady()) {
            readiness = DecisionReadiness.TREND_READY;
        } else {
            readiness = DecisionReadiness.CURRENT_READY;
        }
        return new DecisionReadinessReport(readiness, actionPlan);
    }

    public enum DecisionReadiness {
        BLOCKED,
        CURRENT_READY,
        TREND_READY
    }

    public record DecisionReadinessReport(DecisionReadiness readiness,
                                          LeagueActionPlanAnalyzer.ActionPlan actionPlan) {
        public DecisionReadinessReport {
            Objects.requireNonNull(readiness, "readiness must not be null");
            Objects.requireNonNull(actionPlan, "actionPlan must not be null");
        }

        public LeagueHealthAnalyzer.HealthReport health() {
            return actionPlan.health();
        }

        public boolean currentValueDecisionsReady() {
            return readiness != DecisionReadiness.BLOCKED;
        }

        public boolean trendAwareDecisionsReady() {
            return readiness == DecisionReadiness.TREND_READY;
        }

        public boolean franchiseRankingsReady() {
            return health().franchiseRankingsReady();
        }

        public List<LeagueActionPlanAnalyzer.Action> nextActions() {
            return actionPlan.actions();
        }
    }
}
