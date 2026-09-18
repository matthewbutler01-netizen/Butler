package io.butler.bet.data;

import io.butler.bet.domain.TeamWeekMatchupEvidence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Persists exact provider matchup identity separately from roster/starter evidence. */
public final class TeamWeekMatchupEvidenceRepository {
    private final Database database;

    public TeamWeekMatchupEvidenceRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public void save(TeamWeekMatchupEvidence evidence) throws SQLException {
        Objects.requireNonNull(evidence, "evidence must not be null");
        try (Connection connection = database.openConnection()) {
            ensureTable(connection);
            String sql = "INSERT INTO team_week_matchup_evidence(" +
                "id, league_id, team_id, season, week, provider_matchup_id, source, as_of_date) " +
                "VALUES(?,?,?,?,?,?,?,?) " +
                "ON CONFLICT(league_id, team_id, season, week, source, as_of_date) " +
                "DO UPDATE SET provider_matchup_id=excluded.provider_matchup_id";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, evidence.id());
                statement.setString(2, evidence.leagueId());
                statement.setString(3, evidence.teamId());
                statement.setInt(4, evidence.season());
                statement.setInt(5, evidence.week());
                statement.setInt(6, evidence.providerMatchupId());
                statement.setString(7, evidence.source());
                statement.setString(8, evidence.asOfDate().toString());
                statement.executeUpdate();
            }
        }
    }

    public Optional<TeamWeekMatchupEvidence> findLatest(
        String teamId, int season, int week, String source) throws SQLException {
        requireText(teamId, "teamId");
        requireText(source, "source");
        validateSeasonWeek(season, week);
        try (Connection connection = database.openConnection()) {
            ensureTable(connection);
            String sql = "SELECT id, league_id, team_id, season, week, provider_matchup_id, source, as_of_date " +
                "FROM team_week_matchup_evidence WHERE team_id=? AND season=? AND week=? AND source=? " +
                "ORDER BY as_of_date DESC, id DESC LIMIT 1";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, teamId.trim());
                statement.setInt(2, season);
                statement.setInt(3, week);
                statement.setString(4, source.trim());
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                }
            }
        }
    }

    /** Latest exact matchup row for each team in one league-season week, ordered by team id. */
    public List<TeamWeekMatchupEvidence> findLatestByLeagueSeasonWeek(
        String leagueId, int season, int week, String source) throws SQLException {
        requireText(leagueId, "leagueId");
        requireText(source, "source");
        validateSeasonWeek(season, week);
        List<TeamWeekMatchupEvidence> result = new ArrayList<>();
        try (Connection connection = database.openConnection()) {
            ensureTable(connection);
            String sql = "SELECT id, league_id, team_id, season, week, provider_matchup_id, source, as_of_date " +
                "FROM team_week_matchup_evidence WHERE league_id=? AND season=? AND week=? AND source=? " +
                "ORDER BY team_id ASC, as_of_date DESC, id DESC";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, leagueId.trim());
                statement.setInt(2, season);
                statement.setInt(3, week);
                statement.setString(4, source.trim());
                try (ResultSet rs = statement.executeQuery()) {
                    String previousTeamId = null;
                    while (rs.next()) {
                        String teamId = rs.getString("team_id");
                        if (teamId.equals(previousTeamId)) continue;
                        result.add(map(rs));
                        previousTeamId = teamId;
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    private static TeamWeekMatchupEvidence map(ResultSet rs) throws SQLException {
        return new TeamWeekMatchupEvidence(
            rs.getString("id"),
            rs.getString("league_id"),
            rs.getString("team_id"),
            rs.getInt("season"),
            rs.getInt("week"),
            rs.getInt("provider_matchup_id"),
            rs.getString("source"),
            LocalDate.parse(rs.getString("as_of_date")));
    }

    private static void ensureTable(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS team_week_matchup_evidence (
                    id TEXT PRIMARY KEY,
                    league_id TEXT NOT NULL,
                    team_id TEXT NOT NULL,
                    season INTEGER NOT NULL,
                    week INTEGER NOT NULL,
                    provider_matchup_id INTEGER NOT NULL,
                    source TEXT NOT NULL,
                    as_of_date TEXT NOT NULL,
                    UNIQUE(league_id, team_id, season, week, source, as_of_date),
                    FOREIGN KEY (league_id) REFERENCES leagues(id) ON DELETE CASCADE,
                    FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE,
                    CHECK (season BETWEEN 1999 AND 2100),
                    CHECK (week > 0),
                    CHECK (provider_matchup_id > 0)
                )
                """);
            statement.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_team_week_matchup_lookup " +
                    "ON team_week_matchup_evidence(team_id, season, week, source, as_of_date DESC)");
            statement.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_team_week_matchup_pairing " +
                    "ON team_week_matchup_evidence(league_id, season, week, provider_matchup_id, source, as_of_date DESC)");
        }
    }

    private static void validateSeasonWeek(int season, int week) {
        if (season < 1999 || season > 2100) {
            throw new IllegalArgumentException("season must be between 1999 and 2100");
        }
        if (week <= 0) throw new IllegalArgumentException("week must be positive");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
