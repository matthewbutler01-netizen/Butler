package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.sleeper.SleeperCurrentSeasonHydrationEligibilityAudit;

import java.nio.file.Path;

/** Read-only BF-599 operator surface for 2026 current-season hydration eligibility. */
public final class ButlerSleeperCurrentSeasonHydrationEligibilityAuditCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerSleeperCurrentSeasonHydrationEligibilityAuditCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            Database database = new Database(DATABASE_PATH);
            database.initialize();
            print(new SleeperCurrentSeasonHydrationEligibilityAudit(database).audit(options.leagueId()));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (args == null || args.length != 1) {
            throw new IllegalArgumentException(
                "Usage: sleeperCurrentSeasonHydrationEligibilityAudit <butler-league-id>");
        }
        return new Options(requireText(args[0], "butler-league-id"));
    }

    static void print(SleeperCurrentSeasonHydrationEligibilityAudit.EligibilityReport report) {
        System.out.println("Sleeper 2026 current-season hydration eligibility audit");
        System.out.println("Policy: " + report.policyId());
        System.out.println("League: " + report.leagueName() + " [" + report.leagueId() + "]");
        System.out.println("Linked Sleeper league: " + report.sleeperLeagueId());
        System.out.println("Provider season/status/leg: " + report.providerSeason()
            + "/" + nullable(report.providerStatus()) + "/" + nullable(report.providerLeg()));
        System.out.println("Provider declared/returned rosters: "
            + report.providerDeclaredRosterCount() + "/" + report.providerRosterCount());
        System.out.println("Persisted Butler teams: " + report.persistedTeamCount());
        System.out.println("Current roster entries: " + report.providerRosterEntries());
        System.out.println("Distinct current player identities: " + report.distinctCurrentPlayerIds());
        System.out.println("Already exact-mapped current players: " + report.exactMappedCurrentPlayerIds());
        System.out.println("Current player identities to bootstrap: " + report.unmappedCurrentPlayerIds());
        System.out.println("Bootstrap examples: " + compact(report.unmappedPlayerExamples()));
        System.out.println("Playerless roster ids: " + compact(report.playerlessRosterIds()));
        System.out.println("Starterless roster ids: " + compact(report.starterlessRosterIds()));
        System.out.println("Observed at UTC: " + report.observedAtUtc());
        System.out.println();
        System.out.println("Hydration eligibility: " + report.state());
        for (String blocker : report.blockers()) {
            System.out.println("  - " + blocker);
        }
        if (report.state() == SleeperCurrentSeasonHydrationEligibilityAudit.EligibilityState.READY_TO_HYDRATE
            && report.unmappedCurrentPlayerIds() > 0) {
            System.out.println("  - Unmapped current player identities are expected bootstrap work, not an eligibility blocker.");
        }
        System.out.println();
        System.out.println("Boundary: BF-599 is read-only. It does not invoke the Sleeper importer, persist roster/player state, infer a waiver universe, change historical methodology, tune thresholds/confidence, rank managers, or make recommendations.");
    }

    private static String compact(java.util.List<?> values) {
        return values.isEmpty() ? "none" : values.toString();
    }

    private static String nullable(Object value) {
        return value == null || value.toString().isBlank() ? "none" : value.toString();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    record Options(String leagueId) {}
}
