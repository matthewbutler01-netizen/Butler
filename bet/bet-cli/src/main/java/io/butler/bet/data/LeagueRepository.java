package io.butler.bet.data;

import io.butler.bet.domain.League;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class LeagueRepository {
    private final Database database;

    public LeagueRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public void save(League league) throws SQLException {
        Objects.requireNonNull(league, "league must not be null");
        String sql = "INSERT INTO leagues(id, external_id, name, season) VALUES(?,?,?,?) "
                + "ON CONFLICT(id) DO UPDATE SET external_id=excluded.external_id, name=excluded.name, season=excluded.season";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, league.getId());
            statement.setString(2, league.getExternalId());
            statement.setString(3, league.getName());
            if (league.getSeason() == null) statement.setObject(4, null);
            else statement.setInt(4, league.getSeason());
            statement.executeUpdate();
        }
    }

    public Optional<League> findById(String id) throws SQLException {
        requireText(id, "id");
        return findOne("SELECT id, external_id, name, season FROM leagues WHERE id=?", id);
    }

    public Optional<League> findByExternalId(String externalId) throws SQLException {
        requireText(externalId, "externalId");
        return findOne("SELECT id, external_id, name, season FROM leagues WHERE external_id=?", externalId);
    }

    public List<League> findAll() throws SQLException {
        List<League> result = new ArrayList<>();
        try (Connection connection = database.openConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT id, external_id, name, season FROM leagues ORDER BY name")) {
            while (rs.next()) {
                result.add(map(rs));
            }
        }
        return result;
    }

    public boolean deleteById(String id) throws SQLException {
        requireText(id, "id");
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM leagues WHERE id=?")) {
            statement.setString(1, id);
            return statement.executeUpdate() > 0;
        }
    }

    private Optional<League> findOne(String sql, String value) throws SQLException {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    private static League map(ResultSet rs) throws SQLException {
        Object season = rs.getObject("season");
        return new League(rs.getString("id"), rs.getString("external_id"), rs.getString("name"),
            season == null ? null : rs.getInt("season"));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
