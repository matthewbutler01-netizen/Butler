package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.sleeper.SleeperCurrentSeasonSuccessorRelink;

import java.nio.file.Path;

/** BF-597 operator surface for the governed 2025 -> 2026 Sleeper successor relink. */
public final class ButlerSleeperCurrentSeasonSuccessorRelinkCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerSleeperCurrentSeasonSuccessorRelinkCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            Database database = new Database(DATABASE_PATH);
            database.initialize();
            print(new SleeperCurrentSeasonSuccessorRelink(database)
                .relink(options.leagueId(), options.expectedSuccessorSleeperLeagueId()));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (args == null || args.length != 2) {
            throw new IllegalArgumentException(
                "Usage: sleeperCurrentSeasonSuccessorRelink <butler-league-id> <expected-successor-sleeper-league-id>");
        }
        String leagueId = requireText(args[0], "butler-league-id");
        String successorId = requireText(args[1], "expected-successor-sleeper-league-id");
        return new Options(leagueId, successorId);
    }

    static void print(SleeperCurrentSeasonSuccessorRelink.RelinkReport report) {
        System.out.println("Sleeper 2026 successor relink");
        System.out.println("Policy: " + report.policyId());
        System.out.println("Butler league: " + report.butlerLeagueName() + " [" + report.butlerLeagueId() + "]");
        System.out.println("Previous Sleeper league: " + report.previousSleeperLeagueId());
        System.out.println("New Sleeper league: " + report.newSleeperLeagueId());
        System.out.println("Persisted Butler season: " + report.persistedSeason());
        System.out.println("Successor provider status at proof time: " + nullable(report.successorProviderStatus()));
        System.out.println("Lineage proof newest->oldest: " + report.lineageNewestToOldest());
        System.out.println("Relink state: " + report.state());
        System.out.println();
        System.out.println("Boundary: BF-597 changed only the Butler league external Sleeper id and season after rerunning BF-596 and passing a compare-and-set write. It did not hydrate teams, rosters, players, scoring, lineups, waivers, trades, historical evidence, thresholds, confidence, manager evaluations, or recommendations. Rerun BF-595 before any live capability is treated as ready.");
    }

    private static String nullable(Object value) {
        return value == null || value.toString().isBlank() ? "none" : value.toString();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    record Options(String leagueId, String expectedSuccessorSleeperLeagueId) {}
}
