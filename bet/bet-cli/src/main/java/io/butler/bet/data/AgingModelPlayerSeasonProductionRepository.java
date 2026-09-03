package io.butler.bet.data;

import io.butler.bet.domain.AgingModelPlayerSeasonProduction;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class AgingModelPlayerSeasonProductionRepository {
    private final Database database;

    public AgingModelPlayerSeasonProductionRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public void save(AgingModelPlayerSeasonProduction production) throws SQLException {
        Objects.requireNonNull(production, "production must not be null");
        String sql = "INSERT INTO aging_model_player_season_production(" +
            "gsis_id, season, position, games_played, passing_yards, passing_touchdowns, interceptions, " +
            "rushing_yards, rushing_touchdowns, receptions, receiving_yards, receiving_touchdowns, " +
            "fumbles_lost, source, as_of_date) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) " +
            "ON CONFLICT(gsis_id, season, source, as_of_date) DO UPDATE SET " +
            "position=excluded.position, games_played=excluded.games_played, passing_yards=excluded.passing_yards, " +
            "passing_touchdowns=excluded.passing_touchdowns, interceptions=excluded.interceptions, " +
            "rushing_yards=excluded.rushing_yards, rushing_touchdowns=excluded.rushing_touchdowns, " +
            "receptions=excluded.receptions, receiving_yards=excluded.receiving_yards, " +
            "receiving_touchdowns=excluded.receiving_touchdowns, fumbles_lost=excluded.fumbles_lost";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, production.gsisId());
            statement.setInt(2, production.season());
            statement.setString(3, production.position());
            statement.setInt(4, production.gamesPlayed());
            statement.setInt(5, production.passingYards());
            statement.setInt(6, production.passingTouchdowns());
            statement.setInt(7, production.interceptions());
            statement.setInt(8, production.rushingYards());
            statement.setInt(9, production.rushingTouchdowns());
            statement.setInt(10, production.receptions());
            statement.setInt(11, production.receivingYards());
            statement.setInt(12, production.receivingTouchdowns());
            statement.setInt(13, production.fumblesLost());
            statement.setString(14, production.source());
            statement.setString(15, production.asOfDate().toString());
            statement.executeUpdate();
        }
    }

    public Optional<AgingModelPlayerSeasonProduction> findLatest(String gsisId, int season, String source) throws SQLException {
        requireText(gsisId, "gsisId");
        requireText(source, "source");
        if (season <= 0) throw new IllegalArgumentException("season must be positive");
        String sql = "SELECT * FROM aging_model_player_season_production WHERE gsis_id=? AND season=? AND source=? " +
            "ORDER BY as_of_date DESC LIMIT 1";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, gsisId.trim());
            statement.setInt(2, season);
            statement.setString(3, source.trim());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public List<AgingModelPlayerSeasonProduction> findLatestBySource(String source) throws SQLException {
        requireText(source, "source");
        String sql = "SELECT p.* FROM aging_model_player_season_production p JOIN (" +
            "SELECT gsis_id, season, MAX(as_of_date) AS max_date FROM aging_model_player_season_production " +
            "WHERE source=? GROUP BY gsis_id, season) latest " +
            "ON p.gsis_id=latest.gsis_id AND p.season=latest.season AND p.as_of_date=latest.max_date " +
            "WHERE p.source=? ORDER BY p.gsis_id, p.season";
        List<AgingModelPlayerSeasonProduction> result = new ArrayList<>();
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

    private static AgingModelPlayerSeasonProduction map(ResultSet rs) throws SQLException {
        return new AgingModelPlayerSeasonProduction(rs.getString("gsis_id"), rs.getInt("season"), rs.getString("position"),
            rs.getInt("games_played"), rs.getInt("passing_yards"), rs.getInt("passing_touchdowns"),
            rs.getInt("interceptions"), rs.getInt("rushing_yards"), rs.getInt("rushing_touchdowns"),
            rs.getInt("receptions"), rs.getInt("receiving_yards"), rs.getInt("receiving_touchdowns"),
            rs.getInt("fumbles_lost"), rs.getString("source"), LocalDate.parse(rs.getString("as_of_date")));
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
