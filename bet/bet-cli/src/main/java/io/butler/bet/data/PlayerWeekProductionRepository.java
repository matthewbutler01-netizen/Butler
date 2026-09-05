package io.butler.bet.data;

import io.butler.bet.domain.PlayerWeekProduction;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Persists raw week-level production snapshots without applying fantasy scoring. */
public final class PlayerWeekProductionRepository {
    private final Database database;

    public PlayerWeekProductionRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public void save(PlayerWeekProduction production) throws SQLException {
        Objects.requireNonNull(production, "production must not be null");
        try (Connection connection = database.openConnection()) {
            ensureTable(connection);
            String sql = "INSERT INTO player_week_production(" +
                "id, player_id, season, week, passing_yards, passing_touchdowns, interceptions, " +
                "rushing_yards, rushing_touchdowns, receptions, receiving_yards, receiving_touchdowns, " +
                "fumbles_lost, source, as_of_date) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) " +
                "ON CONFLICT(player_id, season, week, source, as_of_date) DO UPDATE SET " +
                "passing_yards=excluded.passing_yards, passing_touchdowns=excluded.passing_touchdowns, " +
                "interceptions=excluded.interceptions, rushing_yards=excluded.rushing_yards, " +
                "rushing_touchdowns=excluded.rushing_touchdowns, receptions=excluded.receptions, " +
                "receiving_yards=excluded.receiving_yards, receiving_touchdowns=excluded.receiving_touchdowns, " +
                "fumbles_lost=excluded.fumbles_lost";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, production.id());
                statement.setString(2, production.playerId());
                statement.setInt(3, production.season());
                statement.setInt(4, production.week());
                statement.setInt(5, production.passingYards());
                statement.setInt(6, production.passingTouchdowns());
                statement.setInt(7, production.interceptions());
                statement.setInt(8, production.rushingYards());
                statement.setInt(9, production.rushingTouchdowns());
                statement.setInt(10, production.receptions());
                statement.setInt(11, production.receivingYards());
                statement.setInt(12, production.receivingTouchdowns());
                statement.setInt(13, production.fumblesLost());
                statement.setString(14, production.source());
                statement.setString(15, production.asOfDate().toString());
                statement.executeUpdate();
            }
        }
    }

    public Optional<PlayerWeekProduction> findLatest(String playerId, int season, int week, String source)
        throws SQLException {
        requireText(playerId, "playerId");
        requireText(source, "source");
        if (season <= 0) throw new IllegalArgumentException("season must be positive");
        if (week <= 0) throw new IllegalArgumentException("week must be positive");
        try (Connection connection = database.openConnection()) {
            ensureTable(connection);
            String sql = "SELECT * FROM player_week_production WHERE player_id=? AND season=? AND week=? AND source=? " +
                "ORDER BY as_of_date DESC, id DESC LIMIT 1";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, playerId.trim());
                statement.setInt(2, season);
                statement.setInt(3, week);
                statement.setString(4, source.trim());
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                }
            }
        }
    }

    /** Returns the production snapshot from the exact import date used by a coverage decision. */
    public Optional<PlayerWeekProduction> findAtAsOf(
        String playerId, int season, int week, String source, LocalDate asOfDate) throws SQLException {
        requireText(playerId, "playerId");
        requireText(source, "source");
        Objects.requireNonNull(asOfDate, "asOfDate must not be null");
        if (season <= 0) throw new IllegalArgumentException("season must be positive");
        if (week <= 0) throw new IllegalArgumentException("week must be positive");
        try (Connection connection = database.openConnection()) {
            ensureTable(connection);
            String sql = "SELECT * FROM player_week_production " +
                "WHERE player_id=? AND season=? AND week=? AND source=? AND as_of_date=? LIMIT 1";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, playerId.trim());
                statement.setInt(2, season);
                statement.setInt(3, week);
                statement.setString(4, source.trim());
                statement.setString(5, asOfDate.toString());
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                }
            }
        }
    }

    public List<PlayerWeekProduction> findByPlayerSeason(String playerId, int season, String source)
        throws SQLException {
        requireText(playerId, "playerId");
        requireText(source, "source");
        if (season <= 0) throw new IllegalArgumentException("season must be positive");
        List<PlayerWeekProduction> result = new ArrayList<>();
        try (Connection connection = database.openConnection()) {
            ensureTable(connection);
            String sql = "SELECT * FROM player_week_production WHERE player_id=? AND season=? AND source=? " +
                "ORDER BY week, as_of_date DESC, id DESC";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, playerId.trim());
                statement.setInt(2, season);
                statement.setString(3, source.trim());
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) result.add(map(rs));
                }
            }
        }
        return List.copyOf(result);
    }

    private static void ensureTable(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS player_week_production (
                    id TEXT PRIMARY KEY,
                    player_id TEXT NOT NULL,
                    season INTEGER NOT NULL,
                    week INTEGER NOT NULL,
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
                    UNIQUE(player_id, season, week, source, as_of_date),
                    FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE,
                    CHECK (season > 0),
                    CHECK (week > 0),
                    CHECK (passing_touchdowns >= 0),
                    CHECK (interceptions >= 0),
                    CHECK (rushing_touchdowns >= 0),
                    CHECK (receptions >= 0),
                    CHECK (receiving_touchdowns >= 0),
                    CHECK (fumbles_lost >= 0)
                )
                """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_player_week_production_lookup " +
                "ON player_week_production(player_id, season, week, source, as_of_date DESC)");
        }
    }

    private static PlayerWeekProduction map(ResultSet rs) throws SQLException {
        return new PlayerWeekProduction(
            rs.getString("id"), rs.getString("player_id"), rs.getInt("season"), rs.getInt("week"),
            rs.getInt("passing_yards"), rs.getInt("passing_touchdowns"), rs.getInt("interceptions"),
            rs.getInt("rushing_yards"), rs.getInt("rushing_touchdowns"), rs.getInt("receptions"),
            rs.getInt("receiving_yards"), rs.getInt("receiving_touchdowns"), rs.getInt("fumbles_lost"),
            rs.getString("source"), LocalDate.parse(rs.getString("as_of_date")));
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
