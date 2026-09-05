package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.LeagueTeamWeekPotentialLineupAnalyzer;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.SQLException;

/** Exposes governed retrospective team-week potential-lineup evidence without ranking or recommendations. */
public final class ButlerLeagueTeamWeekPotentialLineupCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");
    private static final String COMMAND = "team-week-potential-lineup";

    private ButlerLeagueTeamWeekPotentialLineupCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            print(new LeagueTeamWeekPotentialLineupAnalyzer(initializedDatabase()).analyze(
                options.leagueId(), options.teamId(), options.season(), options.week()));
        } catch (SQLException e) {
            System.err.println("Database error while building team-week potential lineup: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (!isCommand(args) || args.length != 6) {
            throw new IllegalArgumentException(
                "Usage: butler league team-week-potential-lineup <league-id> <team-id> <season> <week>");
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

    static void print(LeagueTeamWeekPotentialLineupAnalyzer.PotentialLineupReport report) {
        System.out.println("Team-week potential lineup");
        System.out.println("League: " + report.leagueId());
        System.out.println("Team: " + report.teamId());
        System.out.println("Season/week: " + report.season() + "/" + report.week());
        System.out.println("Metric scope: " + report.metricScope());
        System.out.println("Interpretation: retrospective potential using observed provider configuration; "
            + "not reconstructed historical startability.");
        System.out.println();
        System.out.println("Policies:");
        System.out.println("  calculation: " + report.policyId());
        System.out.println("  coverage: " + report.coveragePolicyId());
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
        System.out.println("Player score evidence:");
        for (var player : report.playerScores()) {
            System.out.println("  Sleeper " + player.providerPlayerId() + " -> Butler " + player.playerId());
            System.out.println("    eligibility as-of: " + player.eligibilityObservationAsOf());
            System.out.println("    fantasy positions: " + player.providerFantasyPositions());
            System.out.println("    production state: " + player.productionState());
            System.out.println("    production coverage as-of: " + player.productionCoverageAsOf());
            if (player.productionId() == null) {
                System.out.println("    production id: none (identity-covered zero)");
                System.out.println("    scoring policy: none (zero authorized by coverage evidence)");
            } else {
                System.out.println("    production id: " + player.productionId());
                System.out.println("    scoring policy: " + player.scoringPolicyId());
            }
            System.out.println("    points: " + points(player.fantasyPoints()));
        }
        System.out.println();
        System.out.println("Potential starting lineup:");
        for (var assignment : report.lineup().assignments()) {
            if (assignment.filled()) {
                System.out.println("  #" + assignment.slotOrdinal() + " " + assignment.slot()
                    + " -> " + assignment.playerId() + " | " + points(assignment.fantasyPoints()));
            } else {
                System.out.println("  #" + assignment.slotOrdinal() + " " + assignment.slot() + " -> UNFILLED");
            }
        }
        System.out.println("Filled starter slots: " + report.lineup().filledSlots()
            + "/" + report.lineup().startingSlots());
        System.out.println("Complete legal lineup: " + report.lineup().complete());
        System.out.println("Total potential points: " + points(report.lineup().totalPoints()));
        System.out.println();
        System.out.println("Boundary: potential lineup only; not actual historical startability, "
            + "not a ranking, and not a recommendation.");
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
