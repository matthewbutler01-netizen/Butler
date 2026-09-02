package io.butler.bet.data;

import io.butler.bet.domain.PlayerProfile;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class PlayerProfileRepository {
    private final Database database;

    public PlayerProfileRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public void save(PlayerProfile profile) throws SQLException {
        Objects.requireNonNull(profile, "profile must not be null");
        String sql = "INSERT INTO player_profiles(player_id, birth_date, years_experience) VALUES(?,?,?) "
            + "ON CONFLICT(player_id) DO UPDATE SET birth_date=excluded.birth_date, years_experience=excluded.years_experience";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, profile.playerId());
            statement.setString(2, profile.birthDate() == null ? null : profile.birthDate().toString());
            if (profile.yearsExperience() == null) statement.setObject(3, null);
            else statement.setInt(3, profile.yearsExperience());
            statement.executeUpdate();
        }
    }

    public Optional<PlayerProfile> findByPlayerId(String playerId) throws SQLException {
        requireText(playerId, "playerId");
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT player_id, birth_date, years_experience FROM player_profiles WHERE player_id=?")) {
            statement.setString(1, playerId.trim());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public List<PlayerProfile> findAll() throws SQLException {
        List<PlayerProfile> result = new ArrayList<>();
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT player_id, birth_date, years_experience FROM player_profiles ORDER BY player_id");
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) result.add(map(rs));
        }
        return List.copyOf(result);
    }

    public boolean deleteByPlayerId(String playerId) throws SQLException {
        requireText(playerId, "playerId");
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM player_profiles WHERE player_id=?")) {
            statement.setString(1, playerId.trim());
            return statement.executeUpdate() > 0;
        }
    }

    private static PlayerProfile map(ResultSet rs) throws SQLException {
        String birth = rs.getString("birth_date");
        Object experience = rs.getObject("years_experience");
        return new PlayerProfile(rs.getString("player_id"),
            birth == null ? null : LocalDate.parse(birth),
            experience == null ? null : rs.getInt("years_experience"));
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
