package io.butler.bet.data;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
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
                    name TEXT NOT NULL,
                    season INTEGER
                )
                """);
            ensureColumn(connection, "leagues", "season", "INTEGER");

            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS league_value_formats (
                    league_id TEXT PRIMARY KEY,
                    format TEXT NOT NULL,
                    FOREIGN KEY (league_id) REFERENCES leagues(id) ON DELETE CASCADE
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
                CREATE TABLE IF NOT EXISTS team_season_performance (
                    league_id TEXT NOT NULL,
                    team_id TEXT NOT NULL,
                    season INTEGER NOT NULL,
                    wins INTEGER NOT NULL,
                    losses INTEGER NOT NULL,
                    ties INTEGER NOT NULL,
                    points_for REAL NOT NULL,
                    points_against REAL NOT NULL,
                    source TEXT NOT NULL,
                    as_of_date TEXT NOT NULL,
                    PRIMARY KEY (league_id, team_id, season, source, as_of_date),
                    FOREIGN KEY (league_id) REFERENCES leagues(id) ON DELETE CASCADE,
                    FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE,
                    CHECK (season BETWEEN 1999 AND 2100),
                    CHECK (wins >= 0), CHECK (losses >= 0), CHECK (ties >= 0),
                    CHECK (points_for >= 0), CHECK (points_against >= 0)
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
                CREATE TABLE IF NOT EXISTS player_profiles (
                    player_id TEXT PRIMARY KEY,
                    birth_date TEXT,
                    years_experience INTEGER,
                    FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE,
                    CHECK (years_experience IS NULL OR years_experience >= 0)
                )
                """);

            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS player_profile_snapshots (
                    id TEXT PRIMARY KEY,
                    player_id TEXT NOT NULL,
                    reported_age INTEGER,
                    years_experience INTEGER,
                    source TEXT NOT NULL,
                    as_of_date TEXT NOT NULL,
                    FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE,
                    UNIQUE (player_id, source, as_of_date),
                    CHECK (reported_age IS NULL OR reported_age >= 0),
                    CHECK (years_experience IS NULL OR years_experience >= 0)
                )
                """);

            statement.executeUpdate(playerSeasonProductionTableSql());

            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS aging_model_player_profiles (
                    gsis_id TEXT NOT NULL,
                    display_name TEXT NOT NULL,
                    birth_date TEXT,
                    position TEXT NOT NULL,
                    source TEXT NOT NULL,
                    as_of_date TEXT NOT NULL,
                    PRIMARY KEY (gsis_id, source, as_of_date)
                )
                """);

            statement.executeUpdate(agingModelProductionTableSql());
            ensureColumn(connection, "aging_model_player_season_production", "position",
                "TEXT NOT NULL DEFAULT 'UNKNOWN'");

            migrateSignedYardageConstraints(connection);

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

            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS draft_picks (
                    id TEXT PRIMARY KEY,
                    league_id TEXT NOT NULL,
                    season INTEGER NOT NULL,
                    round INTEGER NOT NULL,
                    original_team_id TEXT NOT NULL,
                    owner_team_id TEXT NOT NULL,
                    pick_number INTEGER,
                    FOREIGN KEY (league_id) REFERENCES leagues(id) ON DELETE CASCADE,
                    FOREIGN KEY (original_team_id) REFERENCES teams(id) ON DELETE CASCADE,
                    FOREIGN KEY (owner_team_id) REFERENCES teams(id) ON DELETE CASCADE,
                    UNIQUE (league_id, season, round, original_team_id)
                )
                """);

            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS player_values (
                    id TEXT PRIMARY KEY,
                    player_id TEXT NOT NULL,
                    value REAL NOT NULL,
                    source TEXT NOT NULL,
                    as_of_date TEXT NOT NULL,
                    FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE,
                    UNIQUE (player_id, source, as_of_date)
                )
                """);

            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS draft_pick_values (
                    id TEXT PRIMARY KEY,
                    draft_pick_id TEXT NOT NULL,
                    value REAL NOT NULL,
                    source TEXT NOT NULL,
                    as_of_date TEXT NOT NULL,
                    FOREIGN KEY (draft_pick_id) REFERENCES draft_picks(id) ON DELETE CASCADE,
                    UNIQUE (draft_pick_id, source, as_of_date)
                )
                """);

            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_teams_league_id ON teams(league_id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_team_season_performance_league_season ON team_season_performance(league_id, season)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_team_season_performance_team_source_date ON team_season_performance(team_id, source, as_of_date)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_rosters_team_id ON rosters(team_id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_rosters_player_id ON rosters(player_id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_draft_picks_league_id ON draft_picks(league_id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_draft_picks_owner_team_id ON draft_picks(owner_team_id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_player_values_player_id ON player_values(player_id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_player_values_source_date ON player_values(source, as_of_date)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_player_profile_snapshots_player_source_date ON player_profile_snapshots(player_id, source, as_of_date)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_player_season_production_player_season ON player_season_production(player_id, season)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_player_season_production_source_date ON player_season_production(source, as_of_date)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_aging_model_player_profiles_source_date ON aging_model_player_profiles(source, as_of_date)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_aging_model_player_profiles_birth_date ON aging_model_player_profiles(birth_date)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_aging_model_production_gsis_season ON aging_model_player_season_production(gsis_id, season)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_aging_model_production_position_season ON aging_model_player_season_production(position, season)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_aging_model_production_source_date ON aging_model_player_season_production(source, as_of_date)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_draft_pick_values_pick_id ON draft_pick_values(draft_pick_id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_draft_pick_values_source_date ON draft_pick_values(source, as_of_date)");
        }
    }

    private static String playerSeasonProductionTableSql() {
        return """
            CREATE TABLE IF NOT EXISTS player_season_production (
                id TEXT PRIMARY KEY,
                player_id TEXT NOT NULL,
                season INTEGER NOT NULL,
                games_played INTEGER NOT NULL,
                passing_yards INTEGER NOT NULL,
                passing_touchdowns INTEGER NOT NULL,
                interceptions INTEGER NOT NULL,
                rushing_yards INTEGER NOT NULL,
                rushing_touchdowns INTEGER NOT NULL,
                receptions INTEGER NOT NULL,
                receiving_yards INTEGER NOT NULL,
                receiving_touchdowns INTEGER NOT NULL,
                fumbles_lost INTEGER NOT NULL,
                source TEXT NOT NULL,
                as_of_date TEXT NOT NULL,
                FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE,
                UNIQUE (player_id, season, source, as_of_date),
                CHECK (season > 0),
                CHECK (games_played >= 0),
                CHECK (passing_touchdowns >= 0),
                CHECK (interceptions >= 0),
                CHECK (rushing_touchdowns >= 0),
                CHECK (receptions >= 0),
                CHECK (receiving_touchdowns >= 0),
                CHECK (fumbles_lost >= 0)
            )
            """;
    }

    private static String agingModelProductionTableSql() {
        return """
            CREATE TABLE IF NOT EXISTS aging_model_player_season_production (
                gsis_id TEXT NOT NULL,
                season INTEGER NOT NULL,
                position TEXT NOT NULL DEFAULT 'UNKNOWN',
                games_played INTEGER NOT NULL,
                passing_yards INTEGER NOT NULL,
                passing_touchdowns INTEGER NOT NULL,
                interceptions INTEGER NOT NULL,
                rushing_yards INTEGER NOT NULL,
                rushing_touchdowns INTEGER NOT NULL,
                receptions INTEGER NOT NULL,
                receiving_yards INTEGER NOT NULL,
                receiving_touchdowns INTEGER NOT NULL,
                fumbles_lost INTEGER NOT NULL,
                source TEXT NOT NULL,
                as_of_date TEXT NOT NULL,
                PRIMARY KEY (gsis_id, season, source, as_of_date),
                CHECK (season > 0),
                CHECK (games_played >= 0),
                CHECK (passing_touchdowns >= 0),
                CHECK (interceptions >= 0),
                CHECK (rushing_touchdowns >= 0),
                CHECK (receptions >= 0),
                CHECK (receiving_touchdowns >= 0),
                CHECK (fumbles_lost >= 0)
            )
            """;
    }

    private static void migrateSignedYardageConstraints(Connection connection) throws SQLException {
        if (hasLegacyUnsignedYardageConstraint(connection, "player_season_production")) {
            rebuildPlayerSeasonProduction(connection);
        }
        if (hasLegacyUnsignedYardageConstraint(connection, "aging_model_player_season_production")) {
            rebuildAgingModelProduction(connection);
        }
    }

    private static boolean hasLegacyUnsignedYardageConstraint(Connection connection, String table) throws SQLException {
        try (var statement = connection.prepareStatement(
            "SELECT sql FROM sqlite_master WHERE type='table' AND name=?")) {
            statement.setString(1, table);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return false;
                String sql = rs.getString(1);
                if (sql == null) return false;
                String normalized = sql.toLowerCase();
                return normalized.contains("passing_yards >= 0")
                    || normalized.contains("rushing_yards >= 0")
                    || normalized.contains("receiving_yards >= 0");
            }
        }
    }

    private static void rebuildPlayerSeasonProduction(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE player_season_production RENAME TO player_season_production_legacy_unsigned_yards");
            statement.executeUpdate(playerSeasonProductionTableSql());
            statement.executeUpdate("""
                INSERT INTO player_season_production(
                    id, player_id, season, games_played, passing_yards, passing_touchdowns, interceptions,
                    rushing_yards, rushing_touchdowns, receptions, receiving_yards, receiving_touchdowns,
                    fumbles_lost, source, as_of_date)
                SELECT id, player_id, season, games_played, passing_yards, passing_touchdowns, interceptions,
                    rushing_yards, rushing_touchdowns, receptions, receiving_yards, receiving_touchdowns,
                    fumbles_lost, source, as_of_date
                FROM player_season_production_legacy_unsigned_yards
                """);
            statement.executeUpdate("DROP TABLE player_season_production_legacy_unsigned_yards");
        }
    }

    private static void rebuildAgingModelProduction(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE aging_model_player_season_production RENAME TO aging_model_player_season_production_legacy_unsigned_yards");
            statement.executeUpdate(agingModelProductionTableSql());
            statement.executeUpdate("""
                INSERT INTO aging_model_player_season_production(
                    gsis_id, season, position, games_played, passing_yards, passing_touchdowns, interceptions,
                    rushing_yards, rushing_touchdowns, receptions, receiving_yards, receiving_touchdowns,
                    fumbles_lost, source, as_of_date)
                SELECT gsis_id, season, position, games_played, passing_yards, passing_touchdowns, interceptions,
                    rushing_yards, rushing_touchdowns, receptions, receiving_yards, receiving_touchdowns,
                    fumbles_lost, source, as_of_date
                FROM aging_model_player_season_production_legacy_unsigned_yards
                """);
            statement.executeUpdate("DROP TABLE aging_model_player_season_production_legacy_unsigned_yards");
        }
    }

    private static void ensureColumn(Connection connection, String table, String column, String definition) throws SQLException {
        boolean exists = false;
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    exists = true;
                    break;
                }
            }
        }
        if (!exists) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            }
        }
    }
}
