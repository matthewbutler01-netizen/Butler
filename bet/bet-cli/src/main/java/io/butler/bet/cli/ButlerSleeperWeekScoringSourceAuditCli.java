package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.sleeper.SleeperWeekScoringSourceAudit;

import java.nio.file.Path;

/** Read-only BF-558 operator surface for proving Sleeper weekly raw-stat scoring parity. */
public final class ButlerSleeperWeekScoringSourceAuditCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerSleeperWeekScoringSourceAuditCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            Database database = new Database(DATABASE_PATH);
            database.initialize();
            print(new SleeperWeekScoringSourceAudit(database).audit(
                options.leagueId(), options.season(), options.week()));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (args == null || args.length != 3) {
            throw new IllegalArgumentException(
                "Usage: sleeperWeekScoringSourceAudit <butler-league-id> <season> <week>");
        }
        String leagueId = requireText(args[0], "butler-league-id");
        int season = parseInt(args[1], "season");
        int week = parseInt(args[2], "week");
        if (season < 1999 || season > 2100) {
            throw new IllegalArgumentException("season must be between 1999 and 2100");
        }
        if (week <= 0 || week > 25) throw new IllegalArgumentException("week must be between 1 and 25");
        return new Options(leagueId, season, week);
    }

    static void print(SleeperWeekScoringSourceAudit.AuditReport report) {
        System.out.println("Sleeper weekly scoring source audit");
        System.out.println("Policy: " + report.policyId());
        System.out.println("League: " + report.leagueName() + " [" + report.leagueId() + "]");
        System.out.println("Season/week: " + report.season() + "/" + report.week());
        System.out.println("Historical Sleeper league: " + report.sleeperLeagueId());
        System.out.println("League configuration as-of: " + report.configurationAsOf());
        System.out.println("Stats source: " + report.statsSourceUri());
        System.out.println("Observed lineup slots: " + report.lineupSlots());
        System.out.println("Nonzero league scoring keys: " + report.nonzeroScoringKeys().size());
        System.out.println("Stats payload identities: " + report.payloadIdentityCount());
        System.out.println("Stats payload numeric keys: " + report.payloadNumericKeys().size());
        System.out.println("Globally absent nonzero scoring keys: "
            + (report.globallyAbsentNonzeroScoringKeys().isEmpty()
                ? "none" : report.globallyAbsentNonzeroScoringKeys()));
        System.out.println("Observed unique starters: " + report.starters().size());
        System.out.println("Audit state: " + report.state());
        if (!report.blockers().isEmpty()) {
            System.out.println("Proof blockers:");
            report.blockers().forEach(blocker -> System.out.println("  - " + blocker));
        }
        System.out.println();
        System.out.println("Starter identity | DEF identity | state | missing nonzero keys | known-key dot product | Sleeper players_points | known-dot-product match");
        for (var starter : report.starters()) {
            System.out.println(starter.playerId()
                + " | " + starter.defenseIdentity()
                + " | " + starter.state()
                + " | " + (starter.missingNonzeroScoringKeys().isEmpty() ? "none" : starter.missingNonzeroScoringKeys())
                + " | " + decimal(starter.knownKeyDotProduct())
                + " | " + decimal(starter.providerPoints())
                + " | " + starter.knownKeyDotProductMatchesProvider());
        }
        System.out.println();
        System.out.println("Boundary: read-only evidence audit. Absent raw-stat keys are never inferred as zero. "
            + "This command does not persist Sleeper stats, expand scoring support, add K/DEF eligibility, "
            + "alter lineup capture, widen BF-518/BF-521 readiness, tune thresholds, rank managers, or make recommendations.");
    }

    private static String decimal(java.math.BigDecimal value) {
        return value == null ? "unavailable" : value.stripTrailingZeros().toPlainString();
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

    record Options(String leagueId, int season, int week) {}
}
