package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.sleeper.SleeperPersonalizedTargetService;

import java.nio.file.Path;

/** BF-622 guarded exact personalized target persistence. */
public final class ButlerSleeperPersonalTargetBindCli {
    private ButlerSleeperPersonalTargetBindCli() {}

    public static void main(String[] args) {
        try {
            if (args == null || args.length != 3
                || args[0] == null || args[0].isBlank()
                || args[1] == null || args[1].isBlank()
                || args[2] == null || args[2].isBlank()) {
                throw new IllegalArgumentException(
                    "Usage: sleeperPersonalTargetBind <butler-league-id> <sleeper-username> <sleeper-league-id>");
            }
            Database database = new Database(Path.of("butler.db"));
            database.initialize();
            print(new SleeperPersonalizedTargetService(database)
                .bind(args[0].trim(), args[1].trim(), args[2].trim()));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static void print(SleeperPersonalizedTargetService.BindReport report) {
        var target = report.target();
        System.out.println("Sleeper 2026 exact personalized target binding (BF-622)");
        System.out.println("Policy: " + report.policyId());
        System.out.println("Butler league: " + target.butlerLeagueId());
        System.out.println("Sleeper username / user id: " + target.sleeperUsername() + " / " + target.sleeperUserId());
        System.out.println("Sleeper league / roster: " + target.sleeperLeagueId() + " / " + target.rosterId());
        System.out.println("Provider league: " + target.leagueName() + " | " + target.season() + "/" + target.providerStatus());
        System.out.println("Bound at UTC: " + target.boundAtUtc());
        System.out.println("Binding state: " + report.state());
        System.out.println();
        System.out.println("Boundary: BF-622 persists only the explicitly discovered requesting-user target. A conflicting existing binding cannot be silently overwritten.");
    }
}
