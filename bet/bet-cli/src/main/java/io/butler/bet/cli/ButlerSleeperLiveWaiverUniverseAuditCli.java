package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.sleeper.SleeperLiveWaiverUniverseAudit;

import java.nio.file.Path;

/** BF-601 operator surface for read-only live waiver/free-agent universe proof. */
public final class ButlerSleeperLiveWaiverUniverseAuditCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerSleeperLiveWaiverUniverseAuditCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            Database database = new Database(DATABASE_PATH);
            database.initialize();
            print(new SleeperLiveWaiverUniverseAudit(database).audit(options.leagueId()));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (args == null || args.length != 1) {
            throw new IllegalArgumentException("Usage: sleeperLiveWaiverUniverseAudit <butler-league-id>");
        }
        return new Options(requireText(args[0], "butler-league-id"));
    }

    static void print(SleeperLiveWaiverUniverseAudit.AuditReport report) {
        System.out.println("Sleeper 2026 live waiver/free-agent universe audit");
        System.out.println("Policy: " + report.policyId());
        System.out.println("Active-player source: " + report.activePlayerSource());
        System.out.println("League: " + report.leagueName() + " [" + report.leagueId() + "]");
        System.out.println("Linked Sleeper league: " + report.sleeperLeagueId());
        System.out.println("Provider season/status/leg: "
            + report.providerSeason() + "/" + nullable(report.providerStatus()) + "/" + nullable(report.providerLeg()));
        System.out.println("Provider rosters: " + report.providerRosterCount());
        System.out.println("Current roster player identities: " + report.currentRosterPlayerIdentities());
        System.out.println("Active Sleeper player identities: " + report.activePlayerIdentities());
        System.out.println("Active identities currently rostered: " + report.activeRosteredPlayerIdentities());
        System.out.println("Rostered identities absent from active source: "
            + compact(report.rosteredPlayerIdsAbsentFromActiveSource()));
        System.out.println("Derived free-agent identities: " + report.freeAgentIdentities());
        System.out.println("Exact Butler-mapped free agents: " + report.exactButlerMappedFreeAgents());
        System.out.println("Unmapped free agents: " + report.unmappedFreeAgents());
        System.out.println("Free-agent metadata coverage position/fantasy_positions/team/status: "
            + report.freeAgentsWithPosition() + "/"
            + report.freeAgentsWithFantasyPositions() + "/"
            + report.freeAgentsWithTeam() + "/"
            + report.freeAgentsWithStatus());
        System.out.println("Unmapped free-agent examples:");
        printExamples(report.unmappedFreeAgentExamples());
        System.out.println("Free-agent examples:");
        printExamples(report.freeAgentExamples());
        System.out.println("Observed at UTC: " + report.observedAtUtc());
        System.out.println();
        System.out.println("Waiver/free-agent universe: " + report.state());
        for (String blocker : report.blockers()) System.out.println("  - " + blocker);
        if (report.state() == SleeperLiveWaiverUniverseAudit.AuditState.READY && report.unmappedFreeAgents() > 0) {
            System.out.println("  - Unmapped active free agents remain in the complete inventory; they are downstream metadata/bootstrap work, not silently excluded.");
        }
        System.out.println();
        System.out.println("Boundary: BF-601 proves only the broad active Sleeper identity universe minus exact current roster membership. It does not persist a free-agent snapshot, rank waiver targets, assign FAAB, make add/drop recommendations, evaluate managers, tune confidence/thresholds, or change historical methodology.");
    }

    private static void printExamples(java.util.List<SleeperLiveWaiverUniverseAudit.PlayerExample> examples) {
        if (examples.isEmpty()) {
            System.out.println("  none");
            return;
        }
        for (var example : examples) {
            System.out.println("  " + example.playerId()
                + " | " + nullable(example.name())
                + " | pos=" + nullable(example.position())
                + " | fantasy=" + (example.fantasyPositions().isEmpty() ? "none" : example.fantasyPositions())
                + " | team=" + nullable(example.team())
                + " | status=" + nullable(example.status()));
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
