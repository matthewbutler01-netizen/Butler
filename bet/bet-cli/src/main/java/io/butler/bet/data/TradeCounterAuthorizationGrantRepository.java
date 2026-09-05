package io.butler.bet.data;

import io.butler.bet.intelligence.TradeCounterAuthorizationPolicy;
import io.butler.bet.intelligence.TradeTeamPerspectiveRecommendationPolicy;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/** Durable trusted store for explicitly authorized counter grants. No external action occurs here. */
public final class TradeCounterAuthorizationGrantRepository {
    private final Database database;

    public TradeCounterAuthorizationGrantRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public void initialize() throws SQLException {
        try (var connection = database.openConnection();
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS trade_counter_authorization_grants (
                    grant_id TEXT PRIMARY KEY,
                    policy_id TEXT NOT NULL,
                    granted_at TEXT NOT NULL,
                    league_id TEXT NOT NULL,
                    season INTEGER NOT NULL,
                    source TEXT NOT NULL,
                    minimum_as_of_date TEXT,
                    perspective TEXT NOT NULL,
                    proposal_fingerprint TEXT NOT NULL,
                    action TEXT NOT NULL,
                    destination_type TEXT NOT NULL,
                    destination_id TEXT NOT NULL,
                    max_uses INTEGER NOT NULL,
                    consumed_at TEXT,
                    CHECK (policy_id = 'trade-counter-authorization-v1-explicit-fingerprint-action-destination-once'),
                    CHECK (season BETWEEN 1999 AND 2100),
                    CHECK (length(proposal_fingerprint) = 64),
                    CHECK (proposal_fingerprint NOT GLOB '*[^0-9a-f]*'),
                    CHECK (action IN ('SEND_NEGOTIATION_MESSAGE', 'SUBMIT_COUNTER_TRADE')),
                    CHECK (destination_type IN ('MANAGER', 'LEAGUE')),
                    CHECK (max_uses = 1),
                    CHECK ((action = 'SEND_NEGOTIATION_MESSAGE' AND destination_type = 'MANAGER')
                        OR (action = 'SUBMIT_COUNTER_TRADE' AND destination_type = 'LEAGUE')),
                    CHECK (action <> 'SUBMIT_COUNTER_TRADE' OR destination_id = league_id)
                )
                """);
            statement.executeUpdate("""
                CREATE UNIQUE INDEX IF NOT EXISTS idx_trade_counter_authorization_active_intent
                ON trade_counter_authorization_grants(
                    proposal_fingerprint, action, destination_type, destination_id)
                WHERE consumed_at IS NULL
                """);
            statement.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_trade_counter_authorization_grant_league
                ON trade_counter_authorization_grants(league_id, consumed_at)
                """);
            statement.executeUpdate("""
                CREATE TRIGGER IF NOT EXISTS trg_trade_counter_authorization_grant_delete_retained
                BEFORE DELETE ON trade_counter_authorization_grants
                FOR EACH ROW
                BEGIN
                    SELECT RAISE(ABORT, 'authorization grant audit record is retained and cannot be deleted');
                END
                """);
        }
    }

    public void save(TradeCounterAuthorizationPolicy.AuthorizationGrant grant) throws SQLException {
        Objects.requireNonNull(grant, "grant must not be null");
        try (var connection = database.openConnection();
             var statement = connection.prepareStatement("""
                 INSERT INTO trade_counter_authorization_grants(
                     grant_id, policy_id, granted_at, league_id, season, source,
                     minimum_as_of_date, perspective, proposal_fingerprint, action,
                     destination_type, destination_id, max_uses, consumed_at)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)
                 """)) {
            statement.setString(1, grant.grantId());
            statement.setString(2, grant.policyId());
            statement.setString(3, grant.grantedAt().toString());
            statement.setString(4, grant.leagueId());
            statement.setInt(5, grant.season());
            statement.setString(6, grant.source());
            if (grant.minimumAsOfDate() == null) statement.setNull(7, java.sql.Types.VARCHAR);
            else statement.setString(7, grant.minimumAsOfDate().toString());
            statement.setString(8, grant.perspective().name());
            statement.setString(9, grant.proposalFingerprint());
            statement.setString(10, grant.action().name());
            statement.setString(11, grant.destination().type().name());
            statement.setString(12, grant.destination().id());
            statement.setInt(13, grant.maxUses());
            statement.executeUpdate();
        }
    }

    public Optional<StoredGrant> findById(String grantId) throws SQLException {
        grantId = requireText(grantId, "grantId");
        try (var connection = database.openConnection();
             var statement = connection.prepareStatement("""
                 SELECT * FROM trade_counter_authorization_grants WHERE grant_id = ?
                 """)) {
            statement.setString(1, grantId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        }
    }

    public Optional<StoredGrant> findActive(
        String proposalFingerprint,
        TradeCounterAuthorizationPolicy.Action action,
        TradeCounterAuthorizationPolicy.Destination destination) throws SQLException {
        proposalFingerprint = requireFingerprint(proposalFingerprint);
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(destination, "destination must not be null");
        try (var connection = database.openConnection();
             var statement = connection.prepareStatement("""
                 SELECT *
                 FROM trade_counter_authorization_grants
                 WHERE proposal_fingerprint = ?
                   AND action = ?
                   AND destination_type = ?
                   AND destination_id = ?
                   AND consumed_at IS NULL
                 LIMIT 1
                 """)) {
            statement.setString(1, proposalFingerprint);
            statement.setString(2, action.name());
            statement.setString(3, destination.type().name());
            statement.setString(4, destination.id());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        }
    }

    /**
     * Atomically consumes one trusted grant only when all expected authorization coordinates match.
     * Exactly one caller can change consumed_at from NULL for a given grant.
     */
    public ConsumptionResult consume(
        String grantId,
        String expectedProposalFingerprint,
        TradeCounterAuthorizationPolicy.Action expectedAction,
        TradeCounterAuthorizationPolicy.Destination expectedDestination,
        Instant consumedAt) throws SQLException {
        grantId = requireText(grantId, "grantId");
        expectedProposalFingerprint = requireFingerprint(expectedProposalFingerprint);
        Objects.requireNonNull(expectedAction, "expectedAction must not be null");
        Objects.requireNonNull(expectedDestination, "expectedDestination must not be null");
        Objects.requireNonNull(consumedAt, "consumedAt must not be null");

        try (var connection = database.openConnection();
             var statement = connection.prepareStatement("""
                 UPDATE trade_counter_authorization_grants
                 SET consumed_at = ?
                 WHERE grant_id = ?
                   AND consumed_at IS NULL
                   AND max_uses = 1
                   AND proposal_fingerprint = ?
                   AND action = ?
                   AND destination_type = ?
                   AND destination_id = ?
                 """)) {
            statement.setString(1, consumedAt.toString());
            statement.setString(2, grantId);
            statement.setString(3, expectedProposalFingerprint);
            statement.setString(4, expectedAction.name());
            statement.setString(5, expectedDestination.type().name());
            statement.setString(6, expectedDestination.id());
            if (statement.executeUpdate() == 1) return ConsumptionResult.CONSUMED;
        }

        var stored = findById(grantId);
        if (stored.isEmpty()) return ConsumptionResult.NOT_FOUND;
        if (stored.get().consumedAt() != null) return ConsumptionResult.ALREADY_CONSUMED;
        return ConsumptionResult.MISMATCH;
    }

    private static StoredGrant read(ResultSet rs) throws SQLException {
        String minimumAsOf = rs.getString("minimum_as_of_date");
        String consumedAt = rs.getString("consumed_at");
        var destination = new TradeCounterAuthorizationPolicy.Destination(
            TradeCounterAuthorizationPolicy.DestinationType.valueOf(rs.getString("destination_type")),
            rs.getString("destination_id"));
        var grant = new TradeCounterAuthorizationPolicy.AuthorizationGrant(
            rs.getString("policy_id"),
            rs.getString("grant_id"),
            Instant.parse(rs.getString("granted_at")),
            rs.getString("league_id"),
            rs.getInt("season"),
            rs.getString("source"),
            minimumAsOf == null ? null : LocalDate.parse(minimumAsOf),
            TradeTeamPerspectiveRecommendationPolicy.Perspective.valueOf(rs.getString("perspective")),
            rs.getString("proposal_fingerprint"),
            TradeCounterAuthorizationPolicy.Action.valueOf(rs.getString("action")),
            destination,
            rs.getInt("max_uses"));
        return new StoredGrant(grant, consumedAt == null ? null : Instant.parse(consumedAt));
    }

    public enum ConsumptionResult {
        CONSUMED,
        ALREADY_CONSUMED,
        MISMATCH,
        NOT_FOUND
    }

    public record StoredGrant(
        TradeCounterAuthorizationPolicy.AuthorizationGrant grant,
        Instant consumedAt) {
        public StoredGrant {
            Objects.requireNonNull(grant, "grant must not be null");
        }

        public boolean consumed() {
            return consumedAt != null;
        }
    }

    private static String requireFingerprint(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("expectedProposalFingerprint must be lowercase SHA-256");
        }
        return value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
