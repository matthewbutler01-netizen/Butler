package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.AgingModelSmoothingSensitivityAnalyzer;

import java.nio.file.Path;
import java.sql.SQLException;

/** CLI leaf for paired rolling-origin smoothing sensitivity diagnostics. */
public final class ButlerAgingModelSmoothingSensitivityCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerAgingModelSmoothingSensitivityCli() {}

    public static void main(String[] args) {
        try {
            parse(args);
            print(new AgingModelSmoothingSensitivityAnalyzer(initializedDatabase()).analyze());
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            printUsage();
        } catch (SQLException e) {
            System.err.println("Database error while building aging-model smoothing sensitivity: " + e.getMessage());
            System.exit(1);
        }
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && args[0].equalsIgnoreCase("aging-model")
            && args[1].equalsIgnoreCase("smoothing-sensitivity");
    }

    static void parse(String[] args) {
        if (!isCommand(args)) throw new IllegalArgumentException("expected aging-model smoothing-sensitivity");
        if (args.length != 2) {
            throw new IllegalArgumentException("aging-model smoothing-sensitivity does not accept additional arguments");
        }
    }

    static void print(AgingModelSmoothingSensitivityAnalyzer.SensitivityReport report) {
        if (report == null) throw new IllegalArgumentException("report must not be null");
        System.out.println("Aging-model smoothing sensitivity");
        System.out.println("Candidate observations: " + report.candidateObservations());
        System.out.println("Paired observations: " + report.pairedObservations());
        System.out.println("Local available / exact-age unavailable: " + report.localAvailableCenterUnavailable());
        System.out.println("Neither available: " + report.neitherAvailable());
        System.out.println("Comparison: age +/-1 local median versus exact-age-only median using strictly earlier transitions.");
        System.out.println("No validation threshold or player adjustment is applied.");
        for (var dimension : report.dimensions()) {
            System.out.printf("%s %s n=%d local[mae=%.4f med-abs=%.4f] exact[mae=%.4f med-abs=%.4f] med-diff=%.4f wins[local=%d exact=%d ties=%d local%%=%.1f]%n",
                dimension.position(), dimension.metric(), dimension.pairedObservations(),
                dimension.localMeanAbsoluteError(), dimension.localMedianAbsoluteError(),
                dimension.centerMeanAbsoluteError(), dimension.centerMedianAbsoluteError(),
                dimension.medianAbsoluteErrorDifference(), dimension.localWins(), dimension.centerWins(),
                dimension.ties(), dimension.localWinPercent());
        }
    }

    static void printUsage() {
        System.out.println("  butler aging-model smoothing-sensitivity");
    }

    private static Database initializedDatabase() throws SQLException {
        Database database = new Database(DATABASE_PATH);
        database.initialize();
        return database;
    }
}
