package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer;

import java.nio.file.Path;
import java.sql.SQLException;

/** Exposes the BF-525 cluster-aware descriptive candidate study without selecting a threshold. */
public final class ButlerLeagueLineupCaptureRankingSensitivityCandidateThresholdStudyCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");
    private static final String COMMAND = "lineup-capture-ranking-sensitivity-candidate-threshold-study";

    private ButlerLeagueLineupCaptureRankingSensitivityCandidateThresholdStudyCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            print(new LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer(initializedDatabase())
                .analyze(options.startSeason(), options.endSeason()));
        } catch (SQLException e) {
            System.err.println("Database error while building candidate threshold study evidence: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (!isCommand(args) || args.length != 4) {
            throw new IllegalArgumentException(
                "Usage: butler league lineup-capture-ranking-sensitivity-candidate-threshold-study "
                    + "<start-season> <end-season>");
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

    static void print(
        LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.CandidateThresholdStudyReport report) {
        var readiness = report.sourceReadiness();
        var source = readiness.sourceCorpusAudit();

        System.out.println("Historical lineup-capture rank-sensitivity candidate threshold study");
        System.out.println("Requested seasons: " + source.requestedStartSeason() + ".." + source.requestedEndSeason());
        System.out.println("Policy: " + report.policyId());
        System.out.println("Metric scope: " + report.metricScope());
        System.out.println("Study policy: " + report.studyPolicy());
        System.out.println("Source BF-522 readiness: " + readiness.readinessState());
        System.out.println("Study state: " + report.studyState());
        System.out.println();

        if (report.studyState()
            != LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.StudyState.AVAILABLE) {
            System.out.println("No candidate fold evidence is published because the BF-525 study prerequisite is unavailable.");
            printBoundary();
            return;
        }

        System.out.println("Leave-one-league-season-out folds (league-season is the evaluation cluster):");
        for (var fold : report.folds()) {
            System.out.println("  Held out: " + fold.heldOutLeagueSeason());
            System.out.println("    development clusters: " + fold.developmentClusterCount());
            System.out.println("    repository team count: " + fold.repositoryTeamCount());
            System.out.println("    held-out available cutoffs: " + fold.heldOutAvailableCutoffs());
            System.out.println("    held-out team-cutoff rows: " + fold.heldOutTeamCutoffRows()
                + " (correlated rows; not independent sample N)");

            System.out.println("    Frequency candidates (development-observed rational breakpoints only):");
            for (var evaluation : fold.frequencyEvaluations()) {
                var candidate = evaluation.candidate();
                System.out.println("      <= " + candidate.numerator() + "/" + candidate.denominator()
                    + " (" + candidate.displayValue() + ") : " + evaluation.state());
                printSide("MEETS_CANDIDATE_RULE", evaluation.meetsRule(), "        ");
                printSide("DOES_NOT_MEET_CANDIDATE_RULE", evaluation.doesNotMeetRule(), "        ");
            }

            System.out.println("    Maximum-movement candidates (development-observed integer cutoffs only):");
            for (var evaluation : fold.magnitudeEvaluations()) {
                System.out.println("      <= " + evaluation.candidate().maximumMovementCutoff()
                    + " : " + evaluation.state());
                printSide("MEETS_CANDIDATE_RULE", evaluation.meetsRule(), "        ");
                printSide("DOES_NOT_MEET_CANDIDATE_RULE", evaluation.doesNotMeetRule(), "        ");
            }
        }
        System.out.println();

        System.out.println("Cross-fold frequency candidate support (ordered by candidate value, never performance):");
        for (var summary : report.frequencyCandidates()) {
            var candidate = summary.candidate();
            System.out.println("  " + candidate.numerator() + "/" + candidate.denominator()
                + " (" + candidate.displayValue() + ")");
            for (var outcome : summary.folds()) {
                System.out.println("    " + outcome.heldOutLeagueSeason() + ": " + outcome.state()
                    + " | meets rows=" + outcome.meetsRule().rows()
                    + " retained=" + outcome.meetsRule().exactRankRetainedRows()
                    + " moved=" + outcome.meetsRule().temporalRankMovedRows()
                    + " | does-not-meet rows=" + outcome.doesNotMeetRule().rows()
                    + " retained=" + outcome.doesNotMeetRule().exactRankRetainedRows()
                    + " moved=" + outcome.doesNotMeetRule().temporalRankMovedRows());
            }
        }

        System.out.println("Cross-fold maximum-movement candidate support (ordered by cutoff, never performance):");
        for (var summary : report.magnitudeCandidates()) {
            System.out.println("  <= " + summary.candidate().maximumMovementCutoff());
            for (var outcome : summary.folds()) {
                System.out.println("    " + outcome.heldOutLeagueSeason() + ": " + outcome.state()
                    + " | meets rows=" + outcome.meetsRule().rows()
                    + " retained=" + outcome.meetsRule().exactRankRetainedRows()
                    + " moved=" + outcome.meetsRule().temporalRankMovedRows()
                    + " | does-not-meet rows=" + outcome.doesNotMeetRule().rows()
                    + " retained=" + outcome.doesNotMeetRule().exactRankRetainedRows()
                    + " moved=" + outcome.doesNotMeetRule().temporalRankMovedRows());
            }
        }
        System.out.println();
        printBoundary();
    }

    private static void printSide(
        String label,
        LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.SideSummary side,
        String indent) {
        System.out.println(indent + label + ": rows=" + side.rows()
            + ", retained=" + side.exactRankRetainedRows()
            + ", moved=" + side.temporalRankMovedRows());
        System.out.println(indent + "  cutoff weeks: " + side.cutoffAfterWeeks());
        System.out.println(indent + "  absolute temporal rank displacement: "
            + side.absoluteTemporalRankDisplacementDistribution());
        System.out.println(indent + "  signed temporal rank displacement: "
            + side.signedTemporalRankDisplacementDistribution());
        System.out.println(indent + "  BF-508 classes: " + side.baselineSensitivityClassCounts());
        System.out.println(indent + "  changed-scenario numerators: " + side.changedScenarioNumeratorDistribution());
        System.out.println(indent + "  perturbation denominators: " + side.perturbationDenominatorDistribution());
        System.out.println(indent + "  repository team-count context: " + side.repositoryTeamCountDistribution());
    }

    private static void printBoundary() {
        System.out.println("Boundary: this is a deterministic, cluster-aware descriptive candidate study only. "
            + "Butler does not select a best/optimal/recommended/production threshold, optimize an objective, fit or refine "
            + "candidate values, pool team-cutoff rows as independent N, estimate probability/confidence/significance, "
            + "combine magnitude and frequency, adjust BF-500 ranks, score manager consistency/reliability/quality, create "
            + "a sensitivity leaderboard, issue recommendations, or make cross-league manager comparisons.");
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
