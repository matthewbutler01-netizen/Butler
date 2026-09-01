package io.butler.bet.data;

import io.butler.bet.domain.Roster;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class RosterRepository {
    private final Database database;
    public RosterRepository(Database database) { this.database = database; }

    public void save(Roster roster) throws SQLException {
        String sql = "INSERT INTO rosters(id, external_id, team_id, player_id, slot) VALUES(?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET external_id=excluded.external_id, team_id=excluded.team_id, player_id=excluded.player_id, slot=excluded.slot";
        try (Connection c = database.openConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, roster.getId()); ps.setString(2, roster.getExternalId()); ps.setString(3, roster.getTeamId()); ps.setString(4, roster.getPlayerId()); ps.setString(5, roster.getSlot()); ps.executeUpdate();
        }
    }

    public Optional<Roster> findById(String id) throws SQLException {
        try (Connection c = database.openConnection(); PreparedStatement ps = c.prepareStatement("SELECT id, external_id, team_id, player_id, slot FROM rosters WHERE id=?")) {
            ps.setString(1, id); try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(map(rs)) : Optional.empty(); }
        }
    }

    public List<Roster> findByTeamId(String teamId) throws SQLException {
        List<Roster> result = new ArrayList<>();
        try (Connection c = database.openConnection(); PreparedStatement ps = c.prepareStatement("SELECT id, external_id, team_id, player_id, slot FROM rosters WHERE team_id=? ORDER BY slot, player_id")) {
            ps.setString(1, teamId); try (ResultSet rs = ps.executeQuery()) { while (rs.next()) result.add(map(rs)); }
        }
        return result;
    }

    public void deleteByTeamAndPlayer(String teamId, String playerId) throws SQLException {
        try (Connection c = database.openConnection(); PreparedStatement ps = c.prepareStatement("DELETE FROM rosters WHERE team_id=? AND player_id=?")) {
            ps.setString(1, teamId); ps.setString(2, playerId); ps.executeUpdate();
        }
    }

    private static Roster map(ResultSet rs) throws SQLException { return new Roster(rs.getString("id"), rs.getString("external_id"), rs.getString("team_id"), rs.getString("player_id"), rs.getString("slot")); }
}
