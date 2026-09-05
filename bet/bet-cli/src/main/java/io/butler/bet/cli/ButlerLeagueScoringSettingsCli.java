package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.LeagueScoringSettingsRepository;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.SQLException;

/** Read-only inspection of the exact league scoring rules persisted from the provider. */
public final class ButlerLeagueScoringSettingsCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerLeagueScoringSettingsCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            run(initializedDatabase(), options.leagueId());
        } catch (SQLException e) {
            System.err.println("Database error while reading league scoring settings: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (!isCommand(args) || args.length != 3) {
            throw new IllegalArgumentException("Usage: butler league scoring-settings <league-id>");
        }
        return new Options(requireText(args[2], "league-id"));
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && "league".equalsIgnoreCase(args[0])
            && "scoring-settings".equalsIgnoreCase(args[1]);
    }

    static void run(Database database, String leagueId) throws SQLException {
        if (database == null) throw new IllegalArgumentException("database must not be null");
        leagueId = requireText(leagueId, "league-id");
        var league = new LeagueRepository(database).findById(leagueId)
            .orElseThrow(() -> new IllegalArgumentException("League not found: " + leagueId));
        var settings = new LeagueScoringSettingsRepository(database).findByLeagueId(leagueId);

        System.out.println("League scoring settings");
        System.out.println("League: " + league.getName() + " (" + league.getId() + ")");
        if (league.getExternalId() != null && !league.getExternalId().isBlank()) {
            System.out.println("External league ID: " + league.getExternalId());
        }
        if (settings.isEmpty()) {
            System.out.println("No persisted scoring settings are available. Run a Sleeper league sync to refresh provider evidence.");
        } else {
            System.out.println("Stat | Points per unit");
            settings.forEach((stat, points) ->
                System.out.println(stat + " | " + format(points)));
        }
        System.out.println("Stored provider scoring rules only; this command does not calculate player fantasy totals or recommendations.");
    }

    private static String format(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static Database initializedDatabase() throws SQLException {
        Database database = new Database(DATABASE_PATH);
        database.initialize();
        return database;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    record Options(String leagueId) {}
}
