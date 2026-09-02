package io.butler.bet.data;

import io.butler.bet.domain.LeagueValueFormat;

import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

public final class LeagueValueFormatRepository {
    private final Database database;

    public LeagueValueFormatRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public void save(String leagueId, LeagueValueFormat format) throws SQLException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        Objects.requireNonNull(format, "format must not be null");
        try (var connection = database.openConnection();
             var statement = connection.prepareStatement("""
                 INSERT INTO league_value_formats(league_id, format)
                 VALUES (?, ?)
                 ON CONFLICT(league_id) DO UPDATE SET format = excluded.format
                 """)) {
            statement.setString(1, normalizedLeagueId);
            statement.setString(2, format.name());
            statement.executeUpdate();
        }
    }

    public Optional<LeagueValueFormat> findByLeagueId(String leagueId) throws SQLException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        try (var connection = database.openConnection();
             var statement = connection.prepareStatement(
                 "SELECT format FROM league_value_formats WHERE league_id = ?")) {
            statement.setString(1, normalizedLeagueId);
            try (var results = statement.executeQuery()) {
                if (!results.next()) return Optional.empty();
                return Optional.of(LeagueValueFormat.valueOf(results.getString("format")));
            }
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
