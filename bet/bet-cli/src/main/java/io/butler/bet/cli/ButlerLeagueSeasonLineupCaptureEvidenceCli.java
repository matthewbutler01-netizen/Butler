package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.LeagueSeasonLineupCaptureEvidenceAnalyzer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.sql.SQLException;

/** Exposes team-by-team league-season lineup capture without cross-team ranking or aggregation. */
public final class ButlerLeagueSeasonLineupCaptureEvidenceCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");
    private static final String COMMAND = "season-lineup-capture-evidence";

    private ButlerLeagueSeasonLineupCaptureEvidenceCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            print(new LeagueSeasonLineupCaptureEvidenceAnalyzer(initializedDatabase()).analyze(
                options.leagueId(), options.season()));
        } catch (SQLException e) {
            System.err.println("Database error while building league season lineup capture evidence: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (!isCommand(args) || args.length != 4) {
            throw new IllegalArgumentException(
                "Usage: butler league season-lineup-capture-evidence <league-id> <season>");
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

    static void print(LeagueSeasonLineupCaptureEvidenceAnalyzer.LeagueEvidenceReport report) {
        System.out.println("League season lineup capture evidence");
        System.out.println("League: " + report.leagueName() + " [" + report.leagueId() + "]");
        System.out.println("Season: " + report.season());
        System.out.println("Metric scope: " + report.metricScope());
        System.out.println("Week universe: " + report.weekUniverse());
        System.out.println("Presentation scope: " + report.presentationScope());
        System.out.println("Policy: " + report.policyId());
        System.out.println("Team-season policy: " + report.teamSeasonPolicyId());
        System.out.println("Team order: repository team-name order; never capture-rate-ranked.");
        System.out.println();

        if (report.teams().isEmpty()) {
            System.out.println("Teams: none");
        }
        for (var team : report.teams()) {
            var capture = team.seasonEvidence();
            var season = capture.sourceSeasonPointsGap();
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
            } else {
                System.out.println("  comparable total started points: unavailable");
                System.out.println("  comparable total potential points: unavailable");
                System.out.println("  comparable total potential-minus-started gap: unavailable");
            }
            System.out.println("  coverage denominator: " + aggregate.comparableCompleteWeeks()
                + " comparable complete observed week(s) out of " + aggregate.observedWeeks() + " observed week(s)");
            System.out.println("  lineup capture rate state: " + capture.rateState());
            if (capture.lineupCaptureRate().isPresent()) {
                BigDecimal rate = capture.lineupCaptureRate().orElseThrow();
                System.out.println("  lineup capture rate: " + rate.toPlainString());
                System.out.println("  lineup capture percentage: " + percentage(rate));
            } else {
                System.out.println("  lineup capture rate: unavailable (" + unavailableReason(capture.rateState()) + ")");
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

        System.out.println("Boundary: teams remain in repository team-name order and are not ranked by lineup capture. "
            + "Butler does not average team capture rates, combine team numerators/denominators, assign league capture "
            + "scores, or compare managers. Each team's coverage denominator remains separate. Potential uses observed "
            + "provider configuration and is not reconstructed historical startability. Lineup capture is descriptive "
            + "evidence only, not manager efficiency, a manager grade, rank, tier, recommendation, intent, fault, or "
            + "skill attribution.");
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

    private static String unavailableReason(
        io.butler.bet.intelligence.LeagueTeamSeasonLineupCaptureEvidenceAnalyzer.CaptureRateState state) {
        return switch (state) {
            case UNAVAILABLE_NO_COMPARABLE_WEEKS -> "no comparable complete observed weeks";
            case UNAVAILABLE_ZERO_TOTAL_POTENTIAL -> "comparable total retrospective potential points are zero";
            case UNAVAILABLE_NEGATIVE_COMPARABLE_POINTS -> "a comparable week has negative started or potential points";
            case AVAILABLE -> throw new IllegalArgumentException("available capture state requires a rate");
        };
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
