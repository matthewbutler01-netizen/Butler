package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.LeagueActionPlanAnalyzer;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Stable CLI entry point. Delegates the established command surface to ButlerApp and augments
 * league health status with BF-101's ordered action plan without changing existing command syntax.
 */
public final class ButlerMain {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerMain() {}

    public static void main(String[] args) {
        if (!isSupportedLeagueStatus(args)) {
            ButlerApp.main(args);
            return;
        }

        // Preserve the complete BF-100 status output and its existing error behavior first.
        ButlerApp.main(args);

        try {
            printLeagueActions(analyzeActions(args));
        } catch (SQLException e) {
            System.err.println("Database error while building league action plan: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static boolean isSupportedLeagueStatus(String[] args) {
        if (args == null || args.length < 3) return false;
        if (!args[0].equalsIgnoreCase("league") || !args[1].equalsIgnoreCase("status")) return false;
        if (args.length == 3 || args.length == 4) return true;
        if (args.length == 5) return args[3].equalsIgnoreCase("--minimum-as-of");
        return args.length == 6 && args[4].equalsIgnoreCase("--minimum-as-of");
    }

    private static List<LeagueActionPlanAnalyzer.Action> analyzeActions(String[] args) throws SQLException {
        LeagueActionPlanAnalyzer analyzer = new LeagueActionPlanAnalyzer(initializedDatabase());
        LeagueActionPlanAnalyzer.ActionPlan plan;
        if (args.length == 3) {
            plan = analyzer.analyze(args[2]);
        } else if (args.length == 4) {
            plan = analyzer.analyze(args[2], args[3]);
        } else if (args.length == 5) {
            plan = analyzer.analyze(args[2], parseMinimumAsOfDate(args[4]));
        } else {
            plan = analyzer.analyze(args[2], args[3], parseMinimumAsOfDate(args[5]));
        }
        return plan.actions();
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
}
