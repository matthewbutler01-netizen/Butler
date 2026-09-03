package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.AgingModelNormalizedStabilityAnalyzer;

import java.nio.file.Path;
import java.sql.SQLException;

/** CLI leaf for transition-stability diagnostics normalized to temporal-holdout error. */
public final class ButlerAgingModelNormalizedStabilityCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerAgingModelNormalizedStabilityCli() {}

    public static void main(String[] args) {
        try {
            parse(args);
            print(new AgingModelNormalizedStabilityAnalyzer(initializedDatabase()).analyze());
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            printUsage();
        } catch (SQLException e) {
            System.err.println("Database error while building aging-model normalized stability: " + e.getMessage());
            System.exit(1);
        }
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && args[0].equalsIgnoreCase("aging-model")
            && args[1].equalsIgnoreCase("normalized-stability");
    }

    static void parse(String[] args) {
        if (!isCommand(args)) throw new IllegalArgumentException("expected aging-model normalized-stability");
        if (args.length != 2) {
            throw new IllegalArgumentException("aging-model normalized-stability does not accept additional arguments");
        }
    }

    static void print(AgingModelNormalizedStabilityAnalyzer.NormalizedStabilityReport report) {
        if (report == null) throw new IllegalArgumentException("report must not be null");
        System.out.println("Aging-model normalized transition stability");
        System.out.println("Metric observations: " + report.metricObservations());
        System.out.println("Cells analyzed: " + report.cellsAnalyzed());
        System.out.println("Normalized cells: " + report.normalizedCells());
        System.out.println("Cells without usable holdout scale: " + report.cellsWithoutUsableHoldoutScale());
        System.out.println("Ratios compare leave-one-transition-out median shifts with same position/metric temporal-holdout MAE.");
        System.out.println("No support cutoff, publication rule, or player adjustment is applied.");
        for (var cell : report.cells()) {
            String influential = cell.mostInfluentialStartSeason() == null
                ? "none"
                : cell.mostInfluentialStartSeason() + "-" + cell.mostInfluentialEndSeason();
            String holdoutMae = formatNullable(cell.holdoutMeanAbsoluteError());
            String medianRatio = formatNullable(cell.medianShiftToHoldoutMae());
            String p75Ratio = formatNullable(cell.p75ShiftToHoldoutMae());
            String maxRatio = formatNullable(cell.maximumShiftToHoldoutMae());
            System.out.printf("%s %s age=%d n=%d transitions=%d removals=%d no-support=%d baseline=%.4f shift[median=%.4f p75=%.4f max=%.4f] holdout-mae=%s ratio[median=%s p75=%s max=%s] influential=%s%n",
                cell.position(), cell.metric(), cell.age(), cell.pooledObservations(),
                cell.distinctSeasonTransitions(), cell.evaluatedTransitionRemovals(),
                cell.removalsWithoutRemainingSupport(), cell.baselineMedianDelta(),
                cell.medianAbsoluteShift(), cell.absoluteShiftP75(), cell.maximumAbsoluteShift(),
                holdoutMae, medianRatio, p75Ratio, maxRatio, influential);
        }
    }

    static void printUsage() {
        System.out.println("  butler aging-model normalized-stability");
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
