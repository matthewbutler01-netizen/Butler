package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.LeagueTeamSeasonLineupCaptureEvidenceAnalyzer;
import io.butler.bet.intelligence.LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.sql.SQLException;

/** Exposes governed descriptive team-season lineup capture evidence without manager attribution. */
public final class ButlerLeagueTeamSeasonLineupCaptureEvidenceCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");
    private static final String COMMAND = "team-season-lineup-capture-evidence";

    private ButlerLeagueTeamSeasonLineupCaptureEvidenceCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            print(new LeagueTeamSeasonLineupCaptureEvidenceAnalyzer(initializedDatabase()).analyze(
                options.leagueId(), options.teamId(), options.season()));
        } catch (SQLException e) {
            System.err.println("Database error while building team-season lineup capture evidence: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (!isCommand(args) || args.length != 5) {
            throw new IllegalArgumentException(
                "Usage: butler league team-season-lineup-capture-evidence <league-id> <team-id> <season>");
        }
        String leagueId = requireText(args[2], "league-id");
        String teamId = requireText(args[3], "team-id");
        int season = parseInt(args[4], "season");
        if (season < 1999 || season > 2100) {
            throw new IllegalArgumentException("season must be between 1999 and 2100");
        }
        return new Options(leagueId, teamId, season);
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && "league".equalsIgnoreCase(args[0])
            && COMMAND.equalsIgnoreCase(args[1]);
    }

    static void print(LeagueTeamSeasonLineupCaptureEvidenceAnalyzer.SeasonLineupCaptureReport report) {
        var source = report.sourceSeasonPointsGap();
        System.out.println("Team-season lineup capture evidence");
        System.out.println("League: " + source.leagueId());
        System.out.println("Team: " + source.teamId());
        System.out.println("Season: " + source.season());
        System.out.println("Metric scope: " + report.metricScope());
        System.out.println("Policy: " + report.policyId());
        System.out.println("Source points-gap policy: " + source.policyId());
        System.out.println("Week universe: " + source.weekUniverse());
        System.out.println("Source aggregate policy: " + source.aggregatePolicy());
        System.out.println();
        System.out.println("Observed week evidence:");
        if (source.weeks().isEmpty()) {
            System.out.println("  none");
        }
        for (var week : source.weeks()) {
            System.out.println("  Week " + week.week() + " | " + week.state()
                + " | roster as-of " + week.enumeratedRosterEvidenceAsOf());
            switch (week.state()) {
                case BLOCKED -> {
                    for (String blocker : week.blockers()) {
                        System.out.println("    blocker: " + blocker);
                    }
                    System.out.println("    capture eligibility: excluded because comparison is blocked");
                }
                case POTENTIAL_INCOMPLETE -> {
                    var potential = week.sourcePotentialWeek().potentialLineup();
                    System.out.println("    potential filled starter slots: " + potential.lineup().filledSlots()
                        + "/" + potential.lineup().startingSlots());
                    System.out.println("    potential points: " + points(potential.lineup().totalPoints()));
                    System.out.println("    capture eligibility: excluded because potential lineup is incomplete");
                }
                case STARTED_INCOMPLETE -> {
                    var started = week.startedLineup();
                    System.out.println("    potential points: "
                        + points(week.sourcePotentialWeek().potentialLineup().lineup().totalPoints()));
                    System.out.println("    started filled slots: " + started.filledSlots() + "/" + started.requiredSlots());
                    System.out.println("    recalculated started points: " + points(started.totalStartedPoints()));
                    System.out.println("    capture eligibility: excluded because observed started lineup is incomplete");
                }
                case COMPARABLE_COMPLETE -> printComparableWeek(week);
            }
        }

        var aggregate = source.aggregate();
        System.out.println();
        System.out.println("Season capture source totals over observed roster weeks:");
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
        System.out.println("Lineup capture rate state: " + report.rateState());
        if (report.lineupCaptureRate().isPresent()) {
            BigDecimal rate = report.lineupCaptureRate().orElseThrow();
            System.out.println("Lineup capture rate: " + rate.toPlainString());
            System.out.println("Lineup capture percentage: " + percentage(rate));
        } else {
            System.out.println("Lineup capture rate: unavailable (" + unavailableReason(report.rateState()) + ")");
        }
        System.out.println();
        System.out.println("Boundary: descriptive lineup capture evidence only. The season rate is comparable total "
            + "recalculated started points divided by comparable total retrospective potential points; it is not an "
            + "average of weekly percentages. Coverage remains a separate explicit denominator, and blocked, incomplete, "
            + "and unobserved weeks are not normalized away. Potential uses observed provider configuration and is not "
            + "reconstructed historical startability. This is not manager efficiency, a manager grade, rank, tier, "
            + "recommendation, intent, fault, or skill attribution.");
    }

    private static void printComparableWeek(LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.WeekEvidence week) {
        var gap = week.pointsGap();
        System.out.println("    league configuration as-of: " + gap.leagueConfigurationAsOf());
        System.out.println("    production coverage as-of: " + gap.productionCoverageAsOf());
        System.out.println("    production source: " + gap.productionSourceUri());
        System.out.println("    recalculated started points: " + points(gap.startedPoints()));
        System.out.println("    retrospective potential points: " + points(gap.potentialPoints()));
        System.out.println("    potential-minus-started points gap: " + points(gap.pointsGap()));
        System.out.println("    capture eligibility: included in governed season totals");
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

    private static String unavailableReason(LeagueTeamSeasonLineupCaptureEvidenceAnalyzer.CaptureRateState state) {
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

    record Options(String leagueId, String teamId, int season) {}
}
