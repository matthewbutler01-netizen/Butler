package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.AgingModelPublishedSmootherAnalyzer;

import java.nio.file.Path;
import java.sql.SQLException;

/** CLI leaf for publication-eligible aging-model smoother output. */
public final class ButlerAgingModelPublishedSmootherCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerAgingModelPublishedSmootherCli() {}

    public static void main(String[] args) {
        try {
            parse(args);
            print(new AgingModelPublishedSmootherAnalyzer(initializedDatabase()).analyze());
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            printUsage();
        } catch (SQLException e) {
            System.err.println("Database error while building published aging-model smoother: " + e.getMessage());
            System.exit(1);
        }
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && args[0].equalsIgnoreCase("aging-model")
            && args[1].equalsIgnoreCase("published-smoother");
    }

    static void parse(String[] args) {
        if (!isCommand(args)) throw new IllegalArgumentException("expected aging-model published-smoother");
        if (args.length != 2) {
            throw new IllegalArgumentException("aging-model published-smoother does not accept additional arguments");
        }
    }

    static void print(AgingModelPublishedSmootherAnalyzer.PublishedSmootherReport report) {
        if (report == null) throw new IllegalArgumentException("report must not be null");
        System.out.println("Published aging-model local smoother");
        System.out.println("Profile source: " + report.profileSource());
        System.out.println("Production source: " + report.productionSource());
        System.out.println("Support policy: " + report.supportPolicyId());
        System.out.println("Minimum distinct season transitions: " + report.minimumDistinctSeasonTransitions());
        System.out.printf("Cells: diagnostic=%d published=%d excluded=%d%n",
            report.diagnosticCells(), report.publishedCells(), report.excludedCells());
        System.out.println("Sub-threshold cells remain available through diagnostic commands but are not published here.");
        System.out.println("No age-based strategic label or dynasty-value adjustment is applied.");
        for (var cell : report.cells()) {
            System.out.printf("%s %s age=%d ages=%s target-n=%d pooled-n=%d players=%d transitions=%d delta[p25=%.4f median=%.4f p75=%.4f]%n",
                cell.position(), cell.metric(), cell.age(), cell.contributingAges(),
                cell.targetAgeObservations(), cell.pooledObservations(), cell.uniquePlayers(),
                cell.distinctSeasonTransitions(), cell.deltaP25(), cell.medianDelta(), cell.deltaP75());
        }
    }

    static void printUsage() {
        System.out.println("  butler aging-model published-smoother");
    }

    private static Database initializedDatabase() throws SQLException {
        Database database = new Database(DATABASE_PATH);
        database.initialize();
        return database;
    }
}
