package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.GovernedRecommendationAuditRepository;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** BF-628 read-only inspection and integrity verification of BF-627 recommendation audit history. */
public final class SleeperLiveWaiverRecommendationAuditHistory {
    public static final String POLICY_ID =
        "sleeper-live-waiver-recommendation-audit-history-v1-bf623-read-only-lineage-verified";

    private final GovernedRecommendationAuditRepository repository;

    public SleeperLiveWaiverRecommendationAuditHistory(Database database) {
        this(new GovernedRecommendationAuditRepository(database));
    }

    SleeperLiveWaiverRecommendationAuditHistory(GovernedRecommendationAuditRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    public HistoryReport inspect(SleeperPersonalizedTargetService.VerifiedTarget target) throws SQLException {
        validateVerifiedTarget(target);
        List<GovernedRecommendationAuditRepository.AuditRecord> records =
            repository.findAllForLeague(target.butlerLeagueId());
        List<HistoryEntry> entries = new ArrayList<>();
        for (var record : records) {
            validateRecord(target, record);
            entries.add(new HistoryEntry(
                record.id(),
                record.capturedAtUtc().toString(),
                record.leagueId(),
                record.sleeperOwnerId(),
                record.sleeperLeagueId(),
                record.rosterId(),
                record.season(),
                record.providerStatus(),
                record.providerLeg(),
                record.marketSnapshotId(),
                record.waiverSnapshotId(),
                record.selectionState(),
                record.recommendationState(),
                record.addSleeperPlayerId(),
                record.dropSleeperPlayerId(),
                IntegrityState.LINEAGE_AND_IDENTITY_VERIFIED));
        }
        return new HistoryReport(
            POLICY_ID,
            target.butlerLeagueId(),
            target.sleeperUserId(),
            target.sleeperLeagueId(),
            target.rosterId(),
            List.copyOf(entries),
            entries.isEmpty() ? HistoryState.EMPTY_HISTORY : HistoryState.HISTORY_INTEGRITY_VERIFIED);
    }

    private static void validateVerifiedTarget(SleeperPersonalizedTargetService.VerifiedTarget target) {
        Objects.requireNonNull(target, "target must not be null");
        if (!SleeperPersonalizedTargetService.BF623_POLICY_ID.equals(target.policyId())
            || target.state() != SleeperPersonalizedTargetService.VerificationState.BOUND_TARGET_LIVE_VERIFIED) {
            throw new IllegalStateException("BF-628 BLOCKED: target is not BF-623 live verified");
        }
    }

    private static void validateRecord(
        SleeperPersonalizedTargetService.VerifiedTarget target,
        GovernedRecommendationAuditRepository.AuditRecord record) {
        if (!SleeperLiveWaiverRecommendationAuditCapture.POLICY_ID.equals(record.capturePolicyId())) {
            throw new IllegalStateException("BF-628 BLOCKED: unsupported recommendation audit capture policy");
        }
        if (!target.butlerLeagueId().equals(record.leagueId())
            || !target.sleeperUserId().equals(record.sleeperOwnerId())
            || !target.sleeperLeagueId().equals(record.sleeperLeagueId())
            || target.rosterId() != record.rosterId()) {
            throw new IllegalStateException("BF-628 BLOCKED: stored audit identity does not reconcile to BF-623 live target");
        }
        if (record.season() != SleeperPersonalizedTargetService.TARGET_SEASON
            || !target.providerStatus().equals(record.providerStatus())) {
            throw new IllegalStateException("BF-628 BLOCKED: stored audit season/status does not reconcile to BF-623 live target");
        }
        boolean recommend = "RECOMMEND_ADD_DROP".equals(record.recommendationState());
        boolean uniqueSelection = "UNIQUE_ADD_DROP_SELECTED".equals(record.selectionState());
        if (recommend != uniqueSelection) {
            throw new IllegalStateException("BF-628 BLOCKED: stored selection/recommendation state relationship is inconsistent");
        }
        String expectedLineage = lineageKey(record);
        if (!expectedLineage.equals(record.lineageKey())) {
            throw new IllegalStateException("BF-628 BLOCKED: stored BF-627 lineage key does not reproduce from persisted fields");
        }
    }

    static String lineageKey(GovernedRecommendationAuditRepository.AuditRecord record) {
        Objects.requireNonNull(record, "record must not be null");
        return String.join("|",
            "bf627-v1",
            SleeperPersonalizedTargetService.BF623_POLICY_ID,
            record.leagueId(),
            record.sleeperOwnerId(),
            record.sleeperLeagueId(),
            Integer.toString(record.rosterId()),
            Integer.toString(record.season()),
            record.providerStatus(),
            record.providerLeg() == null ? "none" : record.providerLeg().toString(),
            record.marketSnapshotId(),
            record.waiverSnapshotId(),
            record.bf618PolicyId(),
            record.bf619PolicyId(),
            record.bf620PolicyId(),
            record.bf624PolicyId());
    }

    public enum HistoryState { EMPTY_HISTORY, HISTORY_INTEGRITY_VERIFIED }
    public enum IntegrityState { LINEAGE_AND_IDENTITY_VERIFIED }

    public record HistoryEntry(
        String auditId,
        String capturedAtUtc,
        String leagueId,
        String sleeperOwnerId,
        String sleeperLeagueId,
        int rosterId,
        int providerSeason,
        String providerStatus,
        Integer providerLeg,
        String marketSnapshotId,
        String waiverSnapshotId,
        String selectionState,
        String recommendationState,
        String addSleeperPlayerId,
        String dropSleeperPlayerId,
        IntegrityState integrityState) {
        public HistoryEntry {
            Objects.requireNonNull(integrityState, "integrityState must not be null");
        }
    }

    public record HistoryReport(
        String policyId,
        String leagueId,
        String sleeperOwnerId,
        String sleeperLeagueId,
        int rosterId,
        List<HistoryEntry> entries,
        HistoryState state) {
        public HistoryReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected BF-628 policyId");
            entries = List.copyOf(Objects.requireNonNull(entries, "entries must not be null"));
            Objects.requireNonNull(state, "state must not be null");
        }
    }
}
