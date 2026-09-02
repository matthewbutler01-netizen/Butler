package io.butler.bet.data;

import io.butler.bet.domain.DraftPickValue;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class DraftPickValueRepository {
    private final Database database;

    public DraftPickValueRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public void save(DraftPickValue value) throws SQLException {
        Objects.requireNonNull(value, "value must not be null");
        try (var connection = database.openConnection();
             var statement = connection.prepareStatement("""
                 INSERT INTO draft_pick_values(id, draft_pick_id, value, source, as_of_date)
                 VALUES (?, ?, ?, ?, ?)
                 ON CONFLICT(draft_pick_id, source, as_of_date) DO UPDATE SET value = excluded.value
                 """)) {
            statement.setString(1, value.getId());
            statement.setString(2, value.getDraftPickId());
            statement.setDouble(3, value.getValue());
            statement.setString(4, value.getSource());
            statement.setString(5, value.getAsOfDate().toString());
            statement.executeUpdate();
        }
    }

    public void saveAll(List<DraftPickValue> values) throws SQLException {
        Objects.requireNonNull(values, "values must not be null");
        try (var connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("""
                INSERT INTO draft_pick_values(id, draft_pick_id, value, source, as_of_date)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(draft_pick_id, source, as_of_date) DO UPDATE SET value = excluded.value
                """)) {
                for (DraftPickValue value : values) {
                    Objects.requireNonNull(value, "values must not contain null");
                    statement.setString(1, value.getId());
                    statement.setString(2, value.getDraftPickId());
                    statement.setDouble(3, value.getValue());
                    statement.setString(4, value.getSource());
                    statement.setString(5, value.getAsOfDate().toString());
                    statement.addBatch();
                }
                statement.executeBatch();
                connection.commit();
            } catch (RuntimeException | SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public Optional<DraftPickValue> findLatestByDraftPickIdAndSource(String draftPickId, String source) throws SQLException {
        String normalizedPickId = requireText(draftPickId, "draftPickId");
        String normalizedSource = requireText(source, "source");
        try (var connection = database.openConnection();
             var statement = connection.prepareStatement("""
                 SELECT id, draft_pick_id, value, source, as_of_date
                 FROM draft_pick_values
                 WHERE draft_pick_id = ? AND source = ?
                 ORDER BY as_of_date DESC LIMIT 1
                 """)) {
            statement.setString(1, normalizedPickId);
            statement.setString(2, normalizedSource);
            try (var results = statement.executeQuery()) {
                return results.next() ? Optional.of(map(results)) : Optional.empty();
            }
        }
    }

    public List<DraftPickValue> findByDraftPickIdAndSource(String draftPickId, String source) throws SQLException {
        String normalizedPickId = requireText(draftPickId, "draftPickId");
        String normalizedSource = requireText(source, "source");
        List<DraftPickValue> result = new ArrayList<>();
        try (var connection = database.openConnection();
             var statement = connection.prepareStatement("""
                 SELECT id, draft_pick_id, value, source, as_of_date
                 FROM draft_pick_values
                 WHERE draft_pick_id = ? AND source = ?
                 ORDER BY as_of_date DESC
                 """)) {
            statement.setString(1, normalizedPickId);
            statement.setString(2, normalizedSource);
            try (var results = statement.executeQuery()) {
                while (results.next()) result.add(map(results));
            }
        }
        return List.copyOf(result);
    }

    private static DraftPickValue map(ResultSet results) throws SQLException {
        return new DraftPickValue(
            results.getString("id"),
            results.getString("draft_pick_id"),
            results.getDouble("value"),
            results.getString("source"),
            LocalDate.parse(results.getString("as_of_date")));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
