package io.butler.bet.data;

import io.butler.bet.domain.Team;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class TeamRepository {
    private final Database database;

    public TeamRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public void save(Team team) throws SQLException {
        Objects.requireNonNull(team, "team must not be null");
        String sql = "INSERT INTO teams(id, external_id, league_id, name) VALUES(?,?,?,?) "
                + "ON CONFLICT(id) DO UPDATE SET external_id=excluded.external_id, league_id=excluded.league_id, name=excluded.name";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, team.getId());
            statement.setString(2, team.getExternalId());
            statement.setString(3, team.getLeagueId());
            statement.setString(4, team.getName());
            statement.executeUpdate();
        }
    }

    public Optional<Team> findById(String id) throws SQLException {
        requireText(id, "id");
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT id, external_id, league_id, name FROM teams WHERE id=?")) {
            statement.setString(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public Optional<Team> findByExternalId(String leagueId, String externalId) throws SQLException {
        requireText(leagueId, "leagueId");
        requireText(externalId, "externalId");
        String sql = "SELECT id, external_id, league_id, name FROM teams WHERE league_id=? AND external_id=?";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, leagueId);
            statement.setString(2, externalId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public List<Team> findByLeagueId(String leagueId) throws SQLException {
        requireText(leagueId, "leagueId");
        List<Team> result = new ArrayList<>();
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT id, external_id, league_id, name FROM teams WHERE league_id=? ORDER BY name")) {
            statement.setString(1, leagueId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) result.add(map(rs));
            }
        }
        return result;
    }

    public boolean deleteById(String id) throws SQLException {
        requireText(id, "id");
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM teams WHERE id=?")) {
            statement.setString(1, id);
            return statement.executeUpdate() > 0;
        }
    }

    private static Team map(ResultSet rs) throws SQLException {
        return new Team(rs.getString("id"), rs.getString("external_id"), rs.getString("league_id"), rs.getString("name"));
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
