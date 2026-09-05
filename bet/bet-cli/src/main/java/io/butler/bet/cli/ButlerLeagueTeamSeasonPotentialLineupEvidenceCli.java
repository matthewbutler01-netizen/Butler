package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.LeagueTeamSeasonPotentialLineupEvidenceAnalyzer;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.SQLException;

/** Exposes non-ranked team-season potential-lineup evidence across only observed roster weeks. */
public final class ButlerLeagueTeamSeasonPotentialLineupEvidenceCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");
    private static final String COMMAND = "team-season-potential-lineup-evidence";

    private ButlerLeagueTeamSeasonPotentialLineupEvidenceCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            print(new LeagueTeamSeasonPotentialLineupEvidenceAnalyzer(initializedDatabase()).analyze(
                options.leagueId(), options.teamId(), options.season()));
        } catch (SQLException e) {
            System.err.println("Database error while building team-season potential-lineup evidence: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (!isCommand(args) || args.length != 5) {
            throw new IllegalArgumentException(
                "Usage: butler league team-season-potential-lineup-evidence <league-id> <team-id> <season>");
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

    static void print(LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.SeasonEvidenceReport report) {
        System.out.println("Team-season potential-lineup evidence");
        System.out.println("League: " + report.leagueId());
        System.out.println("Team: " + report.teamId());
        System.out.println("Season: " + report.season());
        System.out.println("Metric scope: " + report.metricScope());
        System.out.println("Week universe: " + report.weekUniverse());
        System.out.println("Average policy: " + report.averagePolicy());
        System.out.println("Policy: " + report.policyId());
        System.out.println("Coverage policy: " + report.coveragePolicyId());
        System.out.println("Potential-lineup policy: " + report.potentialLineupPolicyId());
        System.out.println();
        System.out.println("Observed week evidence:");
        if (report.weeks().isEmpty()) {
            System.out.println("  none");
        }
        for (var week : report.weeks()) {
            System.out.println("  Week " + week.week() + " | " + week.state()
                + " | roster as-of " + week.enumeratedRosterEvidenceAsOf());
            if (week.state() == LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.WeekState.BLOCKED) {
                for (String blocker : week.blockers()) {
                    System.out.println("    blocker: " + blocker);
                }
                if (week.coverage().productionCoverageAsOf() != null) {
                    System.out.println("    production coverage as-of: " + week.coverage().productionCoverageAsOf());
                }
                if (week.coverage().productionSourceUri() != null) {
                    System.out.println("    production source: " + week.coverage().productionSourceUri());
                }
            } else {
                var potential = week.potentialLineup();
                System.out.println("    league configuration as-of: " + potential.leagueConfigurationAsOf());
                System.out.println("    production coverage as-of: " + potential.productionCoverageAsOf());
                System.out.println("    production source: " + potential.productionSourceUri());
                System.out.println("    filled starter slots: " + potential.lineup().filledSlots()
                    + "/" + potential.lineup().startingSlots());
                System.out.println("    complete legal lineup: " + potential.lineup().complete());
                System.out.println("    potential points: " + points(potential.lineup().totalPoints()));
                if (week.state() == LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.WeekState.INCOMPLETE_LINEUP) {
                    System.out.println("    aggregate eligibility: excluded because lineup is incomplete");
                } else {
                    System.out.println("    aggregate eligibility: included");
                }
            }
        }

        var aggregate = report.aggregate();
        System.out.println();
        System.out.println("Season aggregate over observed roster weeks:");
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
        System.out.println();
        System.out.println("Boundary: observed Sleeper roster weeks only; unobserved weeks are not treated as covered. "
            + "Potential lineups are not reconstructed historical startability, not rankings, and not recommendations.");
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
