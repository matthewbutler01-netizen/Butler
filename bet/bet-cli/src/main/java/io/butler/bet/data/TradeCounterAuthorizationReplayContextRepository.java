package io.butler.bet.data;

import io.butler.bet.intelligence.TradeAssetAnalyzer;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Durable immutable replay coordinates for one trusted counter authorization grant.
 * Stores only original trade asset identities needed to rerun the governed proposal pipeline.
 */
public final class TradeCounterAuthorizationReplayContextRepository {
    private final Database database;

    public TradeCounterAuthorizationReplayContextRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public void initialize() throws SQLException {
        new TradeCounterAuthorizationGrantRepository(database).initialize();
        try (var connection = database.openConnection();
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS trade_counter_authorization_replay_assets (
                    grant_id TEXT NOT NULL,
                    side TEXT NOT NULL,
                    asset_type TEXT NOT NULL,
                    ordinal INTEGER NOT NULL,
                    asset_id TEXT NOT NULL,
                    PRIMARY KEY (grant_id, side, asset_type, ordinal),
                    UNIQUE (grant_id, side, asset_type, asset_id),
                    FOREIGN KEY (grant_id) REFERENCES trade_counter_authorization_grants(grant_id)
                        ON DELETE CASCADE,
                    CHECK (side IN ('SIDE_A', 'SIDE_B')),
                    CHECK (asset_type IN ('PLAYER', 'DRAFT_PICK')),
                    CHECK (ordinal >= 0),
                    CHECK (length(trim(asset_id)) > 0)
                )
                """);
            statement.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_trade_counter_authorization_replay_grant
                ON trade_counter_authorization_replay_assets(grant_id, side, asset_type, ordinal)
                """);
        }
    }

    public AttachmentResult attach(
        String grantId,
        TradeAssetAnalyzer.TradePackage originalSideA,
        TradeAssetAnalyzer.TradePackage originalSideB) throws SQLException {
        grantId = requireText(grantId, "grantId");
        var replay = new ReplayContext(grantId, originalSideA, originalSideB);
        initialize();

        try (var connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                requireActiveGrant(connection, grantId);
                var existing = findByGrantId(connection, grantId);
                if (existing.isPresent()) {
                    if (!existing.get().equals(replay)) {
                        throw new IllegalStateException(
                            "authorization replay context is immutable and differs from existing context");
                    }
                    connection.commit();
                    return AttachmentResult.ALREADY_ATTACHED;
                }

                insertPackage(connection, grantId, Side.SIDE_A, replay.originalSideA());
                insertPackage(connection, grantId, Side.SIDE_B, replay.originalSideB());
                connection.commit();
                return AttachmentResult.ATTACHED;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public Optional<ReplayContext> findByGrantId(String grantId) throws SQLException {
        grantId = requireText(grantId, "grantId");
        initialize();
        try (var connection = database.openConnection()) {
            return findByGrantId(connection, grantId);
        }
    }

    private static void requireActiveGrant(Connection connection, String grantId) throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT consumed_at
            FROM trade_counter_authorization_grants
            WHERE grant_id = ?
            """)) {
            statement.setString(1, grantId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException(
                        "authorization replay context requires a trusted persisted grant");
                }
                if (rs.getString("consumed_at") != null) {
                    throw new IllegalStateException(
                        "authorization replay context cannot be attached after grant consumption");
                }
            }
        }
    }

    private static Optional<ReplayContext> findByGrantId(
        Connection connection,
        String grantId) throws SQLException {
        List<String> sideAPlayers = new ArrayList<>();
        List<String> sideAPicks = new ArrayList<>();
        List<String> sideBPlayers = new ArrayList<>();
        List<String> sideBPicks = new ArrayList<>();
        int rows = 0;

        try (var statement = connection.prepareStatement("""
            SELECT side, asset_type, ordinal, asset_id
            FROM trade_counter_authorization_replay_assets
            WHERE grant_id = ?
            ORDER BY side, asset_type, ordinal
            """)) {
            statement.setString(1, grantId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    rows++;
                    Side side = Side.valueOf(rs.getString("side"));
                    AssetType assetType = AssetType.valueOf(rs.getString("asset_type"));
                    String assetId = rs.getString("asset_id");
                    if (side == Side.SIDE_A && assetType == AssetType.PLAYER) sideAPlayers.add(assetId);
                    else if (side == Side.SIDE_A) sideAPicks.add(assetId);
                    else if (assetType == AssetType.PLAYER) sideBPlayers.add(assetId);
                    else sideBPicks.add(assetId);
                }
            }
        }

        if (rows == 0) return Optional.empty();
        return Optional.of(new ReplayContext(
            grantId,
            new TradeAssetAnalyzer.TradePackage(sideAPlayers, sideAPicks),
            new TradeAssetAnalyzer.TradePackage(sideBPlayers, sideBPicks)));
    }

    private static void insertPackage(
        Connection connection,
        String grantId,
        Side side,
        TradeAssetAnalyzer.TradePackage tradePackage) throws SQLException {
        insertAssets(connection, grantId, side, AssetType.PLAYER, tradePackage.playerIds());
        insertAssets(connection, grantId, side, AssetType.DRAFT_PICK, tradePackage.draftPickIds());
    }

    private static void insertAssets(
        Connection connection,
        String grantId,
        Side side,
        AssetType assetType,
        List<String> assetIds) throws SQLException {
        try (var statement = connection.prepareStatement("""
            INSERT INTO trade_counter_authorization_replay_assets(
                grant_id, side, asset_type, ordinal, asset_id)
            VALUES (?, ?, ?, ?, ?)
            """)) {
            for (int i = 0; i < assetIds.size(); i++) {
                statement.setString(1, grantId);
                statement.setString(2, side.name());
                statement.setString(3, assetType.name());
                statement.setInt(4, i);
                statement.setString(5, assetIds.get(i));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static TradeAssetAnalyzer.TradePackage normalizePackage(
        TradeAssetAnalyzer.TradePackage tradePackage,
        String field) {
        Objects.requireNonNull(tradePackage, field + " must not be null");
        List<String> players = normalizeIds(tradePackage.playerIds(), field + ".playerIds");
        List<String> picks = normalizeIds(tradePackage.draftPickIds(), field + ".draftPickIds");
        if (players.isEmpty() && picks.isEmpty()) {
            throw new IllegalArgumentException(field + " must contain at least one asset");
        }
        return new TradeAssetAnalyzer.TradePackage(players, picks);
    }

    private static List<String> normalizeIds(List<String> values, String field) {
        Objects.requireNonNull(values, field + " must not be null");
        List<String> result = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (String value : values) {
            String normalized = requireText(value, field + " entry");
            if (!unique.add(normalized)) {
                throw new IllegalArgumentException(field + " contains duplicate asset: " + normalized);
            }
            result.add(normalized);
        }
        return List.copyOf(result);
    }

    private static void rejectOverlap(
        TradeAssetAnalyzer.TradePackage sideA,
        TradeAssetAnalyzer.TradePackage sideB) {
        Set<String> players = new HashSet<>(sideA.playerIds());
        for (String id : sideB.playerIds()) {
            if (players.contains(id)) {
                throw new IllegalArgumentException("player appears on both replay sides: " + id);
            }
        }
        Set<String> picks = new HashSet<>(sideA.draftPickIds());
        for (String id : sideB.draftPickIds()) {
            if (picks.contains(id)) {
                throw new IllegalArgumentException("draft pick appears on both replay sides: " + id);
            }
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    public enum AttachmentResult {
        ATTACHED,
        ALREADY_ATTACHED
    }

    private enum Side {
        SIDE_A,
        SIDE_B
    }

    private enum AssetType {
        PLAYER,
        DRAFT_PICK
    }

    public record ReplayContext(
        String grantId,
        TradeAssetAnalyzer.TradePackage originalSideA,
        TradeAssetAnalyzer.TradePackage originalSideB) {
        public ReplayContext {
            grantId = requireText(grantId, "grantId");
            originalSideA = normalizePackage(originalSideA, "originalSideA");
            originalSideB = normalizePackage(originalSideB, "originalSideB");
            rejectOverlap(originalSideA, originalSideB);
        }
    }
}
