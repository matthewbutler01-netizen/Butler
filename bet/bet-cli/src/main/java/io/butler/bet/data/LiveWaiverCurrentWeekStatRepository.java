package io.butler.bet.data;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Atomic immutable persistence for BF-607 current-week market-active raw-stat evidence. */
public final class LiveWaiverCurrentWeekStatRepository {
    private final Database database;

    public LiveWaiverCurrentWeekStatRepository(Database database) {
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
                    INSERT INTO live_waiver_current_week_stat_snapshots(
                        id, league_id, market_snapshot_id, availability_snapshot_id, sleeper_league_id,
                        season, provider_status, provider_leg, state_season, state_week, state_season_type,
                        policy_id, source, observation_state, observed_at_utc, candidate_count,
                        source_present_count, source_absent_count, with_pass_att_count,
                        with_rush_att_count, with_rec_tgt_count, with_receptions_count)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """)) {
                    statement.setString(1, snapshot.id());
                    statement.setString(2, snapshot.leagueId());
                    statement.setString(3, snapshot.marketSnapshotId());
                    statement.setString(4, snapshot.availabilitySnapshotId());
                    statement.setString(5, snapshot.sleeperLeagueId());
                    statement.setInt(6, snapshot.season());
                    statement.setString(7, snapshot.providerStatus());
                    if (snapshot.providerLeg() == null) statement.setNull(8, java.sql.Types.INTEGER);
                    else statement.setInt(8, snapshot.providerLeg());
                    statement.setInt(9, snapshot.stateSeason());
                    statement.setInt(10, snapshot.stateWeek());
                    statement.setString(11, snapshot.stateSeasonType());
                    statement.setString(12, snapshot.policyId());
                    statement.setString(13, snapshot.source());
                    statement.setString(14, snapshot.observationState());
                    statement.setString(15, snapshot.observedAtUtc().toString());
                    statement.setInt(16, snapshot.candidateCount());
                    statement.setInt(17, snapshot.sourcePresentCount());
                    statement.setInt(18, snapshot.sourceAbsentCount());
                    statement.setInt(19, snapshot.withPassAttCount());
                    statement.setInt(20, snapshot.withRushAttCount());
                    statement.setInt(21, snapshot.withRecTgtCount());
                    statement.setInt(22, snapshot.withReceptionsCount());
                    statement.executeUpdate();
                }
                try (var statement = connection.prepareStatement("""
                    INSERT INTO live_waiver_current_week_stat_entries(
                        stat_snapshot_id, sleeper_player_id, display_name, position,
                        add_count, drop_count, net_add_attention, frame_membership,
                        source_state, raw_numeric_json,
                        pass_att, pass_cmp, pass_yd, pass_td, pass_int,
                        rush_att, rush_yd, rush_td,
                        rec_tgt, rec, rec_yd, rec_td, fum_lost)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
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
                        statement.setString(10, entry.rawNumericJson());
                        setNullableDouble(statement, 11, entry.passAtt());
                        setNullableDouble(statement, 12, entry.passCmp());
                        setNullableDouble(statement, 13, entry.passYd());
                        setNullableDouble(statement, 14, entry.passTd());
                        setNullableDouble(statement, 15, entry.passInt());
                        setNullableDouble(statement, 16, entry.rushAtt());
                        setNullableDouble(statement, 17, entry.rushYd());
                        setNullableDouble(statement, 18, entry.rushTd());
                        setNullableDouble(statement, 19, entry.recTgt());
                        setNullableDouble(statement, 20, entry.receptions());
                        setNullableDouble(statement, 21, entry.recYd());
                        setNullableDouble(statement, 22, entry.recTd());
                        setNullableDouble(statement, 23, entry.fumLost());
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                Counts counts = counts(connection, snapshot.id());
                if (!counts.matches(snapshot)) {
                    throw new SQLException("BF-607 transactional current-week stat reconciliation failed");
                }
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    public Counts counts(String snapshotId) throws SQLException {
        try (Connection connection = database.openConnection()) {
            ensureTables(connection);
            return counts(connection, requireText(snapshotId, "snapshotId"));
        }
    }

    public List<Entry> entries(String snapshotId) throws SQLException {
        try (Connection connection = database.openConnection()) {
            ensureTables(connection);
            try (var statement = connection.prepareStatement("""
                SELECT sleeper_player_id, display_name, position, add_count, drop_count,
                       net_add_attention, frame_membership, source_state, raw_numeric_json,
                       pass_att, pass_cmp, pass_yd, pass_td, pass_int, rush_att, rush_yd,
                       rush_td, rec_tgt, rec, rec_yd, rec_td, fum_lost
                FROM live_waiver_current_week_stat_entries
                WHERE stat_snapshot_id = ? ORDER BY sleeper_player_id ASC
                """)) {
                statement.setString(1, requireText(snapshotId, "snapshotId"));
                try (ResultSet rs = statement.executeQuery()) {
                    List<Entry> result = new ArrayList<>();
                    while (rs.next()) result.add(mapEntry(rs));
                    return List.copyOf(result);
                }
            }
        }
    }

    public int snapshotCountForLeague(String leagueId) throws SQLException {
        try (Connection connection = database.openConnection()) {
            ensureTables(connection);
            try (var statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM live_waiver_current_week_stat_snapshots WHERE league_id = ?")) {
                statement.setString(1, requireText(leagueId, "leagueId"));
                try (ResultSet rs = statement.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
            }
        }
    }

    private static Counts counts(Connection connection, String snapshotId) throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT COUNT(*) AS total,
                   COALESCE(SUM(CASE WHEN source_state='SOURCE_PRESENT' THEN 1 ELSE 0 END),0) AS present_count,
                   COALESCE(SUM(CASE WHEN source_state='SOURCE_ABSENT' THEN 1 ELSE 0 END),0) AS absent_count,
                   COALESCE(SUM(CASE WHEN pass_att IS NOT NULL THEN 1 ELSE 0 END),0) AS pass_att_count,
                   COALESCE(SUM(CASE WHEN rush_att IS NOT NULL THEN 1 ELSE 0 END),0) AS rush_att_count,
                   COALESCE(SUM(CASE WHEN rec_tgt IS NOT NULL THEN 1 ELSE 0 END),0) AS rec_tgt_count,
                   COALESCE(SUM(CASE WHEN rec IS NOT NULL THEN 1 ELSE 0 END),0) AS rec_count
            FROM live_waiver_current_week_stat_entries WHERE stat_snapshot_id = ?
            """)) {
            statement.setString(1, snapshotId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return new Counts(0,0,0,0,0,0,0);
                return new Counts(rs.getInt("total"), rs.getInt("present_count"), rs.getInt("absent_count"),
                    rs.getInt("pass_att_count"), rs.getInt("rush_att_count"), rs.getInt("rec_tgt_count"),
                    rs.getInt("rec_count"));
            }
        }
    }

    private static Entry mapEntry(ResultSet rs) throws SQLException {
        return new Entry(
            rs.getString("sleeper_player_id"), rs.getString("display_name"), rs.getString("position"),
            rs.getInt("add_count"), rs.getInt("drop_count"), rs.getInt("net_add_attention"),
            rs.getString("frame_membership"), rs.getString("source_state"), rs.getString("raw_numeric_json"),
            nullableDouble(rs, "pass_att"), nullableDouble(rs, "pass_cmp"), nullableDouble(rs, "pass_yd"),
            nullableDouble(rs, "pass_td"), nullableDouble(rs, "pass_int"), nullableDouble(rs, "rush_att"),
            nullableDouble(rs, "rush_yd"), nullableDouble(rs, "rush_td"), nullableDouble(rs, "rec_tgt"),
            nullableDouble(rs, "rec"), nullableDouble(rs, "rec_yd"), nullableDouble(rs, "rec_td"),
            nullableDouble(rs, "fum_lost"));
    }

    private static void ensureTables(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS live_waiver_current_week_stat_snapshots (
                    id TEXT PRIMARY KEY,
                    league_id TEXT NOT NULL,
                    market_snapshot_id TEXT NOT NULL,
                    availability_snapshot_id TEXT NOT NULL,
                    sleeper_league_id TEXT NOT NULL,
                    season INTEGER NOT NULL,
                    provider_status TEXT NOT NULL,
                    provider_leg INTEGER,
                    state_season INTEGER NOT NULL,
                    state_week INTEGER NOT NULL,
                    state_season_type TEXT NOT NULL,
                    policy_id TEXT NOT NULL,
                    source TEXT NOT NULL,
                    observation_state TEXT NOT NULL,
                    observed_at_utc TEXT NOT NULL,
                    candidate_count INTEGER NOT NULL,
                    source_present_count INTEGER NOT NULL,
                    source_absent_count INTEGER NOT NULL,
                    with_pass_att_count INTEGER NOT NULL,
                    with_rush_att_count INTEGER NOT NULL,
                    with_rec_tgt_count INTEGER NOT NULL,
                    with_receptions_count INTEGER NOT NULL,
                    FOREIGN KEY (league_id) REFERENCES leagues(id) ON DELETE CASCADE,
                    FOREIGN KEY (market_snapshot_id) REFERENCES live_waiver_market_attention_snapshots(id) ON DELETE CASCADE,
                    FOREIGN KEY (availability_snapshot_id) REFERENCES live_waiver_availability_snapshots(id) ON DELETE CASCADE,
                    CHECK (season BETWEEN 1999 AND 2100),
                    CHECK (state_season BETWEEN 1999 AND 2100),
                    CHECK (state_week BETWEEN 1 AND 25),
                    CHECK (candidate_count >= 0),
                    CHECK (source_present_count >= 0),
                    CHECK (source_absent_count >= 0),
                    CHECK (candidate_count = source_present_count + source_absent_count),
                    CHECK (with_pass_att_count BETWEEN 0 AND candidate_count),
                    CHECK (with_rush_att_count BETWEEN 0 AND candidate_count),
                    CHECK (with_rec_tgt_count BETWEEN 0 AND candidate_count),
                    CHECK (with_receptions_count BETWEEN 0 AND candidate_count)
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS live_waiver_current_week_stat_entries (
                    stat_snapshot_id TEXT NOT NULL,
                    sleeper_player_id TEXT NOT NULL,
                    display_name TEXT NOT NULL,
                    position TEXT,
                    add_count INTEGER NOT NULL,
                    drop_count INTEGER NOT NULL,
                    net_add_attention INTEGER NOT NULL,
                    frame_membership TEXT NOT NULL,
                    source_state TEXT NOT NULL,
                    raw_numeric_json TEXT,
                    pass_att REAL, pass_cmp REAL, pass_yd REAL, pass_td REAL, pass_int REAL,
                    rush_att REAL, rush_yd REAL, rush_td REAL,
                    rec_tgt REAL, rec REAL, rec_yd REAL, rec_td REAL, fum_lost REAL,
                    PRIMARY KEY (stat_snapshot_id, sleeper_player_id),
                    FOREIGN KEY (stat_snapshot_id) REFERENCES live_waiver_current_week_stat_snapshots(id) ON DELETE CASCADE,
                    CHECK (add_count >= 0),
                    CHECK (drop_count >= 0),
                    CHECK (net_add_attention = add_count - drop_count),
                    CHECK (frame_membership IN ('ADD_ONLY','DROP_ONLY','BOTH')),
                    CHECK (source_state IN ('SOURCE_PRESENT','SOURCE_ABSENT'))
                )
                """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_live_waiver_week_stats_league_observed ON live_waiver_current_week_stat_snapshots(league_id, observed_at_utc)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_live_waiver_week_stats_market ON live_waiver_current_week_stat_snapshots(market_snapshot_id, observed_at_utc)");
        }
    }

    private static void setNullableDouble(java.sql.PreparedStatement statement, int index, Double value) throws SQLException {
        if (value == null) statement.setNull(index, java.sql.Types.REAL);
        else statement.setDouble(index, value);
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    public record Snapshot(
        String id, String leagueId, String marketSnapshotId, String availabilitySnapshotId,
        String sleeperLeagueId, int season, String providerStatus, Integer providerLeg,
        int stateSeason, int stateWeek, String stateSeasonType, String policyId, String source,
        String observationState, Instant observedAtUtc, int candidateCount,
        int sourcePresentCount, int sourceAbsentCount, int withPassAttCount,
        int withRushAttCount, int withRecTgtCount, int withReceptionsCount) {
        public Snapshot {
            id = requireText(id, "id");
            leagueId = requireText(leagueId, "leagueId");
            marketSnapshotId = requireText(marketSnapshotId, "marketSnapshotId");
            availabilitySnapshotId = requireText(availabilitySnapshotId, "availabilitySnapshotId");
            sleeperLeagueId = requireText(sleeperLeagueId, "sleeperLeagueId");
            providerStatus = requireText(providerStatus, "providerStatus");
            stateSeasonType = requireText(stateSeasonType, "stateSeasonType");
            policyId = requireText(policyId, "policyId");
            source = requireText(source, "source");
            observationState = requireText(observationState, "observationState");
            Objects.requireNonNull(observedAtUtc, "observedAtUtc must not be null");
            if (candidateCount != sourcePresentCount + sourceAbsentCount) {
                throw new IllegalArgumentException("source presence counts must reconcile");
            }
        }
    }

    public record Entry(
        String sleeperPlayerId, String displayName, String position,
        int addCount, int dropCount, int netAddAttention, String frameMembership,
        String sourceState, String rawNumericJson,
        Double passAtt, Double passCmp, Double passYd, Double passTd, Double passInt,
        Double rushAtt, Double rushYd, Double rushTd,
        Double recTgt, Double receptions, Double recYd, Double recTd, Double fumLost) {
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
            if ("SOURCE_ABSENT".equals(sourceState) && rawNumericJson != null) {
                throw new IllegalArgumentException("SOURCE_ABSENT must not carry raw numeric JSON");
            }
        }
    }

    public record Counts(
        int total, int sourcePresent, int sourceAbsent,
        int withPassAtt, int withRushAtt, int withRecTgt, int withReceptions) {
        boolean matches(Snapshot snapshot) {
            return total == snapshot.candidateCount()
                && sourcePresent == snapshot.sourcePresentCount()
                && sourceAbsent == snapshot.sourceAbsentCount()
                && withPassAtt == snapshot.withPassAttCount()
                && withRushAtt == snapshot.withRushAttCount()
                && withRecTgt == snapshot.withRecTgtCount()
                && withReceptions == snapshot.withReceptionsCount();
        }
    }
}
