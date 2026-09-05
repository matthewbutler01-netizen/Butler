package io.butler.bet.data;

import io.butler.bet.domain.TeamWeekRosterEvidence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Persists raw week-specific provider roster membership and ordered starter snapshots. */
public final class TeamWeekRosterEvidenceRepository {
    private final Database database;

    public TeamWeekRosterEvidenceRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public void save(TeamWeekRosterEvidence evidence) throws SQLException {
        Objects.requireNonNull(evidence, "evidence must not be null");
        try (Connection connection = database.openConnection()) {
            ensureTables(connection);
            connection.setAutoCommit(false);
            try {
                String evidenceId = findSnapshotId(connection, evidence).orElse(null);
                if (evidenceId == null) {
                    evidenceId = evidence.id();
                    try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO team_week_roster_evidence(" +
                            "id, league_id, team_id, season, week, source, as_of_date) VALUES(?,?,?,?,?,?,?)")) {
                        insert.setString(1, evidenceId);
                        insert.setString(2, evidence.leagueId());
                        insert.setString(3, evidence.teamId());
                        insert.setInt(4, evidence.season());
                        insert.setInt(5, evidence.week());
                        insert.setString(6, evidence.source());
                        insert.setString(7, evidence.asOfDate().toString());
                        insert.executeUpdate();
                    }
                }

                deleteChildren(connection, evidenceId);
                insertOrdered(connection, "team_week_roster_evidence_players", evidenceId, evidence.providerPlayerIds());
                insertOrdered(connection, "team_week_roster_evidence_starters", evidenceId, evidence.providerStarterIds());
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    public Optional<TeamWeekRosterEvidence> findLatest(String teamId, int season, int week, String source)
        throws SQLException {
        requireText(teamId, "teamId");
        requireText(source, "source");
        if (season < 1999 || season > 2100) throw new IllegalArgumentException("season must be between 1999 and 2100");
        if (week <= 0) throw new IllegalArgumentException("week must be positive");

        try (Connection connection = database.openConnection()) {
            ensureTables(connection);
            String sql = "SELECT id, league_id, team_id, season, week, source, as_of_date " +
                "FROM team_week_roster_evidence WHERE team_id=? AND season=? AND week=? AND source=? " +
                "ORDER BY as_of_date DESC, id DESC LIMIT 1";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, teamId.trim());
                statement.setInt(2, season);
                statement.setInt(3, week);
                statement.setString(4, source.trim());
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) return Optional.empty();
                    return Optional.of(map(connection, rs));
                }
            }
        }
    }

    /** Returns exactly one latest persisted roster snapshot for each observed team week, in week order. */
    public List<TeamWeekRosterEvidence> findLatestByTeamSeason(
        String leagueId, String teamId, int season, String source) throws SQLException {
        requireText(leagueId, "leagueId");
        requireText(teamId, "teamId");
        requireText(source, "source");
        if (season < 1999 || season > 2100) throw new IllegalArgumentException("season must be between 1999 and 2100");

        List<TeamWeekRosterEvidence> result = new ArrayList<>();
        try (Connection connection = database.openConnection()) {
            ensureTables(connection);
            String sql = "SELECT id, league_id, team_id, season, week, source, as_of_date " +
                "FROM team_week_roster_evidence WHERE league_id=? AND team_id=? AND season=? AND source=? " +
                "ORDER BY week ASC, as_of_date DESC, id DESC";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, leagueId.trim());
                statement.setString(2, teamId.trim());
                statement.setInt(3, season);
                statement.setString(4, source.trim());
                try (ResultSet rs = statement.executeQuery()) {
                    int previousWeek = -1;
                    while (rs.next()) {
                        int week = rs.getInt("week");
                        if (week == previousWeek) continue;
                        result.add(map(connection, rs));
                        previousWeek = week;
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    private static TeamWeekRosterEvidence map(Connection connection, ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        return new TeamWeekRosterEvidence(
            id,
            rs.getString("league_id"),
            rs.getString("team_id"),
            rs.getInt("season"),
            rs.getInt("week"),
            findOrdered(connection, "team_week_roster_evidence_players", id),
            findOrdered(connection, "team_week_roster_evidence_starters", id),
            rs.getString("source"),
            LocalDate.parse(rs.getString("as_of_date")));
    }

    private static Optional<String> findSnapshotId(Connection connection, TeamWeekRosterEvidence evidence)
        throws SQLException {
        String sql = "SELECT id FROM team_week_roster_evidence " +
            "WHERE league_id=? AND team_id=? AND season=? AND week=? AND source=? AND as_of_date=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, evidence.leagueId());
            statement.setString(2, evidence.teamId());
            statement.setInt(3, evidence.season());
            statement.setInt(4, evidence.week());
            statement.setString(5, evidence.source());
            statement.setString(6, evidence.asOfDate().toString());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString("id")) : Optional.empty();
            }
        }
    }

    private static void deleteChildren(Connection connection, String evidenceId) throws SQLException {
        for (String table : List.of("team_week_roster_evidence_players", "team_week_roster_evidence_starters")) {
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM " + table + " WHERE evidence_id=?")) {
                delete.setString(1, evidenceId);
                delete.executeUpdate();
            }
        }
    }

    private static void insertOrdered(Connection connection, String table, String evidenceId, List<String> playerIds)
        throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
            "INSERT INTO " + table + "(evidence_id, ordinal, player_external_id) VALUES(?,?,?)")) {
            for (int ordinal = 0; ordinal < playerIds.size(); ordinal++) {
                insert.setString(1, evidenceId);
                insert.setInt(2, ordinal);
                insert.setString(3, playerIds.get(ordinal));
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private static List<String> findOrdered(Connection connection, String table, String evidenceId) throws SQLException {
        List<String> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT player_external_id FROM " + table + " WHERE evidence_id=? ORDER BY ordinal")) {
            statement.setString(1, evidenceId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) result.add(rs.getString("player_external_id"));
            }
        }
        return List.copyOf(result);
    }

    private static void ensureTables(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS team_week_roster_evidence (
                    id TEXT PRIMARY KEY,
                    league_id TEXT NOT NULL,
                    team_id TEXT NOT NULL,
                    season INTEGER NOT NULL,
                    week INTEGER NOT NULL,
                    source TEXT NOT NULL,
                    as_of_date TEXT NOT NULL,
                    UNIQUE(league_id, team_id, season, week, source, as_of_date),
                    FOREIGN KEY (league_id) REFERENCES leagues(id) ON DELETE CASCADE,
                    FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE,
                    CHECK (season BETWEEN 1999 AND 2100),
                    CHECK (week > 0)
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS team_week_roster_evidence_players (
                    evidence_id TEXT NOT NULL,
                    ordinal INTEGER NOT NULL,
                    player_external_id TEXT NOT NULL,
                    PRIMARY KEY (evidence_id, ordinal),
                    FOREIGN KEY (evidence_id) REFERENCES team_week_roster_evidence(id) ON DELETE CASCADE,
                    CHECK (ordinal >= 0)
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS team_week_roster_evidence_starters (
                    evidence_id TEXT NOT NULL,
                    ordinal INTEGER NOT NULL,
                    player_external_id TEXT NOT NULL,
                    PRIMARY KEY (evidence_id, ordinal),
                    FOREIGN KEY (evidence_id) REFERENCES team_week_roster_evidence(id) ON DELETE CASCADE,
                    CHECK (ordinal >= 0)
                )
                """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_team_week_roster_evidence_lookup " +
                "ON team_week_roster_evidence(team_id, season, week, source, as_of_date DESC)");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
