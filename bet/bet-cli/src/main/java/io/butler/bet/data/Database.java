package io.butler.bet.data;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public final class Database {
    private final String jdbcUrl;

    public Database(Path databasePath) {
        if (databasePath == null) {
            throw new IllegalArgumentException("databasePath must not be null");
        }
        this.jdbcUrl = "jdbc:sqlite:" + databasePath.toAbsolutePath();
    }

    public Connection openConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
        }
        return connection;
    }

    public void initialize() throws SQLException {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {

            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS leagues (
                    id TEXT PRIMARY KEY,
                    external_id TEXT UNIQUE,
                    name TEXT NOT NULL
                )
                """);

            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS teams (
                    id TEXT PRIMARY KEY,
                    external_id TEXT,
                    league_id TEXT NOT NULL,
                    name TEXT NOT NULL,
                    FOREIGN KEY (league_id) REFERENCES leagues(id) ON DELETE CASCADE,
                    UNIQUE (league_id, external_id)
                )
                """);

            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS players (
                    id TEXT PRIMARY KEY,
                    external_id TEXT UNIQUE,
                    display_name TEXT NOT NULL,
                    position TEXT NOT NULL,
                    nfl_team TEXT
                )
                """);

            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS rosters (
                    id TEXT PRIMARY KEY,
                    external_id TEXT,
                    team_id TEXT NOT NULL,
                    player_id TEXT NOT NULL,
                    slot TEXT NOT NULL,
                    FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE,
                    FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE,
                    UNIQUE (team_id, player_id)
                )
                """);

            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_teams_league_id ON teams(league_id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_rosters_team_id ON rosters(team_id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_rosters_player_id ON rosters(player_id)");
        }
    }
}
