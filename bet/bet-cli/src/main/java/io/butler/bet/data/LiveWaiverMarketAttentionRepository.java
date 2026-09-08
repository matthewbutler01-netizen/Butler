package io.butler.bet.data;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Atomic immutable persistence for BF-603 live waiver market-attention evidence. */
public final class LiveWaiverMarketAttentionRepository {
    private final Database database;

    public LiveWaiverMarketAttentionRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public void save(Snapshot snapshot, List<Entry> entries) throws SQLException {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        entries = List.copyOf(Objects.requireNonNull(entries, "entries must not be null"));
        if (entries.size() != snapshot.candidateCount()) {
            throw new IllegalArgumentException("entry count must equal candidate count");
        }
        try (Connection connection = database.openConnection()) {
            ensureTables(connection);
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.prepareStatement("""
                    INSERT INTO live_waiver_market_attention_snapshots(
                        id, league_id, waiver_snapshot_id, sleeper_league_id, season, provider_status,
                        provider_leg, policy_id, lookback_hours, result_limit, observed_at_utc,
                        candidate_count, add_frame_size, drop_frame_size, add_only_count, drop_only_count,
                        both_count, neither_count)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """)) {
                    statement.setString(1, snapshot.id());
                    statement.setString(2, snapshot.leagueId());
                    statement.setString(3, snapshot.waiverSnapshotId());
                    statement.setString(4, snapshot.sleeperLeagueId());
                    statement.setInt(5, snapshot.season());
                    statement.setString(6, snapshot.providerStatus());
                    if (snapshot.providerLeg() == null) statement.setNull(7, java.sql.Types.INTEGER);
                    else statement.setInt(7, snapshot.providerLeg());
                    statement.setString(8, snapshot.policyId());
                    statement.setInt(9, snapshot.lookbackHours());
                    statement.setInt(10, snapshot.resultLimit());
                    statement.setString(11, snapshot.observedAtUtc().toString());
                    statement.setInt(12, snapshot.candidateCount());
                    statement.setInt(13, snapshot.addFrameSize());
                    statement.setInt(14, snapshot.dropFrameSize());
                    statement.setInt(15, snapshot.addOnlyCount());
                    statement.setInt(16, snapshot.dropOnlyCount());
                    statement.setInt(17, snapshot.bothCount());
                    statement.setInt(18, snapshot.neitherCount());
                    statement.executeUpdate();
                }
                try (var statement = connection.prepareStatement("""
                    INSERT INTO live_waiver_market_attention_entries(
                        market_snapshot_id, sleeper_player_id, display_name, position, nfl_team,
                        provider_status, add_count, drop_count, net_add_attention,
                        in_add_frame, in_drop_frame, frame_membership)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                    """)) {
                    for (Entry entry : entries) {
                        statement.setString(1, snapshot.id());
                        statement.setString(2, entry.sleeperPlayerId());
                        statement.setString(3, entry.displayName());
                        statement.setString(4, entry.position());
                        statement.setString(5, entry.nflTeam());
                        statement.setString(6, entry.providerStatus());
                        statement.setInt(7, entry.addCount());
                        statement.setInt(8, entry.dropCount());
                        statement.setInt(9, entry.netAddAttention());
                        statement.setInt(10, entry.inAddFrame() ? 1 : 0);
                        statement.setInt(11, entry.inDropFrame() ? 1 : 0);
                        statement.setString(12, entry.frameMembership());
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                Counts counts = counts(connection, snapshot.id());
                if (counts.total() != snapshot.candidateCount()
                    || counts.addOnly() != snapshot.addOnlyCount()
                    || counts.dropOnly() != snapshot.dropOnlyCount()
                    || counts.both() != snapshot.bothCount()
                    || counts.neither() != snapshot.neitherCount()) {
                    throw new SQLException("BF-603 transactional market-attention reconciliation failed");
                }
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    public Counts counts(String snapshotId) throws SQLException {
        String normalized = requireText(snapshotId, "snapshotId");
        try (Connection connection = database.openConnection()) {
            ensureTables(connection);
            return counts(connection, normalized);
        }
    }

    public int snapshotCountForLeague(String leagueId) throws SQLException {
        String normalized = requireText(leagueId, "leagueId");
        try (Connection connection = database.openConnection()) {
            ensureTables(connection);
            try (var statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM live_waiver_market_attention_snapshots WHERE league_id = ?")) {
                statement.setString(1, normalized);
                try (var rs = statement.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
            }
        }
    }

    private static Counts counts(Connection connection, String snapshotId) throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT COUNT(*) AS total,
                COALESCE(SUM(CASE WHEN frame_membership='ADD_ONLY' THEN 1 ELSE 0 END),0) AS add_only,
                COALESCE(SUM(CASE WHEN frame_membership='DROP_ONLY' THEN 1 ELSE 0 END),0) AS drop_only,
                COALESCE(SUM(CASE WHEN frame_membership='BOTH' THEN 1 ELSE 0 END),0) AS both_count,
                COALESCE(SUM(CASE WHEN frame_membership='NEITHER' THEN 1 ELSE 0 END),0) AS neither_count
            FROM live_waiver_market_attention_entries WHERE market_snapshot_id = ?
            """)) {
            statement.setString(1, snapshotId);
            try (var rs = statement.executeQuery()) {
                if (!rs.next()) return new Counts(0, 0, 0, 0, 0);
                return new Counts(rs.getInt("total"), rs.getInt("add_only"), rs.getInt("drop_only"),
                    rs.getInt("both_count"), rs.getInt("neither_count"));
            }
        }
    }

    private static void ensureTables(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS live_waiver_market_attention_snapshots (
                    id TEXT PRIMARY KEY,
                    league_id TEXT NOT NULL,
                    waiver_snapshot_id TEXT NOT NULL,
                    sleeper_league_id TEXT NOT NULL,
                    season INTEGER NOT NULL,
                    provider_status TEXT NOT NULL,
                    provider_leg INTEGER,
                    policy_id TEXT NOT NULL,
                    lookback_hours INTEGER NOT NULL,
                    result_limit INTEGER NOT NULL,
                    observed_at_utc TEXT NOT NULL,
                    candidate_count INTEGER NOT NULL,
                    add_frame_size INTEGER NOT NULL,
                    drop_frame_size INTEGER NOT NULL,
                    add_only_count INTEGER NOT NULL,
                    drop_only_count INTEGER NOT NULL,
                    both_count INTEGER NOT NULL,
                    neither_count INTEGER NOT NULL,
                    FOREIGN KEY (league_id) REFERENCES leagues(id) ON DELETE CASCADE,
                    FOREIGN KEY (waiver_snapshot_id) REFERENCES live_waiver_snapshots(id) ON DELETE CASCADE,
                    CHECK (season BETWEEN 1999 AND 2100),
                    CHECK (lookback_hours > 0),
                    CHECK (result_limit > 0),
                    CHECK (candidate_count >= 0),
                    CHECK (add_frame_size >= 0),
                    CHECK (drop_frame_size >= 0),
                    CHECK (add_only_count >= 0),
                    CHECK (drop_only_count >= 0),
                    CHECK (both_count >= 0),
                    CHECK (neither_count >= 0),
                    CHECK (candidate_count = add_only_count + drop_only_count + both_count + neither_count)
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS live_waiver_market_attention_entries (
                    market_snapshot_id TEXT NOT NULL,
                    sleeper_player_id TEXT NOT NULL,
                    display_name TEXT NOT NULL,
                    position TEXT,
                    nfl_team TEXT,
                    provider_status TEXT,
                    add_count INTEGER NOT NULL,
                    drop_count INTEGER NOT NULL,
                    net_add_attention INTEGER NOT NULL,
                    in_add_frame INTEGER NOT NULL,
                    in_drop_frame INTEGER NOT NULL,
                    frame_membership TEXT NOT NULL,
                    PRIMARY KEY (market_snapshot_id, sleeper_player_id),
                    FOREIGN KEY (market_snapshot_id) REFERENCES live_waiver_market_attention_snapshots(id) ON DELETE CASCADE,
                    CHECK (add_count >= 0),
                    CHECK (drop_count >= 0),
                    CHECK (in_add_frame IN (0,1)),
                    CHECK (in_drop_frame IN (0,1)),
                    CHECK (frame_membership IN ('ADD_ONLY','DROP_ONLY','BOTH','NEITHER'))
                )
                """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_live_waiver_market_league_observed ON live_waiver_market_attention_snapshots(league_id, observed_at_utc)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_live_waiver_market_entries_snapshot_attention ON live_waiver_market_attention_entries(market_snapshot_id, add_count, drop_count)");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    public record Snapshot(
        String id,
        String leagueId,
        String waiverSnapshotId,
        String sleeperLeagueId,
        int season,
        String providerStatus,
        Integer providerLeg,
        String policyId,
        int lookbackHours,
        int resultLimit,
        Instant observedAtUtc,
        int candidateCount,
        int addFrameSize,
        int dropFrameSize,
        int addOnlyCount,
        int dropOnlyCount,
        int bothCount,
        int neitherCount) {
        public Snapshot {
            id = requireText(id, "id");
            leagueId = requireText(leagueId, "leagueId");
            waiverSnapshotId = requireText(waiverSnapshotId, "waiverSnapshotId");
            sleeperLeagueId = requireText(sleeperLeagueId, "sleeperLeagueId");
            providerStatus = requireText(providerStatus, "providerStatus");
            policyId = requireText(policyId, "policyId");
            Objects.requireNonNull(observedAtUtc, "observedAtUtc must not be null");
            if (candidateCount != addOnlyCount + dropOnlyCount + bothCount + neitherCount) {
                throw new IllegalArgumentException("candidate membership counts must reconcile");
            }
        }
    }

    public record Entry(
        String sleeperPlayerId,
        String displayName,
        String position,
        String nflTeam,
        String providerStatus,
        int addCount,
        int dropCount,
        int netAddAttention,
        boolean inAddFrame,
        boolean inDropFrame,
        String frameMembership) {
        public Entry {
            sleeperPlayerId = requireText(sleeperPlayerId, "sleeperPlayerId");
            displayName = requireText(displayName, "displayName");
            frameMembership = requireText(frameMembership, "frameMembership");
            if (addCount < 0 || dropCount < 0) throw new IllegalArgumentException("trend counts must be nonnegative");
            if (netAddAttention != addCount - dropCount) throw new IllegalArgumentException("net attention must reconcile");
        }
    }

    public record Counts(int total, int addOnly, int dropOnly, int both, int neither) {}
}
