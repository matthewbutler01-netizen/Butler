package io.butler.bet.integration.sleeper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.butler.bet.data.Database;
import io.butler.bet.intelligence.TradeAssetAnalyzer;
import io.butler.bet.intelligence.TradeCounterAuthorizationPolicy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.HexFormat;

/** Immutable provider-identity/movement snapshot captured before a manual counter trade can be acted on. */
public final class SleeperCounterTradeExpectationSnapshotRepository {
    public static final String POLICY_ID =
        "sleeper-counter-trade-expectation-snapshot-v1-handoff-provider-movement";
    private static final int INTERNAL_RESOLUTION_ROUND = 1;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Database database;
    private final SleeperManualCounterHandoffRepository handoffs;
    private final SleeperTradeExpectationResolver resolver;

    public SleeperCounterTradeExpectationSnapshotRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
        this.handoffs = new SleeperManualCounterHandoffRepository(database);
        this.resolver = new SleeperTradeExpectationResolver(database);
    }

    public void initialize() throws SQLException {
        handoffs.initialize();
        try (var connection = database.openConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS sleeper_counter_trade_expectation_snapshots (
                    claim_id TEXT PRIMARY KEY,
                    handoff_id TEXT NOT NULL UNIQUE,
                    snapshot_policy_id TEXT NOT NULL,
                    resolver_policy_id TEXT NOT NULL,
                    butler_league_id TEXT NOT NULL,
                    side_a_team_id TEXT NOT NULL,
                    side_b_team_id TEXT NOT NULL,
                    sleeper_league_id TEXT NOT NULL,
                    movement_json TEXT NOT NULL,
                    movement_sha256 TEXT NOT NULL,
                    snapshotted_at TEXT NOT NULL,
                    FOREIGN KEY (claim_id) REFERENCES sleeper_manual_counter_handoffs(claim_id) ON DELETE RESTRICT,
                    CHECK (snapshot_policy_id = 'sleeper-counter-trade-expectation-snapshot-v1-handoff-provider-movement'),
                    CHECK (resolver_policy_id = 'sleeper-trade-expectation-resolution-v1-external-id-owned-assets'),
                    CHECK (length(movement_json) > 0),
                    CHECK (length(movement_sha256) = 64),
                    CHECK (movement_sha256 NOT GLOB '*[^0-9a-f]*')
                )
                """);
            statement.executeUpdate("""
                CREATE TRIGGER IF NOT EXISTS trg_sleeper_counter_trade_snapshot_trusted_handoff
                BEFORE INSERT ON sleeper_counter_trade_expectation_snapshots
                FOR EACH ROW
                WHEN NOT EXISTS (
                    SELECT 1 FROM sleeper_manual_counter_handoffs h
                    WHERE h.claim_id = NEW.claim_id
                      AND h.handoff_id = NEW.handoff_id
                      AND h.action = 'SUBMIT_COUNTER_TRADE'
                      AND h.destination_type = 'LEAGUE'
                      AND h.destination_id = NEW.butler_league_id
                      AND h.reconciliation_mode = 'SLEEPER_TRANSACTION_READBACK'
                )
                BEGIN
                    SELECT RAISE(ABORT, 'Sleeper trade expectation snapshot requires matching trade handoff');
                END
                """);
            statement.executeUpdate("""
                CREATE TRIGGER IF NOT EXISTS trg_sleeper_counter_trade_snapshot_immutable
                BEFORE UPDATE ON sleeper_counter_trade_expectation_snapshots
                FOR EACH ROW
                BEGIN
                    SELECT RAISE(ABORT, 'Sleeper trade expectation snapshot is immutable');
                END
                """);
            statement.executeUpdate("""
                CREATE TRIGGER IF NOT EXISTS trg_sleeper_counter_trade_snapshot_delete_immutable
                BEFORE DELETE ON sleeper_counter_trade_expectation_snapshots
                FOR EACH ROW
                BEGIN
                    SELECT RAISE(ABORT, 'Sleeper trade expectation snapshot is immutable');
                END
                """);
        }
    }

    public SnapshotResult snapshot(
        String claimId,
        String butlerLeagueId,
        String sideATeamId,
        String sideBTeamId,
        TradeAssetAnalyzer.TradePackage revisedSideA,
        TradeAssetAnalyzer.TradePackage revisedSideB,
        Instant snapshottedAt) throws SQLException {
        claimId = requireText(claimId, "claimId");
        butlerLeagueId = requireText(butlerLeagueId, "butlerLeagueId");
        sideATeamId = requireText(sideATeamId, "sideATeamId");
        sideBTeamId = requireText(sideBTeamId, "sideBTeamId");
        Objects.requireNonNull(revisedSideA, "revisedSideA must not be null");
        Objects.requireNonNull(revisedSideB, "revisedSideB must not be null");
        Objects.requireNonNull(snapshottedAt, "snapshottedAt must not be null");

        var handoff = handoffs.findByClaimId(claimId).orElse(null);
        if (handoff == null
            || handoff.action() != TradeCounterAuthorizationPolicy.Action.SUBMIT_COUNTER_TRADE
            || handoff.reconciliationMode()
                != SleeperManualCounterHandoffService.ReconciliationMode.SLEEPER_TRANSACTION_READBACK) {
            return new SnapshotResult(State.NOT_AVAILABLE, null,
                "A durable Sleeper trade handoff presentation is required before provider expectation snapshotting.");
        }
        if (!butlerLeagueId.equals(handoff.destination().id())) {
            throw new IllegalArgumentException("Butler league id must match the trusted trade handoff destination");
        }

        var resolution = resolver.resolve(
            butlerLeagueId, sideATeamId, sideBTeamId, revisedSideA, revisedSideB,
            INTERNAL_RESOLUTION_ROUND, null, 0L);
        if (!resolution.available()) {
            return new SnapshotResult(State.NOT_AVAILABLE, null, resolution.reason());
        }
        var expected = resolution.expectedTrade();
        String movementJson = movementJson(expected);
        var snapshot = new Snapshot(
            POLICY_ID,
            SleeperTradeExpectationResolver.POLICY_ID,
            claimId,
            handoff.handoffId(),
            butlerLeagueId,
            sideATeamId,
            sideBTeamId,
            expected.leagueId(),
            expected.rosterIds(),
            expected.playerAdds(),
            expected.playerDrops(),
            expected.draftPicks(),
            movementJson,
            sha256(movementJson),
            snapshottedAt);

        initialize();
        try (var connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                var existing = findByClaimId(connection, claimId);
                if (existing.isPresent()) {
                    requireSame(existing.get(), snapshot);
                    connection.commit();
                    return new SnapshotResult(State.ALREADY_SNAPSHOTTED, existing.get(),
                        "The exact provider movement expectation was already snapshotted; the first snapshot is preserved.");
                }
                insert(connection, snapshot);
                connection.commit();
                return new SnapshotResult(State.SNAPSHOTTED, snapshot,
                    "Sleeper provider identities and exact trade movement were durably snapshotted before manual action.");
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public Optional<Snapshot> findByClaimId(String claimId) throws SQLException {
        claimId = requireText(claimId, "claimId");
        initialize();
        try (var connection = database.openConnection()) {
            return findByClaimId(connection, claimId);
        }
    }

    private static Optional<Snapshot> findByClaimId(Connection connection, String claimId) throws SQLException {
        try (var statement = connection.prepareStatement(
            "SELECT * FROM sleeper_counter_trade_expectation_snapshots WHERE claim_id=?")) {
            statement.setString(1, claimId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        }
    }

    private static void insert(Connection connection, Snapshot snapshot) throws SQLException {
        try (var statement = connection.prepareStatement("""
            INSERT INTO sleeper_counter_trade_expectation_snapshots(
                claim_id, handoff_id, snapshot_policy_id, resolver_policy_id,
                butler_league_id, side_a_team_id, side_b_team_id, sleeper_league_id,
                movement_json, movement_sha256, snapshotted_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?)
            """)) {
            statement.setString(1, snapshot.claimId());
            statement.setString(2, snapshot.handoffId());
            statement.setString(3, snapshot.policyId());
            statement.setString(4, snapshot.resolverPolicyId());
            statement.setString(5, snapshot.butlerLeagueId());
            statement.setString(6, snapshot.sideATeamId());
            statement.setString(7, snapshot.sideBTeamId());
            statement.setString(8, snapshot.sleeperLeagueId());
            statement.setString(9, snapshot.movementJson());
            statement.setString(10, snapshot.movementSha256());
            statement.setString(11, snapshot.snapshottedAt().toString());
            statement.executeUpdate();
        }
    }

    private static Snapshot read(ResultSet rs) throws SQLException {
        try {
            String movementJson = rs.getString("movement_json");
            Movement movement = parseMovement(movementJson);
            return new Snapshot(
                rs.getString("snapshot_policy_id"),
                rs.getString("resolver_policy_id"),
                rs.getString("claim_id"),
                rs.getString("handoff_id"),
                rs.getString("butler_league_id"),
                rs.getString("side_a_team_id"),
                rs.getString("side_b_team_id"),
                rs.getString("sleeper_league_id"),
                movement.rosterIds(), movement.playerAdds(), movement.playerDrops(), movement.draftPicks(),
                movementJson, rs.getString("movement_sha256"),
                Instant.parse(rs.getString("snapshotted_at")));
        } catch (RuntimeException e) {
            throw new IllegalStateException("stored Sleeper trade expectation snapshot is malformed", e);
        }
    }

    private static String movementJson(SleeperTradeReconciliationPolicy.ExpectedTrade expected) {
        ObjectNode root = JSON.createObjectNode();
        var rosters = new ArrayList<>(expected.rosterIds());
        rosters.sort(Integer::compareTo);
        var rosterArray = root.putArray("rosterIds");
        rosters.forEach(rosterArray::add);
        ObjectNode adds = root.putObject("playerAdds");
        expected.playerAdds().entrySet().stream().sorted(Map.Entry.comparingByKey())
            .forEach(entry -> adds.put(entry.getKey(), entry.getValue()));
        ObjectNode drops = root.putObject("playerDrops");
        expected.playerDrops().entrySet().stream().sorted(Map.Entry.comparingByKey())
            .forEach(entry -> drops.put(entry.getKey(), entry.getValue()));
        var picks = new ArrayList<>(expected.draftPicks());
        picks.sort(Comparator.comparing(SleeperReadOnlyClient.DraftPick::season)
            .thenComparingInt(SleeperReadOnlyClient.DraftPick::round)
            .thenComparingInt(SleeperReadOnlyClient.DraftPick::originalRosterId)
            .thenComparingInt(SleeperReadOnlyClient.DraftPick::previousOwnerId)
            .thenComparingInt(SleeperReadOnlyClient.DraftPick::ownerId));
        var pickArray = root.putArray("draftPicks");
        for (var pick : picks) {
            ObjectNode node = pickArray.addObject();
            node.put("season", pick.season());
            node.put("round", pick.round());
            node.put("originalRosterId", pick.originalRosterId());
            node.put("previousOwnerId", pick.previousOwnerId());
            node.put("ownerId", pick.ownerId());
        }
        try {
            return JSON.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize Sleeper trade movement snapshot", e);
        }
    }

    private static Movement parseMovement(String json) {
        try {
            JsonNode root = JSON.readTree(json);
            Set<Integer> rosters = new LinkedHashSet<>();
            root.path("rosterIds").forEach(node -> rosters.add(node.asInt()));
            Map<String, Integer> adds = readPlayerMap(root.path("playerAdds"));
            Map<String, Integer> drops = readPlayerMap(root.path("playerDrops"));
            Set<SleeperReadOnlyClient.DraftPick> picks = new LinkedHashSet<>();
            root.path("draftPicks").forEach(node -> picks.add(new SleeperReadOnlyClient.DraftPick(
                node.path("season").asText(), node.path("round").asInt(),
                node.path("originalRosterId").asInt(), node.path("previousOwnerId").asInt(),
                node.path("ownerId").asInt())));
            return new Movement(rosters, adds, drops, picks);
        } catch (Exception e) {
            throw new IllegalArgumentException("movement_json must be valid governed snapshot JSON", e);
        }
    }

    private static Map<String, Integer> readPlayerMap(JsonNode node) {
        Map<String, Integer> result = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> result.put(entry.getKey(), entry.getValue().asInt()));
        return result;
    }

    private static void requireSame(Snapshot existing, Snapshot requested) {
        if (!existing.claimId().equals(requested.claimId())
            || !existing.handoffId().equals(requested.handoffId())
            || !existing.butlerLeagueId().equals(requested.butlerLeagueId())
            || !existing.sideATeamId().equals(requested.sideATeamId())
            || !existing.sideBTeamId().equals(requested.sideBTeamId())
            || !existing.sleeperLeagueId().equals(requested.sleeperLeagueId())
            || !existing.movementJson().equals(requested.movementJson())
            || !existing.movementSha256().equals(requested.movementSha256())) {
            throw new IllegalStateException("existing Sleeper trade expectation snapshot differs from current governed movement");
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public enum State { SNAPSHOTTED, ALREADY_SNAPSHOTTED, NOT_AVAILABLE }

    public record SnapshotResult(State state, Snapshot snapshot, String reason) {
        public SnapshotResult {
            Objects.requireNonNull(state, "state must not be null");
            if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason must not be blank");
            if ((state != State.NOT_AVAILABLE) != (snapshot != null)) {
                throw new IllegalArgumentException("available snapshot states must carry exactly one snapshot");
            }
        }
    }

    public record Snapshot(
        String policyId,
        String resolverPolicyId,
        String claimId,
        String handoffId,
        String butlerLeagueId,
        String sideATeamId,
        String sideBTeamId,
        String sleeperLeagueId,
        Set<Integer> rosterIds,
        Map<String, Integer> playerAdds,
        Map<String, Integer> playerDrops,
        Set<SleeperReadOnlyClient.DraftPick> draftPicks,
        String movementJson,
        String movementSha256,
        Instant snapshottedAt) {
        public Snapshot {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            if (!SleeperTradeExpectationResolver.POLICY_ID.equals(resolverPolicyId)) {
                throw new IllegalArgumentException("unexpected resolverPolicyId");
            }
            requireText(claimId, "claimId"); requireText(handoffId, "handoffId");
            requireText(butlerLeagueId, "butlerLeagueId"); requireText(sideATeamId, "sideATeamId");
            requireText(sideBTeamId, "sideBTeamId");
            if (sleeperLeagueId == null || !sleeperLeagueId.matches("[0-9]+")) {
                throw new IllegalArgumentException("sleeperLeagueId must be numeric");
            }
            rosterIds = Set.copyOf(Objects.requireNonNull(rosterIds, "rosterIds must not be null"));
            playerAdds = Map.copyOf(Objects.requireNonNull(playerAdds, "playerAdds must not be null"));
            playerDrops = Map.copyOf(Objects.requireNonNull(playerDrops, "playerDrops must not be null"));
            draftPicks = Set.copyOf(Objects.requireNonNull(draftPicks, "draftPicks must not be null"));
            if (rosterIds.size() != 2) throw new IllegalArgumentException("snapshot requires exactly two roster ids");
            requireText(movementJson, "movementJson");
            if (!sha256(movementJson).equals(movementSha256)) {
                throw new IllegalArgumentException("movement snapshot hash does not match JSON");
            }
            Movement parsed = parseMovement(movementJson);
            if (!parsed.rosterIds().equals(rosterIds)
                || !parsed.playerAdds().equals(playerAdds)
                || !parsed.playerDrops().equals(playerDrops)
                || !parsed.draftPicks().equals(draftPicks)) {
                throw new IllegalArgumentException("movement JSON does not match snapshot fields");
            }
            Objects.requireNonNull(snapshottedAt, "snapshottedAt must not be null");
        }

        public SleeperTradeReconciliationPolicy.ExpectedTrade expectedTrade(
            int round, long notBeforeEpochMillis) {
            return new SleeperTradeReconciliationPolicy.ExpectedTrade(
                sleeperLeagueId, round, rosterIds, playerAdds, playerDrops, draftPicks,
                null, notBeforeEpochMillis);
        }
    }

    private record Movement(
        Set<Integer> rosterIds,
        Map<String, Integer> playerAdds,
        Map<String, Integer> playerDrops,
        Set<SleeperReadOnlyClient.DraftPick> draftPicks) {}

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
