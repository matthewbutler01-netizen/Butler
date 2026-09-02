package io.butler.bet.data;

import io.butler.bet.domain.PlayerProfileSnapshot;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class PlayerProfileSnapshotRepository {
    private final Database database;

    public PlayerProfileSnapshotRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public void save(PlayerProfileSnapshot snapshot) throws SQLException {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        if (!snapshot.hasAnyProfileFact()) return;
        String sql = "INSERT INTO player_profile_snapshots(id, player_id, reported_age, years_experience, source, as_of_date) "
            + "VALUES(?,?,?,?,?,?) ON CONFLICT(player_id, source, as_of_date) DO UPDATE SET "
            + "reported_age=excluded.reported_age, years_experience=excluded.years_experience";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, snapshot.id());
            statement.setString(2, snapshot.playerId());
            if (snapshot.reportedAge() == null) statement.setObject(3, null); else statement.setInt(3, snapshot.reportedAge());
            if (snapshot.yearsExperience() == null) statement.setObject(4, null); else statement.setInt(4, snapshot.yearsExperience());
            statement.setString(5, snapshot.source());
            statement.setString(6, snapshot.asOfDate().toString());
            statement.executeUpdate();
        }
    }

    public Optional<PlayerProfileSnapshot> findLatest(String playerId, String source) throws SQLException {
        requireText(playerId, "playerId");
        requireText(source, "source");
        String sql = "SELECT * FROM player_profile_snapshots WHERE player_id=? AND source=? "
            + "ORDER BY as_of_date DESC, id DESC LIMIT 1";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.trim());
            statement.setString(2, source.trim());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public List<PlayerProfileSnapshot> findByPlayerId(String playerId) throws SQLException {
        requireText(playerId, "playerId");
        List<PlayerProfileSnapshot> result = new ArrayList<>();
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT * FROM player_profile_snapshots WHERE player_id=? ORDER BY as_of_date DESC, source, id")) {
            statement.setString(1, playerId.trim());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) result.add(map(rs));
            }
        }
        return List.copyOf(result);
    }

    private static PlayerProfileSnapshot map(ResultSet rs) throws SQLException {
        Object age = rs.getObject("reported_age");
        Object experience = rs.getObject("years_experience");
        return new PlayerProfileSnapshot(rs.getString("id"), rs.getString("player_id"),
            age == null ? null : rs.getInt("reported_age"),
            experience == null ? null : rs.getInt("years_experience"),
            rs.getString("source"), LocalDate.parse(rs.getString("as_of_date")));
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
