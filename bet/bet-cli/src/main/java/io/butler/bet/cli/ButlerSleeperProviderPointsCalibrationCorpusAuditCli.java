package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.sleeper.SleeperProviderPointsCalibrationCorpusAudit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;

/** Read-only BF-565 operator surface for the complete persisted provider-points calibration corpus. */
public final class ButlerSleeperProviderPointsCalibrationCorpusAuditCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerSleeperProviderPointsCalibrationCorpusAuditCli() {}

    public static void main(String[] args) {
        try {
            parse(args);
            Database database = new Database(DATABASE_PATH);
            database.initialize();
            print(new SleeperProviderPointsCalibrationCorpusAudit(database).audit());
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static void parse(String[] args) {
        if (args != null && args.length != 0) {
            throw new IllegalArgumentException("Usage: sleeperProviderPointsCalibrationCorpusAudit");
        }
    }

    static void print(SleeperProviderPointsCalibrationCorpusAudit.AuditReport report) {
        var summary = report.summary();
        System.out.println("Sleeper provider-points calibration corpus audit");
        System.out.println("Policy: " + report.policyId());
        System.out.println("Source: " + report.source());
        System.out.println("Persisted league-seasons: " + summary.leagueSeasons());
        System.out.println("Rule eligible: " + summary.ruleEligibleLeagueSeasons());
        System.out.println("Rule ineligible: " + summary.ruleIneligibleLeagueSeasons());
        System.out.println("Calibrated: " + summary.calibratedLeagueSeasons());
        System.out.println("Calibration errors: " + summary.calibrationErrorLeagueSeasons());
        System.out.println("League-seasons with comparable rows: " + summary.leagueSeasonsWithComparableRows());
        System.out.println("Provider rows across latest snapshots: " + summary.providerRows());
        System.out.println("Comparable rows: " + summary.comparableRows());
        System.out.println("Exact matches: " + summary.exactMatches() + "/" + summary.comparableRows());
        System.out.println("Within 0.01 points: " + summary.withinOneHundredth() + "/" + summary.comparableRows());
        System.out.println("Aggregated non-comparable reasons:");
        if (summary.nonComparableReasons().isEmpty()) {
            System.out.println("  none");
        } else {
            summary.nonComparableReasons().entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().name()))
                .forEach(entry -> System.out.println("  " + entry.getKey() + ": " + entry.getValue()));
        }

        System.out.println();
        System.out.println("Season | League | state | coverage | provider rows | supported/unsupported rules | unsupported keys | comparable | exact | <=0.01 | mean abs delta | p95 abs delta | detail");
        for (var entry : report.entries()) {
            var calibration = entry.calibration();
            String comparable = calibration.map(value -> Integer.toString(value.comparableRows())).orElse("n/a");
            String exact = calibration.map(value -> Integer.toString(value.metrics().exactMatches())).orElse("n/a");
            String within = calibration.map(value -> Integer.toString(value.metrics().withinOneHundredth())).orElse("n/a");
            String meanAbsolute = calibration
                .map(value -> metric(value.metrics().meanAbsoluteDelta()))
                .orElse("n/a");
            String p95 = calibration
                .map(value -> metric(value.metrics().p95AbsoluteDelta()))
                .orElse("n/a");
            String detail = entry.detail().orElse("none");
            System.out.println(entry.season()
                + " | " + entry.leagueName() + " [" + entry.leagueId() + "]"
                + " | " + entry.state()
                + " | " + entry.coverageState()
                + " | " + entry.providerRows()
                + " | " + entry.supportedNonzeroRules() + "/" + entry.unsupportedNonzeroRules()
                + " | " + (entry.unsupportedNonzeroKeys().isEmpty() ? "none" : entry.unsupportedNonzeroKeys())
                + " | " + comparable
                + " | " + exact
                + " | " + within
                + " | " + meanAbsolute
                + " | " + p95
                + " | " + detail);
        }

        System.out.println();
        System.out.println("Selection rule: every distinct league-season with persisted Sleeper provider-points evidence is included exactly once before calibration outcome is observed.");
        System.out.println("Boundary: read-only corpus audit. This command does not infer missing stats as zero, adopt provider points as Butler scoring, ingest play-by-play, add K/DEF eligibility, widen BF-518/BF-521 readiness, tune thresholds, alter rankings or confidence, rank managers, or make recommendations.");
    }

    private static String metric(Optional<BigDecimal> value) {
        if (value.isEmpty()) return "n/a";
        return value.orElseThrow()
            .setScale(6, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString();
    }
}
