package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.LeagueLineupCaptureRankingSensitivityCalibrationCorpusReadinessAnalyzer;

import java.nio.file.Path;
import java.sql.SQLException;

/** Exposes BF-521 structural readiness without claiming statistical adequacy or fitting thresholds. */
public final class ButlerLeagueLineupCaptureRankingSensitivityCalibrationCorpusReadinessCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");
    private static final String COMMAND = "lineup-capture-ranking-sensitivity-calibration-corpus-readiness";

    private ButlerLeagueLineupCaptureRankingSensitivityCalibrationCorpusReadinessCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            print(new LeagueLineupCaptureRankingSensitivityCalibrationCorpusReadinessAnalyzer(initializedDatabase())
                .analyze(options.startSeason(), options.endSeason()));
        } catch (SQLException e) {
            System.err.println("Database error while building calibration corpus structural readiness evidence: "
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
                "Usage: butler league lineup-capture-ranking-sensitivity-calibration-corpus-readiness "
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
        LeagueLineupCaptureRankingSensitivityCalibrationCorpusReadinessAnalyzer.CorpusReadinessReport report) {
        var source = report.sourceCorpusAudit();
        var diagnostics = report.diagnostics();

        System.out.println("Historical lineup-capture rank-sensitivity calibration corpus structural readiness");
        System.out.println("Requested seasons: " + source.requestedStartSeason() + ".." + source.requestedEndSeason());
        System.out.println("Policy: " + report.policyId());
        System.out.println("Metric scope: " + report.metricScope());
        System.out.println("Readiness policy: " + report.readinessPolicy());
        System.out.println("Source BF-518 policy: " + source.policyId());
        System.out.println("Readiness state: " + report.readinessState());
        System.out.println();

        System.out.println("Core BF-521 structural variation gates:");
        for (var gate : report.gates()) {
            System.out.println("  [" + (gate.passed() ? "PASS" : "FAIL") + "] " + gate.gateId());
            System.out.println("    observed distinct states: " + gate.observedDistinctCount());
            System.out.println("    required structural condition: " + gate.requiredCondition());
            System.out.println("    observed values: " + gate.observedValues());
        }
        System.out.println();

        System.out.println("Structural diagnostics:");
        System.out.println("  available league IDs: " + diagnostics.availableLeagueIds());
        System.out.println("  available seasons: " + diagnostics.availableSeasons());
        System.out.println("  available league-season clusters: " + diagnostics.availableLeagueSeasonIdentities());
        System.out.println("  repository team-count strata: " + diagnostics.repositoryTeamCountStrata());
        System.out.println("  perturbation denominators: " + diagnostics.perturbationDenominators());
        System.out.println("  available cutoffs: " + diagnostics.availableCutoffs());
        System.out.println("  available team-cutoff rows: " + diagnostics.availableTeamCutoffRows()
            + " (correlated rows; not independent sample N)");
        System.out.println("  exact numeric rank retained rows: " + diagnostics.exactNumericRankRetainedRows());
        System.out.println("  temporal rank moved rows: " + diagnostics.temporalRankMovedRows());
        System.out.println("  requested league-seasons: " + diagnostics.requestedLeagueSeasons());
        System.out.println("  audited league-seasons: " + diagnostics.auditedLeagueSeasons());
        System.out.println("  source-failure league-seasons: " + diagnostics.sourceFailureLeagueSeasons());
        System.out.println("  excluded cutoffs: " + diagnostics.excludedCutoffs());
        System.out.println("  BF-508 sensitivity-class counts: " + diagnostics.sensitivityClassCounts());
        System.out.println("  changed-scenario numerator distribution: "
            + diagnostics.changedScenarioNumeratorDistribution());
        System.out.println("  BF-512 raw rank-change-frequency distribution: "
            + diagnostics.rankChangeFrequencyDistribution());
        System.out.println();

        System.out.println("Concentration diagnostics (available cutoffs):");
        System.out.println("  by league ID: " + diagnostics.availableCutoffsByLeagueId());
        System.out.println("  by season: " + diagnostics.availableCutoffsBySeason());
        System.out.println("  by league-season cluster: " + diagnostics.availableCutoffsByLeagueSeason());
        System.out.println("  by repository team count: " + diagnostics.availableCutoffsByTeamCount());
        System.out.println("  by perturbation denominator: "
            + diagnostics.availableCutoffsByPerturbationDenominator());
        System.out.println();

        if (report.readinessState()
            == LeagueLineupCaptureRankingSensitivityCalibrationCorpusReadinessAnalyzer.ReadinessState
                .READY_FOR_THRESHOLD_STUDY_METHODOLOGY_DESIGN) {
            System.out.println("Interpretation: the BF-518 corpus passes the six minimum structural variation gates.");
            System.out.println("This authorizes only a later governed threshold-study methodology design.");
        } else {
            System.out.println("Interpretation: the BF-518 corpus does not pass every minimum structural variation gate.");
            System.out.println("Failed gates must remain visible; Butler does not weaken them or synthesize evidence.");
        }

        System.out.println();
        System.out.println("Boundary: BF-521 structural readiness is not statistical sample-size adequacy, calibration, "
            + "confidence, probability, or proof of generalization. This command generates no candidate threshold, "
            + "fits no threshold, adjusts no BF-500 rank, and creates no manager consistency/reliability/quality score, "
            + "sensitivity leaderboard, recommendation, causal claim, or cross-league manager comparison.");
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
