package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.LeagueLineupCaptureRankingSensitivityCandidateCrossFoldSupportAuditAnalyzer;
import io.butler.bet.intelligence.LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer;

import java.nio.file.Path;
import java.sql.SQLException;

/** Exposes the BF-529/BF-530 descriptive cross-fold support audit without selecting a candidate. */
public final class ButlerLeagueLineupCaptureRankingSensitivityCandidateCrossFoldSupportAuditCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");
    private static final String COMMAND = "lineup-capture-ranking-sensitivity-candidate-cross-fold-support-audit";

    private ButlerLeagueLineupCaptureRankingSensitivityCandidateCrossFoldSupportAuditCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            print(new LeagueLineupCaptureRankingSensitivityCandidateCrossFoldSupportAuditAnalyzer(initializedDatabase())
                .analyze(options.startSeason(), options.endSeason()));
        } catch (SQLException e) {
            System.err.println("Database error while building candidate cross-fold support audit: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (!isCommand(args) || args.length != 4) {
            throw new IllegalArgumentException(
                "Usage: butler league lineup-capture-ranking-sensitivity-candidate-cross-fold-support-audit "
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
        LeagueLineupCaptureRankingSensitivityCandidateCrossFoldSupportAuditAnalyzer
            .CandidateCrossFoldSupportAuditReport report) {
        var study = report.sourceCandidateStudy();
        var readiness = study.sourceReadiness();
        var corpus = readiness.sourceCorpusAudit();

        System.out.println("Historical lineup-capture rank-sensitivity candidate cross-fold support audit");
        System.out.println("Requested seasons: " + corpus.requestedStartSeason() + ".." + corpus.requestedEndSeason());
        System.out.println("Policy: " + report.policyId());
        System.out.println("Metric scope: " + report.metricScope());
        System.out.println("Audit policy: " + report.auditPolicy());
        System.out.println("Source BF-526 candidate study: " + study.studyState());
        System.out.println("Audit state: " + report.reportState());
        System.out.println();

        if (report.reportState()
            != LeagueLineupCaptureRankingSensitivityCandidateCrossFoldSupportAuditAnalyzer.ReportState.AVAILABLE) {
            System.out.println(
                "No candidate cross-fold support evidence is published because the BF-526 candidate study is unavailable.");
            printBoundary();
            return;
        }

        System.out.println("Frequency candidates (BF-526 order preserved; never reordered by audit results):");
        for (var candidate : report.frequencyCandidates()) {
            var value = candidate.candidate();
            System.out.println("  " + value.numerator() + "/" + value.denominator()
                + " (" + value.displayValue() + ")");
            printCounts(candidate.counts(), "    ");
            System.out.println("    support state: " + candidate.supportState());
            System.out.println("    evaluable held-out league IDs: " + candidate.evaluableHeldOutLeagueIds());
            System.out.println("    evaluable held-out seasons: " + candidate.evaluableHeldOutSeasons());
            System.out.println("    evaluable league-season clusters: " + candidate.evaluableHeldOutLeagueSeasons());
            System.out.println("    repository team-count strata: " + candidate.repositoryTeamCountStrata());
            System.out.println("    perturbation denominators represented on both rule sides: "
                + candidate.perturbationDenominatorsRepresentedOnBothSides());
            System.out.println("    fold direction counts: " + candidate.directionCounts());
            printDirections(candidate.foldDirections(), "    ");
        }

        System.out.println("Maximum-movement candidates (BF-526 order preserved; never reordered by audit results):");
        for (var candidate : report.magnitudeCandidates()) {
            System.out.println("  <= " + candidate.candidate().maximumMovementCutoff());
            printCounts(candidate.counts(), "    ");
            System.out.println("    support state: " + candidate.supportState());
            System.out.println("    evaluable held-out league IDs: " + candidate.evaluableHeldOutLeagueIds());
            System.out.println("    evaluable held-out seasons: " + candidate.evaluableHeldOutSeasons());
            System.out.println("    evaluable league-season clusters: " + candidate.evaluableHeldOutLeagueSeasons());
            System.out.println("    repository team-count strata: " + candidate.repositoryTeamCountStrata());
            System.out.println("    fold direction counts: " + candidate.directionCounts());
            printDirections(candidate.foldDirections(), "    ");
        }
        System.out.println();
        printBoundary();
    }

    private static void printCounts(
        LeagueLineupCaptureRankingSensitivityCandidateCrossFoldSupportAuditAnalyzer.CandidateCounts counts,
        String indent) {
        System.out.println(indent + "BF-526 folds: total=" + counts.totalFolds()
            + ", generated=" + counts.generatedFolds()
            + ", not-generated=" + counts.notGeneratedFolds()
            + ", evaluable=" + counts.evaluableFolds()
            + ", unevaluable-no-held-out-split=" + counts.unevaluableNoHeldOutSplitFolds());
        System.out.println(indent + "Fold counts are league-season cluster counts, not team-cutoff sample N.");
    }

    private static void printDirections(
        java.util.List<LeagueLineupCaptureRankingSensitivityCandidateCrossFoldSupportAuditAnalyzer.FoldDirectionAudit>
            directions,
        String indent) {
        for (var direction : directions) {
            System.out.println(indent + "held out " + direction.heldOutLeagueSeason()
                + " | repository team count=" + direction.repositoryTeamCount()
                + " | direction=" + direction.directionState());
            printSide(
                "MEETS_CANDIDATE_RULE",
                direction.meetsRuleTotalAbsoluteTemporalRankDisplacement(),
                direction.meetsRule(),
                indent + "  ");
            printSide(
                "DOES_NOT_MEET_CANDIDATE_RULE",
                direction.doesNotMeetRuleTotalAbsoluteTemporalRankDisplacement(),
                direction.doesNotMeetRule(),
                indent + "  ");
        }
    }

    private static void printSide(
        String label,
        long totalAbsoluteDisplacement,
        LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.SideSummary side,
        String indent) {
        System.out.println(indent + label + ": rows=" + side.rows()
            + ", total absolute temporal rank displacement=" + totalAbsoluteDisplacement
            + ", retained=" + side.exactRankRetainedRows()
            + ", moved=" + side.temporalRankMovedRows());
        System.out.println(indent + "  absolute temporal rank displacement: "
            + side.absoluteTemporalRankDisplacementDistribution());
        System.out.println(indent + "  signed temporal rank displacement: "
            + side.signedTemporalRankDisplacementDistribution());
        System.out.println(indent + "  cutoff weeks: " + side.cutoffAfterWeeks());
        System.out.println(indent + "  BF-508 classes: " + side.baselineSensitivityClassCounts());
        System.out.println(indent + "  changed-scenario numerators: " + side.changedScenarioNumeratorDistribution());
        System.out.println(indent + "  perturbation denominators: " + side.perturbationDenominatorDistribution());
        System.out.println(indent + "  repository team-count context: " + side.repositoryTeamCountDistribution());
    }

    private static void printBoundary() {
        System.out.println("Boundary: this is a deterministic, cluster-aware descriptive support/direction audit only. "
            + "Support states are evidence-breadth labels, not confidence. Direction states compare raw total absolute "
            + "temporal rank displacement and remain sensitive to side row counts. Butler does not normalize those totals "
            + "into a scalar score, calculate a win rate/probability/significance measure, rank candidates by apparent "
            + "performance, select or break ties among candidates, define an optimization objective, fit/refine a threshold, "
            + "publish a production threshold or calibrated category, adjust BF-500 ranks, score manager consistency/quality, "
            + "create a sensitivity leaderboard, issue recommendations, or make cross-league manager comparisons.");
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
