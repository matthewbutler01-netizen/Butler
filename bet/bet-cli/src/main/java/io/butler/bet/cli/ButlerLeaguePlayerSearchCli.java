package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.LeagueAssetSearchAnalyzer;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/**
 * Read-only rostered-player discovery over Butler's existing league asset search.
 */
public final class ButlerLeaguePlayerSearchCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerLeaguePlayerSearchCli() {}

    public static void main(String[] args) {
        int exitCode = runEmbedded(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int runEmbedded(String[] args) {
        try {
            Options options = parse(args);
            var report = new LeagueAssetSearchAnalyzer(initializedDatabase())
                .search(options.leagueId(), options.query());
            print(report);
            return 0;
        } catch (SQLException e) {
            System.err.println("Database error while searching league players: " + e.getMessage());
            return 1;
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            return 2;
        }
    }

    static Options parse(String[] args) {
        if (!isCommand(args) || args.length < 4) {
            throw new IllegalArgumentException(
                "Usage: butler league player-search <league-id> <query...>");
        }

        String query = String.join(" ", Arrays.copyOfRange(args, 3, args.length))
            .trim()
            .replaceAll("\\s+", " ");
        return new Options(requireText(args[2], "league-id"), requireText(query, "query"));
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && "league".equalsIgnoreCase(args[0])
            && "player-search".equalsIgnoreCase(args[1]);
    }

    static void print(LeagueAssetSearchAnalyzer.SearchReport report) {
        Objects.requireNonNull(report, "report must not be null");

        System.out.println("League player search evidence");
        System.out.println("League ID: " + report.leagueId());
        System.out.println("Source: " + report.source());
        System.out.println("Query: " + report.query());
        System.out.println("Player matches: " + report.players().size());
        System.out.println("Other asset matches: " + report.draftPicks().size());

        for (var player : report.players()) {
            System.out.println("===BUTLER_PLAYER_SEARCH:PLAYER:BEGIN===");
            System.out.println("Player ID: " + player.playerId());
            System.out.println("Player name: " + player.playerName());
            System.out.println("Position: " + value(player.position()));
            System.out.println("NFL team: " + value(player.nflTeam()));
            System.out.println("Owner team ID: " + player.teamId());
            System.out.println("Owner team name: " + player.teamName());
            System.out.println("Roster slot: " + value(player.slot()));
            System.out.println("Value: " + (player.valued()
                ? String.format(Locale.ROOT, "%.2f", player.value())
                : "UNAVAILABLE"));
            System.out.println("Value as-of: " + (player.asOfDate() == null
                ? "UNAVAILABLE"
                : player.asOfDate()));
            System.out.println("===BUTLER_PLAYER_SEARCH:PLAYER:END===");
        }

        System.out.println(
            "Rostered-player search only; draft-pick matches are counted but not shown here. "
                + "Results preserve existing asset-search order and are NOT A RANKING. "
                + "No player score, value adjustment, buy/sell label, waiver recommendation, "
                + "trade recommendation, or start/sit recommendation is produced.");
    }

    private static String value(String value) {
        return value == null || value.isBlank() ? "UNAVAILABLE" : value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static Database initializedDatabase() throws SQLException {
        Database database = new Database(DATABASE_PATH);
        database.initialize();
        return database;
    }

    record Options(String leagueId, String query) {}
}
