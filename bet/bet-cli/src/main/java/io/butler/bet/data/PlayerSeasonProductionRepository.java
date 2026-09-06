package io.butler.bet.data;

import io.butler.bet.domain.PlayerSeasonProduction;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class PlayerSeasonProductionRepository {
    private final Database database;

    public PlayerSeasonProductionRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public void save(PlayerSeasonProduction production) throws SQLException {
        Objects.requireNonNull(production, "production must not be null");
        try (Connection connection = database.openConnection()) {
            ensureExtendedColumns(connection);
            String sql = "INSERT INTO player_season_production(" +
                "id, player_id, season, games_played, passing_yards, passing_touchdowns, interceptions, " +
                "rushing_yards, rushing_touchdowns, receptions, receiving_yards, receiving_touchdowns, fumbles_lost, " +
                "passing_two_point_conversions, rushing_attempts, rushing_two_point_conversions, receiving_two_point_conversions, " +
                "fumble_recovery_touchdowns, special_teams_touchdowns, raw_scoring_schema_version, source, as_of_date) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) " +
                "ON CONFLICT(player_id, season, source, as_of_date) DO UPDATE SET " +
                "games_played=excluded.games_played, passing_yards=excluded.passing_yards, " +
                "passing_touchdowns=excluded.passing_touchdowns, interceptions=excluded.interceptions, " +
                "rushing_yards=excluded.rushing_yards, rushing_touchdowns=excluded.rushing_touchdowns, " +
                "receptions=excluded.receptions, receiving_yards=excluded.receiving_yards, " +
                "receiving_touchdowns=excluded.receiving_touchdowns, fumbles_lost=excluded.fumbles_lost, " +
                "passing_two_point_conversions=excluded.passing_two_point_conversions, rushing_attempts=excluded.rushing_attempts, " +
                "rushing_two_point_conversions=excluded.rushing_two_point_conversions, receiving_two_point_conversions=excluded.receiving_two_point_conversions, " +
                "fumble_recovery_touchdowns=excluded.fumble_recovery_touchdowns, special_teams_touchdowns=excluded.special_teams_touchdowns, " +
                "raw_scoring_schema_version=excluded.raw_scoring_schema_version";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, production.id()); statement.setString(2, production.playerId());
                statement.setInt(3, production.season()); statement.setInt(4, production.gamesPlayed());
                statement.setInt(5, production.passingYards()); statement.setInt(6, production.passingTouchdowns());
                statement.setInt(7, production.interceptions()); statement.setInt(8, production.rushingYards());
                statement.setInt(9, production.rushingTouchdowns()); statement.setInt(10, production.receptions());
                statement.setInt(11, production.receivingYards()); statement.setInt(12, production.receivingTouchdowns());
                statement.setInt(13, production.fumblesLost()); statement.setInt(14, production.passingTwoPointConversions());
                statement.setInt(15, production.rushingAttempts()); statement.setInt(16, production.rushingTwoPointConversions());
                statement.setInt(17, production.receivingTwoPointConversions()); statement.setInt(18, production.fumbleRecoveryTouchdowns());
                statement.setInt(19, production.specialTeamsTouchdowns()); statement.setInt(20, production.rawScoringSchemaVersion());
                statement.setString(21, production.source()); statement.setString(22, production.asOfDate().toString());
                statement.executeUpdate();
            }
        }
    }

    public Optional<PlayerSeasonProduction> findLatest(String playerId, int season, String source) throws SQLException {
        requireText(playerId, "playerId"); requireText(source, "source");
        if (season <= 0) throw new IllegalArgumentException("season must be positive");
        try (Connection connection = database.openConnection()) {
            ensureExtendedColumns(connection);
            String sql = "SELECT * FROM player_season_production WHERE player_id=? AND season=? AND source=? ORDER BY as_of_date DESC, id DESC LIMIT 1";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, playerId.trim()); statement.setInt(2, season); statement.setString(3, source.trim());
                try (ResultSet rs = statement.executeQuery()) { return rs.next() ? Optional.of(map(rs)) : Optional.empty(); }
            }
        }
    }

    public List<PlayerSeasonProduction> findByPlayerId(String playerId) throws SQLException {
        requireText(playerId, "playerId");
        List<PlayerSeasonProduction> result = new ArrayList<>();
        try (Connection connection = database.openConnection()) {
            ensureExtendedColumns(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM player_season_production WHERE player_id=? ORDER BY season DESC, as_of_date DESC, source")) {
                statement.setString(1, playerId.trim());
                try (ResultSet rs = statement.executeQuery()) { while (rs.next()) result.add(map(rs)); }
            }
        }
        return List.copyOf(result);
    }

    private static void ensureExtendedColumns(Connection connection) throws SQLException {
        ensureColumn(connection, "passing_two_point_conversions", "INTEGER NOT NULL DEFAULT 0");
        ensureColumn(connection, "rushing_attempts", "INTEGER NOT NULL DEFAULT 0");
        ensureColumn(connection, "rushing_two_point_conversions", "INTEGER NOT NULL DEFAULT 0");
        ensureColumn(connection, "receiving_two_point_conversions", "INTEGER NOT NULL DEFAULT 0");
        ensureColumn(connection, "fumble_recovery_touchdowns", "INTEGER NOT NULL DEFAULT 0");
        ensureColumn(connection, "special_teams_touchdowns", "INTEGER NOT NULL DEFAULT 0");
        ensureColumn(connection, "raw_scoring_schema_version", "INTEGER NOT NULL DEFAULT 1");
    }

    private static void ensureColumn(Connection connection, String column, String definition) throws SQLException {
        boolean exists = false;
        try (var statement = connection.createStatement(); var rs = statement.executeQuery("PRAGMA table_info(player_season_production)")) {
            while (rs.next()) if (column.equalsIgnoreCase(rs.getString("name"))) { exists = true; break; }
        }
        if (!exists) try (var statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE player_season_production ADD COLUMN " + column + " " + definition);
        }
    }

    private static PlayerSeasonProduction map(ResultSet rs) throws SQLException {
        return new PlayerSeasonProduction(
            rs.getString("id"), rs.getString("player_id"), rs.getInt("season"), rs.getInt("games_played"),
            rs.getInt("passing_yards"), rs.getInt("passing_touchdowns"), rs.getInt("interceptions"),
            rs.getInt("rushing_yards"), rs.getInt("rushing_touchdowns"), rs.getInt("receptions"),
            rs.getInt("receiving_yards"), rs.getInt("receiving_touchdowns"), rs.getInt("fumbles_lost"),
            rs.getInt("passing_two_point_conversions"), rs.getInt("rushing_attempts"), rs.getInt("rushing_two_point_conversions"),
            rs.getInt("receiving_two_point_conversions"), rs.getInt("fumble_recovery_touchdowns"), rs.getInt("special_teams_touchdowns"),
            rs.getInt("raw_scoring_schema_version"), rs.getString("source"), LocalDate.parse(rs.getString("as_of_date")));
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
