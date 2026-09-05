package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.SQLException;

/** Exposes descriptive team-season lineup points-gap evidence without manager attribution. */
public final class ButlerLeagueTeamSeasonLineupPointsGapEvidenceCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");
    private static final String COMMAND = "team-season-lineup-points-gap-evidence";

    private ButlerLeagueTeamSeasonLineupPointsGapEvidenceCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            print(new LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer(initializedDatabase()).analyze(
                options.leagueId(), options.teamId(), options.season()));
        } catch (SQLException e) {
            System.err.println("Database error while building team-season lineup points-gap evidence: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (!isCommand(args) || args.length != 5) {
            throw new IllegalArgumentException(
                "Usage: butler league team-season-lineup-points-gap-evidence <league-id> <team-id> <season>");
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

    static void print(LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.SeasonEvidenceReport report) {
        System.out.println("Team-season lineup points-gap evidence");
        System.out.println("League: " + report.leagueId());
        System.out.println("Team: " + report.teamId());
        System.out.println("Season: " + report.season());
        System.out.println("Metric scope: " + report.metricScope());
        System.out.println("Week universe: " + report.weekUniverse());
        System.out.println("Aggregate policy: " + report.aggregatePolicy());
        System.out.println("Policy: " + report.policyId());
        System.out.println("Source potential-season policy: " + report.sourcePotentialSeasonPolicyId());
        System.out.println("Started-lineup policy: " + report.startedLineupPolicyId());
        System.out.println("Points-gap policy: " + report.pointsGapPolicyId());
        System.out.println();
        System.out.println("Observed week evidence:");
        if (report.weeks().isEmpty()) {
            System.out.println("  none");
        }
        for (var week : report.weeks()) {
            System.out.println("  Week " + week.week() + " | " + week.state()
                + " | roster as-of " + week.enumeratedRosterEvidenceAsOf());
            switch (week.state()) {
                case BLOCKED -> {
                    for (String blocker : week.blockers()) {
                        System.out.println("    blocker: " + blocker);
                    }
                    System.out.println("    aggregate eligibility: excluded because comparison is blocked");
                }
                case POTENTIAL_INCOMPLETE -> {
                    var potential = week.sourcePotentialWeek().potentialLineup();
                    System.out.println("    potential filled starter slots: " + potential.lineup().filledSlots()
                        + "/" + potential.lineup().startingSlots());
                    System.out.println("    potential points: " + points(potential.lineup().totalPoints()));
                    System.out.println("    aggregate eligibility: excluded because potential lineup is incomplete");
                }
                case STARTED_INCOMPLETE -> {
                    var started = week.startedLineup();
                    System.out.println("    potential points: "
                        + points(week.sourcePotentialWeek().potentialLineup().lineup().totalPoints()));
                    System.out.println("    started filled slots: " + started.filledSlots() + "/" + started.requiredSlots());
                    System.out.println("    recalculated started points: " + points(started.totalStartedPoints()));
                    System.out.println("    aggregate eligibility: excluded because observed started lineup is incomplete");
                }
                case COMPARABLE_COMPLETE -> {
                    var gap = week.pointsGap();
                    System.out.println("    league configuration as-of: " + gap.leagueConfigurationAsOf());
                    System.out.println("    production coverage as-of: " + gap.productionCoverageAsOf());
                    System.out.println("    production source: " + gap.productionSourceUri());
                    System.out.println("    recalculated started points: " + points(gap.startedPoints()));
                    System.out.println("    retrospective potential points: " + points(gap.potentialPoints()));
                    System.out.println("    potential-minus-started points gap: " + points(gap.pointsGap()));
                    System.out.println("    aggregate eligibility: included");
                }
            }
        }

        var aggregate = report.aggregate();
        System.out.println();
        System.out.println("Season aggregate over observed roster weeks:");
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
                + " comparable complete observed week(s) out of " + aggregate.observedWeeks() + " observed week(s)");
        } else {
            System.out.println("  comparable total started points: unavailable");
            System.out.println("  comparable total potential points: unavailable");
            System.out.println("  comparable total potential-minus-started gap: unavailable");
            System.out.println("  aggregate denominator: 0 comparable complete observed weeks out of "
                + aggregate.observedWeeks() + " observed week(s)");
        }
        System.out.println();
        System.out.println("Boundary: raw descriptive totals over comparable complete observed weeks only. "
            + "Unobserved, blocked, and incomplete weeks are not normalized away. Potential uses observed provider "
            + "configuration and is not reconstructed historical startability. No average gap, efficiency percentage, "
            + "manager score, rank, tier, recommendation, intent, fault, or skill attribution is computed.");
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

    record Options(String leagueId, String teamId, int season) {}
}
