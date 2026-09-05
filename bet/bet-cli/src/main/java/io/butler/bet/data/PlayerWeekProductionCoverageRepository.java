package io.butler.bet.data;

import io.butler.bet.domain.PlayerWeekProductionCoverage;

import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Persists provider week-import coverage separately from player production rows. */
public final class PlayerWeekProductionCoverageRepository {
    private final Database database;

    public PlayerWeekProductionCoverageRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public void replace(PlayerWeekProductionCoverage coverage) throws SQLException {
        Objects.requireNonNull(coverage, "coverage must not be null");
        try (Connection connection = database.openConnection()) {
            ensureTables(connection);
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.prepareStatement("""
                    INSERT INTO player_week_production_coverage(
                        season, week, source, source_uri, as_of_date,
                        provider_rows, matched_player_weeks, unmatched_provider_rows)
                    VALUES(?,?,?,?,?,?,?,?)
                    ON CONFLICT(season, week, source, as_of_date) DO UPDATE SET
                        source_uri=excluded.source_uri,
                        provider_rows=excluded.provider_rows,
                        matched_player_weeks=excluded.matched_player_weeks,
                        unmatched_provider_rows=excluded.unmatched_provider_rows
                    """)) {
                    statement.setInt(1, coverage.season());
                    statement.setInt(2, coverage.week());
                    statement.setString(3, coverage.source());
                    statement.setString(4, coverage.sourceUri().toString());
                    statement.setString(5, coverage.asOfDate().toString());
                    statement.setInt(6, coverage.providerRows());
                    statement.setInt(7, coverage.matchedPlayerWeeks());
                    statement.setInt(8, coverage.unmatchedProviderRows());
                    statement.executeUpdate();
                }
                try (var delete = connection.prepareStatement("""
                    DELETE FROM player_week_production_identity_coverage
                    WHERE season=? AND week=? AND source=? AND as_of_date=?
                    """)) {
                    bindKey(delete, coverage.season(), coverage.week(), coverage.source(), coverage.asOfDate());
                    delete.executeUpdate();
                }
                try (var insert = connection.prepareStatement("""
                    INSERT INTO player_week_production_identity_coverage(
                        season, week, source, as_of_date, player_id) VALUES(?,?,?,?,?)
                    """)) {
                    for (String playerId : coverage.identityCoveredPlayerIds()) {
                        bindKey(insert, coverage.season(), coverage.week(), coverage.source(), coverage.asOfDate());
                        insert.setString(5, playerId);
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

    /**
     * Removes same-day coverage authorization before a refresh starts. If a later write fails,
     * partial production rows cannot inherit a stale same-day coverage marker.
     */
    public void deleteBySeasonAsOf(int season, String source, LocalDate asOfDate) throws SQLException {
        requireSeason(season);
        String normalizedSource = requireText(source, "source");
        Objects.requireNonNull(asOfDate, "asOfDate must not be null");
        try (Connection connection = database.openConnection()) {
            ensureTables(connection);
            try (var statement = connection.prepareStatement("""
                DELETE FROM player_week_production_coverage
                WHERE season=? AND source=? AND as_of_date=?
                """)) {
                statement.setInt(1, season);
                statement.setString(2, normalizedSource);
                statement.setString(3, asOfDate.toString());
                statement.executeUpdate();
            }
        }
    }

    public Optional<PlayerWeekProductionCoverage> findLatest(int season, int week, String source)
        throws SQLException {
        requireSeason(season);
        if (week <= 0) throw new IllegalArgumentException("week must be positive");
        String normalizedSource = requireText(source, "source");
        try (Connection connection = database.openConnection()) {
            ensureTables(connection);
            try (var statement = connection.prepareStatement("""
                SELECT season, week, source, source_uri, as_of_date,
                       provider_rows, matched_player_weeks, unmatched_provider_rows
                FROM player_week_production_coverage
                WHERE season=? AND week=? AND source=?
                ORDER BY as_of_date DESC
                LIMIT 1
                """)) {
                statement.setInt(1, season);
                statement.setInt(2, week);
                statement.setString(3, normalizedSource);
                try (var results = statement.executeQuery()) {
                    if (!results.next()) return Optional.empty();
                    LocalDate asOfDate = LocalDate.parse(results.getString("as_of_date"));
                    List<String> coveredPlayers = findCoveredPlayers(
                        connection, season, week, normalizedSource, asOfDate);
                    return Optional.of(new PlayerWeekProductionCoverage(
                        results.getInt("season"),
                        results.getInt("week"),
                        results.getString("source"),
                        URI.create(results.getString("source_uri")),
                        asOfDate,
                        results.getInt("provider_rows"),
                        results.getInt("matched_player_weeks"),
                        results.getInt("unmatched_provider_rows"),
                        coveredPlayers));
                }
            }
        }
    }

    private static List<String> findCoveredPlayers(Connection connection, int season, int week,
                                                    String source, LocalDate asOfDate) throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT player_id
            FROM player_week_production_identity_coverage
            WHERE season=? AND week=? AND source=? AND as_of_date=?
            ORDER BY player_id
            """)) {
            bindKey(statement, season, week, source, asOfDate);
            try (var results = statement.executeQuery()) {
                List<String> playerIds = new ArrayList<>();
                while (results.next()) playerIds.add(results.getString("player_id"));
                return List.copyOf(playerIds);
            }
        }
    }

    private static void ensureTables(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS player_week_production_coverage (
                    season INTEGER NOT NULL,
                    week INTEGER NOT NULL,
                    source TEXT NOT NULL,
                    source_uri TEXT NOT NULL,
                    as_of_date TEXT NOT NULL,
                    provider_rows INTEGER NOT NULL,
                    matched_player_weeks INTEGER NOT NULL,
                    unmatched_provider_rows INTEGER NOT NULL,
                    PRIMARY KEY (season, week, source, as_of_date),
                    CHECK (season >= 1999 AND season <= 2100),
                    CHECK (week > 0),
                    CHECK (provider_rows > 0),
                    CHECK (matched_player_weeks >= 0 AND matched_player_weeks <= provider_rows),
                    CHECK (unmatched_provider_rows >= 0 AND unmatched_provider_rows <= provider_rows)
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS player_week_production_identity_coverage (
                    season INTEGER NOT NULL,
                    week INTEGER NOT NULL,
                    source TEXT NOT NULL,
                    as_of_date TEXT NOT NULL,
                    player_id TEXT NOT NULL,
                    PRIMARY KEY (season, week, source, as_of_date, player_id),
                    FOREIGN KEY (season, week, source, as_of_date)
                        REFERENCES player_week_production_coverage(season, week, source, as_of_date)
                        ON DELETE CASCADE,
                    FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE
                )
                """);
            statement.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_player_week_production_coverage_latest
                ON player_week_production_coverage(season, week, source, as_of_date DESC)
                """);
        }
    }

    private static void bindKey(java.sql.PreparedStatement statement, int season, int week,
                                String source, LocalDate asOfDate) throws SQLException {
        statement.setInt(1, season);
        statement.setInt(2, week);
        statement.setString(3, source);
        statement.setString(4, asOfDate.toString());
    }

    private static void requireSeason(int season) {
        if (season < 1999 || season > 2100) {
            throw new IllegalArgumentException("season must be between 1999 and 2100");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
