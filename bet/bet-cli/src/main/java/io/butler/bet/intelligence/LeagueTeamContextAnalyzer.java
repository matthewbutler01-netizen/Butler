package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds a neutral team-by-team league context board from persisted portfolio value, safe
 * franchise rank, and recent player-value movement. Partial portfolio values remain visible with
 * explicit coverage, but no rank is assigned until the league is fully rankable.
 */
public final class LeagueTeamContextAnalyzer {
    private final LeagueActionPlanAnalyzer actionPlans;
    private final TeamAssetPortfolioAnalyzer portfolios;
    private final FranchiseValueRankingAnalyzer rankings;
    private final TeamValueMovementAnalyzer movement;

    public LeagueTeamContextAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.actionPlans = new LeagueActionPlanAnalyzer(database);
        this.portfolios = new TeamAssetPortfolioAnalyzer(database);
        this.rankings = new FranchiseValueRankingAnalyzer(database);
        this.movement = new TeamValueMovementAnalyzer(database);
    }

    public TeamContextReport analyze(String leagueId) throws SQLException {
        return build(actionPlans.analyze(leagueId));
    }

    public TeamContextReport analyze(String leagueId, String sourceOverride) throws SQLException {
        return build(actionPlans.analyze(leagueId, sourceOverride));
    }

    public TeamContextReport analyze(String leagueId, LocalDate minimumAsOfDate) throws SQLException {
        return build(actionPlans.analyze(leagueId, minimumAsOfDate));
    }

    public TeamContextReport analyze(String leagueId, String sourceOverride,
                                     LocalDate minimumAsOfDate) throws SQLException {
        return build(actionPlans.analyze(leagueId, sourceOverride, minimumAsOfDate));
    }

    private TeamContextReport build(LeagueActionPlanAnalyzer.ActionPlan plan) throws SQLException {
        LeagueHealthAnalyzer.HealthReport health = plan.health();
        if (!health.sourceResolved()) {
            return new TeamContextReport(plan, List.of());
        }

        TeamAssetPortfolioAnalyzer.PortfolioReport portfolio = portfolios.analyze(
            health.leagueId(), health.source());

        Map<String, Integer> rankByTeam = new HashMap<>();
        if (health.franchiseRankingsReady()) {
            FranchiseValueRankingAnalyzer.RankingReport ranking = health.minimumAsOfDate() == null
                ? rankings.rank(health.leagueId(), health.source())
                : rankings.rank(health.leagueId(), health.source(), health.minimumAsOfDate());
            for (var team : ranking.teams()) {
                rankByTeam.put(team.teamId(), team.rank());
            }
        }

        Map<String, TeamValueMovementAnalyzer.TeamMovement> movementByTeam = new HashMap<>();
        var movementReadiness = health.movementReadiness();
        LocalDate previousDate = null;
        LocalDate latestDate = null;
        if (movementReadiness != null
            && movementReadiness.previousDate() != null
            && movementReadiness.latestDate() != null
            && movementReadiness.comparablePlayers() > 0) {
            TeamValueMovementAnalyzer.MovementReport movementReport = movement.analyze(
                health.leagueId(), health.source());
            previousDate = movementReport.previousDate();
            latestDate = movementReport.latestDate();
            for (var team : movementReport.teams()) {
                movementByTeam.put(team.teamId(), team);
            }
        }

        List<TeamContext> teams = new ArrayList<>();
        for (var team : portfolio.teams()) {
            var teamMovement = movementByTeam.get(team.teamId());
            teams.add(new TeamContext(
                rankByTeam.get(team.teamId()),
                team.teamId(),
                team.teamName(),
                team.playerValue(),
                team.draftPickValue(),
                team.totalAssetValue(),
                team.valuedAssets(),
                team.totalAssets(),
                team.coveragePercent(),
                teamMovement == null ? null : teamMovement.delta(),
                teamMovement == null ? 0 : teamMovement.playersWithHistory(),
                teamMovement == null ? team.totalPlayers() : teamMovement.rosterSize(),
                teamMovement == null ? 0 : teamMovement.risers(),
                teamMovement == null ? 0 : teamMovement.fallers(),
                teamMovement == null ? 0 : teamMovement.unchanged()));
        }

        if (health.franchiseRankingsReady()) {
            teams.sort(Comparator.comparingInt(team -> team.rank() == null ? Integer.MAX_VALUE : team.rank()));
        } else {
            teams.sort(Comparator.comparing(TeamContext::teamName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(TeamContext::teamId));
        }

        return new TeamContextReport(plan, previousDate, latestDate, List.copyOf(teams));
    }

    public record TeamContextReport(LeagueActionPlanAnalyzer.ActionPlan actionPlan,
                                    LocalDate movementPreviousDate,
                                    LocalDate movementLatestDate,
                                    List<TeamContext> teams) {
        public TeamContextReport(LeagueActionPlanAnalyzer.ActionPlan actionPlan, List<TeamContext> teams) {
            this(actionPlan, null, null, teams);
        }

        public TeamContextReport {
            Objects.requireNonNull(actionPlan, "actionPlan must not be null");
            teams = List.copyOf(Objects.requireNonNull(teams, "teams must not be null"));
        }

        public LeagueHealthAnalyzer.HealthReport health() {
            return actionPlan.health();
        }

        public boolean ranksAvailable() {
            return health().franchiseRankingsReady();
        }

        public boolean movementAvailable() {
            return movementPreviousDate != null && movementLatestDate != null;
        }
    }

    public record TeamContext(Integer rank,
                              String teamId,
                              String teamName,
                              double playerValue,
                              double draftPickValue,
                              double totalAssetValue,
                              int valuedAssets,
                              int totalAssets,
                              double coveragePercent,
                              Double playerValueDelta,
                              int playersWithMovementHistory,
                              int rosterSize,
                              int risers,
                              int fallers,
                              int unchanged) {
        public boolean rankAvailable() {
            return rank != null;
        }

        public boolean movementAvailable() {
            return playerValueDelta != null;
        }

        public double movementCoveragePercent() {
            return rosterSize == 0 ? 0.0 : playersWithMovementHistory * 100.0 / rosterSize;
        }
    }
}
