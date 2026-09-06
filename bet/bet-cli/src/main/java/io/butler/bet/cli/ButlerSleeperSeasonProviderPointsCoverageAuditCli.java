package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.sleeper.SleeperSeasonProviderPointsCoverageAudit;

import java.nio.file.Path;

/** Read-only BF-559 operator surface for roster-wide historical provider-points coverage. */
public final class ButlerSleeperSeasonProviderPointsCoverageAuditCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerSleeperSeasonProviderPointsCoverageAuditCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            Database database = new Database(DATABASE_PATH);
            database.initialize();
            print(new SleeperSeasonProviderPointsCoverageAudit(database)
                .audit(options.leagueId(), options.season()));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (args == null || args.length != 2) {
            throw new IllegalArgumentException(
                "Usage: sleeperSeasonProviderPointsCoverageAudit <butler-league-id> <season>");
        }
        String leagueId = requireText(args[0], "butler-league-id");
        int season = parseInt(args[1], "season");
        if (season < 1999 || season > 2100) {
            throw new IllegalArgumentException("season must be between 1999 and 2100");
        }
        return new Options(leagueId, season);
    }

    static void print(SleeperSeasonProviderPointsCoverageAudit.AuditReport report) {
        System.out.println("Sleeper season provider-points coverage audit");
        System.out.println("Policy: " + report.policyId());
        System.out.println("League: " + report.leagueName() + " [" + report.leagueId() + "]");
        System.out.println("Season: " + report.season());
        System.out.println("Historical Sleeper league: " + report.sleeperLeagueId());
        System.out.println("Week scan: " + report.firstWeek() + "-" + report.lastWeek());
        System.out.println("Populated weeks: " + report.populatedWeeks());
        System.out.println("Roster player points coverage: " + report.rosterPlayerPointsPresent()
            + "/" + report.rosterPlayerObservations());
        System.out.println("Starter points coverage: " + report.starterPointsPresent()
            + "/" + report.starterObservations());
        System.out.println("Observed DEF identities: "
            + (report.defenseIdentities().isEmpty() ? "none" : report.defenseIdentities()));
        System.out.println("Audit state: " + report.state());
        if (!report.blockers().isEmpty()) {
            System.out.println("Proof blockers:");
            report.blockers().forEach(blocker -> System.out.println("  - " + blocker));
        }
        System.out.println();
        System.out.println("Week | populated | roster points | starter points | DEF ids | missing roster points | missing starter points | starter not in players | duplicate roster ids | complete");
        for (var week : report.weeks()) {
            System.out.println(week.week()
                + " | " + week.populated()
                + " | " + week.rosterPlayerPointsPresent() + "/" + week.rosterPlayerObservations()
                + " | " + week.starterPointsPresent() + "/" + week.starterObservations()
                + " | " + compact(week.defenseIdentities())
                + " | " + compact(week.missingRosterPointIdentities())
                + " | " + compact(week.missingStarterPointIdentities())
                + " | " + compact(week.starterNotInPlayersIdentities())
                + " | " + compact(week.duplicateRosterIdentities())
                + " | " + week.complete());
        }
        System.out.println();
        System.out.println("Boundary: read-only provider-points coverage evidence. This audit does not infer sparse raw-stat keys as zero, persist provider points, replace Butler exact scoring, add K/DEF lineup eligibility, alter lineup capture, widen BF-518/BF-521 readiness, tune thresholds, rank managers, or make recommendations.");
    }

    private static String compact(java.util.List<String> values) {
        return values.isEmpty() ? "none" : values.toString();
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
