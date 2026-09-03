package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.AgingModelPositionAgeCoverageAnalyzer;

import java.nio.file.Path;
import java.sql.SQLException;

/** Argument-free CLI for governed position-age publication coverage diagnostics. */
public final class ButlerAgingModelPositionAgeCoverageCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerAgingModelPositionAgeCoverageCli() {}

    public static void main(String[] args) {
        try {
            parse(args);
            print(new AgingModelPositionAgeCoverageAnalyzer(initializedDatabase()).analyze());
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            printUsage();
        } catch (SQLException e) {
            System.err.println("Database error while auditing aging-model position-age coverage: " + e.getMessage());
            System.exit(1);
        }
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && args[0].equalsIgnoreCase("aging-model")
            && args[1].equalsIgnoreCase("position-age-coverage");
    }

    static void parse(String[] args) {
        if (!isCommand(args)) throw new IllegalArgumentException("expected aging-model position-age-coverage");
        if (args.length != 2) {
            throw new IllegalArgumentException("aging-model position-age-coverage does not accept additional arguments");
        }
    }

    static void print(AgingModelPositionAgeCoverageAnalyzer.CoverageReport report) {
        if (report == null) throw new IllegalArgumentException("report must not be null");
        System.out.println("Aging-model position-age publication coverage");
        System.out.println("Support policy: " + report.supportPolicyId());
        System.out.println("Minimum distinct season transitions: " + report.minimumDistinctSeasonTransitions());
        System.out.println("Profile source: " + report.profileSource());
        System.out.println("Production source: " + report.productionSource());
        System.out.println("Diagnostic only: no age cutoff, extrapolation, score, label, or recommendation is applied.");
        for (var position : report.positions()) {
            System.out.printf("%s observed-age-range=%s-%s full=%d partial=%d below-support=%d not-observed=%d%n",
                position.position(), format(position.minimumObservedAge()), format(position.maximumObservedAge()),
                position.fullAges(), position.partialAges(), position.belowSupportAges(), position.notObservedAges());
            for (var age : position.ages()) {
                System.out.printf("  age=%d status=%s published=%d below-support=%d not-observed=%d total=%d%n",
                    age.age(), age.status(), age.publishedMetrics(), age.belowSupportMetrics(),
                    age.notObservedMetrics(), age.totalMetrics());
            }
        }
    }

    static void printUsage() {
        System.out.println("  butler aging-model position-age-coverage");
    }

    private static String format(Integer value) {
        return value == null ? "-" : value.toString();
    }

    private static Database initializedDatabase() throws SQLException {
        Database database = new Database(DATABASE_PATH);
        database.initialize();
        return database;
    }
}
