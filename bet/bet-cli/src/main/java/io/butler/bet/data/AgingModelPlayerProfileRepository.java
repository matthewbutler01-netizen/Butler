package io.butler.bet.data;

import io.butler.bet.domain.AgingModelPlayerProfile;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class AgingModelPlayerProfileRepository {
    private final Database database;

    public AgingModelPlayerProfileRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public void save(AgingModelPlayerProfile profile) throws SQLException {
        Objects.requireNonNull(profile, "profile must not be null");
        String sql = "INSERT INTO aging_model_player_profiles(" +
            "gsis_id, display_name, birth_date, position, source, as_of_date) VALUES(?,?,?,?,?,?) " +
            "ON CONFLICT(gsis_id, source, as_of_date) DO UPDATE SET " +
            "display_name=excluded.display_name, birth_date=excluded.birth_date, position=excluded.position";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, profile.gsisId());
            statement.setString(2, profile.displayName());
            statement.setString(3, profile.birthDate() == null ? null : profile.birthDate().toString());
            statement.setString(4, profile.position());
            statement.setString(5, profile.source());
            statement.setString(6, profile.asOfDate().toString());
            statement.executeUpdate();
        }
    }

    public Optional<AgingModelPlayerProfile> findLatest(String gsisId, String source) throws SQLException {
        requireText(gsisId, "gsisId");
        requireText(source, "source");
        String sql = "SELECT * FROM aging_model_player_profiles WHERE gsis_id=? AND source=? " +
            "ORDER BY as_of_date DESC LIMIT 1";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, gsisId.trim());
            statement.setString(2, source.trim());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public List<AgingModelPlayerProfile> findLatestBySource(String source) throws SQLException {
        requireText(source, "source");
        String sql = "SELECT p.* FROM aging_model_player_profiles p JOIN (" +
            "SELECT gsis_id, MAX(as_of_date) as max_date FROM aging_model_player_profiles " +
            "WHERE source=? GROUP BY gsis_id) latest " +
            "ON p.gsis_id=latest.gsis_id AND p.as_of_date=latest.max_date WHERE p.source=? ORDER BY p.gsis_id";
        List<AgingModelPlayerProfile> result = new ArrayList<>();
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, source.trim());
            statement.setString(2, source.trim());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) result.add(map(rs));
            }
        }
        return List.copyOf(result);
    }

    public int countLatestWithBirthDate(String source) throws SQLException {
        return (int) findLatestBySource(source).stream().filter(p -> p.birthDate() != null).count();
    }

    private static AgingModelPlayerProfile map(ResultSet rs) throws SQLException {
        String birth = rs.getString("birth_date");
        return new AgingModelPlayerProfile(rs.getString("gsis_id"), rs.getString("display_name"),
            birth == null ? null : LocalDate.parse(birth), rs.getString("position"),
            rs.getString("source"), LocalDate.parse(rs.getString("as_of_date")));
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
