package io.butler.bet.data;

import io.butler.bet.domain.PlayerFantasyPositionObservation;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Persists dated provider observations of ordered player fantasy-position eligibility. */
public final class PlayerFantasyPositionObservationRepository {
    private final Database database;

    public PlayerFantasyPositionObservationRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public void replace(PlayerFantasyPositionObservation observation) throws SQLException {
        Objects.requireNonNull(observation, "observation must not be null");
        try (Connection connection = database.openConnection()) {
            ensureTables(connection);
            connection.setAutoCommit(false);
            try {
                try (var parent = connection.prepareStatement("""
                    INSERT INTO player_fantasy_position_observations(player_id, source, as_of_date)
                    VALUES(?,?,?)
                    ON CONFLICT(player_id, source, as_of_date) DO NOTHING
                    """)) {
                    parent.setString(1, observation.playerId());
                    parent.setString(2, observation.source());
                    parent.setString(3, observation.asOfDate().toString());
                    parent.executeUpdate();
                }
                try (var delete = connection.prepareStatement("""
                    DELETE FROM player_fantasy_position_observation_positions
                    WHERE player_id=? AND source=? AND as_of_date=?
                    """)) {
                    bindKey(delete, observation.playerId(), observation.source(), observation.asOfDate());
                    delete.executeUpdate();
                }
                try (var insert = connection.prepareStatement("""
                    INSERT INTO player_fantasy_position_observation_positions(
                        player_id, source, as_of_date, ordinal, position)
                    VALUES(?,?,?,?,?)
                    """)) {
                    for (int ordinal = 0; ordinal < observation.providerFantasyPositions().size(); ordinal++) {
                        bindKey(insert, observation.playerId(), observation.source(), observation.asOfDate());
                        insert.setInt(4, ordinal);
                        insert.setString(5, observation.providerFantasyPositions().get(ordinal));
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    public Optional<PlayerFantasyPositionObservation> findLatest(String playerId, String source)
        throws SQLException {
        String normalizedPlayerId = requireText(playerId, "playerId").trim();
        String normalizedSource = requireText(source, "source").trim();
        try (Connection connection = database.openConnection()) {
            ensureTables(connection);
            try (var statement = connection.prepareStatement("""
                SELECT as_of_date
                FROM player_fantasy_position_observations
                WHERE player_id=? AND source=?
                ORDER BY as_of_date DESC
                LIMIT 1
                """)) {
                statement.setString(1, normalizedPlayerId);
                statement.setString(2, normalizedSource);
                try (var results = statement.executeQuery()) {
                    if (!results.next()) return Optional.empty();
                    LocalDate asOfDate = LocalDate.parse(results.getString("as_of_date"));
                    return Optional.of(new PlayerFantasyPositionObservation(
                        normalizedPlayerId,
                        normalizedSource,
                        asOfDate,
                        findPositions(connection, normalizedPlayerId, normalizedSource, asOfDate)));
                }
            }
        }
    }

    private static List<String> findPositions(Connection connection, String playerId, String source,
                                              LocalDate asOfDate) throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT position
            FROM player_fantasy_position_observation_positions
            WHERE player_id=? AND source=? AND as_of_date=?
            ORDER BY ordinal
            """)) {
            bindKey(statement, playerId, source, asOfDate);
            try (var results = statement.executeQuery()) {
                List<String> positions = new ArrayList<>();
                while (results.next()) positions.add(results.getString("position"));
                return List.copyOf(positions);
            }
        }
    }

    private static void ensureTables(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS player_fantasy_position_observations (
                    player_id TEXT NOT NULL,
                    source TEXT NOT NULL,
                    as_of_date TEXT NOT NULL,
                    PRIMARY KEY (player_id, source, as_of_date),
                    FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS player_fantasy_position_observation_positions (
                    player_id TEXT NOT NULL,
                    source TEXT NOT NULL,
                    as_of_date TEXT NOT NULL,
                    ordinal INTEGER NOT NULL,
                    position TEXT NOT NULL,
                    PRIMARY KEY (player_id, source, as_of_date, ordinal),
                    FOREIGN KEY (player_id, source, as_of_date)
                        REFERENCES player_fantasy_position_observations(player_id, source, as_of_date)
                        ON DELETE CASCADE,
                    CHECK (ordinal >= 0)
                )
                """);
            statement.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_player_fantasy_position_observations_latest
                ON player_fantasy_position_observations(player_id, source, as_of_date DESC)
                """);
        }
    }

    private static void bindKey(java.sql.PreparedStatement statement, String playerId, String source,
                                LocalDate asOfDate) throws SQLException {
        statement.setString(1, playerId);
        statement.setString(2, source);
        statement.setString(3, asOfDate.toString());
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
