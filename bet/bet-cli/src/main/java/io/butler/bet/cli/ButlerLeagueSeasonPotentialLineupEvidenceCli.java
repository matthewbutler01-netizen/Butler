package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.LeagueSeasonPotentialLineupEvidenceAnalyzer;
import io.butler.bet.intelligence.LeagueTeamSeasonPotentialLineupEvidenceAnalyzer;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.SQLException;

/** Exposes league-wide team season potential-lineup evidence without cross-team ranking or aggregation. */
public final class ButlerLeagueSeasonPotentialLineupEvidenceCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");
    private static final String COMMAND = "season-potential-lineup-evidence";

    private ButlerLeagueSeasonPotentialLineupEvidenceCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            print(new LeagueSeasonPotentialLineupEvidenceAnalyzer(initializedDatabase()).analyze(
                options.leagueId(), options.season()));
        } catch (SQLException e) {
            System.err.println("Database error while building league season potential-lineup evidence: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (!isCommand(args) || args.length != 4) {
            throw new IllegalArgumentException(
                "Usage: butler league season-potential-lineup-evidence <league-id> <season>");
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

    static void print(LeagueSeasonPotentialLineupEvidenceAnalyzer.LeagueEvidenceReport report) {
        System.out.println("League season potential-lineup evidence");
        System.out.println("League: " + report.leagueName() + " [" + report.leagueId() + "]");
        System.out.println("Season: " + report.season());
        System.out.println("Metric scope: " + report.metricScope());
        System.out.println("Week universe: " + report.weekUniverse());
        System.out.println("Policy: " + report.policyId());
        System.out.println("Team-season policy: " + report.teamSeasonPolicyId());
        System.out.println("Team order: repository team-name order; never score-ranked.");
        System.out.println();

        if (report.teams().isEmpty()) {
            System.out.println("Teams: none");
        }
        for (var team : report.teams()) {
            var season = team.seasonEvidence();
            var aggregate = season.aggregate();
            System.out.println(team.teamName() + " [" + team.teamId() + "]");
            System.out.println("  observed weeks: " + aggregate.observedWeeks());
            System.out.println("  qualifying complete weeks: " + aggregate.qualifyingCompleteWeeks());
            System.out.println("  incomplete lineup weeks: " + aggregate.incompleteLineupWeeks());
            System.out.println("  blocked weeks: " + aggregate.blockedWeeks());
            if (aggregate.qualifyingTotalPotentialPoints().isPresent()) {
                System.out.println("  qualifying total potential points: "
                    + points(aggregate.qualifyingTotalPotentialPoints().orElseThrow()));
                System.out.println("  qualifying average potential points: "
                    + points(aggregate.qualifyingAveragePotentialPoints().orElseThrow()));
                System.out.println("  aggregate denominator: " + aggregate.qualifyingCompleteWeeks()
                    + " qualifying complete observed week(s)");
            } else {
                System.out.println("  qualifying total potential points: unavailable");
                System.out.println("  qualifying average potential points: unavailable");
                System.out.println("  aggregate denominator: 0 qualifying complete observed weeks");
            }
            for (var week : season.weeks()) {
                if (week.state() == LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.WeekState.BLOCKED) {
                    System.out.println("  week " + week.week() + " BLOCKED");
                    for (String blocker : week.blockers()) {
                        System.out.println("    blocker: " + blocker);
                    }
                } else if (week.state()
                    == LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.WeekState.INCOMPLETE_LINEUP) {
                    System.out.println("  week " + week.week() + " INCOMPLETE_LINEUP"
                        + " | excluded from aggregate | potential points "
                        + points(week.potentialLineup().lineup().totalPoints()));
                }
            }
            System.out.println();
        }

        System.out.println("Boundary: teams are not ranked, no cross-team points aggregate or comparison is computed, "
            + "and differing team coverage denominators remain separate. Potential lineups are not reconstructed "
            + "historical startability and are not recommendations.");
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

    record Options(String leagueId, int season) {}
}
