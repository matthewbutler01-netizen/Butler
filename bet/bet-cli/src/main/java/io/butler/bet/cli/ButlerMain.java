package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.LeagueActionPlanAnalyzer;
import io.butler.bet.intelligence.LeagueDecisionReadinessAnalyzer;
import io.butler.bet.intelligence.LeagueOverviewAnalyzer;
import io.butler.bet.intelligence.LeagueTeamContextAnalyzer;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Stable CLI entry point. Delegates the established command surface to ButlerApp while exposing
 * newer composed workflows without destabilizing the older command handlers.
 */
public final class ButlerMain {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerMain() {}

    public static void main(String[] args) {
        if (args == null || args.length == 0) {
            ButlerApp.main(args == null ? new String[0] : args);
            printIntelligenceUsage();
            return;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("help")) {
            ButlerApp.main(args);
            printIntelligenceUsage();
            return;
        }
        if (isSupportedLeagueStatus(args)) {
            ButlerApp.main(args);
            try {
                printLeagueActions(analyzeActions(args));
            } catch (SQLException e) {
                failDatabase("building league action plan", e);
            } catch (IllegalArgumentException e) {
                failArgument(e);
            }
            return;
        }
        if (isLeagueOverviewCommand(args)) {
            if (!isSupportedLeagueOverview(args)) {
                printIntelligenceUsage();
                return;
            }
            try {
                printLeagueOverview(analyzeOverview(args));
            } catch (SQLException e) {
                failDatabase("building league overview", e);
            } catch (IllegalArgumentException e) {
                failArgument(e);
            }
            return;
        }
        if (isLeagueTeamContextCommand(args)) {
            if (!isSupportedLeagueTeamContext(args)) {
                printIntelligenceUsage();
                return;
            }
            try {
                printLeagueTeamContext(analyzeTeamContext(args));
            } catch (SQLException e) {
                failDatabase("building league team context", e);
            } catch (IllegalArgumentException e) {
                failArgument(e);
            }
            return;
        }
        if (isLeagueDecisionReadinessCommand(args)) {
            if (!isSupportedLeagueDecisionReadiness(args)) {
                printIntelligenceUsage();
                return;
            }
            try {
                printLeagueDecisionReadiness(analyzeDecisionReadiness(args));
            } catch (SQLException e) {
                failDatabase("building league decision readiness", e);
            } catch (IllegalArgumentException e) {
                failArgument(e);
            }
            return;
        }

        ButlerApp.main(args);
    }

    static boolean isSupportedLeagueStatus(String[] args) {
        return isSupportedLeagueCommand(args, "status");
    }

    static boolean isSupportedLeagueOverview(String[] args) {
        return isSupportedLeagueCommand(args, "overview");
    }

    static boolean isSupportedLeagueTeamContext(String[] args) {
        return isSupportedLeagueCommand(args, "team-context");
    }

    static boolean isSupportedLeagueDecisionReadiness(String[] args) {
        return isSupportedLeagueCommand(args, "decision-readiness");
    }

    private static boolean isLeagueOverviewCommand(String[] args) {
        return isLeagueCommand(args, "overview");
    }

    private static boolean isLeagueTeamContextCommand(String[] args) {
        return isLeagueCommand(args, "team-context");
    }

    private static boolean isLeagueDecisionReadinessCommand(String[] args) {
        return isLeagueCommand(args, "decision-readiness");
    }

    private static boolean isLeagueCommand(String[] args, String command) {
        return args != null && args.length >= 2
            && args[0].equalsIgnoreCase("league")
            && args[1].equalsIgnoreCase(command);
    }

    private static boolean isSupportedLeagueCommand(String[] args, String command) {
        if (args == null || args.length < 3) return false;
        if (!args[0].equalsIgnoreCase("league") || !args[1].equalsIgnoreCase(command)) return false;
        if (args.length == 3 || args.length == 4) return true;
        if (args.length == 5) return args[3].equalsIgnoreCase("--minimum-as-of");
        return args.length == 6 && args[4].equalsIgnoreCase("--minimum-as-of");
    }

    private static List<LeagueActionPlanAnalyzer.Action> analyzeActions(String[] args) throws SQLException {
        LeagueActionPlanAnalyzer analyzer = new LeagueActionPlanAnalyzer(initializedDatabase());
        return analyzeActionPlan(analyzer, args).actions();
    }

    private static LeagueActionPlanAnalyzer.ActionPlan analyzeActionPlan(
        LeagueActionPlanAnalyzer analyzer, String[] args) throws SQLException {
        if (args.length == 3) return analyzer.analyze(args[2]);
        if (args.length == 4) return analyzer.analyze(args[2], args[3]);
        if (args.length == 5) return analyzer.analyze(args[2], parseMinimumAsOfDate(args[4]));
        return analyzer.analyze(args[2], args[3], parseMinimumAsOfDate(args[5]));
    }

    private static LeagueOverviewAnalyzer.OverviewReport analyzeOverview(String[] args) throws SQLException {
        LeagueOverviewAnalyzer analyzer = new LeagueOverviewAnalyzer(initializedDatabase());
        if (args.length == 3) return analyzer.analyze(args[2]);
        if (args.length == 4) return analyzer.analyze(args[2], args[3]);
        if (args.length == 5) return analyzer.analyze(args[2], parseMinimumAsOfDate(args[4]));
        return analyzer.analyze(args[2], args[3], parseMinimumAsOfDate(args[5]));
    }

    private static LeagueTeamContextAnalyzer.TeamContextReport analyzeTeamContext(String[] args) throws SQLException {
        LeagueTeamContextAnalyzer analyzer = new LeagueTeamContextAnalyzer(initializedDatabase());
        if (args.length == 3) return analyzer.analyze(args[2]);
        if (args.length == 4) return analyzer.analyze(args[2], args[3]);
        if (args.length == 5) return analyzer.analyze(args[2], parseMinimumAsOfDate(args[4]));
        return analyzer.analyze(args[2], args[3], parseMinimumAsOfDate(args[5]));
    }

    private static LeagueDecisionReadinessAnalyzer.DecisionReadinessReport analyzeDecisionReadiness(String[] args)
        throws SQLException {
        LeagueDecisionReadinessAnalyzer analyzer = new LeagueDecisionReadinessAnalyzer(initializedDatabase());
        if (args.length == 3) return analyzer.analyze(args[2]);
        if (args.length == 4) return analyzer.analyze(args[2], args[3]);
        if (args.length == 5) return analyzer.analyze(args[2], parseMinimumAsOfDate(args[4]));
        return analyzer.analyze(args[2], args[3], parseMinimumAsOfDate(args[5]));
    }

    static void printLeagueOverview(LeagueOverviewAnalyzer.OverviewReport overview) {
        if (overview == null) throw new IllegalArgumentException("overview must not be null");
        var health = overview.health();

        System.out.println("League overview");
        System.out.println("League: " + health.leagueName() + "  [" + health.leagueId() + "]");
        System.out.println("Status: " + health.status() + "  core-ready=" + health.coreAnalysisReady());
        System.out.println("Source: " + (health.sourceResolved() ? health.source() : "UNRESOLVED"));
        if (health.minimumAsOfDate() != null) {
            System.out.println("Minimum as-of: " + health.minimumAsOfDate());
        }
        System.out.println("Requires attention: " + overview.requiresAttention());

        if (overview.franchiseRankingsAvailable()) {
            System.out.println("Franchise leaders:");
            for (var team : overview.topFranchises(3)) {
                System.out.printf("  %d. %s  total=%.2f  players=%.2f  picks=%.2f  [%s]%n",
                    team.rank(), team.teamName(), team.totalAssetValue(), team.playerValue(),
                    team.draftPickValue(), team.teamId());
            }
        } else {
            System.out.println("Franchise rankings: unavailable until current asset coverage is READY.");
        }

        if (overview.movementAvailable()) {
            var movement = overview.movement();
            System.out.printf("Top value movers: %s -> %s  coverage=%d/%d (%.1f%%)%n",
                movement.previousDate(), movement.latestDate(), movement.comparablePlayers(),
                movement.totalPlayers(), movement.coveragePercent());
            for (var mover : overview.topMovers(5)) {
                System.out.printf("  %+.2f  %s  %s%s  fantasy-team=%s  %.2f -> %.2f  [%s]%n",
                    mover.delta(), mover.position(), mover.playerName(), formatTeam(mover.nflTeam()),
                    mover.teamName(), mover.previousValue(), mover.latestValue(), mover.playerId());
            }
        } else {
            System.out.println("Value movement: unavailable until comparable provider snapshots exist.");
        }

        printLeagueActions(overview.actionPlan().actions());
    }

    static void printLeagueTeamContext(LeagueTeamContextAnalyzer.TeamContextReport context) {
        if (context == null) throw new IllegalArgumentException("context must not be null");
        var health = context.health();

        System.out.println("League team context");
        System.out.println("League: " + health.leagueName() + "  [" + health.leagueId() + "]");
        System.out.println("Status: " + health.status() + "  core-ready=" + health.coreAnalysisReady());
        System.out.println("Source: " + (health.sourceResolved() ? health.source() : "UNRESOLVED"));
        if (health.minimumAsOfDate() != null) {
            System.out.println("Minimum as-of: " + health.minimumAsOfDate());
        }
        System.out.println("Franchise ranks available: " + context.ranksAvailable());
        if (context.movementAvailable()) {
            System.out.println("Movement window: " + context.movementPreviousDate() + " -> " + context.movementLatestDate());
        } else {
            System.out.println("Movement window: unavailable");
        }

        if (context.teams().isEmpty()) {
            System.out.println("No team context available until a value source can be resolved.");
        } else {
            for (var team : context.teams()) {
                String rank = team.rankAvailable() ? Integer.toString(team.rank()) : "-";
                String movement = team.movementAvailable() ? String.format("%+.2f", team.playerValueDelta()) : "unavailable";
                System.out.printf("%s  rank=%s  total=%.2f  players=%.2f  picks=%.2f  coverage=%d/%d (%.1f%%)  movement=%s  movement-coverage=%d/%d (%.1f%%)  risers=%d  fallers=%d  unchanged=%d  [%s]%n",
                    team.teamName(), rank, team.totalAssetValue(), team.playerValue(), team.draftPickValue(),
                    team.valuedAssets(), team.totalAssets(), team.coveragePercent(), movement,
                    team.playersWithMovementHistory(), team.rosterSize(), team.movementCoveragePercent(),
                    team.risers(), team.fallers(), team.unchanged(), team.teamId());
            }
        }

        printLeagueActions(context.actionPlan().actions());
    }

    static void printLeagueDecisionReadiness(LeagueDecisionReadinessAnalyzer.DecisionReadinessReport report) {
        if (report == null) throw new IllegalArgumentException("report must not be null");
        var health = report.health();

        System.out.println("League decision readiness");
        System.out.println("League: " + health.leagueName() + "  [" + health.leagueId() + "]");
        System.out.println("Readiness: " + report.readiness());
        System.out.println("Source: " + (health.sourceResolved() ? health.source() : "UNRESOLVED"));
        if (health.minimumAsOfDate() != null) {
            System.out.println("Minimum as-of: " + health.minimumAsOfDate());
        }
        System.out.println("Current-value decisions ready: " + report.currentValueDecisionsReady());
        System.out.println("Trend-aware decisions ready: " + report.trendAwareDecisionsReady());
        System.out.println("Franchise rankings ready: " + report.franchiseRankingsReady());
        printLeagueActions(report.nextActions());
    }

    static void printLeagueActions(List<LeagueActionPlanAnalyzer.Action> actions) {
        if (actions == null) throw new IllegalArgumentException("actions must not be null");
        if (actions.isEmpty()) {
            System.out.println("Next actions: none.");
            return;
        }

        System.out.println("Next actions:");
        for (var action : actions) {
            String requirement = action.requiredForCoreAnalysis() ? "REQUIRED" : "OPTIONAL";
            System.out.printf("  %d. %s  %s  %s%n",
                action.priority(), requirement, action.kind(), action.description());
            if (action.hasCommand()) {
                System.out.println("     " + action.command());
            }
        }
    }

    static void printIntelligenceUsage() {
        System.out.println("League intelligence:");
        System.out.println("  butler league overview <league-id> [source] [--minimum-as-of YYYY-MM-DD]");
        System.out.println("  butler league team-context <league-id> [source] [--minimum-as-of YYYY-MM-DD]");
        System.out.println("  butler league decision-readiness <league-id> [source] [--minimum-as-of YYYY-MM-DD]");
    }

    static void printOverviewUsage() {
        printIntelligenceUsage();
    }

    private static Database initializedDatabase() throws SQLException {
        Database database = new Database(DATABASE_PATH);
        database.initialize();
        return database;
    }

    private static LocalDate parseMinimumAsOfDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("minimum-as-of must use YYYY-MM-DD: " + value);
        }
    }

    private static String formatTeam(String nflTeam) {
        return nflTeam == null ? "" : "  " + nflTeam;
    }

    private static void failDatabase(String operation, SQLException e) {
        System.err.println("Database error while " + operation + ": " + e.getMessage());
        System.exit(1);
    }

    private static void failArgument(IllegalArgumentException e) {
        System.err.println("Error: " + e.getMessage());
        System.exit(2);
    }
}
