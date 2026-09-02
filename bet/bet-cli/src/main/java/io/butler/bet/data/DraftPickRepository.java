package io.butler.bet.data;

import io.butler.bet.domain.DraftPick;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class DraftPickRepository {
    private final Database database;

    public DraftPickRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public void save(DraftPick pick) throws SQLException {
        Objects.requireNonNull(pick, "pick must not be null");
        try (var connection = database.openConnection();
             var statement = connection.prepareStatement("""
                 INSERT INTO draft_picks(id, league_id, season, round, original_team_id, owner_team_id, pick_number)
                 VALUES (?, ?, ?, ?, ?, ?, ?)
                 ON CONFLICT(league_id, season, round, original_team_id) DO UPDATE SET
                     owner_team_id = excluded.owner_team_id,
                     pick_number = excluded.pick_number
                 """)) {
            statement.setString(1, pick.getId());
            statement.setString(2, pick.getLeagueId());
            statement.setInt(3, pick.getSeason());
            statement.setInt(4, pick.getRound());
            statement.setString(5, pick.getOriginalTeamId());
            statement.setString(6, pick.getOwnerTeamId());
            if (pick.getPickNumber() == null) statement.setNull(7, java.sql.Types.INTEGER);
            else statement.setInt(7, pick.getPickNumber());
            statement.executeUpdate();
        }
    }

    public Optional<DraftPick> findById(String id) throws SQLException {
        String normalizedId = requireText(id, "id");
        try (var connection = database.openConnection();
             var statement = connection.prepareStatement("""
                 SELECT id, league_id, season, round, original_team_id, owner_team_id, pick_number
                 FROM draft_picks WHERE id = ?
                 """)) {
            statement.setString(1, normalizedId);
            try (var results = statement.executeQuery()) {
                return results.next() ? Optional.of(map(results)) : Optional.empty();
            }
        }
    }

    public Optional<DraftPick> findByLeagueSeasonRoundAndOriginalTeam(
        String leagueId, int season, int round, String originalTeamId) throws SQLException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        String normalizedOriginalTeamId = requireText(originalTeamId, "originalTeamId");
        try (var connection = database.openConnection();
             var statement = connection.prepareStatement("""
                 SELECT id, league_id, season, round, original_team_id, owner_team_id, pick_number
                 FROM draft_picks
                 WHERE league_id = ? AND season = ? AND round = ? AND original_team_id = ?
                 """)) {
            statement.setString(1, normalizedLeagueId);
            statement.setInt(2, season);
            statement.setInt(3, round);
            statement.setString(4, normalizedOriginalTeamId);
            try (var results = statement.executeQuery()) {
                return results.next() ? Optional.of(map(results)) : Optional.empty();
            }
        }
    }

    public List<DraftPick> findByLeagueId(String leagueId) throws SQLException {
        return findMany("""
            SELECT id, league_id, season, round, original_team_id, owner_team_id, pick_number
            FROM draft_picks WHERE league_id = ?
            ORDER BY season, round, original_team_id
            """, requireText(leagueId, "leagueId"));
    }

    public List<DraftPick> findByOwnerTeamId(String ownerTeamId) throws SQLException {
        return findMany("""
            SELECT id, league_id, season, round, original_team_id, owner_team_id, pick_number
            FROM draft_picks WHERE owner_team_id = ?
            ORDER BY season, round, original_team_id
            """, requireText(ownerTeamId, "ownerTeamId"));
    }

    public void deleteByLeagueId(String leagueId) throws SQLException {
        try (var connection = database.openConnection();
             var statement = connection.prepareStatement("DELETE FROM draft_picks WHERE league_id = ?")) {
            statement.setString(1, requireText(leagueId, "leagueId"));
            statement.executeUpdate();
        }
    }

    private List<DraftPick> findMany(String sql, String value) throws SQLException {
        List<DraftPick> picks = new ArrayList<>();
        try (var connection = database.openConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            try (var results = statement.executeQuery()) {
                while (results.next()) picks.add(map(results));
            }
        }
        return List.copyOf(picks);
    }

    private static DraftPick map(ResultSet results) throws SQLException {
        int pickNumber = results.getInt("pick_number");
        Integer optionalPickNumber = results.wasNull() ? null : pickNumber;
        return new DraftPick(
            results.getString("id"),
            results.getString("league_id"),
            results.getInt("season"),
            results.getInt("round"),
            results.getString("original_team_id"),
            results.getString("owner_team_id"),
            optionalPickNumber);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
