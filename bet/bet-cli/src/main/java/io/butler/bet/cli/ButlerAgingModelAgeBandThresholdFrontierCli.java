package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.AgingModelAgeBandThresholdFrontierAnalyzer;

import java.nio.file.Path;
import java.sql.SQLException;

/** CLI leaf for inspecting non-dominated age-band support-threshold tradeoffs. */
public final class ButlerAgingModelAgeBandThresholdFrontierCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerAgingModelAgeBandThresholdFrontierCli() {}

    public static void main(String[] args) {
        try {
            parse(args);
            print(new AgingModelAgeBandThresholdFrontierAnalyzer(initializedDatabase()).analyze());
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            printUsage();
        } catch (SQLException e) {
            System.err.println("Database error while building aging-model age-band threshold frontier: " + e.getMessage());
            System.exit(1);
        }
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && args[0].equalsIgnoreCase("aging-model")
            && args[1].equalsIgnoreCase("age-band-threshold-frontier");
    }

    static void parse(String[] args) {
        if (!isCommand(args)) {
            throw new IllegalArgumentException("expected aging-model age-band-threshold-frontier");
        }
        if (args.length != 2) {
            throw new IllegalArgumentException(
                "aging-model age-band-threshold-frontier does not accept additional arguments");
        }
    }

    static void print(AgingModelAgeBandThresholdFrontierAnalyzer.ThresholdFrontierReport report) {
        if (report == null) throw new IllegalArgumentException("report must not be null");
        System.out.println("Aging-model age-band threshold frontier");
        System.out.println("Cells analyzed: " + report.cellsAnalyzed());
        System.out.println("Normalized cells: " + report.normalizedCells());
        System.out.println("Frontier points are non-dominated on retained coverage versus P90 normalized instability.");
        System.out.println("No support cutoff, age limit, publication rule, or player adjustment is selected or applied.");
        for (var band : report.bands()) {
            System.out.printf("position=%s age-band=%s candidates=%d frontier=%d%n",
                band.position(), band.ageBand().label(), band.candidates().size(), band.frontier().size());
            for (var point : band.frontier()) {
                System.out.printf("  min-transitions=%d baseline=%d retained=%d coverage=%.4f p90-ratio=%.4f max-ratio=%s%n",
                    point.minimumDistinctSeasonTransitions(), point.baselineCells(), point.retainedCells(),
                    point.retainedFraction(), point.p90MaximumShiftToHoldoutMae(),
                    formatNullable(point.maximumShiftToHoldoutMae()));
            }
        }
    }

    static void printUsage() {
        System.out.println("  butler aging-model age-band-threshold-frontier");
    }

    private static String formatNullable(Double value) {
        return value == null ? "n/a" : String.format("%.4f", value);
    }

    private static Database initializedDatabase() throws SQLException {
        Database database = new Database(DATABASE_PATH);
        database.initialize();
        return database;
    }
}
