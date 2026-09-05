package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer;

import java.nio.file.Path;
import java.sql.SQLException;

/** Exposes the governed historical rank-sensitivity calibration corpus audit without fitting thresholds. */
public final class ButlerLeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");
    private static final String COMMAND = "lineup-capture-ranking-sensitivity-calibration-corpus-audit";

    private ButlerLeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            print(new LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer(initializedDatabase())
                .analyze(options.startSeason(), options.endSeason()));
        } catch (SQLException e) {
            System.err.println("Database error while building historical lineup-capture rank-sensitivity calibration corpus audit: "
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
                "Usage: butler league lineup-capture-ranking-sensitivity-calibration-corpus-audit <start-season> <end-season>");
        }
        int startSeason = parseInt(args[2], "start-season");
        int endSeason = parseInt(args[3], "end-season");
        if (startSeason < 1999 || endSeason > 2100 || startSeason > endSeason) {
            throw new IllegalArgumentException(
                "season range must be within 1999..2100 and start-season <= end-season");
        }
        return new Options(startSeason, endSeason);
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && "league".equalsIgnoreCase(args[0])
            && COMMAND.equalsIgnoreCase(args[1]);
    }

    static void print(LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer.CorpusAuditReport report) {
        var summary = report.summary();
        System.out.println("Historical lineup-capture rank-sensitivity calibration corpus audit");
        System.out.println("Requested seasons: " + report.requestedStartSeason() + ".." + report.requestedEndSeason());
        System.out.println("Metric scope: " + report.metricScope());
        System.out.println("Policy: " + report.policyId());
        System.out.println("Audit policy: " + report.auditPolicy());
        System.out.println("Baseline common-week floor: " + report.minimumBaselineCommonWeeks());
        System.out.println("Future-only holdout common-week floor: " + report.minimumFutureHoldoutCommonWeeks());
        System.out.println("Persisted leagues without season metadata: " + report.leaguesWithoutSeason());
        System.out.println();

        System.out.println("Corpus breadth:");
        System.out.println("  requested league-seasons: " + summary.requestedLeagueSeasons());
        System.out.println("  audited league-seasons: " + summary.auditedLeagueSeasons());
        System.out.println("  source-failure league-seasons: " + summary.sourceFailureLeagueSeasons());
        System.out.println("  available temporal cutoffs: " + summary.availableCutoffs());
        System.out.println("  excluded temporal cutoffs: " + summary.excludedCutoffs());
        System.out.println("  available team-cutoff rows: " + summary.availableTeamCutoffRows());
        System.out.println("  repository team-count distribution: " + summary.teamCountDistribution());
        System.out.println("  baseline common-week-count distribution: "
            + summary.baselineCommonWeekCountDistribution());
        System.out.println("  future holdout common-week-count distribution: "
            + summary.futureHoldoutCommonWeekCountDistribution());
        System.out.println("  perturbation-denominator distribution: "
            + summary.perturbationDenominatorDistribution());
        System.out.println("  baseline BF-508 sensitivity-class counts: "
            + summary.baselineSensitivityClassCounts());
        System.out.println("  cutoff-state counts: " + summary.cutoffStateCounts());

        if (!report.sourceFailures().isEmpty()) {
            System.out.println();
            System.out.println("League-season source failures:");
            for (var failure : report.sourceFailures()) {
                System.out.println("  " + failure.leagueName() + " [" + failure.leagueId() + "] "
                    + failure.season() + " -> " + failure.state());
            }
        }

        for (var leagueSeason : report.leagueSeasons()) {
            System.out.println();
            System.out.println("League-season: " + leagueSeason.leagueName() + " [" + leagueSeason.leagueId()
                + "] " + leagueSeason.season());
            System.out.println("  audit state: " + leagueSeason.state());
            System.out.println("  repository teams: " + leagueSeason.sourceCommonUniverse().teams().size());
            System.out.println("  full common comparable weeks: "
                + leagueSeason.sourceCommonUniverse().commonComparableWeeks());

            for (var cutoff : leagueSeason.cutoffs()) {
                System.out.println("  cutoff after week " + cutoff.cutoffAfterWeek()
                    + " | baseline=" + cutoff.baselineCommonWeeks()
                    + " | future-holdout=" + cutoff.futureHoldoutCommonWeeks()
                    + " | state=" + cutoff.state());
                if (cutoff.state()
                    == LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer.CutoffState.AVAILABLE) {
                    for (var team : cutoff.teams()) {
                        System.out.println("    " + team.teamName() + " [" + team.teamId() + "]");
                        System.out.println("      baseline rank/rate: " + team.baselineRank() + " / "
                            + team.baselineLineupCaptureRate().toPlainString());
                        System.out.println("      baseline maximum absolute rank movement: "
                            + team.baselineMaximumAbsoluteRankMovement());
                        System.out.println("      baseline BF-508 sensitivity class: " + team.baselineSensitivityClass());
                        System.out.println("      baseline changed scenarios: " + team.baselineRankChangedScenarios()
                            + " of " + team.baselineCommonWeekCount());
                        System.out.println("      baseline rank-change frequency: "
                            + team.baselineRankChangeFrequency().toPlainString());
                        System.out.println("      future-only holdout rank/rate: " + team.futureHoldoutRank() + " / "
                            + team.futureHoldoutLineupCaptureRate().toPlainString());
                        System.out.println("      temporal signed rank displacement: "
                            + team.signedTemporalRankDisplacement());
                        System.out.println("      temporal absolute rank displacement: "
                            + team.absoluteTemporalRankDisplacement());
                        System.out.println("      exact numeric rank retained: " + team.exactNumericRankRetained());
                    }
                } else {
                    System.out.println("    no partial team calibration rows published for this cutoff");
                }
            }
        }

        System.out.println();
        System.out.println("Boundary: this is a historical corpus audit, not a calibrated model. Baseline and future holdout "
            + "windows are temporally disjoint. A later holdout rank is not a true or corrected rank, and ordinal movement "
            + "does not establish that the earlier rank was wrong. Butler fits no qualitative frequency threshold, "
            + "magnitude-frequency composite, probability, confidence score, expected rank, adjusted rank, manager "
            + "consistency/reliability grade, sensitivity leaderboard, recommendation, causal claim, or skill/fault "
            + "attribution. Corpus row counts are not automatically treated as independent sample size, and no arbitrary "
            + "sample-size sufficiency threshold is declared.");
    }

    private static Database initializedDatabase() throws SQLException {
        Database database = new Database(DATABASE_PATH);
        database.initialize();
        return database;
    }

    private static int parseInt(String value, String field) {
        try {
            return Integer.parseInt(requireText(value, field));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " must be an integer: " + value, e);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    record Options(int startSeason, int endSeason) {}
}
