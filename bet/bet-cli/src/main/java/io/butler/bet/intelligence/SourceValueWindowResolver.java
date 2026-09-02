package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

final class SourceValueWindowResolver {
    private final Database database;

    SourceValueWindowResolver(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    Optional<Window> latestWindow(String source) throws SQLException {
        String normalizedSource = requireText(source, "source");
        List<LocalDate> dates = new ArrayList<>(2);
        try (var connection = database.openConnection();
             var statement = connection.prepareStatement("""
                 SELECT DISTINCT as_of_date
                 FROM player_values
                 WHERE source = ?
                 ORDER BY as_of_date DESC
                 LIMIT 2
                 """)) {
            statement.setString(1, normalizedSource);
            try (var results = statement.executeQuery()) {
                while (results.next()) dates.add(LocalDate.parse(results.getString("as_of_date")));
            }
        }
        if (dates.size() < 2) return Optional.empty();
        return Optional.of(new Window(dates.get(1), dates.get(0)));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    record Window(LocalDate previousDate, LocalDate latestDate) {}
}
