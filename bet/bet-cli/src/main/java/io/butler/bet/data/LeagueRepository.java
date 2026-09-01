package io.butler.bet.data;

import io.butler.bet.domain.League;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class LeagueRepository {
    private final Database database;
    public LeagueRepository(Database database) { this.database = database; }

    public void save(League league) throws SQLException {
        String sql = "INSERT INTO leagues(id, external_id, name) VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET external_id=excluded.external_id, name=excluded.name";
        try (Connection c = database.openConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, league.getId()); ps.setString(2, league.getExternalId()); ps.setString(3, league.getName()); ps.executeUpdate();
        }
    }

    public Optional<League> findById(String id) throws SQLException {
        try (Connection c = database.openConnection(); PreparedStatement ps = c.prepareStatement("SELECT id, external_id, name FROM leagues WHERE id=?")) {
            ps.setString(1, id); try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(map(rs)) : Optional.empty(); }
        }
    }

    public List<League> findAll() throws SQLException {
        List<League> result = new ArrayList<>();
        try (Connection c = database.openConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT id, external_id, name FROM leagues ORDER BY name")) {
            while (rs.next()) result.add(map(rs));
        }
        return result;
    }

    private static League map(ResultSet rs) throws SQLException { return new League(rs.getString("id"), rs.getString("external_id"), rs.getString("name")); }
}
