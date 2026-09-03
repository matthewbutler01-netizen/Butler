package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.AgingModelLocalSmootherAnalyzer;

import java.nio.file.Path;
import java.sql.SQLException;

/** CLI leaf for the governed descriptive local aging smoother. */
public final class ButlerAgingModelLocalSmootherCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerAgingModelLocalSmootherCli() {}

    public static void main(String[] args) {
        try {
            parse(args);
            print(new AgingModelLocalSmootherAnalyzer(initializedDatabase()).analyze());
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            printUsage();
        } catch (SQLException e) {
            System.err.println("Database error while building local aging smoother: " + e.getMessage());
            System.exit(1);
        }
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && args[0].equalsIgnoreCase("aging-model")
            && args[1].equalsIgnoreCase("local-smoother");
    }

    static void parse(String[] args) {
        if (!isCommand(args)) throw new IllegalArgumentException("expected aging-model local-smoother");
        if (args.length != 2) {
            throw new IllegalArgumentException("aging-model local-smoother does not accept additional arguments");
        }
    }

    static void print(AgingModelLocalSmootherAnalyzer.LocalSmootherReport report) {
        if (report == null) throw new IllegalArgumentException("report must not be null");
        System.out.println("Aging-model local smoother");
        System.out.println("Profile source: " + report.profileSource());
        System.out.println("Production source: " + report.productionSource());
        System.out.println("Smoothed cells: " + report.smoothedCells());
        System.out.println("Edge cells with fewer than three contributing ages: " + report.edgeCells());
        System.out.println("Window: centered age +/- 1; no extrapolation or publication threshold.");
        for (var cell : report.cells()) {
            System.out.printf("%s %s age=%d ages=%s target-n=%d pooled-n=%d players=%d transitions=%d delta[p25=%.4f median=%.4f p75=%.4f]%n",
                cell.position(), cell.metric(), cell.age(), cell.contributingAges(),
                cell.targetAgeObservations(), cell.pooledObservations(), cell.uniquePlayers(),
                cell.distinctSeasonTransitions(), cell.deltaP25(), cell.medianDelta(), cell.deltaP75());
        }
    }

    static void printUsage() {
        System.out.println("  butler aging-model local-smoother");
    }

    private static Database initializedDatabase() throws SQLException {
        Database database = new Database(DATABASE_PATH);
        database.initialize();
        return database;
    }
}
