package io.butler.bet.data;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Atomic immutable persistence for BF-606 market-active availability and role metadata. */
public final class LiveWaiverAvailabilityRepository {
    private final Database database;

    public LiveWaiverAvailabilityRepository(Database database) {
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
                    INSERT INTO live_waiver_availability_snapshots(
                        id, league_id, market_snapshot_id, sleeper_league_id, season, provider_status,
                        provider_leg, policy_id, source, observed_at_utc, candidate_count,
                        source_present_count, source_absent_count, with_team_count, with_status_count,
                        with_injury_status_count, with_practice_participation_count,
                        with_depth_chart_position_count, with_depth_chart_order_count)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """)) {
                    statement.setString(1, snapshot.id());
                    statement.setString(2, snapshot.leagueId());
                    statement.setString(3, snapshot.marketSnapshotId());
                    statement.setString(4, snapshot.sleeperLeagueId());
                    statement.setInt(5, snapshot.season());
                    statement.setString(6, snapshot.providerStatus());
                    if (snapshot.providerLeg() == null) statement.setNull(7, java.sql.Types.INTEGER);
                    else statement.setInt(7, snapshot.providerLeg());
                    statement.setString(8, snapshot.policyId());
                    statement.setString(9, snapshot.source());
                    statement.setString(10, snapshot.observedAtUtc().toString());
                    statement.setInt(11, snapshot.candidateCount());
                    statement.setInt(12, snapshot.sourcePresentCount());
                    statement.setInt(13, snapshot.sourceAbsentCount());
                    statement.setInt(14, snapshot.withTeamCount());
                    statement.setInt(15, snapshot.withStatusCount());
                    statement.setInt(16, snapshot.withInjuryStatusCount());
                    statement.setInt(17, snapshot.withPracticeParticipationCount());
                    statement.setInt(18, snapshot.withDepthChartPositionCount());
                    statement.setInt(19, snapshot.withDepthChartOrderCount());
                    statement.executeUpdate();
                }
                try (var statement = connection.prepareStatement("""
                    INSERT INTO live_waiver_availability_entries(
                        availability_snapshot_id, sleeper_player_id, display_name, position,
                        add_count, drop_count, net_add_attention, frame_membership, source_state,
                        current_team, current_status, injury_status, injury_start_date,
                        practice_participation, depth_chart_position, depth_chart_order)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """)) {
                    for (Entry entry : entries) {
                        statement.setString(1, snapshot.id());
                        statement.setString(2, entry.sleeperPlayerId());
                        statement.setString(3, entry.displayName());
                        statement.setString(4, entry.position());
                        statement.setInt(5, entry.addCount());
                        statement.setInt(6, entry.dropCount());
                        statement.setInt(7, entry.netAddAttention());
                        statement.setString(8, entry.frameMembership());
                        statement.setString(9, entry.sourceState());
                        statement.setString(10, entry.currentTeam());
                        statement.setString(11, entry.currentStatus());
                        statement.setString(12, entry.injuryStatus());
                        statement.setString(13, entry.injuryStartDate());
                        statement.setString(14, entry.practiceParticipation());
                        statement.setString(15, entry.depthChartPosition());
                        if (entry.depthChartOrder() == null) statement.setNull(16, java.sql.Types.INTEGER);
                        else statement.setInt(16, entry.depthChartOrder());
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                Counts counts = counts(connection, snapshot.id());
                if (!counts.matches(snapshot)) {
                    throw new SQLException("BF-606 transactional availability reconciliation failed");
                }
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    public Optional<Snapshot> latestForMarketSnapshot(String marketSnapshotId) throws SQLException {
        String normalized = requireText(marketSnapshotId, "marketSnapshotId");
        try (Connection connection = database.openConnection()) {
            ensureTables(connection);
            try (var statement = connection.prepareStatement("""
                SELECT id, league_id, market_snapshot_id, sleeper_league_id, season, provider_status,
                       provider_leg, policy_id, source, observed_at_utc, candidate_count,
                       source_present_count, source_absent_count, with_team_count, with_status_count,
                       with_injury_status_count, with_practice_participation_count,
                       with_depth_chart_position_count, with_depth_chart_order_count
                FROM live_waiver_availability_snapshots
                WHERE market_snapshot_id = ?
                ORDER BY observed_at_utc DESC, rowid DESC
                LIMIT 1
                """)) {
                statement.setString(1, normalized);
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next() ? Optional.of(mapSnapshot(rs)) : Optional.empty();
                }
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

    public List<Entry> entries(String snapshotId) throws SQLException {
        String normalized = requireText(snapshotId, "snapshotId");
        try (Connection connection = database.openConnection()) {
            ensureTables(connection);
            try (var statement = connection.prepareStatement("""
                SELECT sleeper_player_id, display_name, position, add_count, drop_count,
                       net_add_attention, frame_membership, source_state, current_team,
                       current_status, injury_status, injury_start_date, practice_participation,
                       depth_chart_position, depth_chart_order
                FROM live_waiver_availability_entries
                WHERE availability_snapshot_id = ?
                ORDER BY sleeper_player_id ASC
                """)) {
                statement.setString(1, normalized);
                try (ResultSet rs = statement.executeQuery()) {
                    List<Entry> result = new ArrayList<>();
                    while (rs.next()) result.add(mapEntry(rs));
                    return List.copyOf(result);
                }
            }
        }
    }

    public int snapshotCountForLeague(String leagueId) throws SQLException {
        String normalized = requireText(leagueId, "leagueId");
        try (Connection connection = database.openConnection()) {
            ensureTables(connection);
            try (var statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM live_waiver_availability_snapshots WHERE league_id = ?")) {
                statement.setString(1, normalized);
                try (ResultSet rs = statement.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
            }
        }
    }

    private static Counts counts(Connection connection, String snapshotId) throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT COUNT(*) AS total,
                   COALESCE(SUM(CASE WHEN source_state='SOURCE_PRESENT' THEN 1 ELSE 0 END),0) AS present_count,
                   COALESCE(SUM(CASE WHEN source_state='SOURCE_ABSENT' THEN 1 ELSE 0 END),0) AS absent_count,
                   COALESCE(SUM(CASE WHEN current_team IS NOT NULL AND TRIM(current_team)<>'' THEN 1 ELSE 0 END),0) AS team_count,
                   COALESCE(SUM(CASE WHEN current_status IS NOT NULL AND TRIM(current_status)<>'' THEN 1 ELSE 0 END),0) AS status_count,
                   COALESCE(SUM(CASE WHEN injury_status IS NOT NULL AND TRIM(injury_status)<>'' THEN 1 ELSE 0 END),0) AS injury_count,
                   COALESCE(SUM(CASE WHEN practice_participation IS NOT NULL AND TRIM(practice_participation)<>'' THEN 1 ELSE 0 END),0) AS practice_count,
                   COALESCE(SUM(CASE WHEN depth_chart_position IS NOT NULL AND TRIM(depth_chart_position)<>'' THEN 1 ELSE 0 END),0) AS depth_position_count,
                   COALESCE(SUM(CASE WHEN depth_chart_order IS NOT NULL THEN 1 ELSE 0 END),0) AS depth_order_count
            FROM live_waiver_availability_entries WHERE availability_snapshot_id = ?
            """)) {
            statement.setString(1, snapshotId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return new Counts(0,0,0,0,0,0,0,0,0);
                return new Counts(
                    rs.getInt("total"), rs.getInt("present_count"), rs.getInt("absent_count"),
                    rs.getInt("team_count"), rs.getInt("status_count"), rs.getInt("injury_count"),
                    rs.getInt("practice_count"), rs.getInt("depth_position_count"), rs.getInt("depth_order_count"));
            }
        }
    }

    private static Snapshot mapSnapshot(ResultSet rs) throws SQLException {
        Object leg = rs.getObject("provider_leg");
        return new Snapshot(
            rs.getString("id"), rs.getString("league_id"), rs.getString("market_snapshot_id"),
            rs.getString("sleeper_league_id"), rs.getInt("season"), rs.getString("provider_status"),
            leg == null ? null : rs.getInt("provider_leg"), rs.getString("policy_id"), rs.getString("source"),
            Instant.parse(rs.getString("observed_at_utc")), rs.getInt("candidate_count"),
            rs.getInt("source_present_count"), rs.getInt("source_absent_count"), rs.getInt("with_team_count"),
            rs.getInt("with_status_count"), rs.getInt("with_injury_status_count"),
            rs.getInt("with_practice_participation_count"), rs.getInt("with_depth_chart_position_count"),
            rs.getInt("with_depth_chart_order_count"));
    }

    private static Entry mapEntry(ResultSet rs) throws SQLException {
        Object order = rs.getObject("depth_chart_order");
        return new Entry(
            rs.getString("sleeper_player_id"), rs.getString("display_name"), rs.getString("position"),
            rs.getInt("add_count"), rs.getInt("drop_count"), rs.getInt("net_add_attention"),
            rs.getString("frame_membership"), rs.getString("source_state"), rs.getString("current_team"),
            rs.getString("current_status"), rs.getString("injury_status"), rs.getString("injury_start_date"),
            rs.getString("practice_participation"), rs.getString("depth_chart_position"),
            order == null ? null : rs.getInt("depth_chart_order"));
    }

    private static void ensureTables(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS live_waiver_availability_snapshots (
                    id TEXT PRIMARY KEY,
                    league_id TEXT NOT NULL,
                    market_snapshot_id TEXT NOT NULL,
                    sleeper_league_id TEXT NOT NULL,
                    season INTEGER NOT NULL,
                    provider_status TEXT NOT NULL,
                    provider_leg INTEGER,
                    policy_id TEXT NOT NULL,
                    source TEXT NOT NULL,
                    observed_at_utc TEXT NOT NULL,
                    candidate_count INTEGER NOT NULL,
                    source_present_count INTEGER NOT NULL,
                    source_absent_count INTEGER NOT NULL,
                    with_team_count INTEGER NOT NULL,
                    with_status_count INTEGER NOT NULL,
                    with_injury_status_count INTEGER NOT NULL,
                    with_practice_participation_count INTEGER NOT NULL,
                    with_depth_chart_position_count INTEGER NOT NULL,
                    with_depth_chart_order_count INTEGER NOT NULL,
                    FOREIGN KEY (league_id) REFERENCES leagues(id) ON DELETE CASCADE,
                    FOREIGN KEY (market_snapshot_id) REFERENCES live_waiver_market_attention_snapshots(id) ON DELETE CASCADE,
                    CHECK (season BETWEEN 1999 AND 2100),
                    CHECK (candidate_count >= 0),
                    CHECK (source_present_count >= 0),
                    CHECK (source_absent_count >= 0),
                    CHECK (candidate_count = source_present_count + source_absent_count),
                    CHECK (with_team_count BETWEEN 0 AND candidate_count),
                    CHECK (with_status_count BETWEEN 0 AND candidate_count),
                    CHECK (with_injury_status_count BETWEEN 0 AND candidate_count),
                    CHECK (with_practice_participation_count BETWEEN 0 AND candidate_count),
                    CHECK (with_depth_chart_position_count BETWEEN 0 AND candidate_count),
                    CHECK (with_depth_chart_order_count BETWEEN 0 AND candidate_count)
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS live_waiver_availability_entries (
                    availability_snapshot_id TEXT NOT NULL,
                    sleeper_player_id TEXT NOT NULL,
                    display_name TEXT NOT NULL,
                    position TEXT,
                    add_count INTEGER NOT NULL,
                    drop_count INTEGER NOT NULL,
                    net_add_attention INTEGER NOT NULL,
                    frame_membership TEXT NOT NULL,
                    source_state TEXT NOT NULL,
                    current_team TEXT,
                    current_status TEXT,
                    injury_status TEXT,
                    injury_start_date TEXT,
                    practice_participation TEXT,
                    depth_chart_position TEXT,
                    depth_chart_order INTEGER,
                    PRIMARY KEY (availability_snapshot_id, sleeper_player_id),
                    FOREIGN KEY (availability_snapshot_id) REFERENCES live_waiver_availability_snapshots(id) ON DELETE CASCADE,
                    CHECK (add_count >= 0),
                    CHECK (drop_count >= 0),
                    CHECK (net_add_attention = add_count - drop_count),
                    CHECK (frame_membership IN ('ADD_ONLY','DROP_ONLY','BOTH')),
                    CHECK (source_state IN ('SOURCE_PRESENT','SOURCE_ABSENT'))
                )
                """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_live_waiver_availability_market_observed ON live_waiver_availability_snapshots(market_snapshot_id, observed_at_utc)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_live_waiver_availability_league_observed ON live_waiver_availability_snapshots(league_id, observed_at_utc)");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    public record Snapshot(
        String id, String leagueId, String marketSnapshotId, String sleeperLeagueId, int season,
        String providerStatus, Integer providerLeg, String policyId, String source, Instant observedAtUtc,
        int candidateCount, int sourcePresentCount, int sourceAbsentCount, int withTeamCount,
        int withStatusCount, int withInjuryStatusCount, int withPracticeParticipationCount,
        int withDepthChartPositionCount, int withDepthChartOrderCount) {
        public Snapshot {
            id = requireText(id, "id");
            leagueId = requireText(leagueId, "leagueId");
            marketSnapshotId = requireText(marketSnapshotId, "marketSnapshotId");
            sleeperLeagueId = requireText(sleeperLeagueId, "sleeperLeagueId");
            providerStatus = requireText(providerStatus, "providerStatus");
            policyId = requireText(policyId, "policyId");
            source = requireText(source, "source");
            Objects.requireNonNull(observedAtUtc, "observedAtUtc must not be null");
            if (candidateCount != sourcePresentCount + sourceAbsentCount) {
                throw new IllegalArgumentException("source presence counts must reconcile");
            }
        }
    }

    public record Entry(
        String sleeperPlayerId, String displayName, String position, int addCount, int dropCount,
        int netAddAttention, String frameMembership, String sourceState, String currentTeam,
        String currentStatus, String injuryStatus, String injuryStartDate, String practiceParticipation,
        String depthChartPosition, Integer depthChartOrder) {
        public Entry {
            sleeperPlayerId = requireText(sleeperPlayerId, "sleeperPlayerId");
            displayName = requireText(displayName, "displayName");
            frameMembership = requireText(frameMembership, "frameMembership");
            sourceState = requireText(sourceState, "sourceState");
            if (addCount < 0 || dropCount < 0) throw new IllegalArgumentException("attention counts must be nonnegative");
            if (netAddAttention != addCount - dropCount) throw new IllegalArgumentException("net attention must reconcile");
            if (!"SOURCE_PRESENT".equals(sourceState) && !"SOURCE_ABSENT".equals(sourceState)) {
                throw new IllegalArgumentException("unexpected sourceState: " + sourceState);
            }
            if ("SOURCE_ABSENT".equals(sourceState)
                && (usable(currentTeam) || usable(currentStatus) || usable(injuryStatus)
                    || usable(injuryStartDate) || usable(practiceParticipation) || usable(depthChartPosition)
                    || depthChartOrder != null)) {
                throw new IllegalArgumentException("SOURCE_ABSENT entry must not contain current provider metadata");
            }
        }
    }

    public record Counts(
        int total, int sourcePresent, int sourceAbsent, int withTeam, int withStatus,
        int withInjuryStatus, int withPracticeParticipation, int withDepthChartPosition,
        int withDepthChartOrder) {
        private boolean matches(Snapshot snapshot) {
            return total == snapshot.candidateCount()
                && sourcePresent == snapshot.sourcePresentCount()
                && sourceAbsent == snapshot.sourceAbsentCount()
                && withTeam == snapshot.withTeamCount()
                && withStatus == snapshot.withStatusCount()
                && withInjuryStatus == snapshot.withInjuryStatusCount()
                && withPracticeParticipation == snapshot.withPracticeParticipationCount()
                && withDepthChartPosition == snapshot.withDepthChartPositionCount()
                && withDepthChartOrder == snapshot.withDepthChartOrderCount();
        }
    }

    private static boolean usable(String value) { return value != null && !value.isBlank(); }
}
