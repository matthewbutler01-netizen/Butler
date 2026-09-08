package io.butler.bet.data;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Atomic persistence for immutable BF-602 live waiver identity snapshots. */
public final class LiveWaiverSnapshotRepository {
    private final Database database;

    public LiveWaiverSnapshotRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public void save(Snapshot snapshot, List<Entry> entries) throws SQLException {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        entries = List.copyOf(Objects.requireNonNull(entries, "entries must not be null"));
        if (entries.size() != snapshot.activeIdentityCount()) {
            throw new IllegalArgumentException("entry count must equal active identity count");
        }
        try (Connection connection = database.openConnection()) {
            ensureTables(connection);
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.prepareStatement("""
                    INSERT INTO live_waiver_snapshots(
                        id, league_id, sleeper_league_id, season, provider_status, provider_leg,
                        source, proof_policy_id, eligibility_policy_id, observed_at_utc,
                        current_roster_identity_count, active_identity_count, active_rostered_identity_count,
                        rostered_absent_active_count, free_agent_identity_count, league_eligible_free_agent_count)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """)) {
                    statement.setString(1, snapshot.id());
                    statement.setString(2, snapshot.leagueId());
                    statement.setString(3, snapshot.sleeperLeagueId());
                    statement.setInt(4, snapshot.season());
                    statement.setString(5, snapshot.providerStatus());
                    if (snapshot.providerLeg() == null) statement.setNull(6, java.sql.Types.INTEGER);
                    else statement.setInt(6, snapshot.providerLeg());
                    statement.setString(7, snapshot.source());
                    statement.setString(8, snapshot.proofPolicyId());
                    statement.setString(9, snapshot.eligibilityPolicyId());
                    statement.setString(10, snapshot.observedAtUtc().toString());
                    statement.setInt(11, snapshot.currentRosterIdentityCount());
                    statement.setInt(12, snapshot.activeIdentityCount());
                    statement.setInt(13, snapshot.activeRosteredIdentityCount());
                    statement.setInt(14, snapshot.rosteredAbsentActiveCount());
                    statement.setInt(15, snapshot.freeAgentIdentityCount());
                    statement.setInt(16, snapshot.leagueEligibleFreeAgentCount());
                    statement.executeUpdate();
                }
                try (var statement = connection.prepareStatement("""
                    INSERT INTO live_waiver_snapshot_entries(
                        snapshot_id, sleeper_player_id, display_name, position, fantasy_positions,
                        nfl_team, provider_status, rostered, free_agent, league_eligible, eligibility_reason)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?)
                    """)) {
                    for (Entry entry : entries) {
                        statement.setString(1, snapshot.id());
                        statement.setString(2, entry.sleeperPlayerId());
                        statement.setString(3, entry.displayName());
                        statement.setString(4, entry.position());
                        statement.setString(5, String.join(",", entry.fantasyPositions()));
                        statement.setString(6, entry.nflTeam());
                        statement.setString(7, entry.providerStatus());
                        statement.setInt(8, entry.rostered() ? 1 : 0);
                        statement.setInt(9, entry.freeAgent() ? 1 : 0);
                        statement.setInt(10, entry.leagueEligible() ? 1 : 0);
                        statement.setString(11, entry.eligibilityReason());
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                if (e instanceof SQLException sql) throw sql;
                throw e;
            }
        }
    }

    public Counts counts(String snapshotId) throws SQLException {
        String normalized = requireText(snapshotId, "snapshotId");
        try (Connection connection = database.openConnection()) {
            ensureTables(connection);
            try (var statement = connection.prepareStatement("""
                SELECT COUNT(*) AS total,
                    COALESCE(SUM(rostered),0) AS rostered,
                    COALESCE(SUM(free_agent),0) AS free_agents,
                    COALESCE(SUM(league_eligible),0) AS eligible
                FROM live_waiver_snapshot_entries WHERE snapshot_id = ?
                """)) {
                statement.setString(1, normalized);
                try (var rs = statement.executeQuery()) {
                    if (!rs.next()) return new Counts(0, 0, 0, 0);
                    return new Counts(rs.getInt("total"), rs.getInt("rostered"), rs.getInt("free_agents"), rs.getInt("eligible"));
                }
            }
        }
    }

    public int snapshotCountForLeague(String leagueId) throws SQLException {
        String normalized = requireText(leagueId, "leagueId");
        try (Connection connection = database.openConnection()) {
            ensureTables(connection);
            try (var statement = connection.prepareStatement("SELECT COUNT(*) FROM live_waiver_snapshots WHERE league_id = ?")) {
                statement.setString(1, normalized);
                try (var rs = statement.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
            }
        }
    }

    private static void ensureTables(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS live_waiver_snapshots (
                    id TEXT PRIMARY KEY,
                    league_id TEXT NOT NULL,
                    sleeper_league_id TEXT NOT NULL,
                    season INTEGER NOT NULL,
                    provider_status TEXT NOT NULL,
                    provider_leg INTEGER,
                    source TEXT NOT NULL,
                    proof_policy_id TEXT NOT NULL,
                    eligibility_policy_id TEXT NOT NULL,
                    observed_at_utc TEXT NOT NULL,
                    current_roster_identity_count INTEGER NOT NULL,
                    active_identity_count INTEGER NOT NULL,
                    active_rostered_identity_count INTEGER NOT NULL,
                    rostered_absent_active_count INTEGER NOT NULL,
                    free_agent_identity_count INTEGER NOT NULL,
                    league_eligible_free_agent_count INTEGER NOT NULL,
                    FOREIGN KEY (league_id) REFERENCES leagues(id) ON DELETE CASCADE,
                    CHECK (season BETWEEN 1999 AND 2100),
                    CHECK (current_roster_identity_count >= 0),
                    CHECK (active_identity_count >= 0),
                    CHECK (active_rostered_identity_count >= 0),
                    CHECK (rostered_absent_active_count >= 0),
                    CHECK (free_agent_identity_count >= 0),
                    CHECK (league_eligible_free_agent_count >= 0)
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS live_waiver_snapshot_entries (
                    snapshot_id TEXT NOT NULL,
                    sleeper_player_id TEXT NOT NULL,
                    display_name TEXT NOT NULL,
                    position TEXT,
                    fantasy_positions TEXT NOT NULL,
                    nfl_team TEXT,
                    provider_status TEXT,
                    rostered INTEGER NOT NULL,
                    free_agent INTEGER NOT NULL,
                    league_eligible INTEGER NOT NULL,
                    eligibility_reason TEXT NOT NULL,
                    PRIMARY KEY (snapshot_id, sleeper_player_id),
                    FOREIGN KEY (snapshot_id) REFERENCES live_waiver_snapshots(id) ON DELETE CASCADE,
                    CHECK (rostered IN (0,1)),
                    CHECK (free_agent IN (0,1)),
                    CHECK (league_eligible IN (0,1)),
                    CHECK (rostered + free_agent = 1),
                    CHECK (league_eligible <= free_agent)
                )
                """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_live_waiver_snapshots_league_observed ON live_waiver_snapshots(league_id, observed_at_utc)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_live_waiver_entries_snapshot_free_eligible ON live_waiver_snapshot_entries(snapshot_id, free_agent, league_eligible)");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    public record Snapshot(
        String id,
        String leagueId,
        String sleeperLeagueId,
        int season,
        String providerStatus,
        Integer providerLeg,
        String source,
        String proofPolicyId,
        String eligibilityPolicyId,
        Instant observedAtUtc,
        int currentRosterIdentityCount,
        int activeIdentityCount,
        int activeRosteredIdentityCount,
        int rosteredAbsentActiveCount,
        int freeAgentIdentityCount,
        int leagueEligibleFreeAgentCount) {
        public Snapshot {
            id = requireText(id, "id");
            leagueId = requireText(leagueId, "leagueId");
            sleeperLeagueId = requireText(sleeperLeagueId, "sleeperLeagueId");
            providerStatus = requireText(providerStatus, "providerStatus");
            source = requireText(source, "source");
            proofPolicyId = requireText(proofPolicyId, "proofPolicyId");
            eligibilityPolicyId = requireText(eligibilityPolicyId, "eligibilityPolicyId");
            Objects.requireNonNull(observedAtUtc, "observedAtUtc must not be null");
        }
    }

    public record Entry(
        String sleeperPlayerId,
        String displayName,
        String position,
        List<String> fantasyPositions,
        String nflTeam,
        String providerStatus,
        boolean rostered,
        boolean freeAgent,
        boolean leagueEligible,
        String eligibilityReason) {
        public Entry {
            sleeperPlayerId = requireText(sleeperPlayerId, "sleeperPlayerId");
            displayName = requireText(displayName, "displayName");
            fantasyPositions = List.copyOf(Objects.requireNonNull(fantasyPositions, "fantasyPositions must not be null"));
            eligibilityReason = requireText(eligibilityReason, "eligibilityReason");
            if (rostered == freeAgent) throw new IllegalArgumentException("entry must be exactly one of rostered or free agent");
            if (leagueEligible && !freeAgent) throw new IllegalArgumentException("only free agents may be league eligible");
        }
    }

    public record Counts(int total, int rostered, int freeAgents, int eligibleFreeAgents) {}
}
