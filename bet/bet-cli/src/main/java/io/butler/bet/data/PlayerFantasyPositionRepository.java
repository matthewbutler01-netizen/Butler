package io.butler.bet.data;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Persists the ordered fantasy-position eligibility declared by the player provider. */
public final class PlayerFantasyPositionRepository {
    private final Database database;

    public PlayerFantasyPositionRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public void replace(String playerId, List<String> positions) throws SQLException {
        String normalizedPlayerId = requireText(playerId, "playerId").trim();
        List<String> validatedPositions = validatePositions(positions);
        try (var connection = database.openConnection()) {
            ensureTable(connection);
            connection.setAutoCommit(false);
            try (var delete = connection.prepareStatement(
                    "DELETE FROM player_fantasy_positions WHERE player_id = ?")) {
                delete.setString(1, normalizedPlayerId);
                delete.executeUpdate();
            }
            try (var insert = connection.prepareStatement(
                    "INSERT INTO player_fantasy_positions(player_id, ordinal, position) VALUES(?,?,?)")) {
                for (int i = 0; i < validatedPositions.size(); i++) {
                    insert.setString(1, normalizedPlayerId);
                    insert.setInt(2, i);
                    insert.setString(3, validatedPositions.get(i));
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            connection.commit();
        }
    }

    public List<String> findByPlayerId(String playerId) throws SQLException {
        String normalizedPlayerId = requireText(playerId, "playerId").trim();
        try (var connection = database.openConnection()) {
            ensureTable(connection);
            try (var statement = connection.prepareStatement(
                    "SELECT position FROM player_fantasy_positions WHERE player_id = ? ORDER BY ordinal")) {
                statement.setString(1, normalizedPlayerId);
                try (var results = statement.executeQuery()) {
                    List<String> positions = new ArrayList<>();
                    while (results.next()) positions.add(results.getString("position"));
                    return List.copyOf(positions);
                }
            }
        }
    }

    private static void ensureTable(java.sql.Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS player_fantasy_positions (
                    player_id TEXT NOT NULL,
                    ordinal INTEGER NOT NULL,
                    position TEXT NOT NULL,
                    PRIMARY KEY (player_id, ordinal),
                    FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE,
                    CHECK (ordinal >= 0)
                )
                """);
        }
    }

    private static List<String> validatePositions(List<String> positions) {
        Objects.requireNonNull(positions, "positions must not be null");
        List<String> validated = new ArrayList<>();
        for (String position : positions) {
            requireText(position, "position");
            validated.add(position);
        }
        return List.copyOf(validated);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
