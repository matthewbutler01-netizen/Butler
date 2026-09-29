package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.sleeper.SleeperPersonalizedTargetService;

import java.nio.file.Path;

/** Read-only current-season league lookup for fresh-profile setup. */
public final class ButlerSleeperLeagueSelectionCli {
    private ButlerSleeperLeagueSelectionCli() {}

    public static void main(String[] args) {
        try {
            if (args == null || args.length != 1 || args[0] == null || args[0].isBlank()) {
                throw new IllegalArgumentException("Usage: sleeperLeagueSelection <sleeper-username>");
            }
            var leagues = new SleeperPersonalizedTargetService(new Database(Path.of("butler.db")))
                .listCurrentLeagues(args[0].trim());
            for (var league : leagues) {
                System.out.println("LEAGUE\t" + league.leagueId() + "\t"
                    + league.name().replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
                    + "\t" + league.season() + "\t" + league.status());
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }
}
