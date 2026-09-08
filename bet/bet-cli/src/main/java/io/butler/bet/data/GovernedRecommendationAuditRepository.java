package io.butler.bet.data;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** BF-627 append-only persistence for governed recommendation audit records. */
public final class GovernedRecommendationAuditRepository {
    private final Database database;

    public GovernedRecommendationAuditRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public CaptureResult capture(AuditRecord desired) throws SQLException {
        Objects.requireNonNull(desired, "desired must not be null");
        try (Connection connection = database.openConnection()) {
            ensureTable(connection);
            connection.setAutoCommit(false);
            try {
                Optional<AuditRecord> existing = findByLineageKey(connection, desired.lineageKey());
                if (existing.isPresent()) {
                    AuditRecord persisted = existing.get();
                    if (!sameDecision(persisted, desired)) {
                        throw new IllegalStateException(
                            "BF-627 BLOCKED: immutable recommendation audit lineage already exists with different decision payload");
                    }
                    connection.rollback();
                    return new CaptureResult(CaptureState.ALREADY_CAPTURED_EXACT, persisted);
                }

                String id = desired.id() == null ? UUID.randomUUID().toString() : desired.id();
                AuditRecord toPersist = desired.withId(id);
                insert(connection, toPersist);
                AuditRecord readback = findByLineageKey(connection, toPersist.lineageKey())
                    .orElseThrow(() -> new SQLException("BF-627 audit record vanished during transactional readback"));
                if (!sameDecision(readback, toPersist)) {
                    throw new SQLException("BF-627 transactional audit readback reconciliation failed");
                }
                connection.commit();
                return new CaptureResult(CaptureState.CAPTURED_VERIFIED, readback);
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    public Optional<AuditRecord> findByLineageKey(String lineageKey) throws SQLException {
        String normalized = requireText(lineageKey, "lineageKey");
        try (Connection connection = database.openConnection()) {
            ensureTable(connection);
            return findByLineageKey(connection, normalized);
        }
    }

    /** BF-628 read-only history access. An absent audit table is a valid empty-history state. */
    public List<AuditRecord> findAllForLeague(String leagueId) throws SQLException {
        String normalized = requireText(leagueId, "leagueId");
        try (Connection connection = database.openConnection()) {
            if (!auditTableExists(connection)) return List.of();
            try (var statement = connection.prepareStatement("""
                SELECT id, lineage_key, capture_policy_id, league_id, sleeper_owner_id,
                    sleeper_league_id, roster_id, season, provider_status, provider_leg,
                    market_snapshot_id, waiver_snapshot_id, bf618_policy_id, bf619_policy_id,
                    bf620_policy_id, bf624_policy_id, selection_state, recommendation_state,
                    add_sleeper_player_id, drop_sleeper_player_id, captured_at_utc
                FROM governed_recommendation_audits
                WHERE league_id = ?
                ORDER BY captured_at_utc ASC, rowid ASC
                """)) {
                statement.setString(1, normalized);
                try (var rs = statement.executeQuery()) {
                    List<AuditRecord> records = new ArrayList<>();
                    while (rs.next()) records.add(map(rs));
                    return List.copyOf(records);
                }
            }
        }
    }

    public int countForLeague(String leagueId) throws SQLException {
        String normalized = requireText(leagueId, "leagueId");
        try (Connection connection = database.openConnection()) {
            ensureTable(connection);
            try (var statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM governed_recommendation_audits WHERE league_id = ?")) {
                statement.setString(1, normalized);
                try (var rs = statement.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        }
    }

    private static void insert(Connection connection, AuditRecord value) throws SQLException {
        try (var statement = connection.prepareStatement("""
            INSERT INTO governed_recommendation_audits(
                id, lineage_key, capture_policy_id, league_id, sleeper_owner_id,
                sleeper_league_id, roster_id, season, provider_status, provider_leg,
                market_snapshot_id, waiver_snapshot_id, bf618_policy_id, bf619_policy_id,
                bf620_policy_id, bf624_policy_id, selection_state, recommendation_state,
                add_sleeper_player_id, drop_sleeper_player_id, captured_at_utc)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """)) {
            statement.setString(1, value.id());
            statement.setString(2, value.lineageKey());
            statement.setString(3, value.capturePolicyId());
            statement.setString(4, value.leagueId());
            statement.setString(5, value.sleeperOwnerId());
            statement.setString(6, value.sleeperLeagueId());
            statement.setInt(7, value.rosterId());
            statement.setInt(8, value.season());
            statement.setString(9, value.providerStatus());
            if (value.providerLeg() == null) statement.setNull(10, java.sql.Types.INTEGER);
            else statement.setInt(10, value.providerLeg());
            statement.setString(11, value.marketSnapshotId());
            statement.setString(12, value.waiverSnapshotId());
            statement.setString(13, value.bf618PolicyId());
            statement.setString(14, value.bf619PolicyId());
            statement.setString(15, value.bf620PolicyId());
            statement.setString(16, value.bf624PolicyId());
            statement.setString(17, value.selectionState());
            statement.setString(18, value.recommendationState());
            statement.setString(19, value.addSleeperPlayerId());
            statement.setString(20, value.dropSleeperPlayerId());
            statement.setString(21, value.capturedAtUtc().toString());
            statement.executeUpdate();
        }
    }

    private static Optional<AuditRecord> findByLineageKey(Connection connection, String lineageKey)
        throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT id, lineage_key, capture_policy_id, league_id, sleeper_owner_id,
                sleeper_league_id, roster_id, season, provider_status, provider_leg,
                market_snapshot_id, waiver_snapshot_id, bf618_policy_id, bf619_policy_id,
                bf620_policy_id, bf624_policy_id, selection_state, recommendation_state,
                add_sleeper_player_id, drop_sleeper_player_id, captured_at_utc
            FROM governed_recommendation_audits
            WHERE lineage_key = ?
            """)) {
            statement.setString(1, lineageKey);
            try (var rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    private static AuditRecord map(ResultSet rs) throws SQLException {
        Object leg = rs.getObject("provider_leg");
        return new AuditRecord(
            rs.getString("id"), rs.getString("lineage_key"), rs.getString("capture_policy_id"),
            rs.getString("league_id"), rs.getString("sleeper_owner_id"), rs.getString("sleeper_league_id"),
            rs.getInt("roster_id"), rs.getInt("season"), rs.getString("provider_status"),
            leg == null ? null : rs.getInt("provider_leg"),
            rs.getString("market_snapshot_id"), rs.getString("waiver_snapshot_id"),
            rs.getString("bf618_policy_id"), rs.getString("bf619_policy_id"), rs.getString("bf620_policy_id"),
            rs.getString("bf624_policy_id"), rs.getString("selection_state"), rs.getString("recommendation_state"),
            rs.getString("add_sleeper_player_id"), rs.getString("drop_sleeper_player_id"),
            Instant.parse(rs.getString("captured_at_utc")));
    }

    private static boolean sameDecision(AuditRecord left, AuditRecord right) {
        return left.lineageKey().equals(right.lineageKey())
            && left.capturePolicyId().equals(right.capturePolicyId())
            && left.leagueId().equals(right.leagueId())
            && left.sleeperOwnerId().equals(right.sleeperOwnerId())
            && left.sleeperLeagueId().equals(right.sleeperLeagueId())
            && left.rosterId() == right.rosterId()
            && left.season() == right.season()
            && left.providerStatus().equals(right.providerStatus())
            && Objects.equals(left.providerLeg(), right.providerLeg())
            && left.marketSnapshotId().equals(right.marketSnapshotId())
            && left.waiverSnapshotId().equals(right.waiverSnapshotId())
            && left.bf618PolicyId().equals(right.bf618PolicyId())
            && left.bf619PolicyId().equals(right.bf619PolicyId())
            && left.bf620PolicyId().equals(right.bf620PolicyId())
            && left.bf624PolicyId().equals(right.bf624PolicyId())
            && left.selectionState().equals(right.selectionState())
            && left.recommendationState().equals(right.recommendationState())
            && Objects.equals(left.addSleeperPlayerId(), right.addSleeperPlayerId())
            && Objects.equals(left.dropSleeperPlayerId(), right.dropSleeperPlayerId());
    }

    private static boolean auditTableExists(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='governed_recommendation_audits'")) {
            try (var rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void ensureTable(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS governed_recommendation_audits (
                    id TEXT PRIMARY KEY,
                    lineage_key TEXT NOT NULL UNIQUE,
                    capture_policy_id TEXT NOT NULL,
                    league_id TEXT NOT NULL,
                    sleeper_owner_id TEXT NOT NULL,
                    sleeper_league_id TEXT NOT NULL,
                    roster_id INTEGER NOT NULL,
                    season INTEGER NOT NULL,
                    provider_status TEXT NOT NULL,
                    provider_leg INTEGER,
                    market_snapshot_id TEXT NOT NULL,
                    waiver_snapshot_id TEXT NOT NULL,
                    bf618_policy_id TEXT NOT NULL,
                    bf619_policy_id TEXT NOT NULL,
                    bf620_policy_id TEXT NOT NULL,
                    bf624_policy_id TEXT NOT NULL,
                    selection_state TEXT NOT NULL,
                    recommendation_state TEXT NOT NULL,
                    add_sleeper_player_id TEXT,
                    drop_sleeper_player_id TEXT,
                    captured_at_utc TEXT NOT NULL,
                    FOREIGN KEY (league_id) REFERENCES leagues(id) ON DELETE RESTRICT,
                    CHECK (roster_id > 0),
                    CHECK (season BETWEEN 1999 AND 2100),
                    CHECK ((recommendation_state = 'RECOMMEND_ADD_DROP' AND add_sleeper_player_id IS NOT NULL AND drop_sleeper_player_id IS NOT NULL)
                        OR (recommendation_state = 'NO_GOVERNED_TRANSACTION' AND add_sleeper_player_id IS NULL AND drop_sleeper_player_id IS NULL))
                )
                """);
            statement.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_governed_recommendation_audits_league_capture "
                    + "ON governed_recommendation_audits(league_id, captured_at_utc)");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public enum CaptureState { CAPTURED_VERIFIED, ALREADY_CAPTURED_EXACT }

    public record CaptureResult(CaptureState state, AuditRecord record) {
        public CaptureResult {
            Objects.requireNonNull(state, "state must not be null");
            Objects.requireNonNull(record, "record must not be null");
        }
    }

    public record AuditRecord(
        String id,
        String lineageKey,
        String capturePolicyId,
        String leagueId,
        String sleeperOwnerId,
        String sleeperLeagueId,
        int rosterId,
        int season,
        String providerStatus,
        Integer providerLeg,
        String marketSnapshotId,
        String waiverSnapshotId,
        String bf618PolicyId,
        String bf619PolicyId,
        String bf620PolicyId,
        String bf624PolicyId,
        String selectionState,
        String recommendationState,
        String addSleeperPlayerId,
        String dropSleeperPlayerId,
        Instant capturedAtUtc) {
        public AuditRecord {
            id = optional(id);
            lineageKey = requireText(lineageKey, "lineageKey");
            capturePolicyId = requireText(capturePolicyId, "capturePolicyId");
            leagueId = requireText(leagueId, "leagueId");
            sleeperOwnerId = requireText(sleeperOwnerId, "sleeperOwnerId");
            sleeperLeagueId = requireText(sleeperLeagueId, "sleeperLeagueId");
            if (rosterId <= 0) throw new IllegalArgumentException("rosterId must be positive");
            if (season < 1999 || season > 2100) throw new IllegalArgumentException("season out of range");
            providerStatus = requireText(providerStatus, "providerStatus");
            marketSnapshotId = requireText(marketSnapshotId, "marketSnapshotId");
            waiverSnapshotId = requireText(waiverSnapshotId, "waiverSnapshotId");
            bf618PolicyId = requireText(bf618PolicyId, "bf618PolicyId");
            bf619PolicyId = requireText(bf619PolicyId, "bf619PolicyId");
            bf620PolicyId = requireText(bf620PolicyId, "bf620PolicyId");
            bf624PolicyId = requireText(bf624PolicyId, "bf624PolicyId");
            selectionState = requireText(selectionState, "selectionState");
            recommendationState = requireText(recommendationState, "recommendationState");
            addSleeperPlayerId = optional(addSleeperPlayerId);
            dropSleeperPlayerId = optional(dropSleeperPlayerId);
            Objects.requireNonNull(capturedAtUtc, "capturedAtUtc must not be null");
            boolean recommendation = "RECOMMEND_ADD_DROP".equals(recommendationState);
            boolean noTransaction = "NO_GOVERNED_TRANSACTION".equals(recommendationState);
            if (!recommendation && !noTransaction) {
                throw new IllegalArgumentException("unsupported recommendationState: " + recommendationState);
            }
            if (recommendation && (addSleeperPlayerId == null || dropSleeperPlayerId == null)) {
                throw new IllegalArgumentException("RECOMMEND_ADD_DROP requires both add and drop ids");
            }
            if (noTransaction && (addSleeperPlayerId != null || dropSleeperPlayerId != null)) {
                throw new IllegalArgumentException("NO_GOVERNED_TRANSACTION must not carry add/drop ids");
            }
        }

        public AuditRecord withId(String newId) {
            return new AuditRecord(newId, lineageKey, capturePolicyId, leagueId, sleeperOwnerId, sleeperLeagueId,
                rosterId, season, providerStatus, providerLeg, marketSnapshotId, waiverSnapshotId,
                bf618PolicyId, bf619PolicyId, bf620PolicyId, bf624PolicyId, selectionState,
                recommendationState, addSleeperPlayerId, dropSleeperPlayerId, capturedAtUtc);
        }
    }
}
