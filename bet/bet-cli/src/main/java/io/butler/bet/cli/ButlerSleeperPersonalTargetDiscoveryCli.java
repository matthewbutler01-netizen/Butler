package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.sleeper.SleeperPersonalizedTargetService;

import java.nio.file.Path;

/** BF-621 read-only exact requesting-user account+league+roster discovery. */
public final class ButlerSleeperPersonalTargetDiscoveryCli {
    private ButlerSleeperPersonalTargetDiscoveryCli() {}

    public static void main(String[] args) {
        try {
            if (args == null || args.length != 2
                || args[0] == null || args[0].isBlank()
                || args[1] == null || args[1].isBlank()) {
                throw new IllegalArgumentException(
                    "Usage: sleeperPersonalTargetDiscovery <sleeper-username> <sleeper-league-id>");
            }
            Database database = new Database(Path.of("butler.db"));
            database.initialize();
            print(new SleeperPersonalizedTargetService(database).discover(args[0].trim(), args[1].trim()));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static void print(SleeperPersonalizedTargetService.DiscoveryReport report) {
        System.out.println("Sleeper 2026 exact personalized target discovery (BF-621)");
        System.out.println("Policy: " + report.policyId());
        System.out.println("Account username / user id: " + report.sleeperUsername() + " / " + report.sleeperUserId());
        System.out.println("Account current 2026 leagues:");
        for (var league : report.currentSeasonLeagues()) {
            System.out.println("  " + league.leagueId() + " | " + league.name() + " | " + league.season() + "/" + league.status());
        }
        System.out.println("Selected league: " + report.sleeperLeagueId() + " | " + report.leagueName()
            + " | " + report.season() + "/" + report.providerStatus());
        System.out.println("Exact roster / role: " + report.rosterId() + " / " + report.membershipRole());
        System.out.println("League display/team: " + value(report.leagueDisplayName()) + " / " + value(report.teamName()));
        System.out.println("Current player count: " + report.playerCount());
        System.out.println("Discovery state: " + report.state());
        System.out.println();
        System.out.println("Boundary: BF-621 proves only the requesting Sleeper account, current league membership, and exact roster. It does not infer identity from historical lineage, display/team name, or shared players and makes no fantasy recommendation.");
    }

    private static String value(Object value) {
        return value == null || value.toString().isBlank() ? "none" : value.toString();
    }
}
