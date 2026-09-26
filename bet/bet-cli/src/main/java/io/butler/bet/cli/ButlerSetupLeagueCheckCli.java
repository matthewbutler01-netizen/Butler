package io.butler.bet.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;

/** Checks only a verified, staged backup, without initializing or migrating it. */
public final class ButlerSetupLeagueCheckCli {
    private ButlerSetupLeagueCheckCli() {}

    public static void verify(Path database, String leagueId) throws Exception {
        String normalized = UUID.fromString(leagueId).toString();
        if (!normalized.equalsIgnoreCase(leagueId)) {
            throw new IllegalArgumentException("League selection must be a full UUID.");
        }
        if (!Files.isRegularFile(database)) {
            throw new IllegalArgumentException("Staged backup database is missing.");
        }
        // immutable prevents journal/SHM creation for this private, stable staged copy.
        String url = "jdbc:sqlite:" + database.toAbsolutePath().toUri() + "?mode=ro&immutable=1";
        try (var connection = DriverManager.getConnection(url);
             var statement = connection.prepareStatement("SELECT 1 FROM leagues WHERE id = ? LIMIT 1")) {
            statement.setString(1, normalized);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalArgumentException("Selected league is absent from the backup database.");
                }
            }
        }
    }

    public static void main(String[] args) {
        try {
            if (args.length != 2) {
                throw new IllegalArgumentException("Expected staged database path and league UUID.");
            }
            verify(Path.of(args[0]), args[1]);
            System.out.println("BUTLER SETUP LEAGUE: VERIFIED");
        } catch (Exception failure) {
            System.err.println("BUTLER SETUP LEAGUE: BLOCKED - " + failure.getMessage());
            System.exit(1);
        }
    }
}
