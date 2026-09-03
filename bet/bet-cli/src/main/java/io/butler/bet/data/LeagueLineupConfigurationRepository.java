package io.butler.bet.data;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Persists the ordered lineup-slot configuration supplied by the league provider. */
public final class LeagueLineupConfigurationRepository {
    private final Database database;

    public LeagueLineupConfigurationRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public void replace(String leagueId, List<String> slots) throws SQLException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        List<String> normalizedSlots = normalizeSlots(slots);
        try (var connection = database.openConnection()) {
            ensureTable(connection);
            connection.setAutoCommit(false);
            try (var delete = connection.prepareStatement("DELETE FROM league_lineup_slots WHERE league_id = ?")) {
                delete.setString(1, normalizedLeagueId);
                delete.executeUpdate();
            }
            try (var insert = connection.prepareStatement(
                "INSERT INTO league_lineup_slots(league_id, ordinal, slot) VALUES(?,?,?)")) {
                for (int i = 0; i < normalizedSlots.size(); i++) {
                    insert.setString(1, normalizedLeagueId);
                    insert.setInt(2, i);
                    insert.setString(3, normalizedSlots.get(i));
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            connection.commit();
        }
    }

    public List<String> findByLeagueId(String leagueId) throws SQLException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        try (var connection = database.openConnection()) {
            ensureTable(connection);
            try (var statement = connection.prepareStatement(
                "SELECT slot FROM league_lineup_slots WHERE league_id = ? ORDER BY ordinal")) {
                statement.setString(1, normalizedLeagueId);
                try (var results = statement.executeQuery()) {
                    List<String> slots = new ArrayList<>();
                    while (results.next()) slots.add(results.getString("slot"));
                    return List.copyOf(slots);
                }
            }
        }
    }

    private static void ensureTable(java.sql.Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS league_lineup_slots (
                    league_id TEXT NOT NULL,
                    ordinal INTEGER NOT NULL,
                    slot TEXT NOT NULL,
                    PRIMARY KEY (league_id, ordinal),
                    FOREIGN KEY (league_id) REFERENCES leagues(id) ON DELETE CASCADE,
                    CHECK (ordinal >= 0)
                )
                """);
        }
    }

    private static List<String> normalizeSlots(List<String> slots) {
        Objects.requireNonNull(slots, "slots must not be null");
        List<String> normalized = new ArrayList<>();
        for (String slot : slots) normalized.add(requireText(slot, "slot"));
        return List.copyOf(normalized);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
