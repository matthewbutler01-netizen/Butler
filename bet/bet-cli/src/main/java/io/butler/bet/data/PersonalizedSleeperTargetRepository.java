package io.butler.bet.data;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Persists one exact personalized Sleeper user+league+roster binding per Butler league. */
public final class PersonalizedSleeperTargetRepository {
    private final Database database;

    public PersonalizedSleeperTargetRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public void initialize() throws SQLException {
        try (var connection = database.openConnection();
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS personalized_sleeper_targets (
                    butler_league_id TEXT PRIMARY KEY,
                    sleeper_username TEXT NOT NULL,
                    sleeper_user_id TEXT NOT NULL,
                    sleeper_league_id TEXT NOT NULL,
                    roster_id INTEGER NOT NULL,
                    league_name TEXT NOT NULL,
                    season INTEGER NOT NULL,
                    provider_status TEXT NOT NULL,
                    bound_at_utc TEXT NOT NULL,
                    FOREIGN KEY (butler_league_id) REFERENCES leagues(id) ON DELETE CASCADE,
                    CHECK (season BETWEEN 1999 AND 2100),
                    CHECK (roster_id > 0)
                )
                """);
        }
    }

    public Optional<Target> findByButlerLeagueId(String butlerLeagueId) throws SQLException {
        String leagueId = requireText(butlerLeagueId, "butlerLeagueId");
        initialize();
        try (var connection = database.openConnection();
             var statement = connection.prepareStatement("""
                 SELECT butler_league_id, sleeper_username, sleeper_user_id, sleeper_league_id,
                        roster_id, league_name, season, provider_status, bound_at_utc
                 FROM personalized_sleeper_targets
                 WHERE butler_league_id = ?
                 """)) {
            statement.setString(1, leagueId);
            try (var rs = statement.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new Target(
                    rs.getString("butler_league_id"),
                    rs.getString("sleeper_username"),
                    rs.getString("sleeper_user_id"),
                    rs.getString("sleeper_league_id"),
                    rs.getInt("roster_id"),
                    rs.getString("league_name"),
                    rs.getInt("season"),
                    rs.getString("provider_status"),
                    Instant.parse(rs.getString("bound_at_utc"))));
            }
        }
    }

    /** First bind is allowed; an exact repeat is idempotent; a different value fails closed. */
    public BindState bindIfAbsentOrExact(Target desired) throws SQLException {
        Objects.requireNonNull(desired, "desired must not be null");
        initialize();
        Optional<Target> existing = findByButlerLeagueId(desired.butlerLeagueId());
        if (existing.isPresent()) {
            if (sameIdentity(existing.get(), desired)) return BindState.ALREADY_BOUND_EXACT;
            throw new IllegalStateException(
                "BF-622 BLOCKED: personalized Sleeper target already exists with a different identity; "
                    + "explicit compare-and-set rebind is required");
        }
        try (var connection = database.openConnection();
             var statement = connection.prepareStatement("""
                 INSERT INTO personalized_sleeper_targets(
                     butler_league_id, sleeper_username, sleeper_user_id, sleeper_league_id,
                     roster_id, league_name, season, provider_status, bound_at_utc)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                 """)) {
            statement.setString(1, desired.butlerLeagueId());
            statement.setString(2, desired.sleeperUsername());
            statement.setString(3, desired.sleeperUserId());
            statement.setString(4, desired.sleeperLeagueId());
            statement.setInt(5, desired.rosterId());
            statement.setString(6, desired.leagueName());
            statement.setInt(7, desired.season());
            statement.setString(8, desired.providerStatus());
            statement.setString(9, desired.boundAtUtc().toString());
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("BF-622 BLOCKED: personalized target insert did not affect exactly one row");
            }
        }
        Target readBack = findByButlerLeagueId(desired.butlerLeagueId())
            .orElseThrow(() -> new IllegalStateException("BF-622 BLOCKED: personalized target read-back is missing"));
        if (!sameIdentity(readBack, desired)) {
            throw new IllegalStateException("BF-622 BLOCKED: personalized target read-back verification failed");
        }
        return BindState.BOUND_VERIFIED;
    }

    /** Guarded rebind: mutation occurs only when the complete currently persisted identity matches expected. */
    public BindState compareAndSet(Target expected, Target desired) throws SQLException {
        Objects.requireNonNull(expected, "expected must not be null");
        Objects.requireNonNull(desired, "desired must not be null");
        if (!expected.butlerLeagueId().equals(desired.butlerLeagueId())) {
            throw new IllegalArgumentException("expected and desired Butler league ids must match");
        }
        initialize();
        try (var connection = database.openConnection();
             var statement = connection.prepareStatement("""
                 UPDATE personalized_sleeper_targets
                 SET sleeper_username=?, sleeper_user_id=?, sleeper_league_id=?, roster_id=?,
                     league_name=?, season=?, provider_status=?, bound_at_utc=?
                 WHERE butler_league_id=? AND sleeper_username=? AND sleeper_user_id=?
                   AND sleeper_league_id=? AND roster_id=? AND league_name=? AND season=?
                   AND provider_status=? AND bound_at_utc=?
                 """)) {
            statement.setString(1, desired.sleeperUsername());
            statement.setString(2, desired.sleeperUserId());
            statement.setString(3, desired.sleeperLeagueId());
            statement.setInt(4, desired.rosterId());
            statement.setString(5, desired.leagueName());
            statement.setInt(6, desired.season());
            statement.setString(7, desired.providerStatus());
            statement.setString(8, desired.boundAtUtc().toString());
            statement.setString(9, expected.butlerLeagueId());
            statement.setString(10, expected.sleeperUsername());
            statement.setString(11, expected.sleeperUserId());
            statement.setString(12, expected.sleeperLeagueId());
            statement.setInt(13, expected.rosterId());
            statement.setString(14, expected.leagueName());
            statement.setInt(15, expected.season());
            statement.setString(16, expected.providerStatus());
            statement.setString(17, expected.boundAtUtc().toString());
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("BF-622 BLOCKED: compare-and-set rebind failed because the persisted target changed");
            }
        }
        Target readBack = findByButlerLeagueId(desired.butlerLeagueId())
            .orElseThrow(() -> new IllegalStateException("BF-622 BLOCKED: rebound target read-back is missing"));
        if (!sameIdentity(readBack, desired)) {
            throw new IllegalStateException("BF-622 BLOCKED: rebound target read-back verification failed");
        }
        return BindState.REBOUND_VERIFIED;
    }

    private static boolean sameIdentity(Target left, Target right) {
        return left.butlerLeagueId().equals(right.butlerLeagueId())
            && left.sleeperUsername().equalsIgnoreCase(right.sleeperUsername())
            && left.sleeperUserId().equals(right.sleeperUserId())
            && left.sleeperLeagueId().equals(right.sleeperLeagueId())
            && left.rosterId() == right.rosterId()
            && left.leagueName().equals(right.leagueName())
            && left.season() == right.season()
            && left.providerStatus().equals(right.providerStatus());
    }

    public enum BindState {
        BOUND_VERIFIED,
        ALREADY_BOUND_EXACT,
        REBOUND_VERIFIED
    }

    public record Target(
        String butlerLeagueId,
        String sleeperUsername,
        String sleeperUserId,
        String sleeperLeagueId,
        int rosterId,
        String leagueName,
        int season,
        String providerStatus,
        Instant boundAtUtc) {
        public Target {
            butlerLeagueId = requireText(butlerLeagueId, "butlerLeagueId");
            sleeperUsername = requireText(sleeperUsername, "sleeperUsername");
            sleeperUserId = requireText(sleeperUserId, "sleeperUserId");
            sleeperLeagueId = requireText(sleeperLeagueId, "sleeperLeagueId");
            leagueName = requireText(leagueName, "leagueName");
            providerStatus = requireText(providerStatus, "providerStatus");
            if (rosterId <= 0) throw new IllegalArgumentException("rosterId must be positive");
            if (season < 1999 || season > 2100) throw new IllegalArgumentException("season out of range");
            Objects.requireNonNull(boundAtUtc, "boundAtUtc must not be null");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
