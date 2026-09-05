package io.butler.bet.data;

import io.butler.bet.domain.LeagueConfigurationObservation;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Persists dated provider observations of league lineup slots and scoring settings together. */
public final class LeagueConfigurationObservationRepository {
    private final Database database;

    public LeagueConfigurationObservationRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public void replace(LeagueConfigurationObservation observation) throws SQLException {
        Objects.requireNonNull(observation, "observation must not be null");
        try (Connection connection = database.openConnection()) {
            ensureTables(connection);
            connection.setAutoCommit(false);
            try {
                try (var parent = connection.prepareStatement("""
                    INSERT INTO league_configuration_observations(
                        league_id, source, as_of_date, provider_season)
                    VALUES(?,?,?,?)
                    ON CONFLICT(league_id, source, as_of_date) DO UPDATE SET
                        provider_season=excluded.provider_season
                    """)) {
                    parent.setString(1, observation.leagueId());
                    parent.setString(2, observation.source());
                    parent.setString(3, observation.asOfDate().toString());
                    if (observation.providerSeason() == null) parent.setNull(4, java.sql.Types.INTEGER);
                    else parent.setInt(4, observation.providerSeason());
                    parent.executeUpdate();
                }
                deleteChildren(connection, observation);
                insertSlots(connection, observation);
                insertScoring(connection, observation);
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    public Optional<LeagueConfigurationObservation> findLatest(String leagueId, String source)
        throws SQLException {
        return findLatestInternal(requireText(leagueId, "leagueId").trim(),
            requireText(source, "source").trim(), null);
    }

    public Optional<LeagueConfigurationObservation> findLatestForSeason(
        String leagueId, int providerSeason, String source) throws SQLException {
        if (providerSeason < 1999 || providerSeason > 2100) {
            throw new IllegalArgumentException("providerSeason must be between 1999 and 2100");
        }
        return findLatestInternal(requireText(leagueId, "leagueId").trim(),
            requireText(source, "source").trim(), providerSeason);
    }

    private Optional<LeagueConfigurationObservation> findLatestInternal(
        String leagueId, String source, Integer providerSeason) throws SQLException {
        try (Connection connection = database.openConnection()) {
            ensureTables(connection);
            String sql = "SELECT provider_season, as_of_date FROM league_configuration_observations "
                + "WHERE league_id=? AND source=?"
                + (providerSeason == null ? "" : " AND provider_season=?")
                + " ORDER BY as_of_date DESC LIMIT 1";
            try (var statement = connection.prepareStatement(sql)) {
                statement.setString(1, leagueId);
                statement.setString(2, source);
                if (providerSeason != null) statement.setInt(3, providerSeason);
                try (var results = statement.executeQuery()) {
                    if (!results.next()) return Optional.empty();
                    Integer observedSeason = (Integer) results.getObject("provider_season");
                    LocalDate asOfDate = LocalDate.parse(results.getString("as_of_date"));
                    return Optional.of(new LeagueConfigurationObservation(
                        leagueId,
                        source,
                        asOfDate,
                        observedSeason,
                        findSlots(connection, leagueId, source, asOfDate),
                        findScoring(connection, leagueId, source, asOfDate)));
                }
            }
        }
    }

    private static void deleteChildren(Connection connection, LeagueConfigurationObservation observation)
        throws SQLException {
        for (String table : List.of(
            "league_configuration_observation_slots",
            "league_configuration_observation_scoring")) {
            try (var delete = connection.prepareStatement(
                "DELETE FROM " + table + " WHERE league_id=? AND source=? AND as_of_date=?")) {
                bindKey(delete, observation.leagueId(), observation.source(), observation.asOfDate());
                delete.executeUpdate();
            }
        }
    }

    private static void insertSlots(Connection connection, LeagueConfigurationObservation observation)
        throws SQLException {
        try (var insert = connection.prepareStatement("""
            INSERT INTO league_configuration_observation_slots(
                league_id, source, as_of_date, ordinal, slot) VALUES(?,?,?,?,?)
            """)) {
            for (int ordinal = 0; ordinal < observation.lineupSlots().size(); ordinal++) {
                bindKey(insert, observation.leagueId(), observation.source(), observation.asOfDate());
                insert.setInt(4, ordinal);
                insert.setString(5, observation.lineupSlots().get(ordinal));
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private static void insertScoring(Connection connection, LeagueConfigurationObservation observation)
        throws SQLException {
        try (var insert = connection.prepareStatement("""
            INSERT INTO league_configuration_observation_scoring(
                league_id, source, as_of_date, stat_key, points_per_unit) VALUES(?,?,?,?,?)
            """)) {
            for (var entry : observation.scoringSettings().entrySet()) {
                bindKey(insert, observation.leagueId(), observation.source(), observation.asOfDate());
                insert.setString(4, entry.getKey());
                insert.setDouble(5, entry.getValue());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private static List<String> findSlots(Connection connection, String leagueId, String source,
                                          LocalDate asOfDate) throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT slot FROM league_configuration_observation_slots
            WHERE league_id=? AND source=? AND as_of_date=? ORDER BY ordinal
            """)) {
            bindKey(statement, leagueId, source, asOfDate);
            try (var results = statement.executeQuery()) {
                List<String> slots = new ArrayList<>();
                while (results.next()) slots.add(results.getString("slot"));
                return List.copyOf(slots);
            }
        }
    }

    private static Map<String, Double> findScoring(Connection connection, String leagueId, String source,
                                                    LocalDate asOfDate) throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT stat_key, points_per_unit FROM league_configuration_observation_scoring
            WHERE league_id=? AND source=? AND as_of_date=? ORDER BY stat_key
            """)) {
            bindKey(statement, leagueId, source, asOfDate);
            try (var results = statement.executeQuery()) {
                Map<String, Double> settings = new LinkedHashMap<>();
                while (results.next()) {
                    settings.put(results.getString("stat_key"), results.getDouble("points_per_unit"));
                }
                return Map.copyOf(settings);
            }
        }
    }

    private static void ensureTables(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS league_configuration_observations (
                    league_id TEXT NOT NULL,
                    source TEXT NOT NULL,
                    as_of_date TEXT NOT NULL,
                    provider_season INTEGER,
                    PRIMARY KEY (league_id, source, as_of_date),
                    FOREIGN KEY (league_id) REFERENCES leagues(id) ON DELETE CASCADE,
                    CHECK (provider_season IS NULL OR (provider_season >= 1999 AND provider_season <= 2100))
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS league_configuration_observation_slots (
                    league_id TEXT NOT NULL,
                    source TEXT NOT NULL,
                    as_of_date TEXT NOT NULL,
                    ordinal INTEGER NOT NULL,
                    slot TEXT NOT NULL,
                    PRIMARY KEY (league_id, source, as_of_date, ordinal),
                    FOREIGN KEY (league_id, source, as_of_date)
                        REFERENCES league_configuration_observations(league_id, source, as_of_date)
                        ON DELETE CASCADE,
                    CHECK (ordinal >= 0)
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS league_configuration_observation_scoring (
                    league_id TEXT NOT NULL,
                    source TEXT NOT NULL,
                    as_of_date TEXT NOT NULL,
                    stat_key TEXT NOT NULL,
                    points_per_unit REAL NOT NULL,
                    PRIMARY KEY (league_id, source, as_of_date, stat_key),
                    FOREIGN KEY (league_id, source, as_of_date)
                        REFERENCES league_configuration_observations(league_id, source, as_of_date)
                        ON DELETE CASCADE
                )
                """);
            statement.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_league_configuration_observations_latest
                ON league_configuration_observations(league_id, source, provider_season, as_of_date DESC)
                """);
        }
    }

    private static void bindKey(java.sql.PreparedStatement statement, String leagueId, String source,
                                LocalDate asOfDate) throws SQLException {
        statement.setString(1, leagueId);
        statement.setString(2, source);
        statement.setString(3, asOfDate.toString());
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
