package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Composes Butler's existing league diagnostics into one safe overview without inventing new
 * strategy thresholds. Rankings are included only when franchise coverage is rankable, and
 * movement is included only when at least one rostered player is comparable across the current
 * source window.
 */
public final class LeagueOverviewAnalyzer {
    private final LeagueActionPlanAnalyzer actionPlans;
    private final FranchiseValueRankingAnalyzer franchiseRankings;
    private final LeagueValueMoverAnalyzer movers;

    public LeagueOverviewAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.actionPlans = new LeagueActionPlanAnalyzer(database);
        this.franchiseRankings = new FranchiseValueRankingAnalyzer(database);
        this.movers = new LeagueValueMoverAnalyzer(database);
    }

    public OverviewReport analyze(String leagueId) throws SQLException {
        return build(actionPlans.analyze(leagueId));
    }

    public OverviewReport analyze(String leagueId, String sourceOverride) throws SQLException {
        return build(actionPlans.analyze(leagueId, sourceOverride));
    }

    public OverviewReport analyze(String leagueId, LocalDate minimumAsOfDate) throws SQLException {
        return build(actionPlans.analyze(leagueId, minimumAsOfDate));
    }

    public OverviewReport analyze(String leagueId, String sourceOverride,
                                  LocalDate minimumAsOfDate) throws SQLException {
        return build(actionPlans.analyze(leagueId, sourceOverride, minimumAsOfDate));
    }

    private OverviewReport build(LeagueActionPlanAnalyzer.ActionPlan plan) throws SQLException {
        LeagueHealthAnalyzer.HealthReport health = plan.health();
        if (!health.sourceResolved()) {
            return new OverviewReport(plan, null, null);
        }

        FranchiseValueRankingAnalyzer.RankingReport rankings = null;
        if (health.franchiseRankingsReady()) {
            rankings = health.minimumAsOfDate() == null
                ? franchiseRankings.rank(health.leagueId(), health.source())
                : franchiseRankings.rank(health.leagueId(), health.source(), health.minimumAsOfDate());
        }

        LeagueValueMoverAnalyzer.MoverReport movement = null;
        var readiness = health.movementReadiness();
        if (readiness != null
            && readiness.previousDate() != null
            && readiness.latestDate() != null
            && readiness.comparablePlayers() > 0) {
            movement = movers.analyze(
                health.leagueId(), health.source(), readiness.previousDate(), readiness.latestDate());
        }

        return new OverviewReport(plan, rankings, movement);
    }

    public record OverviewReport(LeagueActionPlanAnalyzer.ActionPlan actionPlan,
                                 FranchiseValueRankingAnalyzer.RankingReport franchiseRankings,
                                 LeagueValueMoverAnalyzer.MoverReport movement) {
        public OverviewReport {
            Objects.requireNonNull(actionPlan, "actionPlan must not be null");
        }

        public LeagueHealthAnalyzer.HealthReport health() {
            return actionPlan.health();
        }

        public boolean requiresAttention() {
            return actionPlan.hasRequiredActions();
        }

        public boolean franchiseRankingsAvailable() {
            return franchiseRankings != null;
        }

        public boolean movementAvailable() {
            return movement != null;
        }

        public List<FranchiseValueRankingAnalyzer.FranchiseValue> topFranchises(int limit) {
            requirePositiveLimit(limit);
            if (franchiseRankings == null) return List.of();
            return franchiseRankings.teams().stream().limit(limit).toList();
        }

        public List<LeagueValueMoverAnalyzer.Mover> topMovers(int limit) {
            requirePositiveLimit(limit);
            if (movement == null) return List.of();
            return movement.movers().stream().limit(limit).toList();
        }

        private static void requirePositiveLimit(int limit) {
            if (limit < 1) throw new IllegalArgumentException("limit must be >= 1");
        }
    }
}
