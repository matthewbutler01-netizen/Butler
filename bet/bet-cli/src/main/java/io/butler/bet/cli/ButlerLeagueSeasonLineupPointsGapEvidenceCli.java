package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.LeagueSeasonLineupPointsGapEvidenceAnalyzer;
import io.butler.bet.intelligence.LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.SQLException;

/** Exposes league-wide team season points-gap evidence without cross-team ranking or aggregation. */
public final class ButlerLeagueSeasonLineupPointsGapEvidenceCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");
    private static final String COMMAND = "season-lineup-points-gap-evidence";

    private ButlerLeagueSeasonLineupPointsGapEvidenceCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            print(new LeagueSeasonLineupPointsGapEvidenceAnalyzer(initializedDatabase()).analyze(
                options.leagueId(), options.season()));
        } catch (SQLException e) {
            System.err.println("Database error while building league season lineup points-gap evidence: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (!isCommand(args) || args.length != 4) {
            throw new IllegalArgumentException(
                "Usage: butler league season-lineup-points-gap-evidence <league-id> <season>");
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

    static void print(LeagueSeasonLineupPointsGapEvidenceAnalyzer.LeagueEvidenceReport report) {
        System.out.println("League season lineup points-gap evidence");
        System.out.println("League: " + report.leagueName() + " [" + report.leagueId() + "]");
        System.out.println("Season: " + report.season());
        System.out.println("Metric scope: " + report.metricScope());
        System.out.println("Week universe: " + report.weekUniverse());
        System.out.println("Presentation scope: " + report.presentationScope());
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
            System.out.println("  comparable complete weeks: " + aggregate.comparableCompleteWeeks());
            System.out.println("  potential-incomplete weeks: " + aggregate.potentialIncompleteWeeks());
            System.out.println("  started-incomplete weeks: " + aggregate.startedIncompleteWeeks());
            System.out.println("  blocked weeks: " + aggregate.blockedWeeks());
            if (aggregate.comparableTotalPointsGap().isPresent()) {
                System.out.println("  comparable total started points: "
                    + points(aggregate.comparableTotalStartedPoints().orElseThrow()));
                System.out.println("  comparable total potential points: "
                    + points(aggregate.comparableTotalPotentialPoints().orElseThrow()));
                System.out.println("  comparable total potential-minus-started gap: "
                    + points(aggregate.comparableTotalPointsGap().orElseThrow()));
                System.out.println("  aggregate denominator: " + aggregate.comparableCompleteWeeks()
                    + " comparable complete observed week(s) out of " + aggregate.observedWeeks()
                    + " observed week(s)");
            } else {
                System.out.println("  comparable total started points: unavailable");
                System.out.println("  comparable total potential points: unavailable");
                System.out.println("  comparable total potential-minus-started gap: unavailable");
                System.out.println("  aggregate denominator: 0 comparable complete observed weeks out of "
                    + aggregate.observedWeeks() + " observed week(s)");
            }
            for (var week : season.weeks()) {
                switch (week.state()) {
                    case BLOCKED -> {
                        System.out.println("  week " + week.week() + " BLOCKED");
                        for (String blocker : week.blockers()) {
                            System.out.println("    blocker: " + blocker);
                        }
                    }
                    case POTENTIAL_INCOMPLETE -> System.out.println(
                        "  week " + week.week() + " POTENTIAL_INCOMPLETE | excluded from comparable totals");
                    case STARTED_INCOMPLETE -> System.out.println(
                        "  week " + week.week() + " STARTED_INCOMPLETE | excluded from comparable totals");
                    case COMPARABLE_COMPLETE -> { }
                }
            }
            System.out.println();
        }

        System.out.println("Boundary: teams are not ranked and no cross-team started-points, potential-points, "
            + "points-gap total, average, normalized percentage, or comparison score is computed. Differing team "
            + "coverage denominators remain separate. Potential uses observed provider configuration and is not "
            + "reconstructed historical startability. No manager-efficiency score, tier, recommendation, intent, "
            + "fault, or skill attribution is computed.");
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
