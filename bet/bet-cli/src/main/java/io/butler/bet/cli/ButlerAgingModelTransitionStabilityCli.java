package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.AgingModelTransitionStabilityAnalyzer;

import java.nio.file.Path;
import java.sql.SQLException;

/** CLI leaf for leave-one-season-transition-out stability diagnostics. */
public final class ButlerAgingModelTransitionStabilityCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerAgingModelTransitionStabilityCli() {}

    public static void main(String[] args) {
        try {
            parse(args);
            print(new AgingModelTransitionStabilityAnalyzer(initializedDatabase()).analyze());
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            printUsage();
        } catch (SQLException e) {
            System.err.println("Database error while building aging-model transition stability: " + e.getMessage());
            System.exit(1);
        }
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && args[0].equalsIgnoreCase("aging-model")
            && args[1].equalsIgnoreCase("transition-stability");
    }

    static void parse(String[] args) {
        if (!isCommand(args)) throw new IllegalArgumentException("expected aging-model transition-stability");
        if (args.length != 2) {
            throw new IllegalArgumentException("aging-model transition-stability does not accept additional arguments");
        }
    }

    static void print(AgingModelTransitionStabilityAnalyzer.StabilityReport report) {
        if (report == null) throw new IllegalArgumentException("report must not be null");
        System.out.println("Aging-model transition stability");
        System.out.println("Metric observations: " + report.metricObservations());
        System.out.println("Cells analyzed: " + report.cellsAnalyzed());
        System.out.println("Evaluated transition removals: " + report.evaluatedTransitionRemovals());
        System.out.println("Removals without remaining support: " + report.removalsWithoutRemainingSupport());
        System.out.println("No stability threshold or player adjustment is applied.");
        for (var cell : report.cells()) {
            String influential = cell.mostInfluentialStartSeason() == null
                ? "none"
                : cell.mostInfluentialStartSeason() + "-" + cell.mostInfluentialEndSeason();
            System.out.printf("%s %s age=%d n=%d transitions=%d removals=%d no-support=%d baseline=%.4f shift[median=%.4f p75=%.4f max=%.4f] influential=%s%n",
                cell.position(), cell.metric(), cell.age(), cell.pooledObservations(),
                cell.distinctSeasonTransitions(), cell.evaluatedTransitionRemovals(),
                cell.removalsWithoutRemainingSupport(), cell.baselineMedianDelta(),
                cell.medianAbsoluteShift(), cell.absoluteShiftP75(), cell.maximumAbsoluteShift(), influential);
        }
    }

    static void printUsage() {
        System.out.println("  butler aging-model transition-stability");
    }

    private static Database initializedDatabase() throws SQLException {
        Database database = new Database(DATABASE_PATH);
        database.initialize();
        return database;
    }
}
