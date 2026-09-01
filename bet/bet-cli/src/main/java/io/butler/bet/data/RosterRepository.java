package io.butler.bet.data;

import io.butler.bet.domain.Roster;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class RosterRepository {
    private final Database database;

    public RosterRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public void save(Roster roster) throws SQLException {
        Objects.requireNonNull(roster, "roster must not be null");
        String sql = "INSERT INTO rosters(id, external_id, team_id, player_id, slot) VALUES(?,?,?,?,?) "
                + "ON CONFLICT(id) DO UPDATE SET external_id=excluded.external_id, team_id=excluded.team_id, player_id=excluded.player_id, slot=excluded.slot";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, roster.getId());
            statement.setString(2, roster.getExternalId());
            statement.setString(3, roster.getTeamId());
            statement.setString(4, roster.getPlayerId());
            statement.setString(5, roster.getSlot());
            statement.executeUpdate();
        }
    }

    public Optional<Roster> findById(String id) throws SQLException {
        requireText(id, "id");
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT id, external_id, team_id, player_id, slot FROM rosters WHERE id=?")) {
            statement.setString(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public Optional<Roster> findByTeamAndPlayer(String teamId, String playerId) throws SQLException {
        requireText(teamId, "teamId");
        requireText(playerId, "playerId");
        String sql = "SELECT id, external_id, team_id, player_id, slot FROM rosters WHERE team_id=? AND player_id=?";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, teamId);
            statement.setString(2, playerId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public List<Roster> findByTeamId(String teamId) throws SQLException {
        requireText(teamId, "teamId");
        return findMany("SELECT id, external_id, team_id, player_id, slot FROM rosters WHERE team_id=? ORDER BY slot, player_id", teamId);
    }

    public List<Roster> findByPlayerId(String playerId) throws SQLException {
        requireText(playerId, "playerId");
        return findMany("SELECT id, external_id, team_id, player_id, slot FROM rosters WHERE player_id=? ORDER BY team_id", playerId);
    }

    public boolean deleteById(String id) throws SQLException {
        requireText(id, "id");
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM rosters WHERE id=?")) {
            statement.setString(1, id);
            return statement.executeUpdate() > 0;
        }
    }

    public boolean deleteByTeamAndPlayer(String teamId, String playerId) throws SQLException {
        requireText(teamId, "teamId");
        requireText(playerId, "playerId");
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM rosters WHERE team_id=? AND player_id=?")) {
            statement.setString(1, teamId);
            statement.setString(2, playerId);
            return statement.executeUpdate() > 0;
        }
    }

    private List<Roster> findMany(String sql, String value) throws SQLException {
        List<Roster> result = new ArrayList<>();
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) result.add(map(rs));
            }
        }
        return result;
    }

    private static Roster map(ResultSet rs) throws SQLException {
        return new Roster(rs.getString("id"), rs.getString("external_id"), rs.getString("team_id"), rs.getString("player_id"), rs.getString("slot"));
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
