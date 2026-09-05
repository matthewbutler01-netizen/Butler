package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.LeagueSeasonLineupCaptureRankingSensitivityClassificationEvidenceAnalyzer;
import io.butler.bet.intelligence.LeagueSeasonLineupCaptureRankingStabilityEvidenceAnalyzer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.sql.SQLException;

/** Exposes governed observed lineup-capture rank sensitivity classes without manager or confidence claims. */
public final class ButlerLeagueSeasonLineupCaptureRankingSensitivityClassificationEvidenceCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");
    private static final String COMMAND = "season-lineup-capture-ranking-sensitivity-classification-evidence";

    private ButlerLeagueSeasonLineupCaptureRankingSensitivityClassificationEvidenceCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            print(new LeagueSeasonLineupCaptureRankingSensitivityClassificationEvidenceAnalyzer(initializedDatabase())
                .analyze(options.leagueId(), options.season()));
        } catch (SQLException e) {
            System.err.println("Database error while building league lineup-capture ranking sensitivity classification: "
                + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (!isCommand(args) || args.length != 4) {
            throw new IllegalArgumentException(
                "Usage: butler league season-lineup-capture-ranking-sensitivity-classification-evidence <league-id> <season>");
        }
        String leagueId = requireText(args[2], "league-id");
        int season = parseInt(args[3], "season");
        if (season < 1999 || season > 2100) {
            throw new IllegalArgumentException("season must be between 1999 and 2100");
        }
        return new Options(leagueId, season);
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && "league".equalsIgnoreCase(args[0])
            && COMMAND.equalsIgnoreCase(args[1]);
    }

    static void print(
        LeagueSeasonLineupCaptureRankingSensitivityClassificationEvidenceAnalyzer
            .LeagueSensitivityClassificationReport report) {
        var stability = report.sourceRankingStability();
        var baseline = stability.sourceBaselineRanking();
        var common = baseline.sourceCommonUniverse();

        System.out.println("League season lineup-capture ranking sensitivity classification evidence");
        System.out.println("League: " + common.leagueName() + " [" + common.leagueId() + "]");
        System.out.println("Season: " + common.season());
        System.out.println("Metric scope: " + report.metricScope());
        System.out.println("Policy: " + report.policyId());
        System.out.println("Classification policy: " + report.classificationPolicy());
        System.out.println("Source stability policy: " + stability.policyId());
        System.out.println("Source stability state: " + stability.stabilityState());
        System.out.println("Baseline ranking state: " + baseline.rankingState());
        System.out.println("Baseline common comparable weeks: " + common.commonComparableWeeks());
        System.out.println("Classification state: " + report.classificationState());
        System.out.println("Rule: max absolute rank movement 0 = LOW_SENSITIVITY; 1 = MODERATE_SENSITIVITY; 2+ = HIGH_SENSITIVITY");
        System.out.println();

        if (report.classificationState()
            == LeagueSeasonLineupCaptureRankingSensitivityClassificationEvidenceAnalyzer.ClassificationState.AVAILABLE) {
            System.out.println("Observed lineup-capture rank sensitivity classifications:");
            for (var team : report.teamClassifications()) {
                System.out.println("  " + team.teamName() + " [" + team.teamId() + "]");
                System.out.println("    baseline lineup-capture rank: " + team.baselineRank());
                System.out.println("    baseline lineup-capture rate: " + team.baselineLineupCaptureRate().toPlainString());
                System.out.println("    baseline lineup-capture percentage: " + percentage(team.baselineLineupCaptureRate()));
                System.out.println("    perturbation scenario count: " + team.perturbationScenarioCount());
                System.out.println("    maximum absolute rank movement: " + team.maximumAbsoluteRankMovement());
                System.out.println("    rank sensitivity range width: " + team.rankSensitivityRangeWidth());
                System.out.println("    baseline-rank unchanged scenarios: " + team.baselineRankUnchangedScenarios());
                System.out.println("    baseline-rank changed scenarios: " + team.baselineRankChangedScenarios());
                System.out.println("    observed sensitivity class: " + team.sensitivityClass());
            }
        } else {
            System.out.println("Observed lineup-capture rank sensitivity classification: unavailable ("
                + sourceUnavailableReason(stability.stabilityState()) + ")");
            System.out.println("No partial sensitivity classification is published.");
        }

        System.out.println();
        System.out.println("Boundary: LOW_SENSITIVITY, MODERATE_SENSITIVITY, and HIGH_SENSITIVITY are deterministic "
            + "labels for observed maximum absolute movement of the governed lineup-capture rank across the complete "
            + "leave-one-common-week-out perturbation set. They are not manager stability, manager consistency, manager "
            + "reliability, manager quality, statistical confidence, probability, significance, prediction, or a "
            + "replacement rank. The BF-500 baseline lineup-capture rank remains authoritative. Frequency of changed "
            + "scenarios, rate movement, coverage, and manager identity do not alter the class. Sensitivity class is not "
            + "an independent leaderboard key and is not authorized for cross-league comparison.");
    }

    private static String sourceUnavailableReason(
        LeagueSeasonLineupCaptureRankingStabilityEvidenceAnalyzer.StabilityState state) {
        return switch (state) {
            case UNAVAILABLE_BASELINE_RANKING -> "source baseline lineup-capture ranking is unavailable";
            case UNAVAILABLE_INSUFFICIENT_COMMON_WEEKS_FOR_PERTURBATION ->
                "source stability does not meet the five-common-week perturbation floor";
            case UNAVAILABLE_PERTURBATION_TEAM_RATE ->
                "at least one required source perturbation lacks an available normalized team rate";
            case AVAILABLE -> throw new IllegalArgumentException("available source stability requires classifications");
        };
    }

    private static Database initializedDatabase() throws SQLException {
        Database database = new Database(DATABASE_PATH);
        database.initialize();
        return database;
    }

    private static String percentage(BigDecimal rate) {
        return rate.multiply(BigDecimal.valueOf(100))
            .setScale(2, RoundingMode.HALF_UP)
            .toPlainString() + "%";
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
