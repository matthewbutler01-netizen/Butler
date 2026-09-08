package io.butler.bet.sleeper;

import io.butler.bet.data.Database;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;

/** BF-631 read-only freshness revalidation of the persisted BF-603/BF-602 lineage behind the latest governed audit. */
public final class SleeperLiveWaiverRecommendationEvidenceLineageRevalidation {
    public static final String POLICY_ID =
        "sleeper-live-waiver-recommendation-evidence-lineage-freshness-v1-bf623-bf628-bf603-bf602-read-only";

    private final HistorySource historySource;
    private final SnapshotLineageSource snapshotLineageSource;

    public SleeperLiveWaiverRecommendationEvidenceLineageRevalidation(Database database) {
        this(
            target -> new SleeperLiveWaiverRecommendationAuditHistory(database).inspect(target),
            new ReadOnlyDbSnapshotLineageSource(database));
    }

    SleeperLiveWaiverRecommendationEvidenceLineageRevalidation(
        HistorySource historySource,
        SnapshotLineageSource snapshotLineageSource) {
        this.historySource = Objects.requireNonNull(historySource, "historySource must not be null");
        this.snapshotLineageSource = Objects.requireNonNull(snapshotLineageSource, "snapshotLineageSource must not be null");
    }

    public RevalidationReport revalidate(SleeperPersonalizedTargetService.VerifiedTarget target) throws SQLException {
        validateVerifiedTarget(target);
        var history = historySource.inspect(target);
        validateHistory(target, history);

        if (history.entries().isEmpty()) {
            return new RevalidationReport(
                POLICY_ID,
                target.butlerLeagueId(),
                target.sleeperUserId(),
                target.sleeperLeagueId(),
                target.rosterId(),
                null, null, null, null,
                null, null, null, null, null,
                EvidenceLineageState.NO_AUDITED_DECISION);
        }

        var latestAudit = history.entries().get(history.entries().size() - 1);
        EvidenceFrame frame = Objects.requireNonNull(
            snapshotLineageSource.latest(target.butlerLeagueId()),
            "BF-631 BLOCKED: latest persisted evidence frame is null");
        validateFrame(target, latestAudit, frame);

        boolean marketSuperseded = !latestAudit.marketSnapshotId().equals(frame.latestMarket().id());
        boolean waiverSuperseded = !latestAudit.waiverSnapshotId().equals(frame.latestWaiver().id());
        EvidenceLineageState state;
        if (marketSuperseded && waiverSuperseded) {
            state = EvidenceLineageState.MARKET_AND_WAIVER_LINEAGE_SUPERSEDED;
        } else if (marketSuperseded) {
            state = EvidenceLineageState.MARKET_LINEAGE_SUPERSEDED;
        } else if (waiverSuperseded) {
            state = EvidenceLineageState.WAIVER_LINEAGE_SUPERSEDED;
        } else {
            state = EvidenceLineageState.LATEST_EVIDENCE_LINEAGE_VERIFIED;
        }

        return new RevalidationReport(
            POLICY_ID,
            target.butlerLeagueId(),
            target.sleeperUserId(),
            target.sleeperLeagueId(),
            target.rosterId(),
            latestAudit.auditId(),
            latestAudit.capturedAtUtc(),
            latestAudit.marketSnapshotId(),
            latestAudit.waiverSnapshotId(),
            frame.latestMarket().id(),
            frame.latestMarket().observedAtUtc().toString(),
            frame.latestMarket().waiverSnapshotId(),
            frame.latestWaiver().id(),
            frame.latestWaiver().observedAtUtc().toString(),
            state);
    }

    private static void validateVerifiedTarget(SleeperPersonalizedTargetService.VerifiedTarget target) {
        Objects.requireNonNull(target, "target must not be null");
        if (!SleeperPersonalizedTargetService.BF623_POLICY_ID.equals(target.policyId())
            || target.state() != SleeperPersonalizedTargetService.VerificationState.BOUND_TARGET_LIVE_VERIFIED) {
            throw new IllegalStateException("BF-631 BLOCKED: target is not BF-623 live verified");
        }
    }

    private static void validateHistory(
        SleeperPersonalizedTargetService.VerifiedTarget target,
        SleeperLiveWaiverRecommendationAuditHistory.HistoryReport history) {
        Objects.requireNonNull(history, "history must not be null");
        if (!SleeperLiveWaiverRecommendationAuditHistory.POLICY_ID.equals(history.policyId())
            || !target.butlerLeagueId().equals(history.leagueId())
            || !target.sleeperUserId().equals(history.sleeperOwnerId())
            || !target.sleeperLeagueId().equals(history.sleeperLeagueId())
            || target.rosterId() != history.rosterId()) {
            throw new IllegalStateException("BF-631 BLOCKED: BF-628 history does not reconcile to BF-623 target");
        }
        if (!history.entries().isEmpty()
            && history.state() != SleeperLiveWaiverRecommendationAuditHistory.HistoryState.HISTORY_INTEGRITY_VERIFIED) {
            throw new IllegalStateException("BF-631 BLOCKED: non-empty BF-628 history is not integrity verified");
        }
    }

    private static void validateFrame(
        SleeperPersonalizedTargetService.VerifiedTarget target,
        SleeperLiveWaiverRecommendationAuditHistory.HistoryEntry audit,
        EvidenceFrame frame) {
        Objects.requireNonNull(frame.latestMarket(), "BF-631 BLOCKED: latest BF-603 market snapshot is missing");
        Objects.requireNonNull(frame.latestWaiver(), "BF-631 BLOCKED: latest BF-602 waiver snapshot is missing");
        Objects.requireNonNull(frame.marketReferencedWaiver(), "BF-631 BLOCKED: BF-603 referenced BF-602 snapshot is missing");

        validateTargetIdentity(target, frame.latestMarket(), "latest BF-603");
        validateTargetIdentity(target, frame.latestWaiver(), "latest BF-602");
        validateTargetIdentity(target, frame.marketReferencedWaiver(), "BF-603 referenced BF-602");

        if (!frame.latestMarket().waiverSnapshotId().equals(frame.marketReferencedWaiver().id())) {
            throw new IllegalStateException("BF-631 BLOCKED: latest BF-603 referenced waiver id does not match loaded BF-602 row");
        }
        if (frame.latestMarket().providerSeason() != frame.marketReferencedWaiver().providerSeason()
            || !frame.latestMarket().providerStatus().equals(frame.marketReferencedWaiver().providerStatus())
            || !Objects.equals(frame.latestMarket().providerLeg(), frame.marketReferencedWaiver().providerLeg())) {
            throw new IllegalStateException("BF-631 BLOCKED: latest BF-603 provider frame does not reconcile to its referenced BF-602 snapshot");
        }
        if (frame.latestMarket().id().equals(audit.marketSnapshotId())
            && !frame.latestMarket().waiverSnapshotId().equals(audit.waiverSnapshotId())) {
            throw new IllegalStateException("BF-631 BLOCKED: audited BF-603 id now resolves to a different BF-602 lineage");
        }
    }

    private static void validateTargetIdentity(
        SleeperPersonalizedTargetService.VerifiedTarget target,
        ProviderFrame frame,
        String label) {
        if (!target.butlerLeagueId().equals(frame.leagueId())
            || !target.sleeperLeagueId().equals(frame.sleeperLeagueId())
            || frame.providerSeason() != SleeperPersonalizedTargetService.TARGET_SEASON
            || !target.providerStatus().equals(frame.providerStatus())) {
            throw new IllegalStateException("BF-631 BLOCKED: " + label + " identity/season/status does not reconcile to BF-623 target");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    @FunctionalInterface
    interface HistorySource {
        SleeperLiveWaiverRecommendationAuditHistory.HistoryReport inspect(
            SleeperPersonalizedTargetService.VerifiedTarget target) throws SQLException;
    }

    @FunctionalInterface
    interface SnapshotLineageSource {
        EvidenceFrame latest(String leagueId) throws SQLException;
    }

    /** SELECT-only BF-631 persistence reader. It deliberately does not call BF-602/BF-603 repository ensureTables methods. */
    static final class ReadOnlyDbSnapshotLineageSource implements SnapshotLineageSource {
        private final Database database;

        ReadOnlyDbSnapshotLineageSource(Database database) {
            this.database = Objects.requireNonNull(database, "database must not be null");
        }

        @Override
        public EvidenceFrame latest(String leagueId) throws SQLException {
            String normalized = requireText(leagueId, "leagueId");
            try (Connection connection = database.openConnection()) {
                requireTable(connection, "live_waiver_snapshots");
                requireTable(connection, "live_waiver_market_attention_snapshots");
                MarketFrame market = latestMarket(connection, normalized);
                WaiverFrame waiver = latestWaiver(connection, normalized);
                WaiverFrame referenced = waiverById(connection, market.waiverSnapshotId());
                return new EvidenceFrame(market, waiver, referenced);
            }
        }

        private static void requireTable(Connection connection, String table) throws SQLException {
            try (var statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
                statement.setString(1, table);
                try (var rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalStateException("BF-631 BLOCKED: required persisted table is absent: " + table);
                    }
                }
            }
        }

        private static MarketFrame latestMarket(Connection connection, String leagueId) throws SQLException {
            try (var statement = connection.prepareStatement("""
                SELECT id, league_id, waiver_snapshot_id, sleeper_league_id, season,
                       provider_status, provider_leg, observed_at_utc
                FROM live_waiver_market_attention_snapshots
                WHERE league_id=?
                ORDER BY observed_at_utc DESC, rowid DESC
                LIMIT 1
                """)) {
                statement.setString(1, leagueId);
                try (var rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalStateException("BF-631 BLOCKED: no persisted BF-603 market snapshot exists for league " + leagueId);
                    }
                    return market(rs);
                }
            }
        }

        private static WaiverFrame latestWaiver(Connection connection, String leagueId) throws SQLException {
            try (var statement = connection.prepareStatement("""
                SELECT id, league_id, sleeper_league_id, season, provider_status, provider_leg, observed_at_utc
                FROM live_waiver_snapshots
                WHERE league_id=?
                ORDER BY observed_at_utc DESC, rowid DESC
                LIMIT 1
                """)) {
                statement.setString(1, leagueId);
                try (var rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalStateException("BF-631 BLOCKED: no persisted BF-602 waiver snapshot exists for league " + leagueId);
                    }
                    return waiver(rs);
                }
            }
        }

        private static WaiverFrame waiverById(Connection connection, String snapshotId) throws SQLException {
            try (var statement = connection.prepareStatement("""
                SELECT id, league_id, sleeper_league_id, season, provider_status, provider_leg, observed_at_utc
                FROM live_waiver_snapshots
                WHERE id=?
                """)) {
                statement.setString(1, requireText(snapshotId, "snapshotId"));
                try (var rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalStateException("BF-631 BLOCKED: latest BF-603 references a missing BF-602 snapshot " + snapshotId);
                    }
                    WaiverFrame value = waiver(rs);
                    if (rs.next()) {
                        throw new IllegalStateException("BF-631 BLOCKED: duplicate BF-602 snapshot id " + snapshotId);
                    }
                    return value;
                }
            }
        }

        private static MarketFrame market(ResultSet rs) throws SQLException {
            return new MarketFrame(
                rs.getString("id"), rs.getString("league_id"), rs.getString("sleeper_league_id"),
                rs.getInt("season"), rs.getString("provider_status"), nullableInt(rs, "provider_leg"),
                Instant.parse(rs.getString("observed_at_utc")), rs.getString("waiver_snapshot_id"));
        }

        private static WaiverFrame waiver(ResultSet rs) throws SQLException {
            return new WaiverFrame(
                rs.getString("id"), rs.getString("league_id"), rs.getString("sleeper_league_id"),
                rs.getInt("season"), rs.getString("provider_status"), nullableInt(rs, "provider_leg"),
                Instant.parse(rs.getString("observed_at_utc")));
        }

        private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
            Object value = rs.getObject(column);
            return value == null ? null : rs.getInt(column);
        }
    }

    public enum EvidenceLineageState {
        NO_AUDITED_DECISION,
        LATEST_EVIDENCE_LINEAGE_VERIFIED,
        MARKET_LINEAGE_SUPERSEDED,
        WAIVER_LINEAGE_SUPERSEDED,
        MARKET_AND_WAIVER_LINEAGE_SUPERSEDED
    }

    interface ProviderFrame {
        String leagueId();
        String sleeperLeagueId();
        int providerSeason();
        String providerStatus();
        Integer providerLeg();
    }

    record MarketFrame(
        String id,
        String leagueId,
        String sleeperLeagueId,
        int providerSeason,
        String providerStatus,
        Integer providerLeg,
        Instant observedAtUtc,
        String waiverSnapshotId) implements ProviderFrame {
        MarketFrame {
            id = requireText(id, "market id");
            leagueId = requireText(leagueId, "market leagueId");
            sleeperLeagueId = requireText(sleeperLeagueId, "market sleeperLeagueId");
            providerStatus = requireText(providerStatus, "market providerStatus");
            observedAtUtc = Objects.requireNonNull(observedAtUtc, "market observedAtUtc must not be null");
            waiverSnapshotId = requireText(waiverSnapshotId, "market waiverSnapshotId");
        }
    }

    record WaiverFrame(
        String id,
        String leagueId,
        String sleeperLeagueId,
        int providerSeason,
        String providerStatus,
        Integer providerLeg,
        Instant observedAtUtc) implements ProviderFrame {
        WaiverFrame {
            id = requireText(id, "waiver id");
            leagueId = requireText(leagueId, "waiver leagueId");
            sleeperLeagueId = requireText(sleeperLeagueId, "waiver sleeperLeagueId");
            providerStatus = requireText(providerStatus, "waiver providerStatus");
            observedAtUtc = Objects.requireNonNull(observedAtUtc, "waiver observedAtUtc must not be null");
        }
    }

    record EvidenceFrame(MarketFrame latestMarket, WaiverFrame latestWaiver, WaiverFrame marketReferencedWaiver) {}

    public record RevalidationReport(
        String policyId,
        String leagueId,
        String sleeperOwnerId,
        String sleeperLeagueId,
        int rosterId,
        String auditId,
        String capturedAtUtc,
        String auditedMarketSnapshotId,
        String auditedWaiverSnapshotId,
        String latestMarketSnapshotId,
        String latestMarketObservedAtUtc,
        String latestMarketReferencedWaiverSnapshotId,
        String latestWaiverSnapshotId,
        String latestWaiverObservedAtUtc,
        EvidenceLineageState state) {
        public RevalidationReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected BF-631 policyId");
            Objects.requireNonNull(state, "state must not be null");
        }
    }
}
