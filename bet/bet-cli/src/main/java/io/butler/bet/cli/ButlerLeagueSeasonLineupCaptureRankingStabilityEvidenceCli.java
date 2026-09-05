package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.LeagueSeasonLineupCaptureRankingStabilityEvidenceAnalyzer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.sql.SQLException;

/** Exposes deterministic lineup-capture ranking sensitivity without statistical or manager attribution. */
public final class ButlerLeagueSeasonLineupCaptureRankingStabilityEvidenceCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");
    private static final String COMMAND = "season-lineup-capture-ranking-stability-evidence";

    private ButlerLeagueSeasonLineupCaptureRankingStabilityEvidenceCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            print(new LeagueSeasonLineupCaptureRankingStabilityEvidenceAnalyzer(initializedDatabase())
                .analyze(options.leagueId(), options.season()));
        } catch (SQLException e) {
            System.err.println("Database error while building league lineup-capture ranking stability evidence: "
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
                "Usage: butler league season-lineup-capture-ranking-stability-evidence <league-id> <season>");
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

    static void print(LeagueSeasonLineupCaptureRankingStabilityEvidenceAnalyzer.LeagueStabilityReport report) {
        var baseline = report.sourceBaselineRanking();
        var source = baseline.sourceCommonUniverse();
        System.out.println("League season lineup-capture ranking stability evidence");
        System.out.println("League: " + source.leagueName() + " [" + source.leagueId() + "]");
        System.out.println("Season: " + source.season());
        System.out.println("Metric scope: " + report.metricScope());
        System.out.println("Policy: " + report.policyId());
        System.out.println("Sensitivity policy: " + report.sensitivityPolicy());
        System.out.println("Baseline ranking policy: " + baseline.policyId());
        System.out.println("Baseline ranking state: " + baseline.rankingState());
        System.out.println("Minimum common-week floor for baseline rank: " + baseline.minimumCommonWeeks());
        System.out.println("Minimum common-week floor for leave-one-week-out stability: "
            + report.minimumCommonWeeksForStability());
        System.out.println("Baseline common comparable weeks: " + source.commonComparableWeeks());
        System.out.println("Baseline common comparable week count: " + source.commonComparableWeeks().size());
        System.out.println("Stability state: " + report.stabilityState());
        System.out.println();

        if (!report.scenarios().isEmpty()) {
            System.out.println("Leave-one-common-week-out perturbations:");
            for (var scenario : report.scenarios()) {
                System.out.println("  omitted common week " + scenario.omittedCommonWeek()
                    + " | retained=" + scenario.retainedCommonWeeks()
                    + " | state=" + scenario.state());
                for (var team : scenario.teams()) {
                    System.out.println("    " + team.teamName() + " [" + team.teamId() + "]"
                        + " | started=" + points(team.totalStartedPoints())
                        + " | potential=" + points(team.totalPotentialPoints())
                        + " | gap=" + points(team.totalPointsGap())
                        + " | rate-state=" + team.rateState()
                        + " | rate=" + team.lineupCaptureRate().map(BigDecimal::toPlainString).orElse("unavailable")
                        + " | lineup-capture-rank=" + team.lineupCaptureRank().map(String::valueOf).orElse("unavailable"));
                }
            }
            System.out.println();
        }

        if (report.stabilityState()
            == LeagueSeasonLineupCaptureRankingStabilityEvidenceAnalyzer.StabilityState.AVAILABLE) {
            System.out.println("Deterministic team rank/rate sensitivity summaries:");
            for (var team : report.teamSummaries()) {
                System.out.println("  " + team.teamName() + " [" + team.teamId() + "]");
                System.out.println("    baseline lineup-capture rank: " + team.baselineRank());
                System.out.println("    baseline lineup-capture rate: " + team.baselineLineupCaptureRate().toPlainString());
                System.out.println("    baseline lineup-capture percentage: " + percentage(team.baselineLineupCaptureRate()));
                System.out.println("    perturbation scenario count: " + team.perturbationScenarioCount());
                System.out.println("    distinct perturbation ranks: " + team.distinctPerturbationRanks());
                System.out.println("    best perturbation rank: " + team.bestPerturbationRank());
                System.out.println("    worst perturbation rank: " + team.worstPerturbationRank());
                System.out.println("    rank sensitivity range width: " + team.rankSensitivityRangeWidth());
                System.out.println("    maximum absolute rank movement: " + team.maximumAbsoluteRankMovement());
                System.out.println("    baseline-rank unchanged scenarios: " + team.baselineRankUnchangedScenarios());
                System.out.println("    baseline-rank changed scenarios: " + team.baselineRankChangedScenarios());
                System.out.println("    minimum perturbation rate: " + team.minimumPerturbationRate().toPlainString());
                System.out.println("    maximum perturbation rate: " + team.maximumPerturbationRate().toPlainString());
                System.out.println("    maximum absolute rate movement: " + team.maximumAbsoluteRateMovement().toPlainString());
                System.out.println("    rank unchanged in all perturbations: " + team.rankUnchangedInAllScenarios());
            }
        } else {
            System.out.println("Deterministic team sensitivity summary: unavailable ("
                + unavailableReason(report.stabilityState(), report.minimumCommonWeeksForStability()) + ")");
            System.out.println("No partial stability summary is published.");
        }

        System.out.println();
        System.out.println("Boundary: this is deterministic leave-one-common-week-out sensitivity of the governed "
            + "lineup-capture ranking. It is not a confidence interval, probability, p-value, bootstrap result, "
            + "predictive claim, manager-consistency score, manager-reliability grade, skill estimate, fault assignment, "
            + "or causal judgment. The baseline lineup-capture rank remains authoritative; perturbation ranks do not "
            + "create an average, median, modal, consensus, or stability-adjusted replacement rank. Butler assigns no "
            + "stable/unstable tier and computes no league-wide stability score. Historical startability remains limited "
            + "to the governed evidence actually persisted.");
    }

    private static String unavailableReason(
        LeagueSeasonLineupCaptureRankingStabilityEvidenceAnalyzer.StabilityState state,
        int minimumCommonWeeks) {
        return switch (state) {
            case UNAVAILABLE_BASELINE_RANKING -> "baseline governed lineup-capture ranking is unavailable";
            case UNAVAILABLE_INSUFFICIENT_COMMON_WEEKS_FOR_PERTURBATION ->
                "baseline common comparable week count is below the v1 stability minimum of " + minimumCommonWeeks;
            case UNAVAILABLE_PERTURBATION_TEAM_RATE ->
                "at least one required omitted-week perturbation lacks an available normalized rate for every team";
            case AVAILABLE -> throw new IllegalArgumentException("available stability state requires summaries");
        };
    }

    private static Database initializedDatabase() throws SQLException {
        Database database = new Database(DATABASE_PATH);
        database.initialize();
        return database;
    }

    private static String points(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
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
