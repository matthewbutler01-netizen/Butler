package io.butler.bet.data;

import io.butler.bet.domain.PlayerValue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class PlayerValueRepository {
    private final Database database;

    public PlayerValueRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public void save(PlayerValue value) throws SQLException {
        Objects.requireNonNull(value, "value must not be null");
        try (var connection = database.openConnection()) {
            save(connection, value);
        }
    }

    public void saveAll(List<PlayerValue> values) throws SQLException {
        Objects.requireNonNull(values, "values must not be null");
        for (PlayerValue value : values) Objects.requireNonNull(value, "value must not be null");

        try (var connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                for (PlayerValue value : values) save(connection, value);
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private static void save(Connection connection, PlayerValue value) throws SQLException {
        try (var statement = connection.prepareStatement("""
             INSERT INTO player_values(id, player_id, value, source, as_of_date)
             VALUES (?, ?, ?, ?, ?)
             ON CONFLICT(player_id, source, as_of_date) DO UPDATE SET
                 value = excluded.value
             """)) {
            statement.setString(1, value.getId());
            statement.setString(2, value.getPlayerId());
            statement.setDouble(3, value.getValue());
            statement.setString(4, value.getSource());
            statement.setString(5, value.getAsOfDate().toString());
            statement.executeUpdate();
        }
    }

    public Optional<PlayerValue> findLatestByPlayerId(String playerId) throws SQLException {
        requireText(playerId, "playerId");
        try (var connection = database.openConnection();
             var statement = connection.prepareStatement("""
                 SELECT id, player_id, value, source, as_of_date
                 FROM player_values
                 WHERE player_id = ?
                 ORDER BY as_of_date DESC, source ASC
                 LIMIT 1
                 """)) {
            statement.setString(1, playerId);
            try (var results = statement.executeQuery()) {
                return results.next() ? Optional.of(map(results)) : Optional.empty();
            }
        }
    }

    public Optional<PlayerValue> findLatestByPlayerIdAndSource(String playerId, String source) throws SQLException {
        requireText(playerId, "playerId");
        requireText(source, "source");
        try (var connection = database.openConnection();
             var statement = connection.prepareStatement("""
                 SELECT id, player_id, value, source, as_of_date
                 FROM player_values
                 WHERE player_id = ? AND source = ?
                 ORDER BY as_of_date DESC
                 LIMIT 1
                 """)) {
            statement.setString(1, playerId);
            statement.setString(2, source);
            try (var results = statement.executeQuery()) {
                return results.next() ? Optional.of(map(results)) : Optional.empty();
            }
        }
    }

    public List<PlayerValue> findByPlayerId(String playerId) throws SQLException {
        requireText(playerId, "playerId");
        List<PlayerValue> values = new ArrayList<>();
        try (var connection = database.openConnection();
             var statement = connection.prepareStatement("""
                 SELECT id, player_id, value, source, as_of_date
                 FROM player_values
                 WHERE player_id = ?
                 ORDER BY as_of_date DESC, source ASC
                 """)) {
            statement.setString(1, playerId);
            try (var results = statement.executeQuery()) {
                while (results.next()) values.add(map(results));
            }
        }
        return List.copyOf(values);
    }

    public List<PlayerValue> findLatestBySource(String source) throws SQLException {
        requireText(source, "source");
        List<PlayerValue> values = new ArrayList<>();
        try (var connection = database.openConnection();
             var statement = connection.prepareStatement("""
                 SELECT pv.id, pv.player_id, pv.value, pv.source, pv.as_of_date
                 FROM player_values pv
                 JOIN (
                     SELECT player_id, MAX(as_of_date) AS max_date
                     FROM player_values
                     WHERE source = ?
                     GROUP BY player_id
                 ) latest ON latest.player_id = pv.player_id AND latest.max_date = pv.as_of_date
                 WHERE pv.source = ?
                 ORDER BY pv.value DESC, pv.player_id ASC
                 """)) {
            statement.setString(1, source);
            statement.setString(2, source);
            try (var results = statement.executeQuery()) {
                while (results.next()) values.add(map(results));
            }
        }
        return List.copyOf(values);
    }

    public void deleteByPlayerId(String playerId) throws SQLException {
        requireText(playerId, "playerId");
        try (var connection = database.openConnection();
             var statement = connection.prepareStatement("DELETE FROM player_values WHERE player_id = ?")) {
            statement.setString(1, playerId);
            statement.executeUpdate();
        }
    }

    private static PlayerValue map(ResultSet results) throws SQLException {
        return new PlayerValue(
            results.getString("id"),
            results.getString("player_id"),
            results.getDouble("value"),
            results.getString("source"),
            LocalDate.parse(results.getString("as_of_date")));
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
