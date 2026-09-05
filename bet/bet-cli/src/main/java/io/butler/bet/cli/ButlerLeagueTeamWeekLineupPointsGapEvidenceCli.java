package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.LeagueTeamWeekLineupPointsGapEvidenceAnalyzer;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.SQLException;

/** Exposes descriptive complete-lineup points-gap evidence without manager attribution. */
public final class ButlerLeagueTeamWeekLineupPointsGapEvidenceCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");
    private static final String COMMAND = "team-week-lineup-points-gap-evidence";

    private ButlerLeagueTeamWeekLineupPointsGapEvidenceCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            print(new LeagueTeamWeekLineupPointsGapEvidenceAnalyzer(initializedDatabase()).analyze(
                options.leagueId(), options.teamId(), options.season(), options.week()));
        } catch (SQLException e) {
            System.err.println("Database error while building team-week lineup points-gap evidence: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (!isCommand(args) || args.length != 6) {
            throw new IllegalArgumentException(
                "Usage: butler league team-week-lineup-points-gap-evidence <league-id> <team-id> <season> <week>");
        }
        String leagueId = requireText(args[2], "league-id");
        String teamId = requireText(args[3], "team-id");
        int season = parseInt(args[4], "season");
        int week = parseInt(args[5], "week");
        if (season < 1999 || season > 2100) {
            throw new IllegalArgumentException("season must be between 1999 and 2100");
        }
        if (week <= 0) throw new IllegalArgumentException("week must be positive");
        return new Options(leagueId, teamId, season, week);
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && "league".equalsIgnoreCase(args[0])
            && COMMAND.equalsIgnoreCase(args[1]);
    }

    static void print(LeagueTeamWeekLineupPointsGapEvidenceAnalyzer.LineupPointsGapReport report) {
        System.out.println("Team-week lineup points-gap evidence");
        System.out.println("League: " + report.leagueId());
        System.out.println("Team: " + report.teamId());
        System.out.println("Season/week: " + report.season() + "/" + report.week());
        System.out.println("Metric scope: " + report.metricScope());
        System.out.println("Interpretation: potential points minus recalculated started points for two complete "
            + "governed lineups under the same evidence boundary.");
        System.out.println();
        System.out.println("Source metric scopes:");
        System.out.println("  potential: " + report.potentialMetricScope());
        System.out.println("  started: " + report.startedMetricScope());
        System.out.println();
        System.out.println("Policies:");
        System.out.println("  gap calculation: " + report.policyId());
        System.out.println("  potential lineup: " + report.potentialLineupPolicyId());
        System.out.println("  started lineup: " + report.startedLineupPolicyId());
        System.out.println("  scoring: " + report.scoringPolicyId());
        System.out.println("  solver: " + report.solverPolicyId());
        System.out.println("  eligibility: " + report.eligibilityPolicyId());
        System.out.println();
        System.out.println("Evidence provenance:");
        System.out.println("  league configuration as-of: " + report.leagueConfigurationAsOf());
        System.out.println("  roster evidence as-of: " + report.rosterEvidenceAsOf());
        System.out.println("  production coverage as-of: " + report.productionCoverageAsOf());
        System.out.println("  production source: " + report.productionSourceUri());
        System.out.println();
        System.out.println("Complete starting slots: " + report.startingSlots());
        System.out.println("Recalculated started points: " + points(report.startedPoints()));
        System.out.println("Retrospective potential points: " + points(report.potentialPoints()));
        System.out.println("Potential-minus-started points gap: " + points(report.pointsGap()));
        System.out.println();
        System.out.println("Boundary: descriptive points-gap evidence only. Potential uses observed provider "
            + "configuration and is not reconstructed historical startability. The gap is not a manager-efficiency "
            + "score, percentage, rank, tier, recommendation, intent, fault, or skill attribution.");
    }

    private static Database initializedDatabase() throws SQLException {
        Database database = new Database(DATABASE_PATH);
        database.initialize();
        return database;
    }

    private static String points(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
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

    record Options(String leagueId, String teamId, int season, int week) {}
}
