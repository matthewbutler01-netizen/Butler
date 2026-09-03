package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.AgingModelTemporalHoldoutAnalyzer;

import java.nio.file.Path;
import java.sql.SQLException;

/** CLI leaf for rolling-origin temporal validation of the local aging smoother. */
public final class ButlerAgingModelTemporalHoldoutCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerAgingModelTemporalHoldoutCli() {}

    public static void main(String[] args) {
        try {
            parse(args);
            print(new AgingModelTemporalHoldoutAnalyzer(initializedDatabase()).analyze());
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            printUsage();
        } catch (SQLException e) {
            System.err.println("Database error while building aging-model temporal holdout: " + e.getMessage());
            System.exit(1);
        }
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && args[0].equalsIgnoreCase("aging-model")
            && args[1].equalsIgnoreCase("temporal-holdout");
    }

    static void parse(String[] args) {
        if (!isCommand(args)) throw new IllegalArgumentException("expected aging-model temporal-holdout");
        if (args.length != 2) {
            throw new IllegalArgumentException("aging-model temporal-holdout does not accept additional arguments");
        }
    }

    static void print(AgingModelTemporalHoldoutAnalyzer.TemporalHoldoutReport report) {
        if (report == null) throw new IllegalArgumentException("report must not be null");
        System.out.println("Aging-model temporal holdout");
        System.out.println("Candidate observations: " + report.candidateObservations());
        System.out.println("Evaluated observations: " + report.evaluatedObservations());
        System.out.println("Without prior training: " + report.observationsWithoutPriorTraining());
        System.out.println("Rolling origin: each prediction uses strictly earlier season transitions only.");
        System.out.println("No validation threshold or player adjustment is applied.");
        for (var dimension : report.dimensions()) {
            System.out.printf("%s %s n=%d error[median=%.4f mae=%.4f med-abs=%.4f p75-abs=%.4f]%n",
                dimension.position(), dimension.metric(), dimension.evaluatedObservations(),
                dimension.medianError(), dimension.meanAbsoluteError(),
                dimension.medianAbsoluteError(), dimension.absoluteErrorP75());
        }
        System.out.println("Season transitions:");
        for (var transition : report.transitions()) {
            System.out.printf("  %d-%d n=%d mae=%.4f med-abs=%.4f%n",
                transition.startSeason(), transition.endSeason(), transition.evaluatedObservations(),
                transition.meanAbsoluteError(), transition.medianAbsoluteError());
        }
    }

    static void printUsage() {
        System.out.println("  butler aging-model temporal-holdout");
    }

    private static Database initializedDatabase() throws SQLException {
        Database database = new Database(DATABASE_PATH);
        database.initialize();
        return database;
    }
}
