package io.butler.bet.data;

import io.butler.bet.domain.Player;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class PlayerRepository {
    private final Database database;
    public PlayerRepository(Database database) { this.database = database; }

    public void save(Player player) throws SQLException {
        String sql = "INSERT INTO players(id, external_id, display_name, position, nfl_team) VALUES(?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET external_id=excluded.external_id, display_name=excluded.display_name, position=excluded.position, nfl_team=excluded.nfl_team";
        try (Connection c = database.openConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, player.getId()); ps.setString(2, player.getExternalId()); ps.setString(3, player.getDisplayName()); ps.setString(4, player.getPosition()); ps.setString(5, player.getNflTeam()); ps.executeUpdate();
        }
    }

    public Optional<Player> findById(String id) throws SQLException {
        try (Connection c = database.openConnection(); PreparedStatement ps = c.prepareStatement("SELECT id, external_id, display_name, position, nfl_team FROM players WHERE id=?")) {
            ps.setString(1, id); try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(map(rs)) : Optional.empty(); }
        }
    }

    public Optional<Player> findByExternalId(String externalId) throws SQLException {
        try (Connection c = database.openConnection(); PreparedStatement ps = c.prepareStatement("SELECT id, external_id, display_name, position, nfl_team FROM players WHERE external_id=?")) {
            ps.setString(1, externalId); try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(map(rs)) : Optional.empty(); }
        }
    }

    public List<Player> findAll() throws SQLException {
        List<Player> result = new ArrayList<>();
        try (Connection c = database.openConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT id, external_id, display_name, position, nfl_team FROM players ORDER BY display_name")) {
            while (rs.next()) result.add(map(rs));
        }
        return result;
    }

    private static Player map(ResultSet rs) throws SQLException { return new Player(rs.getString("id"), rs.getString("external_id"), rs.getString("display_name"), rs.getString("position"), rs.getString("nfl_team")); }
}
