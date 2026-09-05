package io.butler.bet.data;

import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Persists the exact numeric scoring map supplied by the league provider. */
public final class LeagueScoringSettingsRepository {
    private final Database database;

    public LeagueScoringSettingsRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public void replace(String leagueId, Map<String, Double> settings) throws SQLException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        Map<String, Double> normalizedSettings = normalize(settings);
        try (var connection = database.openConnection()) {
            ensureTable(connection);
            connection.setAutoCommit(false);
            try {
                try (var delete = connection.prepareStatement(
                    "DELETE FROM league_scoring_settings WHERE league_id = ?")) {
                    delete.setString(1, normalizedLeagueId);
                    delete.executeUpdate();
                }
                try (var insert = connection.prepareStatement("""
                    INSERT INTO league_scoring_settings(league_id, stat_key, points_per_unit)
                    VALUES(?,?,?)
                    """)) {
                    for (var entry : normalizedSettings.entrySet()) {
                        insert.setString(1, normalizedLeagueId);
                        insert.setString(2, entry.getKey());
                        insert.setDouble(3, entry.getValue());
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public Map<String, Double> findByLeagueId(String leagueId) throws SQLException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        try (var connection = database.openConnection()) {
            ensureTable(connection);
            try (var statement = connection.prepareStatement("""
                SELECT stat_key, points_per_unit
                FROM league_scoring_settings
                WHERE league_id = ?
                ORDER BY stat_key
                """)) {
                statement.setString(1, normalizedLeagueId);
                try (var results = statement.executeQuery()) {
                    Map<String, Double> settings = new LinkedHashMap<>();
                    while (results.next()) {
                        settings.put(results.getString("stat_key"), results.getDouble("points_per_unit"));
                    }
                    return Collections.unmodifiableMap(settings);
                }
            }
        }
    }

    private static void ensureTable(java.sql.Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS league_scoring_settings (
                    league_id TEXT NOT NULL,
                    stat_key TEXT NOT NULL,
                    points_per_unit REAL NOT NULL,
                    PRIMARY KEY (league_id, stat_key),
                    FOREIGN KEY (league_id) REFERENCES leagues(id) ON DELETE CASCADE,
                    CHECK (length(trim(stat_key)) > 0)
                )
                """);
        }
    }

    private static Map<String, Double> normalize(Map<String, Double> settings) {
        Objects.requireNonNull(settings, "settings must not be null");
        Map<String, Double> normalized = new LinkedHashMap<>();
        settings.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> {
                String key = requireText(entry.getKey(), "stat key");
                Double value = Objects.requireNonNull(entry.getValue(), "scoring value must not be null");
                if (!Double.isFinite(value)) {
                    throw new IllegalArgumentException("scoring value must be finite for " + key);
                }
                normalized.put(key, value);
            });
        return Collections.unmodifiableMap(normalized);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
