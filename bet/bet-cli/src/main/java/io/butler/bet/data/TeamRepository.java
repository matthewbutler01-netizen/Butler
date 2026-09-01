package io.butler.bet.data;

import io.butler.bet.domain.Team;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class TeamRepository {
    private final Database database;
    public TeamRepository(Database database) { this.database = database; }

    public void save(Team team) throws SQLException {
        String sql = "INSERT INTO teams(id, external_id, league_id, name) VALUES(?,?,?,?) ON CONFLICT(id) DO UPDATE SET external_id=excluded.external_id, league_id=excluded.league_id, name=excluded.name";
        try (Connection c = database.openConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, team.getId()); ps.setString(2, team.getExternalId()); ps.setString(3, team.getLeagueId()); ps.setString(4, team.getName()); ps.executeUpdate();
        }
    }

    public Optional<Team> findById(String id) throws SQLException {
        try (Connection c = database.openConnection(); PreparedStatement ps = c.prepareStatement("SELECT id, external_id, league_id, name FROM teams WHERE id=?")) {
            ps.setString(1, id); try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(map(rs)) : Optional.empty(); }
        }
    }

    public List<Team> findByLeagueId(String leagueId) throws SQLException {
        List<Team> result = new ArrayList<>();
        try (Connection c = database.openConnection(); PreparedStatement ps = c.prepareStatement("SELECT id, external_id, league_id, name FROM teams WHERE league_id=? ORDER BY name")) {
            ps.setString(1, leagueId); try (ResultSet rs = ps.executeQuery()) { while (rs.next()) result.add(map(rs)); }
        }
        return result;
    }

    private static Team map(ResultSet rs) throws SQLException { return new Team(rs.getString("id"), rs.getString("external_id"), rs.getString("league_id"), rs.getString("name")); }
}
