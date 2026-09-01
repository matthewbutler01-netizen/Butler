package io.butler.bet.data;

import io.butler.bet.domain.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class PlayerRepository {
    private final Database database;

    public PlayerRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public void save(Player player) throws SQLException {
        Objects.requireNonNull(player, "player must not be null");
        String sql = "INSERT INTO players(id, external_id, display_name, position, nfl_team) VALUES(?,?,?,?,?) "
                + "ON CONFLICT(id) DO UPDATE SET external_id=excluded.external_id, display_name=excluded.display_name, position=excluded.position, nfl_team=excluded.nfl_team";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, player.getId());
            statement.setString(2, player.getExternalId());
            statement.setString(3, player.getDisplayName());
            statement.setString(4, player.getPosition());
            statement.setString(5, player.getNflTeam());
            statement.executeUpdate();
        }
    }

    public Optional<Player> findById(String id) throws SQLException {
        requireText(id, "id");
        return findOne("SELECT id, external_id, display_name, position, nfl_team FROM players WHERE id=?", id);
    }

    public Optional<Player> findByExternalId(String externalId) throws SQLException {
        requireText(externalId, "externalId");
        return findOne("SELECT id, external_id, display_name, position, nfl_team FROM players WHERE external_id=?", externalId);
    }

    public List<Player> findAll() throws SQLException {
        List<Player> result = new ArrayList<>();
        try (Connection connection = database.openConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT id, external_id, display_name, position, nfl_team FROM players ORDER BY display_name")) {
            while (rs.next()) result.add(map(rs));
        }
        return result;
    }

    public boolean deleteById(String id) throws SQLException {
        requireText(id, "id");
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM players WHERE id=?")) {
            statement.setString(1, id);
            return statement.executeUpdate() > 0;
        }
    }

    private Optional<Player> findOne(String sql, String value) throws SQLException {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    private static Player map(ResultSet rs) throws SQLException {
        return new Player(rs.getString("id"), rs.getString("external_id"), rs.getString("display_name"), rs.getString("position"), rs.getString("nfl_team"));
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
