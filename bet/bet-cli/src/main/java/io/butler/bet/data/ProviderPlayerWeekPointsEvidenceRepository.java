package io.butler.bet.data;

import io.butler.bet.domain.ProviderPlayerWeekPointsEvidence;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Persists exact provider-returned league-scored points without coercing through double. */
public final class ProviderPlayerWeekPointsEvidenceRepository {
    private final Database database;

    public ProviderPlayerWeekPointsEvidenceRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    /**
     * Atomically replaces one league-season/source/as-of snapshot. All supplied rows must belong
     * to that exact snapshot. Points are stored as decimal text to preserve provider precision.
     */
    public void replaceSeasonSnapshot(
        String leagueId,
        int season,
        String source,
        LocalDate asOfDate,
        List<ProviderPlayerWeekPointsEvidence> evidence) throws SQLException {
        leagueId = requireText(leagueId, "leagueId");
        source = requireText(source, "source");
        Objects.requireNonNull(asOfDate, "asOfDate must not be null");
        Objects.requireNonNull(evidence, "evidence must not be null");
        if (season < 1999 || season > 2100) {
            throw new IllegalArgumentException("season must be between 1999 and 2100");
        }
        if (evidence.isEmpty()) throw new IllegalArgumentException("evidence must not be empty");
        for (var row : evidence) {
            Objects.requireNonNull(row, "evidence row must not be null");
            if (!leagueId.equals(row.leagueId()) || season != row.season()
                || !source.equals(row.source()) || !asOfDate.equals(row.asOfDate())) {
                throw new IllegalArgumentException("all evidence rows must belong to the requested snapshot");
            }
        }

        try (Connection connection = database.openConnection()) {
            ensureTables(connection);
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM provider_player_week_points_evidence " +
                        "WHERE league_id=? AND season=? AND source=? AND as_of_date=?")) {
                    delete.setString(1, leagueId);
                    delete.setInt(2, season);
                    delete.setString(3, source);
                    delete.setString(4, asOfDate.toString());
                    delete.executeUpdate();
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO provider_player_week_points_evidence(
                        id, league_id, team_id, provider_roster_id, provider_league_id,
                        season, week, provider_player_id, points_decimal, source,
                        source_surface, as_of_date)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                    """)) {
                    for (var row : evidence) {
                        insert.setString(1, row.id());
                        insert.setString(2, row.leagueId());
                        insert.setString(3, row.teamId());
                        insert.setString(4, row.providerRosterId());
                        insert.setString(5, row.providerLeagueId());
                        insert.setInt(6, row.season());
                        insert.setInt(7, row.week());
                        insert.setString(8, row.providerPlayerId());
                        insert.setString(9, row.points().toPlainString());
                        insert.setString(10, row.source());
                        insert.setString(11, row.sourceSurface());
                        insert.setString(12, row.asOfDate().toString());
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

    /** Returns the newest complete snapshot, deterministically ordered by week/team/provider identity. */
    public List<ProviderPlayerWeekPointsEvidence> findLatestByLeagueSeason(
        String leagueId, int season, String source) throws SQLException {
        leagueId = requireText(leagueId, "leagueId");
        source = requireText(source, "source");
        if (season < 1999 || season > 2100) {
            throw new IllegalArgumentException("season must be between 1999 and 2100");
        }
        try (Connection connection = database.openConnection()) {
            ensureTables(connection);
            String latestDate = null;
            try (PreparedStatement latest = connection.prepareStatement(
                "SELECT MAX(as_of_date) AS latest_date FROM provider_player_week_points_evidence " +
                    "WHERE league_id=? AND season=? AND source=?")) {
                latest.setString(1, leagueId);
                latest.setInt(2, season);
                latest.setString(3, source);
                try (ResultSet rs = latest.executeQuery()) {
                    if (rs.next()) latestDate = rs.getString("latest_date");
                }
            }
            if (latestDate == null) return List.of();
            return findSnapshot(connection, leagueId, season, source, LocalDate.parse(latestDate));
        }
    }

    /**
     * Enumerates the complete persisted evidence universe for one source. A league-season appears
     * exactly once regardless of how many snapshots or player-week rows exist for it.
     */
    public List<LeagueSeasonRef> findDistinctLeagueSeasons(String source) throws SQLException {
        source = requireText(source, "source");
        List<LeagueSeasonRef> result = new ArrayList<>();
        try (Connection connection = database.openConnection()) {
            ensureTables(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT DISTINCT league_id, season FROM provider_player_week_points_evidence " +
                    "WHERE source=? ORDER BY season ASC, league_id ASC")) {
                statement.setString(1, source);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        result.add(new LeagueSeasonRef(rs.getString("league_id"), rs.getInt("season")));
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    public List<ProviderPlayerWeekPointsEvidence> findSnapshot(
        String leagueId, int season, String source, LocalDate asOfDate) throws SQLException {
        leagueId = requireText(leagueId, "leagueId");
        source = requireText(source, "source");
        Objects.requireNonNull(asOfDate, "asOfDate must not be null");
        try (Connection connection = database.openConnection()) {
            ensureTables(connection);
            return findSnapshot(connection, leagueId, season, source, asOfDate);
        }
    }

    private static List<ProviderPlayerWeekPointsEvidence> findSnapshot(
        Connection connection, String leagueId, int season, String source, LocalDate asOfDate)
        throws SQLException {
        List<ProviderPlayerWeekPointsEvidence> result = new ArrayList<>();
        String sql = "SELECT id, league_id, team_id, provider_roster_id, provider_league_id, " +
            "season, week, provider_player_id, points_decimal, source, source_surface, as_of_date " +
            "FROM provider_player_week_points_evidence " +
            "WHERE league_id=? AND season=? AND source=? AND as_of_date=? " +
            "ORDER BY week ASC, team_id ASC, provider_player_id ASC";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, leagueId);
            statement.setInt(2, season);
            statement.setString(3, source);
            statement.setString(4, asOfDate.toString());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) result.add(map(rs));
            }
        }
        return List.copyOf(result);
    }

    private static ProviderPlayerWeekPointsEvidence map(ResultSet rs) throws SQLException {
        return new ProviderPlayerWeekPointsEvidence(
            rs.getString("id"),
            rs.getString("league_id"),
            rs.getString("team_id"),
            rs.getString("provider_roster_id"),
            rs.getString("provider_league_id"),
            rs.getInt("season"),
            rs.getInt("week"),
            rs.getString("provider_player_id"),
            new BigDecimal(rs.getString("points_decimal")),
            rs.getString("source"),
            rs.getString("source_surface"),
            LocalDate.parse(rs.getString("as_of_date")));
    }

    private static void ensureTables(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS provider_player_week_points_evidence (
                    id TEXT PRIMARY KEY,
                    league_id TEXT NOT NULL,
                    team_id TEXT NOT NULL,
                    provider_roster_id TEXT NOT NULL,
                    provider_league_id TEXT NOT NULL,
                    season INTEGER NOT NULL,
                    week INTEGER NOT NULL,
                    provider_player_id TEXT NOT NULL,
                    points_decimal TEXT NOT NULL,
                    source TEXT NOT NULL,
                    source_surface TEXT NOT NULL,
                    as_of_date TEXT NOT NULL,
                    UNIQUE(league_id, season, week, provider_player_id, source, as_of_date),
                    FOREIGN KEY (league_id) REFERENCES leagues(id) ON DELETE CASCADE,
                    FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE,
                    CHECK (season BETWEEN 1999 AND 2100),
                    CHECK (week > 0)
                )
                """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_provider_player_week_points_latest " +
                "ON provider_player_week_points_evidence(league_id, season, source, as_of_date DESC, week)");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    public record LeagueSeasonRef(String leagueId, int season) {
        public LeagueSeasonRef {
            leagueId = requireText(leagueId, "leagueId");
            if (season < 1999 || season > 2100) throw new IllegalArgumentException("invalid season");
        }
    }
}
