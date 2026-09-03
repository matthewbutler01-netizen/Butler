package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.AgingModelPositionAgeEvidenceAnalyzer;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Set;

/** CLI leaf for governed, interpretation-free aging-model evidence by position and age. */
public final class ButlerAgingModelPositionAgeEvidenceCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");
    private static final Set<String> SUPPORTED_POSITIONS = Set.of("QB", "RB", "WR", "TE");

    private ButlerAgingModelPositionAgeEvidenceCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            print(new AgingModelPositionAgeEvidenceAnalyzer(initializedDatabase())
                .analyze(options.position(), options.age()));
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            printUsage();
        } catch (SQLException e) {
            System.err.println("Database error while building aging-model position-age evidence: " + e.getMessage());
            System.exit(1);
        }
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && args[0].equalsIgnoreCase("aging-model")
            && args[1].equalsIgnoreCase("position-age-evidence");
    }

    static Options parse(String[] args) {
        if (!isCommand(args) || args.length != 4) {
            throw new IllegalArgumentException("expected aging-model position-age-evidence <position> <age>");
        }
        String position = args[2] == null ? "" : args[2].trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_POSITIONS.contains(position)) {
            throw new IllegalArgumentException("position must be one of QB, RB, WR, TE");
        }
        int age;
        try {
            age = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("age must be a whole number");
        }
        if (age < 0 || age > 100) throw new IllegalArgumentException("age must be between 0 and 100");
        return new Options(position, age);
    }

    static void print(AgingModelPositionAgeEvidenceAnalyzer.PositionAgeEvidenceReport report) {
        if (report == null) throw new IllegalArgumentException("report must not be null");
        System.out.println("Aging-model position-age evidence");
        System.out.printf("Coordinate: %s age=%d%n", report.position(), report.age());
        System.out.println("Support policy: " + report.supportPolicyId());
        System.out.println("Minimum distinct season transitions: " + report.minimumDistinctSeasonTransitions());
        System.out.println("Profile source: " + report.profileSource());
        System.out.println("Production source: " + report.productionSource());
        System.out.printf("Metric availability: published=%d below-support=%d not-observed=%d%n",
            report.publishedMetrics(), report.belowSupportMetrics(), report.notObservedMetrics());
        System.out.println("No cross-metric score, career-stage label, dynasty adjustment, or recommendation is applied.");
        for (var metric : report.metrics()) {
            System.out.printf("%s status=%s", metric.metric(), metric.status());
            if (!metric.available()) {
                System.out.println(" cell=unavailable");
                continue;
            }
            var cell = metric.cell();
            System.out.printf(" ages=%s target-n=%d pooled-n=%d players=%d transitions=%d delta[p25=%.4f median=%.4f p75=%.4f]%n",
                cell.contributingAges(), cell.targetAgeObservations(), cell.pooledObservations(), cell.uniquePlayers(),
                cell.distinctSeasonTransitions(), cell.deltaP25(), cell.medianDelta(), cell.deltaP75());
        }
    }

    static void printUsage() {
        System.out.println("  butler aging-model position-age-evidence <QB|RB|WR|TE> <age>");
    }

    private static Database initializedDatabase() throws SQLException {
        Database database = new Database(DATABASE_PATH);
        database.initialize();
        return database;
    }

    record Options(String position, int age) {}
}
