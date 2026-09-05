package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.LeagueSeasonLineupCaptureRankingChangeFrequencyEvidenceAnalyzer;
import io.butler.bet.intelligence.LeagueSeasonLineupCaptureRankingStabilityEvidenceAnalyzer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.sql.SQLException;

/** Exposes deterministic observed lineup-capture rank-change frequency without probability or manager attribution. */
public final class ButlerLeagueSeasonLineupCaptureRankingChangeFrequencyEvidenceCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");
    private static final String COMMAND = "season-lineup-capture-ranking-change-frequency-evidence";

    private ButlerLeagueSeasonLineupCaptureRankingChangeFrequencyEvidenceCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            print(new LeagueSeasonLineupCaptureRankingChangeFrequencyEvidenceAnalyzer(initializedDatabase())
                .analyze(options.leagueId(), options.season()));
        } catch (SQLException e) {
            System.err.println("Database error while building league lineup-capture ranking change-frequency evidence: "
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
                "Usage: butler league season-lineup-capture-ranking-change-frequency-evidence <league-id> <season>");
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

    static void print(LeagueSeasonLineupCaptureRankingChangeFrequencyEvidenceAnalyzer.LeagueRankChangeFrequencyReport report) {
        var stability = report.sourceRankingStability();
        var baseline = stability.sourceBaselineRanking();
        var source = baseline.sourceCommonUniverse();

        System.out.println("League season lineup-capture ranking change-frequency evidence");
        System.out.println("League: " + source.leagueName() + " [" + source.leagueId() + "]");
        System.out.println("Season: " + source.season());
        System.out.println("Metric scope: " + report.metricScope());
        System.out.println("Policy: " + report.policyId());
        System.out.println("Frequency policy: " + report.frequencyPolicy());
        System.out.println("Source ranking-stability policy: " + stability.policyId());
        System.out.println("Source stability state: " + stability.stabilityState());
        System.out.println("Baseline common comparable weeks: " + source.commonComparableWeeks());
        System.out.println("Required perturbation scenario count: " + stability.scenarios().size());
        System.out.println("Frequency state: " + report.frequencyState());
        System.out.println();

        if (report.frequencyState()
            == LeagueSeasonLineupCaptureRankingChangeFrequencyEvidenceAnalyzer.FrequencyState.AVAILABLE) {
            System.out.println("Team observed rank-change frequencies (source stability-summary order):");
            for (var team : report.teams()) {
                System.out.println("  " + team.teamName() + " [" + team.teamId() + "]");
                System.out.println("    baseline lineup-capture rank: " + team.baselineRank());
                System.out.println("    baseline lineup-capture rate: " + team.baselineLineupCaptureRate().toPlainString());
                System.out.println("    maximum absolute rank movement: " + team.maximumAbsoluteRankMovement());
                System.out.println("    BF-508 magnitude sensitivity class: " + team.magnitudeSensitivityClass());
                System.out.println("    changed scenarios: " + team.baselineRankChangedScenarios()
                    + " of " + team.perturbationScenarioCount());
                System.out.println("    unchanged scenarios: " + team.baselineRankUnchangedScenarios()
                    + " of " + team.perturbationScenarioCount());
                System.out.println("    observed rank-change frequency: " + team.rankChangeFrequency().toPlainString()
                    + " (" + percentage(team.rankChangeFrequency()) + ")");
                System.out.println("    observed rank-retention frequency: " + team.rankRetentionFrequency().toPlainString()
                    + " (" + percentage(team.rankRetentionFrequency()) + ")");
            }
        } else {
            System.out.println("Observed rank-change frequency: unavailable ("
                + unavailableReason(stability.stabilityState()) + ")");
            System.out.println("No partial team frequency rows are published.");
        }

        System.out.println();
        System.out.println("Boundary: rank-change frequency is changed required leave-one-week-out scenarios divided by the "
            + "complete perturbation count. It is deterministic observed sensitivity context, not probability, confidence, "
            + "prediction, manager consistency, manager reliability, manager quality, or skill. BF-508 movement magnitude "
            + "and BF-512 change frequency remain separate dimensions; Butler computes no combined score, qualitative "
            + "frequency tier, frequency-adjusted rank, sensitivity leaderboard, or league-wide stability score. The BF-500 "
            + "baseline lineup-capture rank remains authoritative.");
    }

    private static String unavailableReason(
        LeagueSeasonLineupCaptureRankingStabilityEvidenceAnalyzer.StabilityState state) {
        return switch (state) {
            case UNAVAILABLE_BASELINE_RANKING -> "baseline governed lineup-capture ranking is unavailable";
            case UNAVAILABLE_INSUFFICIENT_COMMON_WEEKS_FOR_PERTURBATION ->
                "source stability lacks the required five common comparable weeks";
            case UNAVAILABLE_PERTURBATION_TEAM_RATE ->
                "at least one required perturbation lacks an available normalized rate for every team";
            case AVAILABLE -> throw new IllegalArgumentException("available source stability requires frequency rows");
        };
    }

    private static Database initializedDatabase() throws SQLException {
        Database database = new Database(DATABASE_PATH);
        database.initialize();
        return database;
    }

    private static String percentage(BigDecimal frequency) {
        return frequency.multiply(BigDecimal.valueOf(100))
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
