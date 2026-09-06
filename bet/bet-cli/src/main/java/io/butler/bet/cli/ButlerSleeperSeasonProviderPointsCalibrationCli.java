package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.sleeper.SleeperSeasonProviderPointsCalibration;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.Comparator;

/** Read-only BF-562 operator surface for Sleeper-provider versus Butler exact-scoring calibration. */
public final class ButlerSleeperSeasonProviderPointsCalibrationCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerSleeperSeasonProviderPointsCalibrationCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            Database database = new Database(DATABASE_PATH);
            database.initialize();
            print(new SleeperSeasonProviderPointsCalibration(database)
                .calibrate(options.leagueId(), options.season()));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (args == null || args.length != 2) {
            throw new IllegalArgumentException(
                "Usage: sleeperSeasonProviderPointsCalibration <butler-league-id> <season>");
        }
        String leagueId = requireText(args[0], "butler-league-id");
        int season = parseInt(args[1], "season");
        if (season < 1999 || season > 2100) {
            throw new IllegalArgumentException("season must be between 1999 and 2100");
        }
        return new Options(leagueId, season);
    }

    static void print(SleeperSeasonProviderPointsCalibration.CalibrationReport report) {
        var metrics = report.metrics();
        System.out.println("Sleeper season provider-points calibration");
        System.out.println("Policy: " + report.policyId());
        System.out.println("League: " + report.leagueName() + " [" + report.leagueId() + "]");
        System.out.println("Season: " + report.season());
        System.out.println("Provider source: " + report.providerSource());
        System.out.println("Provider surface: " + report.providerSourceSurface());
        System.out.println("Provider as-of: " + report.providerAsOf());
        System.out.println("Butler production source: " + report.butlerProductionSource());
        System.out.println("Provider rows: " + report.providerRows());
        System.out.println("Identity-mapped rows: " + report.identityMappedRows());
        System.out.println("Comparable rows: " + report.comparableRows());
        System.out.println("Non-comparable rows: " + report.nonComparableRows());
        System.out.println("Non-comparable reasons:");
        if (report.nonComparableReasons().isEmpty()) {
            System.out.println("  none");
        } else {
            report.nonComparableReasons().entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().name()))
                .forEach(entry -> System.out.println("  " + entry.getKey() + ": " + entry.getValue()));
        }
        System.out.println("Exact matches: " + metrics.exactMatches() + "/" + metrics.comparableRows());
        System.out.println("Within 0.01 points: " + metrics.withinOneHundredth()
            + "/" + metrics.comparableRows());
        System.out.println("Mean signed delta: " + metric(metrics.meanSignedDelta()));
        System.out.println("Mean absolute delta: " + metric(metrics.meanAbsoluteDelta()));
        System.out.println("P50 absolute delta: " + metric(metrics.p50AbsoluteDelta()));
        System.out.println("P95 absolute delta: " + metric(metrics.p95AbsoluteDelta()));
        System.out.println("Max absolute delta: " + metric(metrics.maxAbsoluteDelta()));
        System.out.println("Delta convention: Butler exact points - Sleeper provider points");
        System.out.println("State: " + report.state());
        System.out.println();
        System.out.println("Boundary: read-only precision/overlap calibration only. This command does not persist calibration output, infer sparse raw-stat keys as zero, replace Butler exact scoring, change potential-lineup scoring, add K/DEF eligibility, widen BF-518/BF-521 readiness, tune thresholds, adjust rankings, create confidence, rank managers, or make recommendations.");
    }

    private static String metric(java.util.Optional<BigDecimal> value) {
        if (value.isEmpty()) return "n/a";
        return value.orElseThrow().setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static int parseInt(String value, String field) {
        try {
            return Integer.parseInt(requireText(value, field));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " must be an integer: " + value, e);
        }
    }

    record Options(String leagueId, int season) {}
}
