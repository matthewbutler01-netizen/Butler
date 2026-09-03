package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.AgingModelPublishedCellLookup;
import io.butler.bet.intelligence.AgingModelSampleAuditAnalyzer;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Set;

/** CLI leaf for fail-closed lookup of one publication-eligible aging-model cell. */
public final class ButlerAgingModelPublishedCellCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");
    private static final Set<String> SUPPORTED_POSITIONS = Set.of("QB", "RB", "WR", "TE");

    private ButlerAgingModelPublishedCellCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            print(options, new AgingModelPublishedCellLookup(initializedDatabase()).lookup(
                options.position(), options.metric(), options.age()));
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            printUsage();
        } catch (SQLException e) {
            System.err.println("Database error while looking up published aging-model cell: " + e.getMessage());
            System.exit(1);
        }
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && args[0].equalsIgnoreCase("aging-model")
            && args[1].equalsIgnoreCase("published-cell");
    }

    static Options parse(String[] args) {
        if (!isCommand(args) || args.length != 5) {
            throw new IllegalArgumentException("expected aging-model published-cell <position> <metric> <age>");
        }
        String position = args[2] == null ? "" : args[2].trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_POSITIONS.contains(position)) {
            throw new IllegalArgumentException("position must be one of QB, RB, WR, TE");
        }
        AgingModelSampleAuditAnalyzer.Metric metric;
        try {
            metric = AgingModelSampleAuditAnalyzer.Metric.valueOf(args[3].trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("metric must match a supported aging-model metric name");
        }
        int age;
        try {
            age = Integer.parseInt(args[4]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("age must be a whole number");
        }
        if (age < 0 || age > 100) throw new IllegalArgumentException("age must be between 0 and 100");
        return new Options(position, metric, age);
    }

    static void print(Options options, AgingModelPublishedCellLookup.LookupResult result) {
        if (options == null) throw new IllegalArgumentException("options must not be null");
        if (result == null) throw new IllegalArgumentException("result must not be null");
        System.out.println("Published aging-model cell lookup");
        System.out.printf("Coordinate: %s %s age=%d%n", options.position(), options.metric(), options.age());
        System.out.println("Status: " + result.status());
        System.out.println("Support policy: " + result.supportPolicyId());
        System.out.println("Minimum distinct season transitions: " + result.minimumDistinctSeasonTransitions());
        if (!result.available()) {
            System.out.println("Cell: unavailable; no model value is exposed.");
            return;
        }
        var cell = result.cell();
        System.out.printf("Cell: ages=%s target-n=%d pooled-n=%d players=%d transitions=%d delta[p25=%.4f median=%.4f p75=%.4f]%n",
            cell.contributingAges(), cell.targetAgeObservations(), cell.pooledObservations(), cell.uniquePlayers(),
            cell.distinctSeasonTransitions(), cell.deltaP25(), cell.medianDelta(), cell.deltaP75());
    }

    static void printUsage() {
        System.out.println("  butler aging-model published-cell <QB|RB|WR|TE> <METRIC_NAME> <age>");
    }

    private static Database initializedDatabase() throws SQLException {
        Database database = new Database(DATABASE_PATH);
        database.initialize();
        return database;
    }

    record Options(String position, AgingModelSampleAuditAnalyzer.Metric metric, int age) {}
}
