package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.sleeper.SleeperLiveSeasonOperationalReadinessAudit;

import java.nio.file.Path;

/** Read-only BF-595 operator surface for 2026 live-season operational readiness. */
public final class ButlerSleeperLiveSeasonOperationalReadinessAuditCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerSleeperLiveSeasonOperationalReadinessAuditCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            Database database = new Database(DATABASE_PATH);
            database.initialize();
            print(new SleeperLiveSeasonOperationalReadinessAudit(database).audit(options.leagueId()));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (args == null || args.length != 1) {
            throw new IllegalArgumentException(
                "Usage: sleeperLiveSeasonOperationalReadinessAudit <butler-league-id>");
        }
        return new Options(requireText(args[0], "butler-league-id"));
    }

    static void print(SleeperLiveSeasonOperationalReadinessAudit.AuditReport report) {
        System.out.println("Sleeper 2026 live-season operational readiness audit");
        System.out.println("Policy: " + report.policyId());
        System.out.println("League: " + report.leagueName() + " [" + report.leagueId() + "]");
        System.out.println("Linked Sleeper league: " + report.sleeperLeagueId());
        System.out.println("Target/provider season: " + report.targetSeason() + "/" + report.providerSeason());
        System.out.println("Provider status: " + nullable(report.providerStatus()));
        System.out.println("Provider leg/week context: " + nullable(report.providerLeg()));
        System.out.println("Observed at UTC: " + report.observedAtUtc());
        System.out.println("Provider declared/returned rosters: "
            + report.providerDeclaredRosterCount() + "/" + report.providerRosterCount());
        System.out.println("Persisted Butler teams: " + report.persistedTeamCount());
        System.out.println("Provider roster positions: " + report.rosterPositionCount());
        System.out.println("Provider scoring settings: " + report.scoringSettingCount());
        System.out.println("Current roster entries: " + report.providerRosterEntries());
        System.out.println("Distinct current player identities: " + report.distinctCurrentPlayerIds());
        System.out.println("Exact Butler-mapped current players: " + report.exactMappedCurrentPlayerIds());
        System.out.println("Unmapped current players: " + report.unmappedCurrentPlayerIds());
        System.out.println("Unmapped examples: " + compact(report.unmappedPlayerExamples()));
        System.out.println("Provider roster ids without persisted teams: "
            + compact(report.providerRosterIdsMissingPersistedTeam()));
        System.out.println("Persisted team roster ids absent from provider: "
            + compact(report.persistedTeamRosterIdsMissingProvider()));
        System.out.println("Ownerless rosters: " + report.ownerlessRosters());
        System.out.println("Roster owner ids absent from provider users: " + compact(report.unknownOwnerIds()));
        System.out.println();

        System.out.println("Roster | owner | owner in provider users | players | starters");
        for (var roster : report.rosterOwners()) {
            System.out.println(roster.rosterId()
                + " | " + nullable(roster.ownerId())
                + " | " + roster.ownerPresentInUserList()
                + " | " + roster.playerCount()
                + " | " + roster.starterCount());
        }
        System.out.println();

        printCapability("Current-roster context", report.currentRosterContext());
        printCapability("Lineup-context prerequisites", report.lineupContextPrerequisites());
        printCapability("Waiver/free-agent inventory prerequisites", report.waiverFreeAgentInventoryPrerequisites());
        printCapability("Trade-context prerequisites", report.tradeContextPrerequisites());
        System.out.println();
        System.out.println("Boundary: read-only live evidence audit. This command does not import or mutate league, team, roster, player, lineup, waiver, or trade state; does not infer the user's team by name; does not fuzzy-match identities; does not change historical BF-518/BF-521 methodology or thresholds; and does not make start/sit, waiver, trade, manager, confidence, or recommendation judgments.");
    }

    private static void printCapability(
        String label,
        SleeperLiveSeasonOperationalReadinessAudit.CapabilityReadiness readiness) {
        System.out.println(label + ": " + readiness.state());
        for (String blocker : readiness.blockers()) {
            System.out.println("  - " + blocker);
        }
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
