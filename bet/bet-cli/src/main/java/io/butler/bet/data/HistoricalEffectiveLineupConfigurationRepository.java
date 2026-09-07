package io.butler.bet.data;

import io.butler.bet.domain.HistoricalEffectiveLineupConfiguration;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Persists BF-592 effective historical lineup derivations without mutating raw provider configuration. */
public final class HistoricalEffectiveLineupConfigurationRepository {
    private final Database database;

    public HistoricalEffectiveLineupConfigurationRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public void replace(HistoricalEffectiveLineupConfiguration value) throws SQLException {
        Objects.requireNonNull(value, "value must not be null");
        try (Connection connection = database.openConnection()) {
            ensureTables(connection);
            connection.setAutoCommit(false);
            try {
                try (var parent = connection.prepareStatement("""
                    INSERT INTO historical_effective_lineup_configurations(
                        league_id, season, source, raw_configuration_as_of, derived_as_of,
                        provider_league_id, predecessor_provider_league_id, predecessor_season,
                        omitted_ordinal, omitted_slot, derivation_policy_id)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?)
                    ON CONFLICT(league_id, season, source, derived_as_of) DO UPDATE SET
                        raw_configuration_as_of=excluded.raw_configuration_as_of,
                        provider_league_id=excluded.provider_league_id,
                        predecessor_provider_league_id=excluded.predecessor_provider_league_id,
                        predecessor_season=excluded.predecessor_season,
                        omitted_ordinal=excluded.omitted_ordinal,
                        omitted_slot=excluded.omitted_slot,
                        derivation_policy_id=excluded.derivation_policy_id
                    """)) {
                    bindParent(parent, value);
                    parent.executeUpdate();
                }
                deleteSlots(connection, value);
                insertSlots(connection, value, "raw", value.rawSupportedStartingSlots());
                insertSlots(connection, value, "effective", value.effectiveSupportedStartingSlots());
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    public Optional<HistoricalEffectiveLineupConfiguration> findLatestForSeason(
        String leagueId, int season, String source) throws SQLException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        String normalizedSource = requireText(source, "source");
        if (season < 1999 || season > 2100) {
            throw new IllegalArgumentException("season must be between 1999 and 2100");
        }
        try (Connection connection = database.openConnection()) {
            ensureTables(connection);
            try (var statement = connection.prepareStatement("""
                SELECT raw_configuration_as_of, derived_as_of, provider_league_id,
                       predecessor_provider_league_id, predecessor_season,
                       omitted_ordinal, omitted_slot, derivation_policy_id
                FROM historical_effective_lineup_configurations
                WHERE league_id=? AND season=? AND source=?
                ORDER BY derived_as_of DESC
                LIMIT 1
                """)) {
                statement.setString(1, normalizedLeagueId);
                statement.setInt(2, season);
                statement.setString(3, normalizedSource);
                try (var rs = statement.executeQuery()) {
                    if (!rs.next()) return Optional.empty();
                    LocalDate derivedAsOf = LocalDate.parse(rs.getString("derived_as_of"));
                    return Optional.of(new HistoricalEffectiveLineupConfiguration(
                        normalizedLeagueId,
                        season,
                        normalizedSource,
                        LocalDate.parse(rs.getString("raw_configuration_as_of")),
                        derivedAsOf,
                        rs.getString("provider_league_id"),
                        rs.getString("predecessor_provider_league_id"),
                        rs.getInt("predecessor_season"),
                        rs.getInt("omitted_ordinal"),
                        rs.getString("omitted_slot"),
                        findSlots(connection, normalizedLeagueId, season, normalizedSource, derivedAsOf, "raw"),
                        findSlots(connection, normalizedLeagueId, season, normalizedSource, derivedAsOf, "effective"),
                        rs.getString("derivation_policy_id")));
                }
            }
        }
    }

    private static void ensureTables(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS historical_effective_lineup_configurations (
                    league_id TEXT NOT NULL,
                    season INTEGER NOT NULL,
                    source TEXT NOT NULL,
                    raw_configuration_as_of TEXT NOT NULL,
                    derived_as_of TEXT NOT NULL,
                    provider_league_id TEXT NOT NULL,
                    predecessor_provider_league_id TEXT NOT NULL,
                    predecessor_season INTEGER NOT NULL,
                    omitted_ordinal INTEGER NOT NULL,
                    omitted_slot TEXT NOT NULL,
                    derivation_policy_id TEXT NOT NULL,
                    PRIMARY KEY (league_id, season, source, derived_as_of),
                    FOREIGN KEY (league_id) REFERENCES leagues(id) ON DELETE CASCADE,
                    CHECK (season BETWEEN 1999 AND 2100),
                    CHECK (predecessor_season BETWEEN 1999 AND 2100),
                    CHECK (omitted_ordinal >= 0)
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS historical_effective_lineup_configuration_slots (
                    league_id TEXT NOT NULL,
                    season INTEGER NOT NULL,
                    source TEXT NOT NULL,
                    derived_as_of TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    ordinal INTEGER NOT NULL,
                    slot TEXT NOT NULL,
                    PRIMARY KEY (league_id, season, source, derived_as_of, kind, ordinal),
                    FOREIGN KEY (league_id, season, source, derived_as_of)
                        REFERENCES historical_effective_lineup_configurations(
                            league_id, season, source, derived_as_of) ON DELETE CASCADE,
                    CHECK (kind IN ('raw','effective')),
                    CHECK (ordinal >= 0)
                )
                """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_historical_effective_lineup_latest "
                + "ON historical_effective_lineup_configurations(league_id, season, source, derived_as_of DESC)");
        }
    }

    private static void bindParent(
        java.sql.PreparedStatement statement,
        HistoricalEffectiveLineupConfiguration value) throws SQLException {
        statement.setString(1, value.leagueId());
        statement.setInt(2, value.season());
        statement.setString(3, value.source());
        statement.setString(4, value.rawConfigurationAsOf().toString());
        statement.setString(5, value.derivedAsOf().toString());
        statement.setString(6, value.providerLeagueId());
        statement.setString(7, value.predecessorProviderLeagueId());
        statement.setInt(8, value.predecessorSeason());
        statement.setInt(9, value.omittedOrdinal());
        statement.setString(10, value.omittedSlot());
        statement.setString(11, value.derivationPolicyId());
    }

    private static void deleteSlots(Connection connection, HistoricalEffectiveLineupConfiguration value)
        throws SQLException {
        try (var statement = connection.prepareStatement("""
            DELETE FROM historical_effective_lineup_configuration_slots
            WHERE league_id=? AND season=? AND source=? AND derived_as_of=?
            """)) {
            statement.setString(1, value.leagueId());
            statement.setInt(2, value.season());
            statement.setString(3, value.source());
            statement.setString(4, value.derivedAsOf().toString());
            statement.executeUpdate();
        }
    }

    private static void insertSlots(
        Connection connection,
        HistoricalEffectiveLineupConfiguration value,
        String kind,
        List<String> slots) throws SQLException {
        try (var statement = connection.prepareStatement("""
            INSERT INTO historical_effective_lineup_configuration_slots(
                league_id, season, source, derived_as_of, kind, ordinal, slot)
            VALUES(?,?,?,?,?,?,?)
            """)) {
            for (int ordinal = 0; ordinal < slots.size(); ordinal++) {
                statement.setString(1, value.leagueId());
                statement.setInt(2, value.season());
                statement.setString(3, value.source());
                statement.setString(4, value.derivedAsOf().toString());
                statement.setString(5, kind);
                statement.setInt(6, ordinal);
                statement.setString(7, slots.get(ordinal));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static List<String> findSlots(
        Connection connection,
        String leagueId,
        int season,
        String source,
        LocalDate derivedAsOf,
        String kind) throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT slot FROM historical_effective_lineup_configuration_slots
            WHERE league_id=? AND season=? AND source=? AND derived_as_of=? AND kind=?
            ORDER BY ordinal
            """)) {
            statement.setString(1, leagueId);
            statement.setInt(2, season);
            statement.setString(3, source);
            statement.setString(4, derivedAsOf.toString());
            statement.setString(5, kind);
            try (var rs = statement.executeQuery()) {
                List<String> result = new ArrayList<>();
                while (rs.next()) result.add(rs.getString("slot"));
                return List.copyOf(result);
            }
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
