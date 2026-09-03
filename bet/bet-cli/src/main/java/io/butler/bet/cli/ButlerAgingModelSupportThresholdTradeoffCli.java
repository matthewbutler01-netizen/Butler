package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.AgingModelSupportThresholdTradeoffAnalyzer;

import java.nio.file.Path;
import java.sql.SQLException;

/** CLI leaf for comparing candidate aging-model support thresholds without selecting one. */
public final class ButlerAgingModelSupportThresholdTradeoffCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerAgingModelSupportThresholdTradeoffCli() {}

    public static void main(String[] args) {
        try {
            parse(args);
            print(new AgingModelSupportThresholdTradeoffAnalyzer(initializedDatabase()).analyze());
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            printUsage();
        } catch (SQLException e) {
            System.err.println("Database error while building aging-model support threshold tradeoffs: " + e.getMessage());
            System.exit(1);
        }
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && args[0].equalsIgnoreCase("aging-model")
            && args[1].equalsIgnoreCase("support-thresholds");
    }

    static void parse(String[] args) {
        if (!isCommand(args)) throw new IllegalArgumentException("expected aging-model support-thresholds");
        if (args.length != 2) {
            throw new IllegalArgumentException("aging-model support-thresholds does not accept additional arguments");
        }
    }

    static void print(AgingModelSupportThresholdTradeoffAnalyzer.ThresholdTradeoffReport report) {
        if (report == null) throw new IllegalArgumentException("report must not be null");
        System.out.println("Aging-model support threshold tradeoffs");
        System.out.println("Cells analyzed: " + report.cellsAnalyzed());
        System.out.println("Normalized cells: " + report.normalizedCells());
        System.out.println("Thresholds compare retained coverage with maximum transition sensitivity normalized to temporal-holdout MAE.");
        System.out.println("No production support cutoff, publication rule, or player adjustment is selected or applied.");
        for (var threshold : report.thresholds()) {
            System.out.printf("min-transitions=%d retained=%d excluded=%d coverage=%.4f max-ratio[median=%s p75=%s p90=%s max=%s]%n",
                threshold.minimumDistinctSeasonTransitions(), threshold.retainedCells(), threshold.excludedCells(),
                threshold.retainedFraction(), formatNullable(threshold.medianMaximumShiftToHoldoutMae()),
                formatNullable(threshold.p75MaximumShiftToHoldoutMae()), formatNullable(threshold.p90MaximumShiftToHoldoutMae()),
                formatNullable(threshold.maximumShiftToHoldoutMae()));
        }
    }

    static void printUsage() {
        System.out.println("  butler aging-model support-thresholds");
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
