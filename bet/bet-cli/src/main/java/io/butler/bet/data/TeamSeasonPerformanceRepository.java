package io.butler.bet.data;

import io.butler.bet.domain.TeamSeasonPerformance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class TeamSeasonPerformanceRepository {
    private final Database database;

    public TeamSeasonPerformanceRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public void save(TeamSeasonPerformance performance) throws SQLException {
        Objects.requireNonNull(performance, "performance must not be null");
        String sql = "INSERT INTO team_season_performance(" +
            "league_id, team_id, season, wins, losses, ties, points_for, points_against, source, as_of_date) " +
            "VALUES(?,?,?,?,?,?,?,?,?,?) " +
            "ON CONFLICT(league_id, team_id, season, source, as_of_date) DO UPDATE SET " +
            "wins=excluded.wins, losses=excluded.losses, ties=excluded.ties, " +
            "points_for=excluded.points_for, points_against=excluded.points_against";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, performance.leagueId());
            statement.setString(2, performance.teamId());
            statement.setInt(3, performance.season());
            statement.setInt(4, performance.wins());
            statement.setInt(5, performance.losses());
            statement.setInt(6, performance.ties());
            statement.setDouble(7, performance.pointsFor());
            statement.setDouble(8, performance.pointsAgainst());
            statement.setString(9, performance.source());
            statement.setString(10, performance.asOfDate().toString());
            statement.executeUpdate();
        }
    }

    public Optional<TeamSeasonPerformance> findLatest(String leagueId, String teamId, int season, String source)
        throws SQLException {
        requireText(leagueId, "leagueId");
        requireText(teamId, "teamId");
        requireText(source, "source");
        if (season < 1999 || season > 2100) throw new IllegalArgumentException("season must be between 1999 and 2100");
        String sql = "SELECT * FROM team_season_performance WHERE league_id=? AND team_id=? AND season=? AND source=? " +
            "ORDER BY as_of_date DESC LIMIT 1";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, leagueId.trim());
            statement.setString(2, teamId.trim());
            statement.setInt(3, season);
            statement.setString(4, source.trim());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public List<TeamSeasonPerformance> findLatestByLeague(String leagueId, int season, String source)
        throws SQLException {
        requireText(leagueId, "leagueId");
        requireText(source, "source");
        if (season < 1999 || season > 2100) throw new IllegalArgumentException("season must be between 1999 and 2100");
        String sql = "SELECT p.* FROM team_season_performance p JOIN (" +
            "SELECT team_id, MAX(as_of_date) latest_as_of FROM team_season_performance " +
            "WHERE league_id=? AND season=? AND source=? GROUP BY team_id" +
            ") latest ON latest.team_id=p.team_id AND latest.latest_as_of=p.as_of_date " +
            "WHERE p.league_id=? AND p.season=? AND p.source=? ORDER BY p.team_id";
        List<TeamSeasonPerformance> result = new ArrayList<>();
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, leagueId.trim());
            statement.setInt(2, season);
            statement.setString(3, source.trim());
            statement.setString(4, leagueId.trim());
            statement.setInt(5, season);
            statement.setString(6, source.trim());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) result.add(map(rs));
            }
        }
        return List.copyOf(result);
    }

    private static TeamSeasonPerformance map(ResultSet rs) throws SQLException {
        return new TeamSeasonPerformance(
            rs.getString("league_id"), rs.getString("team_id"), rs.getInt("season"),
            rs.getInt("wins"), rs.getInt("losses"), rs.getInt("ties"),
            rs.getDouble("points_for"), rs.getDouble("points_against"),
            rs.getString("source"), LocalDate.parse(rs.getString("as_of_date")));
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
