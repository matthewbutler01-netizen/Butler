package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.sleeper.SleeperCurrentSeasonSuccessorDiscovery;

import java.nio.file.Path;

/** Read-only BF-596 operator surface for 2026 Sleeper successor discovery. */
public final class ButlerSleeperCurrentSeasonSuccessorDiscoveryCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerSleeperCurrentSeasonSuccessorDiscoveryCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            Database database = new Database(DATABASE_PATH);
            database.initialize();
            print(new SleeperCurrentSeasonSuccessorDiscovery(database).discover(options.leagueId()));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (args == null || args.length != 1) {
            throw new IllegalArgumentException(
                "Usage: sleeperCurrentSeasonSuccessorDiscovery <butler-league-id>");
        }
        String leagueId = args[0] == null ? null : args[0].trim();
        if (leagueId == null || leagueId.isBlank()) {
            throw new IllegalArgumentException("butler-league-id must not be blank");
        }
        return new Options(leagueId);
    }

    static void print(SleeperCurrentSeasonSuccessorDiscovery.DiscoveryReport report) {
        System.out.println("Sleeper 2026 successor discovery");
        System.out.println("Policy: " + report.policyId());
        System.out.println("Butler league: " + report.butlerLeagueName() + " [" + report.butlerLeagueId() + "]");
        System.out.println("Linked Sleeper league: " + report.linkedSleeperLeagueName()
            + " [" + report.linkedSleeperLeagueId() + "]");
        System.out.println("Linked provider season/status: " + report.linkedProviderSeason()
            + "/" + nullable(report.linkedProviderStatus()));
        System.out.println("Target season: " + report.targetSeason());
        System.out.println("Distinct linked-league owners scanned: " + report.distinctOwnerIds());
        System.out.println("Ownerless linked rosters: " + report.ownerlessRosters());
        System.out.println("2026 candidates discovered: " + report.candidates().size());
        System.out.println();

        for (var candidate : report.candidates()) {
            System.out.println((candidate.lineageContainsLinkedLeague() ? "MATCH" : "REJECT")
                + " | league=" + candidate.sleeperLeagueId()
                + " | name=" + candidate.name()
                + " | season=" + candidate.season()
                + " | status=" + nullable(candidate.status()));
            System.out.println("  surfaced by owners=" + candidate.surfacedByOwnerIds());
            System.out.println("  lineage newest->oldest=" + candidate.lineageNewestToOldest());
        }

        System.out.println();
        System.out.println("Discovery state: " + report.state());
        System.out.println("Matching lineage-backed successors: " + report.matchingCandidates().size());
        System.out.println("Unique successor Sleeper league: " + nullable(report.uniqueSuccessorSleeperLeagueId()));
        System.out.println();
        System.out.println("Boundary: read-only provider-lineage discovery only. This command does not relink or import a league, does not match league names fuzzily, does not mutate teams/rosters/players, does not change historical BF-518/BF-521 evidence or thresholds, and does not make lineup, waiver, trade, confidence, manager, or recommendation judgments.");
    }

    private static String nullable(Object value) {
        return value == null || value.toString().isBlank() ? "none" : value.toString();
    }

    record Options(String leagueId) {}
}
