package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.LeagueActionPlanAnalyzer;
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
            printWorkflowUsage();
            return;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("help")) {
            ButlerApp.main(args);
            printWorkflowUsage();
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
                printOverviewUsage();
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
        if (isLeagueFranchisesCommand(args)) {
            if (!isSupportedLeagueFranchises(args)) {
                printFranchisesUsage();
                return;
            }
            try {
                printLeagueFranchises(analyzeFranchises(args));
            } catch (SQLException e) {
                failDatabase("building league franchise context", e);
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

    static boolean isSupportedLeagueFranchises(String[] args) {
        return isSupportedLeagueCommand(args, "franchises");
    }

    private static boolean isLeagueOverviewCommand(String[] args) {
        return isLeagueCommand(args, "overview");
    }

    private static boolean isLeagueFranchisesCommand(String[] args) {
        return isLeagueCommand(args, "franchises");
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

    private static LeagueTeamContextAnalyzer.TeamContextReport analyzeFranchises(String[] args) throws SQLException {
        LeagueTeamContextAnalyzer analyzer = new LeagueTeamContextAnalyzer(initializedDatabase());
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

    static void printLeagueFranchises(LeagueTeamContextAnalyzer.TeamContextReport report) {
        if (report == null) throw new IllegalArgumentException("report must not be null");
        var health = report.health();

        System.out.println("League franchises");
        System.out.println("League: " + health.leagueName() + "  [" + health.leagueId() + "]");
        System.out.println("Status: " + health.status());
        System.out.println("Source: " + (health.sourceResolved() ? health.source() : "UNRESOLVED"));
        if (health.minimumAsOfDate() != null) {
            System.out.println("Minimum as-of: " + health.minimumAsOfDate());
        }
        System.out.println("Franchise ranks available: " + report.ranksAvailable());
        if (report.movementAvailable()) {
            System.out.println("Movement window: " + report.movementPreviousDate() + " -> " + report.movementLatestDate());
        } else {
            System.out.println("Movement window: unavailable");
        }

        if (report.teams().isEmpty()) {
            System.out.println("No team context is available until the league value source can be resolved.");
        } else {
            for (var team : report.teams()) {
                String rank = team.rankAvailable() ? "#" + team.rank() : "UNRANKED";
                String movement = team.movementAvailable()
                    ? String.format("%+.2f", team.playerValueDelta())
                    : "unavailable";
                System.out.printf("%s  %s  total=%.2f  players=%.2f  picks=%.2f  coverage=%d/%d (%.1f%%)  movement=%s  movement-coverage=%d/%d (%.1f%%)  risers=%d  fallers=%d  [%s]%n",
                    rank, team.teamName(), team.totalAssetValue(), team.playerValue(), team.draftPickValue(),
                    team.valuedAssets(), team.totalAssets(), team.coveragePercent(), movement,
                    team.playersWithMovementHistory(), team.rosterSize(), team.movementCoveragePercent(),
                    team.risers(), team.fallers(), team.teamId());
            }
        }

        printLeagueActions(report.actionPlan().actions());
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

    static void printWorkflowUsage() {
        printOverviewUsage();
        printFranchisesUsage();
    }

    static void printOverviewUsage() {
        System.out.println("League overview:");
        System.out.println("  butler league overview <league-id> [source] [--minimum-as-of YYYY-MM-DD]");
    }

    static void printFranchisesUsage() {
        System.out.println("League franchise context:");
        System.out.println("  butler league franchises <league-id> [source] [--minimum-as-of YYYY-MM-DD]");
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
