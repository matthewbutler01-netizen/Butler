package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.AgingModelAgeBandStabilityAnalyzer;

import java.nio.file.Path;
import java.sql.SQLException;

/** CLI leaf for inspecting normalized stability by support threshold, position, and age band. */
public final class ButlerAgingModelAgeBandStabilityCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerAgingModelAgeBandStabilityCli() {}

    public static void main(String[] args) {
        try {
            parse(args);
            print(new AgingModelAgeBandStabilityAnalyzer(initializedDatabase()).analyze());
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            printUsage();
        } catch (SQLException e) {
            System.err.println("Database error while building aging-model age-band stability: " + e.getMessage());
            System.exit(1);
        }
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && args[0].equalsIgnoreCase("aging-model")
            && args[1].equalsIgnoreCase("age-band-stability");
    }

    static void parse(String[] args) {
        if (!isCommand(args)) throw new IllegalArgumentException("expected aging-model age-band-stability");
        if (args.length != 2) {
            throw new IllegalArgumentException("aging-model age-band-stability does not accept additional arguments");
        }
    }

    static void print(AgingModelAgeBandStabilityAnalyzer.AgeBandStabilityReport report) {
        if (report == null) throw new IllegalArgumentException("report must not be null");
        System.out.println("Aging-model age-band stability");
        System.out.println("Cells analyzed: " + report.cellsAnalyzed());
        System.out.println("Normalized cells: " + report.normalizedCells());
        System.out.println("Bands compare maximum transition sensitivity normalized to temporal-holdout MAE.");
        System.out.println("No support cutoff, age limit, publication rule, or player adjustment is selected or applied.");
        for (var band : report.bands()) {
            System.out.printf("min-transitions=%d position=%s age-band=%s baseline=%d retained=%d coverage=%.4f max-ratio[median=%s p90=%s max=%s]%n",
                band.minimumDistinctSeasonTransitions(), band.position(), band.ageBand().label(), band.baselineCells(),
                band.retainedCells(), band.retainedFraction(),
                formatNullable(band.medianMaximumShiftToHoldoutMae()),
                formatNullable(band.p90MaximumShiftToHoldoutMae()),
                formatNullable(band.maximumShiftToHoldoutMae()));
        }
    }

    static void printUsage() {
        System.out.println("  butler aging-model age-band-stability");
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
